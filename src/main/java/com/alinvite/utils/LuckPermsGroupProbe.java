package com.alinvite.utils;

import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Reads only loaded LuckPerms users. Call with player UUID on the entity thread; never loadUser/join. */
public final class LuckPermsGroupProbe {
    private LuckPermsGroupProbe() {}

    public static Set<String> groups(Player player) {
        return player == null ? Collections.emptySet() : groups(player.getUniqueId());
    }

    public static Set<String> groups(UUID uuid) {
        try {
            Class<?> apiClass = Class.forName("net.luckperms.api.LuckPerms");
            Object api = Class.forName("net.luckperms.api.LuckPermsProvider").getMethod("get").invoke(null);
            Object manager = apiClass.getMethod("getUserManager").invoke(api);
            Class<?> managerClass = Class.forName("net.luckperms.api.model.user.UserManager");
            // getUser reads the loaded-user cache. A missing online user is retried next tick.
            Object user = managerClass.getMethod("getUser", UUID.class).invoke(manager, uuid);
            if (user == null) {
                throw new IllegalStateException("LuckPerms 玩家缓存尚未就绪，本次不结算权限组");
            }
            Class<?> holderClass = Class.forName("net.luckperms.api.model.PermissionHolder");
            Class<?> userClass = Class.forName("net.luckperms.api.model.user.User");
            Class<?> optionsClass = Class.forName("net.luckperms.api.query.QueryOptions");
            Object options = holderClass.getMethod("getQueryOptions").invoke(user);
            Object inherited = holderClass.getMethod("getInheritedGroups", optionsClass).invoke(user, options);
            Set<String> names = new LinkedHashSet<>();
            Object primary = userClass.getMethod("getPrimaryGroup").invoke(user);
            if (primary instanceof String name && !name.isBlank()) {
                names.add(name);
            }
            Method getName = Class.forName("net.luckperms.api.model.group.Group").getMethod("getName");
            if (inherited instanceof Collection<?> groups) {
                for (Object group : groups) {
                    Object name = getName.invoke(group);
                    if (name instanceof String text && !text.isBlank()) {
                        names.add(text);
                    }
                }
            }
            return names;
        } catch (ClassNotFoundException absent) {
            // LuckPerms is optional. No API on this server means configured fallback rule.
            return Collections.emptySet();
        } catch (ReflectiveOperationException error) {
            // Never turn a temporary/API failure into a policy change for all VIP players.
            throw new IllegalStateException("读取 LuckPerms 真实组名失败，保留本次配额进度", error);
        }
    }

    public static String primaryGroup(Player player) {
        Set<String> names = groups(player);
        return names.isEmpty() ? null : names.iterator().next();
    }
}
