package com.alinvite.gui.render;

import com.alinvite.ALInvite;
import com.alinvite.gui.ActionPdc;
import com.alinvite.gui.GuiPageStore;
import com.alinvite.gui.MenuConfig;
import com.alinvite.gui.MenuItem;
import com.alinvite.gui.MenuNames;
import com.alinvite.gui.MenuSessionStore;
import com.alinvite.gui.Pagination;
import com.alinvite.manager.MilestoneManager;
import com.alinvite.scheduler.ALInviteScheduler;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 邀请中心（里程碑）菜单。
 * 分页容量 = shape 中动态字符的槽位数（不再硬编码），页码计算与实际渲染严格一致。
 */
public class VeteranMenuRenderer extends BaseMenuRenderer<VeteranMenuRenderer.VeteranData> {

    public record VeteranData(RenderContext context,
                              int total,
                              Set<String> claimed,
                              List<Map.Entry<Integer, MilestoneManager.Milestone>> entries) {
    }

    public VeteranMenuRenderer(ALInvite plugin, MenuSessionStore sessions, GuiPageStore pages,
                               ALInviteScheduler scheduler, ActionPdc pdc) {
        super(plugin, sessions, pages, scheduler, pdc);
    }

    @Override
    protected String menuName() {
        return MenuNames.VETERAN;
    }

    @Override
    protected VeteranData loadData(Player player) {
        UUID uuid = player.getUniqueId();
        RenderContext context = plugin.getPlaceholderResolver().renderContext(player);

        int total = plugin.getCacheManager().getStats(uuid, id -> {
            var data = plugin.getDatabaseManager().getPlayerDataSync(id);
            return data != null ? data.totalInvites : 0;
        });
        Set<String> claimed = parseStringSet(plugin.getDatabaseManager().getClaimedMilestonesSync(uuid));

        context.add("current", String.valueOf(total));

        // 当前礼包预览（G 槽）的奖励行
        var gift = resolveCurrentGift(uuid);
        if (gift != null) {
            context.addList("gift_reward_lore", rewardLines(gift.lore, gift.rewards));
        }

        List<Map.Entry<Integer, MilestoneManager.Milestone>> entries =
            new ArrayList<>(plugin.getMilestoneManager().getMilestones().entrySet());
        return new VeteranData(context, total, claimed, entries);
    }

    private com.alinvite.manager.GiftManager.GiftConfig resolveCurrentGift(UUID uuid) {
        String giftId = plugin.getDatabaseManager().getGiftIdSync(uuid);
        if (giftId == null) {
            boolean requireGift = plugin.getConfigManager().getConfig()
                .getBoolean("new_player_reward.require_gift", false);
            if (requireGift) {
                return null;
            }
            giftId = plugin.getConfigManager().getConfig()
                .getString("new_player_reward.default_gift_id", "default");
        }
        var gift = plugin.getGiftManager().getGift(giftId);
        return gift != null ? gift : plugin.getGiftManager().getGift(
            plugin.getConfigManager().getConfig().getString("new_player_reward.default_gift_id", "default"));
    }

    @Override
    protected RenderContext contextOf(VeteranData data) {
        return data.context();
    }

    @Override
    protected void fillDynamic(Player player, MenuConfig config, Inventory inventory, VeteranData data, int page) {
        List<Integer> slots = config.getDynamicSlots();
        if (slots.isEmpty()) {
            return;
        }

        UUID uuid = player.getUniqueId();
        Map<UUID, Integer> pageView = new HashMap<>();
        pageView.put(uuid, page);
        Pagination.Page<Map.Entry<Integer, MilestoneManager.Milestone>> result =
            Pagination.page(data.entries(), pageView, uuid, slots.size());
        pages.set(uuid, menuName(), result.currentPage());

        RenderContext pageContext = data.context().copy()
            .add("page", String.valueOf(result.currentPage()))
            .add("total_pages", String.valueOf(result.totalPages()))
            .add("prev_page_hint", result.currentPage() <= 1
                ? langRaw("menu.page.first_page") : langRaw("menu.page.prev_hint"))
            .add("next_page_hint", result.currentPage() >= result.totalPages()
                ? langRaw("menu.page.last_page") : langRaw("menu.page.next_hint"));

        MenuItem milestoneItem = config.getItems().values().stream()
            .filter(MenuItem::isDynamic)
            .findFirst()
            .orElse(null);
        if (milestoneItem == null) {
            return;
        }

        int index = 0;
        for (Map.Entry<Integer, MilestoneManager.Milestone> entry : result.entries()) {
            if (index >= slots.size()) {
                break;
            }
            int required = entry.getKey();
            MilestoneManager.Milestone milestone = entry.getValue();

            String state;
            if (data.claimed().contains(String.valueOf(required))) {
                state = "claimed";
            } else if (data.total() >= required) {
                state = "available";
            } else {
                state = "locked";
            }

            RenderContext itemContext = pageContext.copy()
                .add("milestone_name", milestone.name)
                .add("milestone_required", String.valueOf(required))
                .add("required", String.valueOf(required))
                .add("remaining", String.valueOf(Math.max(0, required - data.total())))
                .add("milestone_status", statusText(state))
                .addList("reward_lore", rewardLines(milestone.lore, milestone.rewards));

            setItemSafe(inventory, slots.get(index),
                MenuItems.build(plugin, pdc(), milestoneItem, milestoneItem.getState(state), itemContext));
            index++;
        }

        renderPagingButtons(config, inventory, pageContext);
    }

    private List<String> rewardLines(List<String> loreOverride, List<MilestoneManager.Reward> rewards) {
        if (loreOverride != null && !loreOverride.isEmpty()) {
            return loreOverride;
        }
        if (rewards == null || rewards.isEmpty()) {
            return List.of(langRaw("menu.reward.none"));
        }
        List<String> lines = new ArrayList<>();
        for (MilestoneManager.Reward reward : rewards) {
            String value = String.valueOf(reward.value);
            lines.add(switch (reward.type) {
                case "money" -> langRaw("menu.reward.money").replace("{value}", value);
                case "points" -> langRaw("menu.reward.points").replace("{value}", value);
                case "item" -> langRaw("menu.reward.item").replace("{value}", value);
                case "command" -> langRaw("menu.reward.command").replace("{value}", value);
                default -> langRaw("menu.reward.other")
                    .replace("{type}", reward.type).replace("{value}", value);
            });
        }
        return lines;
    }

    private String statusText(String state) {
        return switch (state) {
            case "locked" -> langRaw("menu.status.locked");
            case "available" -> langRaw("menu.status.available");
            case "claimed" -> langRaw("menu.status.claimed");
            default -> state;
        };
    }

    protected String langRaw(String key) {
        return plugin.getConfigManager().getMessageRaw(key);
    }

    private Set<String> parseStringSet(String json) {
        Set<String> result = new HashSet<>();
        if (json == null || json.trim().isEmpty() || json.equals("[]")) {
            return result;
        }
        for (String part : json.replace("[", "").replace("]", "").replace("\"", "").split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
