package com.alinvite.utils;

import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/** Keeps online-session listeners continuously registered during a configuration reload. */
public final class ListenerRegistration {
    private ListenerRegistration() {}

    public static void unregisterExcept(Plugin plugin, Listener preserved) {
        for (var registration : HandlerList.getRegisteredListeners(plugin)) {
            if (registration.getListener() != preserved) {
                HandlerList.unregisterAll(registration.getListener());
            }
        }
    }
}
