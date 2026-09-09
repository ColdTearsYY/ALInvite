package com.alinvite.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * 玩家成功绑定邀请码后触发（全局线程调用）。
 */
public class InviteBindEvent extends Event {

    private static final HandlerList HANDLER_LIST = new HandlerList();

    private final Player invitee;
    private final UUID inviterUuid;
    private final String inviteCode;

    public InviteBindEvent(Player invitee, UUID inviterUuid, String inviteCode) {
        this.invitee = invitee;
        this.inviterUuid = inviterUuid;
        this.inviteCode = inviteCode;
    }

    /** 被邀请（绑定邀请码）的玩家。 */
    public Player getInvitee() {
        return invitee;
    }

    /** 邀请人的 UUID。 */
    public UUID getInviterUuid() {
        return inviterUuid;
    }

    /** 被绑定的邀请码。 */
    public String getInviteCode() {
        return inviteCode;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLER_LIST;
    }

    public static HandlerList getHandlerList() {
        return HANDLER_LIST;
    }
}
