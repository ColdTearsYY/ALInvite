package com.alinvite.database.ledger;

import com.alinvite.database.delivery.DeliveryDao;
import com.alinvite.database.playtime.PlaytimeDao;
import com.alinvite.manager.BindingDeliveryPlan;
import com.alinvite.manager.QuotaRule;
import com.alinvite.manager.QuotaRuleSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/** The same suite runs on SQLite by default, or on a real isolated MySQL via -Dalinvite.test.mysql. */
class AtomicInviteLedgerTest {
    @TempDir Path temp;
    final QuotaRule normal = QuotaRule.recurring("default",48,1,0);
    final QuotaRule vip = QuotaRule.recurring("vip1",24,2,10);
    final QuotaRuleSet rules = QuotaRuleSet.of(List.of(normal,vip),"default");
    final AtomicLong clock = new AtomicLong(1_800_000_000_000L);
    final AtomicInteger seq = new AtomicInteger();
    String url, prefix;
    boolean mysql;
    InviteLedgerDao ledger;
    AtomicInviteLedger atomic;
    PlaytimeDao time;
    DeliveryDao deliveries;
    UUID owner, session;
    static final long HOUR=3_600_000L, TTL=20*HOUR;

    Connection open() throws SQLException { return mysql ? DriverManager.getConnection(url,"root","") : DriverManager.getConnection(url); }
    @BeforeEach void setup() throws Exception {
        url = System.getProperty("alinvite.test.mysql", "jdbc:sqlite:"+temp.resolve("atomic.db"));
        mysql = url.startsWith("jdbc:mysql:");
        prefix = "a"+UUID.randomUUID().toString().replace("-", "").substring(0,10)+"_";
        String engine=mysql ? " ENGINE=InnoDB" : "";
        try (Connection conn=open(); Statement s=conn.createStatement()) {
            if (!mysql) s.execute("PRAGMA journal_mode=WAL");
            s.execute("CREATE TABLE "+prefix+"players (uuid VARCHAR(36) PRIMARY KEY,invite_code VARCHAR(16) NOT NULL UNIQUE,bound_invite_code VARCHAR(16),personal_code_ready INT NOT NULL DEFAULT 0,total_invites INT NOT NULL DEFAULT 0,invite_slots INT NOT NULL DEFAULT 0,last_slot_replenish BIGINT NOT NULL DEFAULT 0,quota_periods_granted INT NOT NULL DEFAULT 0,bind_ip VARCHAR(45),last_invite_time BIGINT,last_code_change BIGINT,milestone_enabled INT NOT NULL DEFAULT 1,rebate_enabled INT NOT NULL DEFAULT 1,gift_enabled INT NOT NULL DEFAULT 1,gift_id VARCHAR(32),gift_purchase_time BIGINT,claimed_milestones TEXT,created_at BIGINT NOT NULL)"+engine);
            s.execute("CREATE TABLE "+prefix+"pending_invites (target_name VARCHAR(16) PRIMARY KEY,target_display_name VARCHAR(16) NOT NULL,inviter_uuid VARCHAR(36) NOT NULL,inviter_ip VARCHAR(45),created_at BIGINT NOT NULL)"+engine);
            s.execute("CREATE TABLE "+prefix+"records (id "+(mysql?"INT AUTO_INCREMENT":"INTEGER")+" PRIMARY KEY"+(mysql?"":" AUTOINCREMENT")+",inviter_uuid VARCHAR(36) NOT NULL,invitee_uuid VARCHAR(36) NOT NULL UNIQUE,invitee_ip VARCHAR(45) NOT NULL,invitee_name VARCHAR(16) NOT NULL,invited_at BIGINT NOT NULL)"+engine);
            s.execute("CREATE TABLE "+prefix+"hidden_registration_codes (code VARCHAR(32) PRIMARY KEY,batch_id VARCHAR(36),claimed_uuid VARCHAR(36),claimed_name VARCHAR(16),created_at BIGINT,claimed_at BIGINT)"+engine);
            s.execute("CREATE TABLE "+prefix+"ip_claims (ip VARCHAR(45) PRIMARY KEY,bind_count INT NOT NULL DEFAULT 0,last_bound_at BIGINT NOT NULL DEFAULT 0)"+engine);
            s.execute("CREATE TABLE "+prefix+"bound_player_names (name_lower VARCHAR(16) PRIMARY KEY,player_uuid VARCHAR(36) NOT NULL,display_name VARCHAR(16) NOT NULL,inviter_uuid VARCHAR(36) NOT NULL,bound_at BIGINT NOT NULL,updated_at BIGINT NOT NULL)"+engine);
        }
        ledger=new InviteLedgerDao(this::open,prefix,mysql,()->"T"+seq.incrementAndGet());
        ledger.ensureSchema();
        time=new PlaytimeDao(this::open,prefix,mysql); time.ensureSchema();
        deliveries=new DeliveryDao(this::open,prefix,mysql); deliveries.ensureSchema();
        atomic=newAtomic(); atomic.ensureSchema();
        owner=UUID.randomUUID(); session=UUID.randomUUID();
        ledger.ensureEligiblePlayer(owner,true);
        time.getTotalMillis(owner);
        assertEquals(0,atomic.tick(owner,0,normal,rules::byKey).granted());
    }
    AtomicInviteLedger newAtomic() {
        var dao=new AtomicInviteLedger(this::open,prefix,mysql,()->"T"+seq.incrementAndGet(),clock::get);
        dao.configure(rules,true); return dao;
    }
    void hours(long hours) {
        try { time.record(owner,session,hours*HOUR,0); }
        catch (SQLException error) { throw new IllegalStateException(error); }
    }
    String earn() { hours(48); assertEquals(1,atomic.tick(owner,0,normal,rules::byKey).granted()); return ledger.firstAvailable(owner); }
    InviteLedgerDao.BindRequest request(String code, String target) {
        UUID invitee=UUID.randomUUID();
        return new InviteLedgerDao.BindRequest(owner,invitee,"10.2."+seq.incrementAndGet()+".1",null,
            "P"+invitee.toString().substring(0,8),code,target,target==null,InviteLedgerDao.BindSettings.defaults());
    }
    AtomicInviteLedger.Pending reserve() { earn(); assertEquals(InviteLedgerDao.ReserveStatus.SUCCESS,atomic.reserve(owner,null,"newplayer","NewPlayer",0,TTL).status()); return atomic.getPending("newplayer"); }

    @Test void remoteConsumeSettlesFull96HourBoundaryBeforeFreeingStock() {
        String code=earn(); hours(96);
        assertTrue(newAtomic().bind(request(code,null),null).success());
        assertEquals(0,atomic.tick(owner,0,normal,rules::byKey).granted());
        assertEquals(0,ledger.countAvailable(owner));
        assertEquals(3,atomic.inventory(owner).nextBoundary());
        hours(144); assertEquals(1,atomic.tick(owner,0,normal,rules::byKey).granted());
    }
    @Test void reservedClaimAlsoSkipsFull96HourBoundary() {
        var pending=reserve(); hours(96);
        assertTrue(newAtomic().bind(request(pending.reservedCode(),pending.targetName()),pending.reservationId()).success());
        assertEquals(0,atomic.tick(owner,0,normal,rules::byKey).granted());
        assertEquals(3,atomic.inventory(owner).nextBoundary());
    }
    @Test void reservationSnapshotsExactly20WallClockHours() {
        var pending=reserve();
        assertEquals(TTL,pending.expiresAt()-pending.createdAt());
        clock.set(pending.expiresAt()-1);
        assertTrue(atomic.bind(request(pending.reservedCode(),pending.targetName()),pending.reservationId()).success());
        clock.incrementAndGet(); assertTrue(atomic.expirePending(100).isEmpty());
        assertEquals(0,ledger.countAvailable(owner));
    }
    @Test void claimAtExactExpiryRefundsOriginalAndNeverBinds() {
        var pending=reserve(); clock.set(pending.expiresAt());
        assertFalse(atomic.bind(request(pending.reservedCode(),pending.targetName()),pending.reservationId()).success());
        assertNull(atomic.getPending(pending.targetName()));
        assertEquals(List.of(pending.reservedCode()),ledger.listAvailable(owner));
        assertTrue(atomic.expirePending(100).isEmpty());
    }
    @Test void expiryWorksOfflineAfterReopenAndDoesNotRetimeExistingRecords() throws Exception {
        var pending=reserve(); var reopened=newAtomic(); reopened.ensureSchema();
        assertEquals(pending.expiresAt(),reopened.getPending(pending.targetName()).expiresAt());
        clock.set(pending.expiresAt()+HOUR);
        assertEquals(List.of(owner),reopened.expirePending(100));
        assertFalse(reopened.expire(pending));
        assertEquals(List.of(pending.reservedCode()),ledger.listAvailable(owner));
        reopened.reserve(owner,null,pending.targetName(),pending.displayName(),0,0);
        assertNull(reopened.getPending(pending.targetName()).expiresAt());
    }
    @Test void oldCancelCannotReleaseRecreatedSameTargetEvenWithSameCode() {
        var pending=reserve(); assertTrue(atomic.release(pending));
        atomic.reserve(owner,null,pending.targetName(),pending.displayName(),0,TTL);
        var next=atomic.getPending(pending.targetName());
        assertEquals(pending.reservedCode(),next.reservedCode());
        assertNotEquals(pending.reservationId(),next.reservationId());
        assertFalse(atomic.release(pending));
        assertFalse(atomic.bind(request(pending.reservedCode(),pending.targetName()),pending.reservationId()).success());
        assertEquals(next,atomic.getPending(next.targetName()));
    }
    @Test void availableCodesAndLegacyUnlimitedReservationsNeverExpire() throws Exception {
        String code=earn();
        ledger.reserve(owner,null,"legacy","Legacy",0);
        atomic.ensureSchema(); var pending=atomic.getPending("legacy");
        assertNull(pending.expiresAt()); assertNotNull(pending.reservationId());
        clock.addAndGet(1000*HOUR); assertTrue(atomic.expirePending(100).isEmpty());
        assertTrue(atomic.release(pending));
        assertEquals(List.of(code),ledger.listAvailable(owner));
    }
    @Test void cancellationOwnershipAndListScope() {
        var pending=reserve(); UUID other=UUID.randomUUID();
        assertTrue(atomic.listPending(other,50).isEmpty());
        var forged=new AtomicInviteLedger.Pending(pending.targetName(),pending.displayName(),other,null,
            pending.reservedCode(),pending.reservationId(),pending.createdAt(),pending.expiresAt());
        assertFalse(atomic.release(forged));
        assertEquals(1,atomic.listPending(owner,50).size());
    }
    @Test void claimCancelAndExpiryRaceConservesExactlyOneQualification() throws Exception {
        var pending=reserve();
        ExecutorService pool=Executors.newFixedThreadPool(3);
        try {
            CountDownLatch start=new CountDownLatch(1);
            Future<Boolean> claim=pool.submit(()->{start.await(); return newAtomic().bind(request(pending.reservedCode(),pending.targetName()),pending.reservationId()).success();});
            Future<Boolean> cancel=pool.submit(()->{start.await(); return newAtomic().release(pending);});
            Future<Boolean> expire=pool.submit(()->{start.await(); return newAtomic().expire(pending);});
            start.countDown(); boolean bound=claim.get(20,TimeUnit.SECONDS); boolean returned=cancel.get(20,TimeUnit.SECONDS);
            assertFalse(expire.get(20,TimeUnit.SECONDS)); assertNotEquals(bound,returned);
            assertEquals(bound?0:1,ledger.countAvailable(owner)); assertNull(atomic.getPending(pending.targetName()));
        } finally { pool.shutdownNow(); }
    }
    @Test void duplicateConcurrentConsumersHaveOneWinner() throws Exception {
        String code=earn(); hours(96);
        ExecutorService pool=Executors.newFixedThreadPool(4);
        try {
            List<Callable<Boolean>> calls=new ArrayList<>();
            for(int i=0;i<4;i++) calls.add(()->newAtomic().bind(request(code,null),null).success());
            int won=0; for(Future<Boolean> f:pool.invokeAll(calls)) if(f.get()) won++;
            assertEquals(1,won); assertEquals(0,ledger.countAvailable(owner)); assertEquals(3,atomic.inventory(owner).nextBoundary());
        } finally { pool.shutdownNow(); }
    }
    @Test void deliveryInsertFailureRollsBackBindingCodeAndQuotaCursor() throws Exception {
        String code=earn(); hours(96); var request=request(code,null);
        atomic.setBindHook((conn,req,outcome)->{
            deliveries.enqueue(conn,List.of(new DeliveryDao.WorkSpec("rollback",req.inviteeUuid(),"reward","{}",false)));
            throw new SQLException("injected after enqueue");
        });
        assertThrows(IllegalStateException.class,()->atomic.bind(request,null));
        assertNull(deliveries.find("rollback")); assertEquals(List.of(code),ledger.listAvailable(owner));
        assertEquals(2,atomic.inventory(owner).nextBoundary());
        try(Connection conn=open();Statement s=conn.createStatement();ResultSet rs=s.executeQuery("SELECT COUNT(*) FROM "+prefix+"records")) {rs.next();assertEquals(0,rs.getInt(1));}
        atomic.setBindHook((conn,req,out)->{}); assertTrue(atomic.bind(request,null).success());
    }
    @Test void bindingSavesGiftWhitelistAndMilestoneSnapshotsWithoutReplayingHistory() throws Exception {
        String code=earn();
        var gift=new BindingDeliveryPlan.Gift(List.of(new BindingDeliveryPlan.Reward("item","BREAD 32"),new BindingDeliveryPlan.Reward("points","20")),0);
        var milestone=new BindingDeliveryPlan.Milestone("First",List.of(new BindingDeliveryPlan.Reward("points","10")));
        var plan=new BindingDeliveryPlan(deliveries,prefix,Map.of("default",gift),Map.of(1,milestone),"default",false,true,true,"whitelist add {player}","A","PLAYERPOINTS","");
        atomic.setBindHook(plan::onBound); var req=request(code,null);
        assertTrue(atomic.bind(req,null).success()); assertEquals(3,deliveries.list(req.inviteeUuid(),50).size());
        assertEquals(1,deliveries.list(owner,50).size());
        var saved=deliveries.find("bind:"+req.inviteeUuid()+":gift:0");
        assertEquals("BREAD 32",BindingDeliveryPlan.decode(saved.payload()).value());
        assertFalse(atomic.bind(req,null).success()); assertEquals(4,deliveries.list(null,50).size());
        try(Connection conn=open()) {conn.setAutoCommit(false);assertFalse(plan.claimMilestone(conn,owner,1));conn.rollback();}
    }
    @Test void unregisteredQueryAndTickDoNotMintOrCreateAccount() {
        UUID locked=UUID.randomUUID();
        assertFalse(atomic.inventory(locked).registered());
        assertEquals(InviteLedgerDao.TickStatus.NOT_REGISTERED,atomic.tick(locked,1000,normal,rules::byKey).status());
        assertEquals(0,atomic.inventory(locked).available()); assertFalse(atomic.inventory(locked).registered());
    }
    @Test void unknownPersistedPolicyFailsWithoutSpendingInventory() throws Exception {
        String code=earn();
        try(Connection conn=open();PreparedStatement ps=conn.prepareStatement("UPDATE "+prefix+"players SET quota_policy_key='broken' WHERE uuid=?")) {ps.setString(1,owner.toString());ps.executeUpdate();}
        assertThrows(IllegalStateException.class,()->atomic.bind(request(code,null),null));
        assertEquals(List.of(code),ledger.listAvailable(owner));
    }

    @Test void closedOnlineWindowKeepsReservationCodeAndDoesNotEnqueueReward() throws Exception {
        var pending=reserve(); var req=request(pending.reservedCode(),pending.targetName());
        atomic.setBindHook((conn,r,out)->deliveries.enqueue(conn,List.of(
            new DeliveryDao.WorkSpec("guarded",r.inviteeUuid(),"reward","{}",false))));
        assertEquals(InviteLedgerDao.BindStatus.ADMISSION_CLOSED,
            atomic.bind(req,pending.reservationId(),()->false).status);
        assertEquals(pending,atomic.getPending(pending.targetName()));
        assertEquals(0,ledger.countAvailable(owner)); assertEquals(1,atomic.inventory(owner).reserved());
        assertNull(deliveries.find("guarded")); assertEquals(0,rowCount("records"));
        assertTrue(atomic.bind(req,pending.reservationId(),()->true).success());
        assertNull(atomic.getPending(pending.targetName())); assertNotNull(deliveries.find("guarded"));
        assertEquals(1,rowCount("records")); assertFalse(atomic.release(pending));
        assertFalse(atomic.bind(req,pending.reservationId(),()->true).success());
        assertEquals(1,deliveries.list(null,50).size());
    }

    @Test void deadlineAndDisconnectedSessionRejectBeforeConsumingThenNewLoginCanClaim() {
        var pending=reserve(); var req=request(pending.reservedCode(),pending.targetName());
        var gate=new com.alinvite.manager.GateClaimSession(100); gate.ready();
        long token=gate.beginLookup(99);
        assertEquals(InviteLedgerDao.BindStatus.ADMISSION_CLOSED,
            atomic.bind(req,pending.reservationId(),()->gate.claim(token,100)).status);
        gate.close();
        assertEquals(InviteLedgerDao.BindStatus.ADMISSION_CLOSED,
            atomic.bind(req,pending.reservationId(),()->gate.claim(token,99)).status);
        assertEquals(pending,atomic.getPending(pending.targetName()));
        var next=new com.alinvite.manager.GateClaimSession(200); next.ready();
        long newToken=next.beginLookup(101);
        assertTrue(atomic.bind(req,pending.reservationId(),()->next.claim(newToken,102)).success());
        assertEquals(0,atomic.inventory(owner).available()); assertEquals(0,atomic.inventory(owner).reserved());
    }

    @Test void simultaneousPreInvitationsReserveOnlyOneOwnersCode() throws Exception {
        earn(); UUID other=UUID.randomUUID(); ledger.ensureEligiblePlayer(other,true);
        atomic.tick(other,0,normal,rules::byKey); atomic.tick(other,48,normal,rules::byKey);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            var a=pool.submit(()->{start.await();return newAtomic().reserve(owner,null,"newplayer","NewPlayer",0,TTL);});
            var b=pool.submit(()->{start.await();return newAtomic().reserve(other,null,"newplayer","NewPlayer",0,TTL);});
            start.countDown(); var ar=a.get(20,TimeUnit.SECONDS); var br=b.get(20,TimeUnit.SECONDS);
            assertNotEquals(ar.status()==InviteLedgerDao.ReserveStatus.SUCCESS,br.status()==InviteLedgerDao.ReserveStatus.SUCCESS);
            assertEquals(1,atomic.listPending(null,50).size());
            assertEquals(1,atomic.inventory(owner).available()+atomic.inventory(other).available());
            assertEquals(1,atomic.inventory(owner).reserved()+atomic.inventory(other).reserved());
            assertTrue(atomic.release(atomic.getPending("newplayer")));
            assertEquals(2,atomic.inventory(owner).available()+atomic.inventory(other).available());
        }
    }

    @Test void liveClaimAndManualBindingCompeteForOneRelationshipAndOneReward() throws Exception {
        var pending=reserve(); UUID other=UUID.randomUUID(); ledger.ensureEligiblePlayer(other,true);
        atomic.tick(other,0,normal,rules::byKey); atomic.tick(other,48,normal,rules::byKey);
        var live=request(pending.reservedCode(),pending.targetName());
        var manual=new InviteLedgerDao.BindRequest(other,live.inviteeUuid(),live.inviteeIp(),null,
            live.inviteeName(),ledger.firstAvailable(other),null,true,InviteLedgerDao.BindSettings.defaults());
        var liveDao=newAtomic(); var manualDao=newAtomic();
        AtomicInviteLedger.BindHook hook=(conn,r,out)->deliveries.enqueue(conn,List.of(
            new DeliveryDao.WorkSpec("winner:"+r.inviteeUuid(),r.inviteeUuid(),"reward","{}",false)));
        liveDao.setBindHook(hook); manualDao.setBindHook(hook);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            var a=pool.submit(()->{start.await();return liveDao.bind(live,pending.reservationId(),()->true);});
            var b=pool.submit(()->{start.await();return manualDao.bind(manual,null);});
            start.countDown(); boolean liveWon=a.get(20,TimeUnit.SECONDS).success();
            assertNotEquals(liveWon,b.get(20,TimeUnit.SECONDS).success());
            assertEquals(1,rowCount("records")); assertEquals(1,deliveries.list(null,50).size());
            if (!liveWon) assertTrue(atomic.release(pending));
            assertFalse(atomic.release(pending));
            assertEquals(1,atomic.inventory(owner).available()+atomic.inventory(other).available());
            assertEquals(0,atomic.inventory(owner).reserved()+atomic.inventory(other).reserved());
        }
    }

    int rowCount(String table) throws SQLException {
        try(Connection conn=open(); Statement s=conn.createStatement(); ResultSet rs=s.executeQuery("SELECT COUNT(*) FROM "+prefix+table)) {
            rs.next(); return rs.getInt(1);
        }
    }
}
