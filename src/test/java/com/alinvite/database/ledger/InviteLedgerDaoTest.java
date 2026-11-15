package com.alinvite.database.ledger;

import com.alinvite.manager.InviteQuotaPolicy;
import com.alinvite.manager.QuotaRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executes the real production ledger SQL ({@link InviteLedgerDao}) against a real SQLite database.
 *
 * <p>The base tables mirror the production DDL for the columns the ledger touches (including the
 * unique {@code records.invitee_uuid} index that makes duplicate invites impossible); the ledger's
 * own tables and columns are created by the production {@code ensureSchema()}.
 */
class InviteLedgerDaoTest {

    private static final QuotaRule DEFAULT_RULE =
        QuotaRule.recurring("default", 48.0, 1, 0);
    private static final QuotaRule VIP_RULE =
        QuotaRule.recurring("vip1", 24.0, 2, 10);

    private Path dbFile;
    private InviteLedgerDao dao;
    private final AtomicInteger codeSeq = new AtomicInteger(1);

    @BeforeEach
    void setUp() throws Exception {
        Path dir = Paths.get("target", "test-db");
        Files.createDirectories(dir);
        dbFile = dir.resolve("ledger-" + UUID.randomUUID() + ".db");
        try (Connection conn = open(); Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("""
                CREATE TABLE alinvite_players (
                    uuid VARCHAR(36) PRIMARY KEY,
                    invite_code VARCHAR(16) NOT NULL UNIQUE,
                    bound_invite_code VARCHAR(16),
                    personal_code_ready INT NOT NULL DEFAULT 1,
                    total_invites INT NOT NULL DEFAULT 0,
                    invite_slots INT NOT NULL DEFAULT 0,
                    last_slot_replenish BIGINT NOT NULL DEFAULT 0,
                    quota_periods_granted INT NOT NULL DEFAULT 0,
                    bind_ip VARCHAR(45),
                    last_invite_time BIGINT,
                    last_code_change BIGINT,
                    milestone_enabled INT NOT NULL DEFAULT 1,
                    rebate_enabled INT NOT NULL DEFAULT 1,
                    gift_enabled INT NOT NULL DEFAULT 1,
                    created_at BIGINT NOT NULL
                )
                """);
            stmt.execute("""
                CREATE TABLE alinvite_pending_invites (
                    target_name VARCHAR(16) PRIMARY KEY,
                    target_display_name VARCHAR(16) NOT NULL,
                    inviter_uuid VARCHAR(36) NOT NULL,
                    inviter_ip VARCHAR(45),
                    created_at BIGINT NOT NULL
                )
                """);
            stmt.execute("""
                CREATE TABLE alinvite_records (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    inviter_uuid VARCHAR(36) NOT NULL,
                    invitee_uuid VARCHAR(36) NOT NULL,
                    invitee_ip VARCHAR(45) NOT NULL,
                    invitee_name VARCHAR(16) NOT NULL,
                    invited_at BIGINT NOT NULL
                )
                """);
            stmt.execute("CREATE UNIQUE INDEX idx_alinvite_records_invitee_uniq"
                + " ON alinvite_records(invitee_uuid)");
            stmt.execute("""
                CREATE TABLE alinvite_hidden_registration_codes (
                    code VARCHAR(32) PRIMARY KEY,
                    batch_id VARCHAR(36) NOT NULL,
                    claimed_uuid VARCHAR(36),
                    claimed_name VARCHAR(16),
                    created_at BIGINT NOT NULL,
                    claimed_at BIGINT
                )
                """);
            stmt.execute("""
                CREATE TABLE alinvite_ip_claims (
                    ip VARCHAR(45) PRIMARY KEY,
                    bind_count INT NOT NULL DEFAULT 0,
                    last_bound_at BIGINT NOT NULL DEFAULT 0
                )
                """);
            stmt.execute("""
                CREATE TABLE alinvite_bound_player_names (
                    name_lower VARCHAR(16) PRIMARY KEY,
                    player_uuid VARCHAR(36) NOT NULL,
                    display_name VARCHAR(16) NOT NULL,
                    inviter_uuid VARCHAR(36) NOT NULL,
                    bound_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL
                )
                """);
        }
        dao = new InviteLedgerDao(this::open, "alinvite_", false,
            () -> "N" + codeSeq.getAndIncrement());
        dao.ensureSchema();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (dbFile == null) {
            return;
        }
        for (String suffix : new String[]{"", "-wal", "-shm"}) {
            Files.deleteIfExists(Paths.get(dbFile + suffix));
        }
    }

    // ───────────────────────── migration ─────────────────────────

    @Test
    void migrationZeroSlotsRetiresLegacyCodeForever() throws Exception {
        UUID player = UUID.randomUUID();
        insertLegacyPlayer(player, "LEGACY1", 0, true, 1);
        markRegistered(player);

        InviteLedgerDao.MigrationResult result = dao.migrateLegacy(player, DEFAULT_RULE, 50.0);
        assertEquals(InviteLedgerDao.MigrationStatus.MIGRATED, result.status());
        assertEquals(0, result.convertedCodes());
        assertEquals(1, result.retiredLegacy());
        assertTrue(dao.listAvailable(player).isEmpty());

        // The old permanent string must never resolve or bind again.
        assertNull(dao.resolveAvailableOwner("LEGACY1"));
        assertEquals(InviteLedgerDao.BindStatus.LEGACY_RETIRED,
            bind(player, UUID.randomUUID(), "LEGACY1", "10.0.0.1").status);

        // Watermark baselined at 50h -> boundary 1 already consumed, nothing granted now.
        InviteLedgerDao.TickResult tick = dao.applyQuotaTick(player, 50.0, DEFAULT_RULE, k -> DEFAULT_RULE);
        assertEquals(0, tick.granted());
        assertEquals(2, tick.nextBoundary());

        // Idempotent: a second migration changes nothing.
        InviteLedgerDao.MigrationResult again = dao.migrateLegacy(player, DEFAULT_RULE, 50.0);
        assertEquals(InviteLedgerDao.MigrationStatus.ALREADY_MIGRATED, again.status());
        assertTrue(dao.listAvailable(player).isEmpty());
    }

    @Test
    void migrationConvertsPositiveSlotsAndPreservesExcessAboveCap() {
        UUID player = UUID.randomUUID();
        insertLegacyPlayer(player, "LEGACY2", 5, true, 3);
        markRegistered(player);

        InviteLedgerDao.MigrationResult result = dao.migrateLegacy(player, DEFAULT_RULE, 10.0);
        assertEquals(InviteLedgerDao.MigrationStatus.MIGRATED, result.status());
        assertEquals(5, result.convertedCodes());
        List<String> codes = dao.listAvailable(player);
        assertEquals(5, codes.size());
        assertEquals(5, codes.stream().distinct().count());

        // Cap is 1 but the grandfathered excess is preserved and blocks further grants.
        InviteLedgerDao.TickResult tick = dao.applyQuotaTick(player, 400.0, DEFAULT_RULE, k -> DEFAULT_RULE);
        assertEquals(0, tick.granted());
        assertEquals(5, dao.listAvailable(player).size());
    }

    @Test
    void migrationIsIdempotentAcrossRepeatedRuns() {
        UUID player = UUID.randomUUID();
        insertLegacyPlayer(player, "LEGACY3", 2, true, 1);
        markRegistered(player);

        assertEquals(2, dao.migrateLegacy(player, DEFAULT_RULE, 5.0).convertedCodes());
        assertEquals(InviteLedgerDao.MigrationStatus.ALREADY_MIGRATED,
            dao.migrateLegacy(player, DEFAULT_RULE, 5.0).status());
        assertEquals(InviteLedgerDao.MigrationStatus.ALREADY_MIGRATED,
            dao.migrateLegacy(player, DEFAULT_RULE, 500.0).status());
        assertEquals(2, dao.listAvailable(player).size());
    }

    @Test
    void migrationTurnsPendingInvitesIntoReservedTokens() throws Exception {
        UUID player = UUID.randomUUID();
        insertLegacyPlayer(player, "LEGACY4", 2, true, 1);
        markRegistered(player);
        insertPending("alice", "Alice", player);
        insertPending("bob", "Bob", player);

        InviteLedgerDao.MigrationResult result = dao.migrateLegacy(player, DEFAULT_RULE, 5.0);
        assertEquals(2, result.reservedPending());
        assertEquals(2, dao.listAvailable(player).size());
        assertNotNull(reservedCodeOf("alice"));
        assertNotNull(reservedCodeOf("bob"));
        assertNotEquals(reservedCodeOf("alice"), reservedCodeOf("bob"));

        UUID invitee = UUID.randomUUID();
        InviteLedgerDao.BindOutcome claim = dao.bind(new InviteLedgerDao.BindRequest(
            player, invitee, "10.0.0.9", null, "Alice", reservedCodeOf("alice"), "alice",
            false, InviteLedgerDao.BindSettings.defaults()));
        assertEquals(InviteLedgerDao.BindStatus.SUCCESS, claim.status);
        assertNull(reservedCodeOf("alice"));
        // Old /add already deducted its slot: the two remaining slots are separate inventory.
        assertEquals(2, dao.listAvailable(player).size());
    }

    // ───────────────────────── one-use enforcement ─────────────────────────

    @Test
    void usedCodeCannotBeReplayedAfterReplenishment() {
        UUID player = UUID.randomUUID();
        insertLegacyPlayer(player, "LEGACY5", 1, true, 1);
        markRegistered(player);
        dao.migrateLegacy(player, DEFAULT_RULE, 10.0);

        String code = dao.listAvailable(player).get(0);
        assertEquals(InviteLedgerDao.BindStatus.SUCCESS,
            bind(player, UUID.randomUUID(), code, "10.0.0.2").status);
        assertEquals(InviteLedgerDao.BindStatus.CODE_NOT_AVAILABLE,
            bind(player, UUID.randomUUID(), code, "10.0.0.3").status);

        // Reaching the next boundary mints a brand new code; the used one stays dead.
        InviteLedgerDao.TickResult tick = dao.applyQuotaTick(player, 96.0, DEFAULT_RULE, k -> DEFAULT_RULE);
        assertEquals(1, tick.granted());
        String fresh = dao.listAvailable(player).get(0);
        assertNotEquals(code, fresh);
        assertNull(dao.resolveAvailableOwner(code));
        assertEquals(InviteLedgerDao.BindStatus.CODE_NOT_AVAILABLE,
            bind(player, UUID.randomUUID(), code, "10.0.0.4").status);
    }

    @Test
    void vipRuleGrantsTwoDistinctCodesOnSeparateBoundaries() {
        UUID player = UUID.randomUUID();
        insertFreshPlayer(player);
        markRegistered(player);

        dao.migrateLegacy(player, VIP_RULE, 48.0);
        InviteLedgerDao.TickResult tick = dao.applyQuotaTick(player, 48.0, VIP_RULE, k -> VIP_RULE);
        assertEquals(2, tick.granted());
        assertEquals(2, tick.held());
        List<String> codes = dao.listAvailable(player);
        assertEquals(2, codes.size());
        assertNotEquals(codes.get(0), codes.get(1));

        // Cap is 2: the 72h boundary is skipped, no backlog appears later.
        InviteLedgerDao.TickResult capped = dao.applyQuotaTick(player, 72.0, VIP_RULE, k -> VIP_RULE);
        assertEquals(0, capped.granted());
        assertEquals(2, capped.available());
    }

    @Test
    void fullCapSkipsPeriodsAndNeverBacklogs() {
        UUID player = UUID.randomUUID();
        insertFreshPlayer(player);
        markRegistered(player);
        dao.migrateLegacy(player, DEFAULT_RULE, 48.0);

        InviteLedgerDao.TickResult first = dao.applyQuotaTick(player, 48.0, DEFAULT_RULE, k -> DEFAULT_RULE);
        assertEquals(1, first.granted());
        assertEquals(1, first.available());

        InviteLedgerDao.TickResult skipped = dao.applyQuotaTick(player, 400.0, DEFAULT_RULE, k -> DEFAULT_RULE);
        assertEquals(0, skipped.granted());
        assertEquals(1, skipped.available());
        assertTrue(skipped.nextBoundary() >= 9, "watermark must advance past skipped periods");

        // Consuming the only code frees capacity, but the skipped periods are gone for good.
        String code = dao.listAvailable(player).get(0);
        assertEquals(InviteLedgerDao.BindStatus.SUCCESS,
            bind(player, UUID.randomUUID(), code, "10.0.0.5").status);
        InviteLedgerDao.TickResult afterUse = dao.applyQuotaTick(player, 400.0, DEFAULT_RULE, k -> DEFAULT_RULE);
        assertEquals(0, afterUse.granted());
        assertEquals(0, afterUse.available());
    }

    @Test
    void downgradeKeepsCodesAndReupgradeReplaysNothing() {
        UUID player = UUID.randomUUID();
        insertFreshPlayer(player);
        markRegistered(player);

        dao.migrateLegacy(player, VIP_RULE, 48.0);
        assertEquals(2, dao.applyQuotaTick(player, 48.0, VIP_RULE, k -> VIP_RULE).granted());

        // vip1 -> default at 50h: the 2 codes stay, the new policy is baselined at 50h.
        InviteLedgerDao.TickResult downgrade = dao.applyQuotaTick(player, 50.0, DEFAULT_RULE,
            key -> key.startsWith("vip1") ? VIP_RULE : DEFAULT_RULE);
        assertEquals(0, downgrade.granted());
        assertEquals(2, dao.listAvailable(player).size());

        // Still above the new cap of 1 at 96h -> skipped.
        assertEquals(0, dao.applyQuotaTick(player, 96.0, DEFAULT_RULE, k -> DEFAULT_RULE).granted());
        assertEquals(2, dao.listAvailable(player).size());

        // Re-upgrade at 100h: no boundary is replayed.
        InviteLedgerDao.TickResult reupgrade = dao.applyQuotaTick(player, 100.0, VIP_RULE,
            key -> key.startsWith("vip1") ? VIP_RULE : DEFAULT_RULE);
        assertEquals(0, reupgrade.granted());
        assertEquals(2, dao.listAvailable(player).size());
    }

    // ───────────────────────── concurrency ─────────────────────────

    @Test
    void concurrentSameCodeBindOnlyOneWins() throws Exception {
        UUID player = UUID.randomUUID();
        insertLegacyPlayer(player, "LEGACY6", 1, true, 1);
        markRegistered(player);
        dao.migrateLegacy(player, DEFAULT_RULE, 5.0);
        String code = dao.listAvailable(player).get(0);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        try {
            for (int i = 0; i < 2; i++) {
                final String ip = "10.1.0." + (i + 1);
                pool.submit(() -> {
                    try {
                        start.await();
                        InviteLedgerDao.BindOutcome outcome =
                            bind(player, UUID.randomUUID(), code, ip);
                        if (outcome.status == InviteLedgerDao.BindStatus.SUCCESS) {
                            successes.incrementAndGet();
                        } else {
                            failures.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, successes.get(), "exactly one concurrent bind may win");
        assertEquals(1, failures.get());
        assertEquals(1, countRows("SELECT COUNT(*) FROM alinvite_records"));
        assertEquals("USED", codeStatus(code));
        assertTrue(dao.listAvailable(player).isEmpty());
    }

    @Test
    void reservedTokenCannotBeBoundByStringAndReleaseIsIdempotent() {
        UUID player = UUID.randomUUID();
        insertLegacyPlayer(player, "LEGACY7", 1, true, 1);
        markRegistered(player);
        dao.migrateLegacy(player, DEFAULT_RULE, 5.0);
        String code = dao.listAvailable(player).get(0);

        InviteLedgerDao.ReserveResult reserve =
            dao.reserve(player, "10.2.0.1", "carol", "Carol", 0);
        assertEquals(InviteLedgerDao.ReserveStatus.SUCCESS, reserve.status());
        assertEquals(code, reserve.reservedCode());
        assertTrue(dao.listAvailable(player).isEmpty());

        // A reserved token is not bindable through a plain string code.
        assertEquals(InviteLedgerDao.BindStatus.CODE_RESERVED,
            bind(player, UUID.randomUUID(), code, "10.2.0.2").status);

        assertTrue(dao.release(player, "carol"));
        assertFalse(dao.release(player, "carol"), "second release must be a no-op");
        assertEquals(1, dao.listAvailable(player).size(), "stock must not be duplicated");

        assertEquals(InviteLedgerDao.BindStatus.SUCCESS,
            bind(player, UUID.randomUUID(), code, "10.2.0.3").status);
    }

    @Test
    void duplicateInviteeRollsBackTheCodeConsumption() throws Exception {
        UUID player = UUID.randomUUID();
        insertLegacyPlayer(player, "LEGACY8", 2, true, 1);
        markRegistered(player);
        dao.migrateLegacy(player, DEFAULT_RULE, 5.0);
        List<String> codes = dao.listAvailable(player);
        assertEquals(2, codes.size());

        UUID invitee = UUID.randomUUID();
        assertEquals(InviteLedgerDao.BindStatus.SUCCESS,
            bind(player, invitee, codes.get(0), "10.3.0.1").status);

        // Same invitee again -> duplicate record, transaction rolls back, second code stays intact.
        InviteLedgerDao.BindOutcome duplicate = bind(player, invitee, codes.get(1), "10.3.0.2");
        assertEquals(InviteLedgerDao.BindStatus.DUPLICATE, duplicate.status);
        assertEquals("AVAILABLE", codeStatus(codes.get(1)));
        assertEquals(1, dao.listAvailable(player).size());
        assertEquals(1, countRows("SELECT COUNT(*) FROM alinvite_records"));
    }

    @Test
    void unregisteredGateLockedPlayerFarmsNoCodes() {
        UUID player = UUID.randomUUID();
        insertFreshPlayer(player);

        InviteLedgerDao.TickResult tick = dao.applyQuotaTick(player, 500.0, DEFAULT_RULE, k -> DEFAULT_RULE);
        assertEquals(InviteLedgerDao.TickStatus.SKIPPED_UNREGISTERED, tick.status());
        assertTrue(dao.listAvailable(player).isEmpty());
    }

    @Test
    void hiddenRegistrationWithoutPlayerRowGetsFirstCodeAt48Hours() {
        UUID owner = UUID.randomUUID();
        markRegistered(owner);
        dao.migrateLegacy(owner, DEFAULT_RULE, 48.0);
        assertEquals(1, dao.applyQuotaTick(owner, 48.0, DEFAULT_RULE, k -> DEFAULT_RULE).granted());
        assertEquals(1, dao.listAvailable(owner).size());
    }

    @Test
    void veteranWithoutPlayerRowIsInitializedButGateNewcomerIsNot() throws Exception {
        UUID veteran = UUID.randomUUID();
        dao.ensureEligiblePlayer(veteran, true);
        assertEquals(1, dao.applyQuotaTick(veteran, 48.0, DEFAULT_RULE, k -> DEFAULT_RULE).granted());
        UUID newcomer = UUID.randomUUID();
        dao.ensureEligiblePlayer(newcomer, false);
        assertEquals(0, countRows("SELECT COUNT(*) FROM alinvite_players WHERE uuid='" + newcomer + "'"));
        assertEquals(InviteLedgerDao.TickStatus.NOT_REGISTERED,
            dao.applyQuotaTick(newcomer, 500, DEFAULT_RULE, k -> DEFAULT_RULE).status());
    }

    @Test
    void ledgerCreatedInviteeKeepsFirstBoundaryEvenWithNonblankPlaceholder() {
        UUID owner = UUID.randomUUID();
        insertLegacyPlayer(owner, "OLDOWNER", 1, true, 1);
        dao.migrateLegacy(owner, DEFAULT_RULE, 0);
        UUID invited = UUID.randomUUID();
        assertEquals(InviteLedgerDao.BindStatus.SUCCESS,
            bind(owner, invited, dao.firstAvailable(owner), "10.4.0.1").status);
        dao.migrateLegacy(invited, VIP_RULE, 48);
        assertEquals(2, dao.applyQuotaTick(invited, 48, VIP_RULE, k -> VIP_RULE).granted());
    }

    @Test
    void migrationPreservesVeteranGuardAndAuditAcrossReset() throws Exception {
        UUID veteran = UUID.randomUUID();
        insertLegacyPlayer(veteran, "OLDVETERAN", 2, true, 1);
        dao.migrateLegacy(veteran, DEFAULT_RULE, 0);
        UUID inviter = UUID.randomUUID();
        insertLegacyPlayer(inviter, "OTHEROWNER", 1, true, 1);
        dao.migrateLegacy(inviter, DEFAULT_RULE, 0);
        assertEquals(InviteLedgerDao.BindStatus.INVITEE_VETERAN,
            bind(inviter, veteran, dao.firstAvailable(inviter), "10.4.0.2").status);
        dao.retireAllHeld(veteran, "RESET");
        dao.resetWatermark(veteran);
        dao.migrateLegacy(veteran, DEFAULT_RULE, 100);
        assertEquals(2, countRows("SELECT converted_codes FROM alinvite_legacy_code_archive WHERE uuid='" + veteran + "'"));
    }

    @Test
    void startupMigrationPreservesOfflinePendingAndAvailableStock() throws Exception {
        UUID owner = UUID.randomUUID();
        insertLegacyPlayer(owner, "OFFLINEOWNER", 2, true, 1);
        insertPending("offlineguest", "OfflineGuest", owner);
        dao.migrateExistingInventories(DEFAULT_RULE);
        assertEquals(2, dao.listAvailable(owner).size());
        String reserved = reservedCodeOf("offlineguest");
        assertNotNull(reserved);
        assertEquals(InviteLedgerDao.BindStatus.SUCCESS, dao.bind(new InviteLedgerDao.BindRequest(
            owner, UUID.randomUUID(), "10.4.0.3", null, "OfflineGuest", reserved, "offlineguest", false,
            InviteLedgerDao.BindSettings.defaults())).status);
        assertEquals(2, dao.listAvailable(owner).size());
        assertEquals(0, dao.applyQuotaTick(owner, 100, VIP_RULE, k -> VIP_RULE).granted());
        assertEquals(5, dao.applyQuotaTick(owner, 100, VIP_RULE, k -> VIP_RULE).nextBoundary());
        dao.migrateExistingInventories(DEFAULT_RULE);
        assertEquals(2, dao.listAvailable(owner).size());
    }

    @Test
    void migrationRetainsAlreadyDeductedPendingWhenNoFreeSlotRemains() throws Exception {
        UUID owner = UUID.randomUUID();
        insertLegacyPlayer(owner, "ZEROPENDING", 0, true, 1);
        insertPending("prepaid", "Prepaid", owner);
        dao.migrateExistingInventories(DEFAULT_RULE);
        assertTrue(dao.listAvailable(owner).isEmpty());
        assertNotNull(reservedCodeOf("prepaid"));
        assertTrue(dao.release(owner, "prepaid"));
        assertFalse(dao.release(owner, "prepaid"));
        assertEquals(1, dao.listAvailable(owner).size());
    }

    @Test
    void mintConflictRollsBackWithoutBurningPeriodOrReusingOfflineLegacyCode() throws Exception {
        UUID owner = UUID.randomUUID();
        markRegistered(owner);
        dao.ensureEligiblePlayer(owner, false);
        UUID offlineOwner = UUID.randomUUID();
        insertLegacyPlayer(offlineOwner, "COLLISION", 1, true, 1);
        InviteLedgerDao conflicting = new InviteLedgerDao(this::open, "alinvite_", false, () -> "COLLISION");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
            () -> conflicting.applyQuotaTick(owner, 48, DEFAULT_RULE, k -> DEFAULT_RULE));
        assertEquals(1, countRows("SELECT quota_next_boundary FROM alinvite_players WHERE uuid='" + owner + "'"));
        assertTrue(dao.listAvailable(owner).isEmpty());
        assertEquals(1, dao.applyQuotaTick(owner, 48, DEFAULT_RULE, k -> DEFAULT_RULE).granted());
        assertNotEquals("COLLISION", dao.firstAvailable(owner));
    }

    @Test
    void migrationFailureRollsBackWholeInventoryAndCanRetry() throws Exception {
        UUID owner = UUID.randomUUID();
        insertLegacyPlayer(owner, "MIGFAIL", 2, true, 1);
        InviteLedgerDao broken = new InviteLedgerDao(this::open, "alinvite_", false, () -> null);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
            () -> broken.migrateLegacy(owner, DEFAULT_RULE, 10));
        assertEquals(0, countRows("SELECT COUNT(*) FROM alinvite_legacy_code_archive"));
        assertEquals(2, countRows("SELECT invite_slots FROM alinvite_players WHERE uuid='" + owner + "'"));
        assertEquals(2, dao.migrateLegacy(owner, DEFAULT_RULE, 10).convertedCodes());
    }

    @Test
    void inventoryOver5000IsNotSilentlyDiscarded() {
        UUID owner = UUID.randomUUID();
        insertLegacyPlayer(owner, "LARGESTOCK", 5001, true, 1);
        assertEquals(5001, dao.migrateLegacy(owner, DEFAULT_RULE, 10).convertedCodes());
        assertEquals(5001, dao.listAvailable(owner).size());
    }

    @Test
    void fullInventorySkipsMoreThan4096PeriodsAndMinuteRoundingCannotGrantEarly() {
        UUID owner = UUID.randomUUID();
        markRegistered(owner);
        dao.ensureEligiblePlayer(owner, false);
        assertEquals(0, dao.applyQuotaTick(owner, 47.999, DEFAULT_RULE, k -> DEFAULT_RULE).granted());
        assertEquals(1, dao.applyQuotaTick(owner, 48, DEFAULT_RULE, k -> DEFAULT_RULE).granted());
        assertEquals(10001, dao.applyQuotaTick(owner, 480000, DEFAULT_RULE, k -> DEFAULT_RULE).nextBoundary());
        assertEquals(InviteLedgerDao.BindStatus.SUCCESS,
            bind(owner, UUID.randomUUID(), dao.firstAvailable(owner), "10.4.0.4").status);
        assertEquals(0, dao.applyQuotaTick(owner, 480000, DEFAULT_RULE, k -> DEFAULT_RULE).granted());
    }

    @Test
    void punishmentRetiresActualAvailableCode() {
        UUID owner = UUID.randomUUID();
        insertLegacyPlayer(owner, "PUNISHSTOCK", 2, true, 1);
        dao.migrateLegacy(owner, DEFAULT_RULE, 10);
        String code = dao.firstAvailable(owner);
        assertEquals(1, dao.retireAvailable(owner, 1, "PUNISHMENT"));
        assertEquals("RETIRED", codeStatus(code));
        assertEquals(1, dao.listAvailable(owner).size());
    }

    @Test
    void builtInTimeFeedsQuotaBoundaryWithoutReplayingOnRetry() throws Exception {
        UUID owner = UUID.randomUUID();
        markRegistered(owner);
        dao.ensureEligiblePlayer(owner, false);
        try (Connection conn = open(); PreparedStatement stmt = conn.prepareStatement(
                "UPDATE alinvite_players SET quota_high_water_minutes=2879 WHERE uuid=?")) {
            stmt.setString(1, owner.toString());
            stmt.executeUpdate();
        }
        var time = new com.alinvite.database.playtime.PlaytimeDao(this::open, "alinvite_", false);
        time.ensureSchema();
        assertEquals(2879L * 60000L, time.getTotalMillis(owner));
        UUID session = UUID.randomUUID();
        long total = time.record(owner, session, 60000L, 0L);
        assertEquals(48L * 3600000L, total);
        assertEquals(1, dao.applyQuotaTick(owner, total / 3600000.0, DEFAULT_RULE, k -> DEFAULT_RULE).granted());
        assertEquals(total, time.record(owner, session, 60000L, 0L));
        assertEquals(0, dao.applyQuotaTick(owner, total / 3600000.0, DEFAULT_RULE, k -> DEFAULT_RULE).granted());
        assertEquals(1, dao.listAvailable(owner).size());
    }

    // ───────────────────────── helpers ─────────────────────────

    private Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
    }

    private InviteLedgerDao.BindOutcome bind(UUID inviter, UUID invitee, String code, String ip) {
        return dao.bind(new InviteLedgerDao.BindRequest(inviter, invitee, ip, null,
            "player" + invitee.toString().substring(0, 6), code, null, true,
            InviteLedgerDao.BindSettings.defaults()));
    }

    private void insertLegacyPlayer(UUID uuid, String code, int slots, boolean ready, int periods) {
        try (Connection conn = open(); PreparedStatement stmt = conn.prepareStatement(
            "INSERT INTO alinvite_players (uuid, invite_code, personal_code_ready, total_invites,"
                + " invite_slots, last_slot_replenish, quota_periods_granted, created_at)"
                + " VALUES (?, ?, ?, 0, ?, 0, ?, ?)")) {
            stmt.setString(1, uuid.toString());
            stmt.setString(2, code);
            stmt.setInt(3, ready ? 1 : 0);
            stmt.setInt(4, slots);
            stmt.setInt(5, periods);
            stmt.setLong(6, System.currentTimeMillis());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** No legacy artifacts at all: the player keeps the normal schedule from boundary 1. */
    private void insertFreshPlayer(UUID uuid) {
        try (Connection conn = open(); PreparedStatement stmt = conn.prepareStatement(
            "INSERT INTO alinvite_players (uuid, invite_code, personal_code_ready, total_invites,"
                + " invite_slots, last_slot_replenish, quota_periods_granted, quota_legacy_migrated,"
                + " quota_next_boundary, created_at) VALUES (?, ?, 0, 0, 0, 0, 0, 1, 1, ?)")) {
            stmt.setString(1, uuid.toString());
            stmt.setString(2, "B" + uuid.toString().replace("-", "").substring(0, 15));
            stmt.setLong(3, System.currentTimeMillis());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void markRegistered(UUID uuid) {
        try (Connection conn = open(); PreparedStatement stmt = conn.prepareStatement(
            "INSERT INTO alinvite_hidden_registration_codes"
                + " (code, batch_id, claimed_uuid, claimed_name, created_at, claimed_at)"
                + " VALUES (?, ?, ?, ?, ?, ?)")) {
            stmt.setString(1, "REG-" + uuid.toString().substring(0, 8));
            stmt.setString(2, "batch");
            stmt.setString(3, uuid.toString());
            stmt.setString(4, "reg");
            stmt.setLong(5, System.currentTimeMillis());
            stmt.setLong(6, System.currentTimeMillis());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void insertPending(String target, String display, UUID inviter) {
        try (Connection conn = open(); PreparedStatement stmt = conn.prepareStatement(
            "INSERT INTO alinvite_pending_invites"
                + " (target_name, target_display_name, inviter_uuid, inviter_ip, created_at)"
                + " VALUES (?, ?, ?, ?, ?)")) {
            stmt.setString(1, target);
            stmt.setString(2, display);
            stmt.setString(3, inviter.toString());
            stmt.setString(4, "10.0.0.1");
            stmt.setLong(5, System.currentTimeMillis());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private String reservedCodeOf(String target) {
        try (Connection conn = open(); PreparedStatement stmt = conn.prepareStatement(
            "SELECT reserved_code FROM alinvite_pending_invites WHERE target_name = ?")) {
            stmt.setString(1, target);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private String codeStatus(String code) {
        try (Connection conn = open(); PreparedStatement stmt = conn.prepareStatement(
            "SELECT status FROM alinvite_invite_codes WHERE code = ?")) {
            stmt.setString(1, code);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private int countRows(String sql) throws Exception {
        try (Connection conn = open();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** Sanity check that the policy math used by the tests matches the production helper. */
    @Test
    void boundaryMathMatchesProductionPolicy() {
        assertEquals(1, InviteQuotaPolicy.boundaryIndex(48.0, 48.0));
        assertEquals(0, InviteQuotaPolicy.boundaryIndex(47.9, 48.0));
        assertEquals(2, InviteQuotaPolicy.firstGrantableBoundary(50.0, 48.0));
        assertEquals(3, InviteQuotaPolicy.firstGrantableBoundary(48.0, 24.0));
    }
}
