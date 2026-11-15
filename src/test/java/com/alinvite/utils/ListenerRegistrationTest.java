package com.alinvite.utils;

import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ListenerRegistrationTest {
    @Test
    void quitListenerStillReceivesEventsInsideReloadWindow() throws Exception {
        Plugin plugin = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class[]{Plugin.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> "reload-test";
                case "isEnabled" -> true;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> null;
            });
        Listener clock = new Listener() {};
        Listener menu = new Listener() {};
        AtomicInteger quits = new AtomicInteger();
        AtomicInteger staleMenus = new AtomicInteger();
        HandlerList handlers = new HandlerList();
        try {
            handlers.register(new RegisteredListener(clock, (listener, event) -> quits.incrementAndGet(),
                EventPriority.MONITOR, plugin, false));
            handlers.register(new RegisteredListener(menu, (listener, event) -> staleMenus.incrementAndGet(),
                EventPriority.NORMAL, plugin, false));
            ListenerRegistration.unregisterExcept(plugin, clock);
            Event quitDuringReload = new Event() {
                @Override public HandlerList getHandlers() { return handlers; }
            };
            for (RegisteredListener registration : handlers.getRegisteredListeners()) {
                registration.callEvent(quitDuringReload);
            }
            assertEquals(1, quits.get());
            assertEquals(0, staleMenus.get());
            ListenerRegistration.unregisterExcept(plugin, clock);
            assertEquals(1, handlers.getRegisteredListeners().length);
        } finally {
            HandlerList.unregisterAll(plugin);
        }
    }
}
