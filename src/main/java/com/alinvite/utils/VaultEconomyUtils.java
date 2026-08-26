package com.alinvite.utils;

import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;

public final class VaultEconomyUtils {

    private VaultEconomyUtils() {
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object getProvider(Plugin plugin) throws ReflectiveOperationException {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("Vault")) {
            return null;
        }

        Class economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
        RegisteredServiceProvider<?> registration = plugin.getServer().getServicesManager()
            .getRegistration(economyClass);
        return registration == null ? null : registration.getProvider();
    }

    public static boolean isAvailable(Plugin plugin) {
        try {
            return getProvider(plugin) != null;
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    public static boolean deposit(Plugin plugin, OfflinePlayer player, double amount) {
        try {
            Object economy = getProvider(plugin);
            if (economy == null) return false;
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            Method depositPlayer = economyClass.getMethod("depositPlayer", OfflinePlayer.class, double.class);
            depositPlayer.invoke(economy, player, amount);
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            plugin.getLogger().warning("Failed to deposit through Vault: " + e.getMessage());
            return false;
        }
    }

    public static boolean has(Plugin plugin, OfflinePlayer player, double amount) {
        try {
            Object economy = getProvider(plugin);
            if (economy == null) return false;
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            Method has = economyClass.getMethod("has", OfflinePlayer.class, double.class);
            return Boolean.TRUE.equals(has.invoke(economy, player, amount));
        } catch (ReflectiveOperationException | LinkageError e) {
            plugin.getLogger().warning("Failed to check Vault balance: " + e.getMessage());
            return false;
        }
    }

    public static boolean withdraw(Plugin plugin, OfflinePlayer player, double amount) {
        try {
            Object economy = getProvider(plugin);
            if (economy == null) return false;
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            Method withdrawPlayer = economyClass.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
            withdrawPlayer.invoke(economy, player, amount);
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            plugin.getLogger().warning("Failed to withdraw through Vault: " + e.getMessage());
            return false;
        }
    }
}
