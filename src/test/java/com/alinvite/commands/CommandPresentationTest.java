package com.alinvite.commands;

import com.alinvite.config.ConfigManager;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CommandPresentationTest {
    @Test void normalizesLegacyRootsAndNamespacedCommandsWithoutChangingArguments() {
        assertEquals("请用 /travel bind CODE，或 /travel add NewPlayer",
            CommandPresentation.preferTravel("请用 /alinvite bind CODE，或 /ALInvite:ALInvite add NewPlayer"));
        assertEquals("/travel /travel help /travel status /travel&7 /travel。",
            CommandPresentation.preferTravel("/ali /invite help /alinvite status /alinvite&7 /alinvite。"));
        assertNull(CommandPresentation.preferTravel(null));
    }

    @Test void preservesPluginIdentityPermissionsPlaceholdersUrlsAndOtherCommands() {
        String text = "ALInvite alinvite.use %alinvite_playtime% com.alinvite "
            + "https://github.com/ColdTearsYY/ALInvite /alinvite-extra /alinvites /other:alinvite";
        assertEquals(text, CommandPresentation.preferTravel(text));
    }

    @Test void existingCustomizedLanguageListsAndRawMessagesUseTravelWithoutMutatingConfiguration() throws Exception {
        ConfigManager manager = new ConfigManager(null);
        YamlConfiguration config = new YamlConfiguration();
        YamlConfiguration lang = new YamlConfiguration();
        lang.set("prefix", "&6[自定义] ");
        lang.set("commands.help", List.of("&e/alinvite bind", "自定义说明 /alinvite admin open [数量]"));
        for (var entry : Map.of("config", config, "langConfig", lang).entrySet()) {
            Field field = ConfigManager.class.getDeclaredField(entry.getKey());
            field.setAccessible(true);
            field.set(manager, entry.getValue());
        }
        assertTrue(manager.getMessage("commands.help").contains("[自定义]"));
        assertTrue(manager.getMessageRaw("commands.help").contains("/travel admin open"));
        assertFalse(manager.getMessage("commands.help").contains("/alinvite"));
        assertTrue(manager.getMessageList("commands.help").stream().allMatch(s -> s.contains("/travel")));
        assertTrue(lang.getStringList("commands.help").get(0).contains("/alinvite"));
    }

    @Test void legacyMenuLoreAndMiniMessageClickHintsUseTravel() {
        assertEquals("§7查看 /travel status", ConfigManager.colorize("&7查看 /alinvite status"));
        assertEquals("§e/travel bind", ConfigManager.colorize("&e/alinvite bind"));
        String serialized = CommandPresentation.preferTravel(
            "<click:run_command:'/alinvite bind CODE'>/alinvite bind</click>");
        assertTrue(serialized.contains("/travel bind CODE"));
        assertFalse(serialized.contains("/alinvite"));
    }

    @SuppressWarnings("unchecked")
    @Test void preferredRootRetainsAllLegacyAliasesAndOriginalPermission() throws Exception {
        try (var stream = getClass().getResourceAsStream("/plugin.yml")) {
            Map<String, Object> root = new Yaml().load(stream);
            Map<String, Object> commands = (Map<String, Object>) root.get("commands");
            assertEquals(List.of("travel"), List.copyOf(commands.keySet()));
            Map<String, Object> travel = (Map<String, Object>) commands.get("travel");
            assertEquals(List.of("alinvite", "ali", "invite"), travel.get("aliases"));
            assertEquals("alinvite.use", travel.get("permission"));
            assertEquals("/travel [args]", travel.get("usage"));
        }
    }
}
