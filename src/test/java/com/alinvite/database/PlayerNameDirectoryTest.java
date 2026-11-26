package com.alinvite.database;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerNameDirectoryTest {
    private Path file;
    private PlayerNameDirectory directory;

    @BeforeEach
    void setUp() throws Exception {
        file = Files.createTempFile("alinvite-player-names-", ".db");
        try (Connection conn = open(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE alinvite_bound_player_names (name_lower VARCHAR(16) PRIMARY KEY,"
                + " player_uuid VARCHAR(36) NOT NULL, display_name VARCHAR(16) NOT NULL, inviter_uuid VARCHAR(36) NOT NULL,"
                + " bound_at BIGINT NOT NULL, updated_at BIGINT NOT NULL)");
        }
        directory = new PlayerNameDirectory(this::open, "alinvite_", false);
        directory.ensureSchema();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (file != null) {
            Files.deleteIfExists(Path.of(file + "-wal"));
            Files.deleteIfExists(Path.of(file + "-shm"));
            Files.deleteIfExists(file);
        }
    }

    @Test
    void renamedPlayersResolveOnlyByTheirCurrentExactName() throws Exception {
        UUID player = UUID.randomUUID();
        directory.upsert(player, "OldName");
        directory.upsert(player, "CurrentName");

        assertNull(directory.find("oldname"));
        PlayerNameDirectory.PlayerIdentity identity = directory.find("CURRENTNAME");
        assertNotNull(identity);
        assertEquals(player, identity.uuid());
        assertEquals("CurrentName", identity.currentName());
    }

    @Test
    void bulkLuckPermsImportIsSearchableAndSuggestionsCanUseNames() throws Exception {
        UUID one = UUID.randomUUID();
        UUID two = UUID.randomUUID();
        directory.upsertAll(List.of(
            new PlayerNameDirectory.PlayerIdentity(one, "Havasmoj"),
            new PlayerNameDirectory.PlayerIdentity(two, "HarborFox")));

        assertEquals(one, directory.find("havasmoj").uuid());
        assertEquals(List.of("HarborFox", "Havasmoj"), directory.listNames());
        assertTrue(directory.listNames().stream().anyMatch("Havasmoj"::equals));
    }

    private Connection open() throws java.sql.SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
    }
}
