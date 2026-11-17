package com.alinvite.manager;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/** Immutable per-calculation snapshot shared by online display and offline settlement. */
public record RebateRatePolicy(double baseRate, List<Tier> tiers) {
    public record Tier(String permission, double rate, int weight) {}

    public RebateRatePolicy {
        tiers = List.copyOf(tiers);
    }

    public static RebateRatePolicy from(ConfigurationSection config) {
        String prefix = config.getString("points_rebate.permission_prefix", "alinvite.rebate");
        ConfigurationSection section = config.getConfigurationSection("points_rebate.rebate_rates");
        double base = config.getDouble("points_rebate.rebate_rates.base.rate", 0.05);
        List<Tier> tiers = section == null ? List.of() : section.getKeys(false).stream()
            .filter(key -> !key.equals("base") && section.isConfigurationSection(key))
            .map(key -> new Tier(prefix + "." + key, section.getDouble(key + ".rate", 0.10),
                section.getInt(key + ".weight", 1)))
            .sorted(Comparator.comparingInt(Tier::weight).reversed())
            .toList();
        return new RebateRatePolicy(base, tiers);
    }

    public double select(Predicate<String> hasPermission) {
        for (Tier tier : tiers) {
            if (hasPermission.test(tier.permission())) return tier.rate();
        }
        return baseRate;
    }
}
