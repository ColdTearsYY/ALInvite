package com.alinvite.manager;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AutoVeteranManagerTest {
    @Test
    void replacesOnlyTheObsoleteDefaultUnlockClaim() {
        assertEquals(AutoVeteranManager.DEFAULT_NOTIFY_MESSAGE,
            AutoVeteranManager.resolveNotifyMessage(AutoVeteranManager.LEGACY_INVITE_UNLOCK_MESSAGE));
    }

    @Test
    void preservesCustomAndDisabledNotifications() {
        assertEquals("&bVeteran access granted.", AutoVeteranManager.resolveNotifyMessage("&bVeteran access granted."));
        assertEquals("", AutoVeteranManager.resolveNotifyMessage(""));
        assertEquals(null, AutoVeteranManager.resolveNotifyMessage(null));
    }
    @Test
    void veteranThresholdIsSeparateFromInviteQuotaIntervals() {
        var config = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        assertEquals("24h", config.getString("auto_veteran.playtime"));
        assertEquals(48.0, config.getDouble("invite_quota.groups.default.hours_per_slot"));
        assertEquals(24.0, config.getDouble("invite_quota.groups.vip1.hours_per_slot"));
    }

}
