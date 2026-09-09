package com.alinvite.gui;

import com.alinvite.ALInvite;
import org.bukkit.entity.Player;

import java.util.function.Consumer;

/**
 * 动作串执行器：解析 delay 并经统一调度器在玩家实体线程延迟执行。
 */
public class MenuActionExecutor {

    private final ALInvite plugin;

    public MenuActionExecutor(ALInvite plugin) {
        this.plugin = plugin;
    }

    public void execute(Player player, String encodedActions, Consumer<String> actionHandler) {
        for (MenuActionParser.PlannedAction planned : MenuActionParser.parse(encodedActions)) {
            if (planned.delayTicks() <= 0L) {
                dispatch(player, planned.action(), actionHandler);
                continue;
            }
            plugin.getScheduler().runAtPlayerDelayed(player, () -> {
                if (player.isOnline()) {
                    dispatch(player, planned.action(), actionHandler);
                }
            }, planned.delayTicks());
        }
    }

    private void dispatch(Player player, String action, Consumer<String> actionHandler) {
        try {
            actionHandler.accept(action);
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("执行菜单动作失败 (" + player.getName() + "): " + action
                + " - " + exception.getMessage());
        }
    }
}
