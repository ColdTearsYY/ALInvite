package com.alinvite.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * 返利入池前触发。取消事件（setCancelled(true)）将不把返利计入未领取池
 * （流水仍会标记为已处理，不会重复发放）。全局线程调用。
 */
public class RebateGrantEvent extends Event implements Cancellable {

    private static final HandlerList HANDLER_LIST = new HandlerList();

    private final Player inviter;
    private final String sourcePlayerName;
    private final double rechargeAmount;
    private final double rebateAmount;
    private final boolean pointsMode;
    private boolean cancelled;

    public RebateGrantEvent(Player inviter, String sourcePlayerName, double rechargeAmount,
                            double rebateAmount, boolean pointsMode) {
        this.inviter = inviter;
        this.sourcePlayerName = sourcePlayerName;
        this.rechargeAmount = rechargeAmount;
        this.rebateAmount = rebateAmount;
        this.pointsMode = pointsMode;
    }

    /** 获得返利的邀请人。 */
    public Player getInviter() {
        return inviter;
    }

    /** 充值的玩家名。 */
    public String getSourcePlayerName() {
        return sourcePlayerName;
    }

    /** 充值点券数量。 */
    public double getRechargeAmount() {
        return rechargeAmount;
    }

    /** 本次返利数量。 */
    public double getRebateAmount() {
        return rebateAmount;
    }

    /** 是否为点券模式（true = 玩家可自行领取；false = 现金模式，管理员核销后线下发放）。 */
    public boolean isPointsMode() {
        return pointsMode;
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
