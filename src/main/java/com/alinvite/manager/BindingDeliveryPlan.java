package com.alinvite.manager;

import com.alinvite.database.delivery.DeliveryDao;
import com.alinvite.database.ledger.InviteLedgerDao;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Immutable configuration snapshot; runs only inside the caller's JDBC transaction. */
public final class BindingDeliveryPlan {
    public record Reward(String type, String value) {}
    public record Gift(List<Reward> rewards, long durationMillis) {
        public Gift { rewards = List.copyOf(rewards); }
    }
    public record Milestone(String name, List<Reward> rewards) {
        public Milestone { rewards = List.copyOf(rewards); }
    }
    public record Payload(String type, String value, String serverId, int milestone,
                          String name, List<Reward> rewards, String pointsType, String pointsCommand) {}

    private static final Gson JSON = new Gson();
    private final DeliveryDao deliveries;
    private final String prefix;
    private final Map<String, Gift> gifts;
    private final Map<Integer, Milestone> milestones;
    private final String defaultGift, whitelistCommand, serverId, pointsType, pointsCommand;
    private final boolean requireGift, autoClaim, whitelistEnabled;

    public BindingDeliveryPlan(DeliveryDao deliveries, String prefix, Map<String, Gift> gifts,
            Map<Integer, Milestone> milestones, String defaultGift, boolean requireGift,
            boolean autoClaim, boolean whitelistEnabled, String whitelistCommand, String serverId,
            String pointsType, String pointsCommand) {
        this.deliveries = deliveries;
        this.prefix = prefix;
        this.gifts = Map.copyOf(gifts);
        this.milestones = Map.copyOf(milestones);
        this.defaultGift = defaultGift;
        this.requireGift = requireGift;
        this.autoClaim = autoClaim;
        this.whitelistEnabled = whitelistEnabled;
        this.whitelistCommand = whitelistCommand;
        this.serverId = serverId;
        this.pointsType = pointsType;
        this.pointsCommand = pointsCommand;
    }

    public static Payload decode(String json) { return JSON.fromJson(json, Payload.class); }

    public void onBound(Connection conn, InviteLedgerDao.BindRequest request,
                        InviteLedgerDao.BindOutcome outcome) throws SQLException {
        List<DeliveryDao.WorkSpec> jobs = new ArrayList<>();
        UUID invitee = request.inviteeUuid();
        if (whitelistEnabled) {
            boolean nativeWhitelist = "whitelist add {player}".equalsIgnoreCase(whitelistCommand.trim());
            Payload payload = new Payload(nativeWhitelist ? "native_whitelist" : "command",
                whitelistCommand, serverId, 0, null, null, null, null);
            jobs.add(new DeliveryDao.WorkSpec("bind:" + invitee + ":whitelist", invitee,
                "whitelist", JSON.toJson(payload), nativeWhitelist));
        }
        if (outcome.giftEnabled) {
            String giftId = null;
            long purchasedAt = 0L;
            try (PreparedStatement stmt = conn.prepareStatement("SELECT gift_id, gift_purchase_time FROM "
                    + prefix + "players WHERE uuid = ?")) {
                stmt.setString(1, request.inviterUuid().toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) { giftId = rs.getString(1); purchasedAt = rs.getLong(2); }
                }
            }
            Gift selected = giftId == null ? null : gifts.get(giftId);
            if (selected != null && selected.durationMillis() > 0 && purchasedAt > 0
                    && System.currentTimeMillis() - purchasedAt >= selected.durationMillis()) {
                giftId = null;
                selected = null;
            }
            if (giftId != null || !requireGift) {
                if (selected == null) selected = gifts.get(defaultGift);
                if (selected != null) jobs.addAll(rewardJobs("bind:" + invitee + ":gift", invitee,
                    selected.rewards(), pointsType, pointsCommand));
            }
        }
        deliveries.enqueue(conn, jobs);
        if (autoClaim) {
            for (int required : milestones.keySet().stream().sorted().toList()) {
                if (required <= outcome.totalInvites) claimMilestone(conn, request.inviterUuid(), required);
            }
        }
    }

    /** Caller owns the player row lock. Claimed means accepted for durable delivery, not paid. */
    public boolean claimMilestone(Connection conn, UUID player, int required) throws SQLException {
        Milestone milestone = milestones.get(required);
        if (milestone == null) return false;
        String claimedJson;
        try (PreparedStatement stmt = conn.prepareStatement("SELECT total_invites, milestone_enabled, claimed_milestones"
                + " FROM " + prefix + "players WHERE uuid = ?")) {
            stmt.setString(1, player.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next() || rs.getInt(1) < required || !rs.getBoolean(2)) return false;
                claimedJson = rs.getString(3);
            }
        }
        Set<String> claimed = new LinkedHashSet<>();
        if (claimedJson != null && !claimedJson.isBlank()) {
            try {
                List<String> existing = JSON.fromJson(claimedJson, new TypeToken<List<String>>() {}.getType());
                if (existing != null) claimed.addAll(existing);
            } catch (RuntimeException error) {
                throw new SQLException("Malformed historical milestone claims; refusing to replay", error);
            }
        }
        if (!claimed.add(Integer.toString(required))) return false;
        Payload payload = new Payload("milestone", null, null, required, milestone.name(),
            milestone.rewards(), pointsType, pointsCommand);
        deliveries.enqueue(conn, List.of(new DeliveryDao.WorkSpec("milestone:" + player + ":" + required,
            player, "milestone", JSON.toJson(payload), true)));
        try (PreparedStatement stmt = conn.prepareStatement("UPDATE " + prefix
                + "players SET claimed_milestones = ? WHERE uuid = ?")) {
            stmt.setString(1, JSON.toJson(claimed));
            stmt.setString(2, player.toString());
            if (stmt.executeUpdate() != 1) throw new SQLException("Milestone owner disappeared");
        }
        return true;
    }

    public static List<DeliveryDao.WorkSpec> rewardJobs(String base, UUID recipient,
            List<Reward> rewards, String pointsType, String pointsCommand) {
        List<DeliveryDao.WorkSpec> result = new ArrayList<>();
        for (int i = 0; i < rewards.size(); i++) {
            Reward reward = rewards.get(i);
            Payload payload = new Payload(reward.type(), reward.value(), null, 0, null, null,
                pointsType, pointsCommand);
            result.add(new DeliveryDao.WorkSpec(base + ":" + i, recipient, "reward", JSON.toJson(payload), false));
        }
        return result;
    }
}
