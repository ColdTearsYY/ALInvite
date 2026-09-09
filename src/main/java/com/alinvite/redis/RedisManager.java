package com.alinvite.redis;

import com.alinvite.ALInvite;
import org.bukkit.Bukkit;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Redis 跨服通信（Jedis 连接池 + Pub/Sub）。
 * 设计要点：
 *  - publish 永不阻塞主线程（统一丢到 IO 线程池，失败仅记日志，不影响业务）
 *  - 订阅跑在专用守护线程，断线自动重连（退避重试），不影响主流程
 *  - Redis 未启用 / 连接失败时插件完整可用，只是没有跨服实时同步
 */
public class RedisManager {

    private final ALInvite plugin;
    private final String host;
    private final int port;
    private final String password;
    private final String channel;

    private volatile JedisPool jedisPool;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread subscriberThread;

    public RedisManager(ALInvite plugin, String host, int port, String password, String channel) {
        this.plugin = plugin;
        this.host = host;
        this.port = port;
        this.password = password == null ? "" : password;
        this.channel = channel == null || channel.isBlank() ? "alinvite_channel" : channel;
    }

    public boolean isAvailable() {
        return running.get() && jedisPool != null;
    }

    public String getChannel() {
        return channel;
    }

    /**
     * 建池并启动订阅线程。连接失败时降级为不可用（不影响插件其它功能）。
     *
     * @param onMessage 收到消息后的处理回调（在订阅线程回调，回调内不得碰 Bukkit API）
     */
    public synchronized void init(Consumer<String> onMessage) {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(8);
        poolConfig.setMaxIdle(4);
        poolConfig.setMinIdle(1);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setMaxWait(Duration.ofMillis(2000));

        JedisPool pool = password.isEmpty()
            ? new JedisPool(poolConfig, host, port, 2000)
            : new JedisPool(poolConfig, host, port, 2000, password);

        try (Jedis jedis = pool.getResource()) {
            jedis.ping();
        } catch (Exception e) {
            plugin.getLogger().severe("Redis 连接失败，跨服同步不可用: " + e.getMessage());
            pool.close();
            return;
        }

        this.jedisPool = pool;
        this.running.set(true);
        plugin.getLogger().info("Redis 跨服同步已启用（频道: " + channel + "）");

        subscriberThread = new Thread(() -> subscribeLoop(onMessage), "alinvite-redis-subscriber");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
    }

    /** 订阅循环：断线退避重连。 */
    private void subscribeLoop(Consumer<String> onMessage) {
        long backoff = 1000L;
        while (running.get()) {
            JedisPool pool = jedisPool;
            if (pool == null) {
                return;
            }
            JedisPubSub pubSub = new JedisPubSub() {
                @Override
                public void onMessage(String channel, String message) {
                    try {
                        onMessage.accept(message);
                    } catch (Exception e) {
                        plugin.getLogger().warning("处理跨服消息失败: " + e.getMessage());
                    }
                }
            };
            try (Jedis jedis = pool.getResource()) {
                backoff = 1000L;
                jedis.subscribe(pubSub, channel);
            } catch (Exception e) {
                if (running.get()) {
                    plugin.getLogger().warning("Redis 订阅断开，" + backoff / 1000 + " 秒后重连: " + e.getMessage());
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    backoff = Math.min(backoff * 2, 15000L);
                }
            }
        }
    }

    /** 发布消息：异步、失败静默降级（仅记日志），绝不阻塞调用线程。 */
    public void publish(String message) {
        if (!isAvailable()) {
            return;
        }
        com.alinvite.utils.AsyncPool.run(() -> {
            JedisPool pool = jedisPool;
            if (pool == null) {
                return;
            }
            try (Jedis jedis = pool.getResource()) {
                jedis.publish(channel, message);
            } catch (Exception e) {
                plugin.getLogger().warning("Redis 发布失败（消息未同步到其他服务器）: " + e.getMessage());
            }
        });
    }

    /** 关闭订阅线程与连接池。 */
    public void close() {
        running.set(false);
        Thread thread = subscriberThread;
        if (thread != null) {
            thread.interrupt();
            subscriberThread = null;
        }
        JedisPool pool = jedisPool;
        jedisPool = null;
        if (pool != null) {
            try {
                pool.close();
            } catch (Exception ignored) {
            }
        }
    }
}
