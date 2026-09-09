package com.alinvite.gui.render;

import com.alinvite.ALInvite;
import com.alinvite.gui.ActionPdc;
import com.alinvite.gui.GuiPageStore;
import com.alinvite.gui.MenuConfig;
import com.alinvite.gui.MenuItem;
import com.alinvite.gui.MenuNames;
import com.alinvite.gui.MenuSessionStore;
import com.alinvite.gui.Pagination;
import com.alinvite.manager.GiftManager;
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
 * 礼包商店菜单。
 * 动作串为 buy_gift:&lt;礼包ID&gt;（PDC），按 ID 定位购买目标——修复旧版按槽位换算导致的点错礼包问题。
 */
public class ShopMenuRenderer extends BaseMenuRenderer<ShopMenuRenderer.ShopData> {

    public record ShopData(RenderContext context,
                           List<GiftManager.GiftConfig> gifts,
                           Set<String> purchased,
                           String currentGiftId) {
    }

    public ShopMenuRenderer(ALInvite plugin, MenuSessionStore sessions, GuiPageStore pages,
                            ALInviteScheduler scheduler, ActionPdc pdc) {
        super(plugin, sessions, pages, scheduler, pdc);
    }

    @Override
    protected String menuName() {
        return MenuNames.SHOP;
    }

    @Override
    protected ShopData loadData(Player player) {
        UUID uuid = player.getUniqueId();
        RenderContext context = plugin.getPlaceholderResolver().renderContext(player);
        Set<String> purchased = new HashSet<>(plugin.getDatabaseManager().getPurchasedGiftsSync(uuid));
        String currentGiftId = plugin.getDatabaseManager().getGiftIdSync(uuid);
        List<GiftManager.GiftConfig> gifts = new ArrayList<>(plugin.getGiftManager().getAllGifts().values());
        return new ShopData(context, gifts, purchased, currentGiftId);
    }

    @Override
    protected RenderContext contextOf(ShopData data) {
        return data.context();
    }

    @Override
    protected void fillDynamic(Player player, MenuConfig config, Inventory inventory, ShopData data, int page) {
        List<Integer> slots = config.getDynamicSlots();
        if (slots.isEmpty()) {
            return;
        }

        UUID uuid = player.getUniqueId();
        Map<UUID, Integer> pageView = new HashMap<>();
        pageView.put(uuid, page);
        Pagination.Page<GiftManager.GiftConfig> result =
            Pagination.page(data.gifts(), pageView, uuid, slots.size());
        pages.set(uuid, menuName(), result.currentPage());

        RenderContext pageContext = data.context().copy()
            .add("page", String.valueOf(result.currentPage()))
            .add("total_pages", String.valueOf(result.totalPages()))
            .add("prev_page_hint", result.currentPage() <= 1
                ? langRaw("menu.page.first_page") : langRaw("menu.page.prev_hint"))
            .add("next_page_hint", result.currentPage() >= result.totalPages()
                ? langRaw("menu.page.last_page") : langRaw("menu.page.next_hint"));

        MenuItem giftItem = config.getItems().values().stream()
            .filter(MenuItem::isDynamic)
            .findFirst()
            .orElse(null);
        if (giftItem == null) {
            return;
        }

        int index = 0;
        for (GiftManager.GiftConfig gift : result.entries()) {
            if (index >= slots.size()) {
                break;
            }

            String state;
            if (gift.id.equals(data.currentGiftId())) {
                state = "current";
            } else if (data.purchased().contains(gift.id)) {
                state = "purchased";
            } else {
                state = "available";
            }

            String durationText = gift.durationDays == 0
                ? langRaw("menu.duration.permanent")
                : langRaw("menu.duration.days").replace("{value}", String.valueOf(gift.durationDays));

            RenderContext itemContext = pageContext.copy()
                .add("gift_id", gift.id)
                .add("gift_name", gift.name)
                .add("gift_material", gift.material.name())
                .add("price_money", String.valueOf(gift.priceMoney))
                .add("price_points", String.valueOf(gift.pricePoints))
                .add("duration_days", String.valueOf(gift.durationDays))
                .add("duration_text", durationText)
                .addList("reward_lore", rewardLines(gift.lore, gift.rewards));

            setItemSafe(inventory, slots.get(index),
                MenuItems.build(plugin, pdc(), giftItem, giftItem.getState(state), itemContext));
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

    protected String langRaw(String key) {
        return plugin.getConfigManager().getMessageRaw(key);
    }
}
