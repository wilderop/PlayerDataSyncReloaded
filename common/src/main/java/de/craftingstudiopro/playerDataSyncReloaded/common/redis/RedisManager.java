package de.craftingstudiopro.playerDataSyncReloaded.common.redis;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolAbstract;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.JedisSentinelPool;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Logger;

public class RedisManager {
    private final String host, password;
    private final int port;
    private final boolean ssl;
    private final String sentinelMaster;
    private final Set<String> sentinels;
    private JedisPoolAbstract jedisPool;
    private final Logger logger;
    private final String channel = "pdasync_sync_notify";

    public RedisManager(Logger logger, String host, int port, String password, boolean ssl) {
        this(logger, host, port, password, ssl, "azpbmd", List.of(
                "127.0.0.1:26379", "127.0.0.1:26379", "127.0.0.1:26379"));
    }

    public RedisManager(Logger logger, String host, int port, String password, boolean ssl,
                        String sentinelMaster, List<String> sentinelAddrs) {
        this.logger = logger;
        this.host = host;
        this.port = port;
        this.password = password;
        this.ssl = ssl;
        this.sentinelMaster = sentinelMaster == null ? "" : sentinelMaster.trim();
        this.sentinels = new LinkedHashSet<>();
        if (sentinelAddrs != null) {
            for (String s : sentinelAddrs) {
                if (s != null && !s.isBlank()) {
                    this.sentinels.add(s.trim());
                }
            }
        }
    }

    public void init() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(16);
        boolean auth = password != null && !password.isEmpty();
        if (!sentinelMaster.isEmpty() && !sentinels.isEmpty()) {
            try {
                jedisPool = auth
                        ? new JedisSentinelPool(sentinelMaster, sentinels, poolConfig, 2000, password)
                        : new JedisSentinelPool(sentinelMaster, sentinels, poolConfig, 2000);
                try (Jedis j = jedisPool.getResource()) {
                    j.ping();
                }
                logger.info("Redis via Sentinel master=" + sentinelMaster);
                return;
            } catch (Exception e) {
                logger.warning("Redis Sentinel failed (" + e.getMessage() + "), falling back to " + host + ":" + port);
                if (jedisPool != null) {
                    try { jedisPool.close(); } catch (Exception ignored) {}
                    jedisPool = null;
                }
            }
        }
        if (!auth) {
            jedisPool = new JedisPool(poolConfig, host, port, 2000, ssl);
        } else {
            jedisPool = new JedisPool(poolConfig, host, port, 2000, password, ssl);
        }
        logger.info("Redis connection established.");
    }

    public void subscribe(Consumer<String> messageConsumer) {
        CompletableFuture.runAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.subscribe(new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        messageConsumer.accept(message);
                    }
                }, channel);
            } catch (Exception e) {
                logger.severe("Redis subscription failed: " + e.getMessage());
            }
        });
    }

    public void publish(String message) {
        CompletableFuture.runAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.publish(channel, message);
            } catch (Exception e) {
                logger.severe("Redis publish failed: " + e.getMessage());
            }
        });
    }

    public void close() {
        if (jedisPool != null) jedisPool.close();
    }
}
