package com.alinvite.gui.render;

import com.alinvite.ALInvite;
import com.alinvite.config.ConfigManager;
import com.alinvite.gui.ActionPdc;
import com.alinvite.gui.GuiPageStore;
import com.alinvite.gui.InviteMenuHolder;
import com.alinvite.gui.MenuConfig;
import com.alinvite.gui.MenuItem;
import com.alinvite.gui.MenuSessionStore;
import com.alinvite.scheduler.ALInviteScheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * 渲染器基类，统一两条流水线：
 *  打开：异步取数（IO 线程）→ 切回实体线程 → 建箱按 shape 填静态区 + 子类填动态区 → 打开；
 *  翻页：异步取数 → 切回实体线程 → 校验会话（引用相等）→ 只重绘动态区，不整箱重建。
 *
 * @param <D> 一次取数得到的数据载荷（渲染上下文 + 结构化数据）
 */
public abstract class BaseMenuRenderer<D> {

    protected final ALInvite plugin;
    protected final MenuSessionStore sessions;
    protected final GuiPageStore pages;
    protected final ALInviteScheduler scheduler;
    private final ActionPdc pdc;

    protected BaseMenuRenderer(ALInvite plugin, MenuSessionStore sessions, GuiPageStore pages,
                               ALInviteScheduler scheduler, ActionPdc pdc) {
        this.plugin = plugin;
        this.sessions = sessions;
        this.pages = pages;
        this.scheduler = scheduler;
        this.pdc = pdc;
    }

    /** 本渲染器负责的菜单名。 */
    protected abstract String menuName();

    /** 异步阶段（IO 线程，可阻塞缓存加载）：取数并构建数据载荷。 */
    protected abstract D loadData(Player player);

    /** 实体线程阶段：填充动态区（静态区已由基类按 shape 填好）。 */
    protected abstract void fillDynamic(Player player, MenuConfig config, Inventory inventory, D data, int page);

    /** 打开菜单。请在玩家线程调用（事件/命令回调里直接调用即可）。 */
    public void open(Player player) {
        MenuConfig config = plugin.getMenuConfigLoader().get(menuName());
        if (config == null) {
            player.sendMessage(ConfigManager.colorize("&c菜单配置不存在: " + menuName()));
            return;
        }
        scheduler.runAsync(() -> {
            D data = loadData(player);
            scheduler.runAtPlayer(player, () -> {
                if (!player.isOnline()) {
                    return;
                }
                Inventory inventory = createAndFill(player, config, data, pageOf(player));
                sessions.track(player.getUniqueId(), menuName(), inventory);
                player.openInventory(inventory);
            });
        });
    }

    /** 翻页：只重绘动态区。任何线程调用都安全，异步回来后先校验会话再动手。 */
    public void refresh(Player player) {
        MenuSessionStore.Session session = sessions.get(player.getUniqueId());
        if (session == null || !menuName().equals(session.menuName())) {
            return;
        }
        Inventory inventory = session.inventory();
        scheduler.runAsync(() -> {
            D data = loadData(player);
            scheduler.runAtPlayer(player, () -> {
                if (!player.isOnline() || !sessions.isTracked(player.getUniqueId(), inventory)) {
                    return;
                }
                MenuConfig config = plugin.getMenuConfigLoader().get(menuName());
                if (config == null) {
                    return;
                }
                fillDynamic(player, config, inventory, data, pageOf(player));
            });
        });
    }

    private int pageOf(Player player) {
        return pages.get(player.getUniqueId(), menuName());
    }

    private Inventory createAndFill(Player player, MenuConfig config, D data, int page) {
        String title = MenuText.render(config.getTitle(), data == null ? null : contextOf(data));
        title = MenuText.applyPapi(title == null ? config.getTitle() : title, player);
        InviteMenuHolder holder = new InviteMenuHolder(menuName());
        Inventory inventory = Bukkit.createInventory(holder, config.size(), ConfigManager.colorize(title));
        holder.setInventory(inventory);

        if (data != null) {
            int slot = 0;
            for (String row : config.getShape()) {
                for (char c : row.toCharArray()) {
                    if (c == ' ') {
                        slot++;
                        continue;
                    }
                    MenuItem item = config.getItems().get(String.valueOf(c));
                    if (item != null && !item.isDynamic() && inventory.getItem(slot) == null) {
                        inventory.setItem(slot, MenuItems.build(plugin, pdc, item, null, contextOf(data)));
                    }
                    slot++;
                }
            }
            fillDynamic(player, config, inventory, data, page);
        }
        return inventory;
    }

    protected abstract RenderContext contextOf(D data);

    /** P/N 分页按钮的文案随页码变化，动态区刷新时一并重绘（含 H 主页/R 返回等页脚按钮可按需扩展）。 */
    protected void renderPagingButtons(MenuConfig config, Inventory inventory, RenderContext pageContext) {
        for (Map.Entry<String, MenuItem> entry : config.getItems().entrySet()) {
            String key = entry.getKey();
            if (!"P".equals(key) && !"N".equals(key)) {
                continue;
            }
            List<Integer> itemSlots = config.slotsOf(key.charAt(0));
            if (itemSlots.isEmpty()) {
                continue;
            }
            ItemStack built = MenuItems.build(plugin, pdc, entry.getValue(), null, pageContext);
            for (int slot : itemSlots) {
                setItemSafe(inventory, slot, built);
            }
        }
    }

    protected ActionPdc pdc() {
        return pdc;
    }

    protected void setItemSafe(Inventory inventory, int slot, ItemStack item) {
        if (slot < 0 || slot >= inventory.getSize()) {
            return;
        }
        if (item == null || item.getType().isAir()) {
            // AIR 物品 = 该槽位刻意留空
            inventory.setItem(slot, null);
            return;
        }
        inventory.setItem(slot, item);
    }
}
