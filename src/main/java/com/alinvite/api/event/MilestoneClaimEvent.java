package com.alinvite.api.event;

import com.alinvite.manager.MilestoneManager;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * 里程碑奖励发放前触发。取消事件（setCancelled(true)）将不发放奖励，
 * 但领取状态仍会记录（玩家不会丢失领取资格，重新领取时再次触发本事件）。
 * 全局线程调用。
 */
public class MilestoneClaimEvent extends Event implements Cancellable {

    private static final HandlerList HANDLER_LIST = new HandlerList();

    private final Player player;
    private final int required;
    private final MilestoneManager.Milestone milestone;
    private boolean cancelled;

    public MilestoneClaimEvent(Player player, int required, MilestoneManager.Milestone milestone) {
        this.player = player;
        this.required = required;
        this.milestone = milestone;
    }

    /** 触发领取的玩家。 */
    public Player getPlayer() {
        return player;
    }

    /** 里程碑所需邀请人数。 */
    public int getRequired() {
        return required;
    }

    /** 里程碑配置。 */
    public MilestoneManager.Milestone getMilestone() {
        return milestone;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLER_LIST;
    }

    public static HandlerList getHandlerList() {
        return HANDLER_LIST;
    }
}
