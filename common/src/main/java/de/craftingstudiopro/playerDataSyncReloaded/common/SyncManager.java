package de.craftingstudiopro.playerDataSyncReloaded.common;

import de.craftingstudiopro.playerDataSyncReloaded.api.PDSPlayer;
import de.craftingstudiopro.playerDataSyncReloaded.api.PlayerData;
import de.craftingstudiopro.playerDataSyncReloaded.api.VersionHandler;
import de.craftingstudiopro.playerDataSyncReloaded.common.redis.RedisManager;
import de.craftingstudiopro.playerDataSyncReloaded.common.storage.Storage;
import de.craftingstudiopro.playerDataSyncReloaded.common.util.DiscordWebhookManager;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Logger;

public class SyncManager {
    private final Platform platform;
    private final Storage storage;
    private final VersionHandler versionHandler;
    private final Logger logger;
    private final ConcurrentHashMap<UUID, Boolean> syncInProgress = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> inventoryHashes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastSaveMillis = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CompletableFuture<?>> pendingSaves = new ConcurrentHashMap<>();
    private final Set<String> excludedWorlds = ConcurrentHashMap.newKeySet();
    private final Set<UUID> appliedSnapshots = ConcurrentHashMap.newKeySet();

    private final LongAdder loadAttempts = new LongAdder();
    private final LongAdder loadSuccess = new LongAdder();
    private final LongAdder loadFailed = new LongAdder();
    private final LongAdder saveAttempts = new LongAdder();
    private final LongAdder saveSuccess = new LongAdder();
    private final LongAdder saveFailed = new LongAdder();
    private final LongAdder skippedByWorld = new LongAdder();
    private final LongAdder skippedByCooldown = new LongAdder();
    private final LongAdder skippedByHash = new LongAdder();
    private final AtomicLong lastLoadDurationMs = new AtomicLong(-1L);
    private final AtomicLong lastSaveDurationMs = new AtomicLong(-1L);
    private final AtomicLong lastErrorAt = new AtomicLong(-1L);
    private volatile String lastErrorMessage = "";

    private RedisManager redisManager;
    private DiscordWebhookManager discordManager;
    private Object economy; // Vault Economy — kept as Object so common/ stays Bukkit/Vault-free; accessed via reflection.

    public SyncManager(Platform platform, Storage storage, VersionHandler versionHandler) {
        this.platform = platform;
        this.storage = storage;
        this.versionHandler = versionHandler;
        this.logger = platform.getLogger();
        refreshExclusions();
    }

    public void refreshExclusions() {
        this.versionHandler.setItemExclusions(platform.getConfigStringList("exclusions.items"));
        excludedWorlds.clear();
        List<String> worlds = platform.getConfigStringList("exclusions.worlds");
        if (worlds != null) {
            worlds.stream()
                    .filter(w -> w != null && !w.isBlank())
                    .map(this::normalizeWorldName)
                    .forEach(excludedWorlds::add);
        }
    }

    public void setRedisManager(RedisManager redisManager) {
        this.redisManager = redisManager;
        this.redisManager.subscribe(message -> {
            if (message.startsWith("saved:")) {
                String uuidStr = message.substring(6);
                UUID uuid = UUID.fromString(uuidStr);
                // Implementation depends on platform to find player and trigger handleJoin
                // For now, we'll assume the platform handles the event or we need a way to find PDSPlayer
            }
        });
    }

    public void clearRedisManager() {
        this.redisManager = null;
    }

    public void setDiscordManager(DiscordWebhookManager discordManager) {
        this.discordManager = discordManager;
    }

    public void clearDiscordManager() {
        this.discordManager = null;
    }

    public void setEconomy(Object economy) {
        this.economy = economy;
    }

    private double readBalance(PDSPlayer player) {
        if (economy == null) return 0.0;
        try {
            Class<?> offlinePlayerCls = Class.forName("org.bukkit.OfflinePlayer");
            return ((Number) economy.getClass()
                    .getMethod("getBalance", offlinePlayerCls)
                    .invoke(economy, player.getHandle())).doubleValue();
        } catch (Throwable t) {
            logger.warning("Failed to read balance for " + player.getName() + ": " + t.getMessage());
            return 0.0;
        }
    }

    private void writeBalance(PDSPlayer player, double target) {
        if (economy == null) return;
        try {
            Class<?> offlinePlayerCls = Class.forName("org.bukkit.OfflinePlayer");
            Object handle = player.getHandle();
            double current = ((Number) economy.getClass()
                    .getMethod("getBalance", offlinePlayerCls)
                    .invoke(economy, handle)).doubleValue();
            double delta = target - current;
            if (delta > 0.0) {
                economy.getClass()
                        .getMethod("depositPlayer", offlinePlayerCls, double.class)
                        .invoke(economy, handle, delta);
            } else if (delta < 0.0) {
                economy.getClass()
                        .getMethod("withdrawPlayer", offlinePlayerCls, double.class)
                        .invoke(economy, handle, -delta);
            }
        } catch (Throwable t) {
            logger.warning("Failed to write balance for " + player.getName() + ": " + t.getMessage());
        }
    }

    public void handleJoin(PDSPlayer player) {
        if (isWorldExcluded(player.getWorldName())) {
            skippedByWorld.increment();
            logger.info("Skipped loading for " + player.getName() + " in excluded world " + player.getWorldName());
            return;
        }

        loadAttempts.increment();
        if (syncInProgress.putIfAbsent(player.getUniqueId(), true) != null) {
            return;
        }

        String syncStarted = platform.getConfigString("messages.sync_started", "&7Syncing your data...");
        if (!syncStarted.isEmpty()) {
            platform.sendMessage(player.getUniqueId(), syncStarted);
        }

        long startTime = System.currentTimeMillis();
        storage.load(player.getUniqueId()).thenAccept(optionalData -> {
            if (optionalData.isPresent()) {
                PlayerData data = optionalData.get();
                platform.runTask(() -> applyData(player, data, startTime));
            } else {
                syncInProgress.remove(player.getUniqueId());
                loadSuccess.increment();
            }
        }).exceptionally(ex -> {
            syncInProgress.remove(player.getUniqueId());
            loadFailed.increment();
            trackError("load data for " + player.getName(), ex);
            String syncFailed = platform.getConfigString("messages.sync_failed", "&cFailed to sync your data. Please contact an admin.");
            if (!syncFailed.isEmpty()) {
                platform.sendMessage(player.getUniqueId(), syncFailed);
            }

            if (discordManager != null && platform.getConfigBoolean("discord.events.sync_failed", true)) {
                discordManager.sendSyncFailed(player.getName(), ex.getMessage());
            }
            return null;
        });
    }

    private void applyData(PDSPlayer player, PlayerData data, long startTime) {
        if (platform.isOnline(player.getUniqueId())) {
            try {
                filterData(data);
                versionHandler.apply(player, data);

                if (platform.getConfigBoolean("sync.economy", true)) {
                    writeBalance(player, data.balance);
                }

                inventoryHashes.put(player.getUniqueId(), snapshotHash(data));
                appliedSnapshots.add(player.getUniqueId());

                long duration = System.currentTimeMillis() - startTime;
                lastLoadDurationMs.set(duration);
                loadSuccess.increment();

                String syncComplete = platform.getConfigString("messages.sync_complete", "&aData synced successfully!");
                if (!syncComplete.isEmpty()) {
                    platform.sendMessage(player.getUniqueId(), syncComplete);
                }
                logger.info("Successfully synced data for player: " + player.getName() + " (" + duration + "ms)");

                if (discordManager != null && platform.getConfigBoolean("discord.events.sync_success", true)) {
                    discordManager.sendSyncSuccess(player.getName(), duration);
                }
            } catch (Throwable ex) {
                loadFailed.increment();
                trackError("apply data for " + player.getName(), ex);
            }
        }
        syncInProgress.remove(player.getUniqueId());
    }

    public void handleQuit(PDSPlayer player) {
        handleQuit(player, false, false);
    }

    public void handleQuit(PDSPlayer player, boolean isAutosave) {
        handleQuit(player, isAutosave, false);
    }

    public void handleQuit(PDSPlayer player, boolean isAutosave, boolean force) {
        if (isWorldExcluded(player.getWorldName())) {
            skippedByWorld.increment();
            logger.info("Skipped saving for " + player.getName() + " in excluded world " + player.getWorldName());
            return;
        }

        if (!force
                && platform.getConfigBoolean("sync.only_save_after_successful_load", false)
                && !appliedSnapshots.contains(player.getUniqueId())) {
            logger.info("Skipped save for " + player.getName()
                    + ": no synced snapshot loaded this session (blocks blank first-write, still saves real drops after a load)");
            return;
        }

        if (isSyncInProgress(player.getUniqueId())) {
            logger.warning("Player " + player.getName() + " quit while sync was still in progress!");
        }

        long now = System.currentTimeMillis();
        long minSaveIntervalMs = getConfigLong("sync.min_save_interval_ms", 0L);
        boolean enforceOnQuit = platform.getConfigBoolean("sync.enforce_cooldown_on_quit", false);
        if (minSaveIntervalMs > 0L && (isAutosave || enforceOnQuit)) {
            Long lastSaved = lastSaveMillis.get(player.getUniqueId());
            if (lastSaved != null && now - lastSaved < minSaveIntervalMs) {
                skippedByCooldown.increment();
                return;
            }
        }

        saveAttempts.increment();
        PlayerData data = versionHandler.capture(player);
        if (platform.getConfigBoolean("sync.economy", true)) {
            data.balance = readBalance(player);
        }
        filterData(data); // Apply config filters

        int currentHash = snapshotHash(data);
        Integer lastHash = inventoryHashes.get(player.getUniqueId());
        if (!force && lastHash != null && lastHash == currentHash
                && !platform.getConfigBoolean("sync.force_save_on_quit", false)) {
            skippedByHash.increment();
            return;
        }

        if (platform.getConfigBoolean("sync.merge_existing_fields", false)) {
            storage.load(player.getUniqueId()).thenAccept(existing -> {
                PlayerData toSave = existing.map(e -> mergeUnsetFields(e, data)).orElse(data);
                persist(player, toSave, isAutosave);
            }).exceptionally(ex -> {
                trackError("merge existing data for " + player.getName(), ex);
                persist(player, data, isAutosave);
                return null;
            });
            return;
        }

        persist(player, data, isAutosave);
    }

    private void persist(PDSPlayer player, PlayerData data, boolean isAutosave) {
        long startTime = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();
        CompletableFuture<Void> fut = storage.save(data).thenRun(() -> {
            long duration = System.currentTimeMillis() - startTime;
            lastSaveDurationMs.set(duration);
            saveSuccess.increment();
            lastSaveMillis.put(uuid, System.currentTimeMillis());
            inventoryHashes.put(uuid, snapshotHash(data));
            if (platform.getConfigBoolean("snapshots.enabled", true)) {
                String serverId = platform.getConfigString("server-id", "unknown");
                storage.saveDailySnapshot(serverId, data);
            }
            if (!isAutosave) {
                logger.info("Saved data for player: " + player.getName());
                if (redisManager != null) {
                    redisManager.publish("saved:" + uuid);
                }
            }
        }).exceptionally(ex -> {
            saveFailed.increment();
            trackError("save data for " + player.getName(), ex);
            return null;
        });
        pendingSaves.put(uuid, fut);
        fut.whenComplete((ok, err) -> pendingSaves.remove(uuid, fut));
    }

    /** Capture current online inventories and wait for SQL so a JVM stop cannot drop them. */
    public void awaitPendingSaves(long timeoutMs) {
        CompletableFuture<?>[] all = pendingSaves.values().toArray(CompletableFuture[]::new);
        if (all.length == 0) {
            return;
        }
        logger.info("Waiting for " + all.length + " player data save(s) to finish...");
        try {
            CompletableFuture.allOf(all).get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            logger.warning("Player data flush did not finish in " + timeoutMs + "ms: " + e.getMessage());
        }
    }

    /** Wait for one player's in-flight SQL save (server switch / gate). */
    public void awaitPendingSave(UUID uuid, long timeoutMs) {
        CompletableFuture<?> fut = pendingSaves.get(uuid);
        if (fut == null) {
            return;
        }
        try {
            fut.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            logger.warning("Player data flush for " + uuid + " did not finish in "
                    + timeoutMs + "ms: " + e.getMessage());
        }
    }

    /** Inventory + ender chest. Hashing only the hotbar skipped echest-only edits. */
    private static int snapshotHash(PlayerData data) {
        int h = 1;
        h = 31 * h + (data.inventoryContents == null ? 0 : data.inventoryContents.hashCode());
        h = 31 * h + (data.enderChestContents == null ? 0 : data.enderChestContents.hashCode());
        return h;
    }

    private PlayerData mergeUnsetFields(PlayerData existing, PlayerData captured) {
        if (captured.enderChestContents == null) captured.enderChestContents = existing.enderChestContents;
        if (captured.potionEffects == null) captured.potionEffects = existing.potionEffects;
        if (captured.advancements == null) captured.advancements = existing.advancements;
        if (captured.statistics == null) captured.statistics = existing.statistics;
        if (captured.attributes == null) captured.attributes = existing.attributes;
        if (captured.persistentDataContainer == null) captured.persistentDataContainer = existing.persistentDataContainer;
        if (captured.healthScale <= 0) captured.healthScale = existing.healthScale > 0 ? existing.healthScale : 20.0;
        if (captured.health <= 0) captured.health = existing.health > 0 ? existing.health : 20.0;
        return captured;
    }

    private void filterData(PlayerData data) {
        if (!platform.getConfigBoolean("sync.inventory", true)) data.inventoryContents = null;
        if (!platform.getConfigBoolean("sync.ender_chest", true)) data.enderChestContents = null;
        if (!platform.getConfigBoolean("sync.health", true)) data.health = 20.0;
        if (!platform.getConfigBoolean("sync.experience", true)) {
            data.exp = 0;
            data.level = 0;
            data.totalExperience = 0;
        }
        if (!platform.getConfigBoolean("sync.potion_effects", true)) data.potionEffects = null;
        if (!platform.getConfigBoolean("sync.food", true)) {
            data.foodLevel = 20;
            data.saturation = 5.0f;
        }
        if (!platform.getConfigBoolean("sync.game_mode", true)) data.gameMode = "SURVIVAL";
        if (!platform.getConfigBoolean("sync.advancements", true)) data.advancements = null;
        if (!platform.getConfigBoolean("sync.statistics", true)) data.statistics = null;
        if (!platform.getConfigBoolean("sync.air_level", true)) data.airLevel = 300;
        if (!platform.getConfigBoolean("sync.fire_ticks", true)) data.fireTicks = 0;
        if (!platform.getConfigBoolean("sync.player_time", true)) data.playerTime = -1;
        if (!platform.getConfigBoolean("sync.player_weather", true)) data.playerWeather = null;
        if (!platform.getConfigBoolean("sync.freeze_ticks", true)) data.freezeTicks = 0;
        if (!platform.getConfigBoolean("sync.arrows_in_body", true)) data.arrowsInBody = 0;
        if (!platform.getConfigBoolean("sync.absorption", true)) data.absorptionAmount = 0.0;
        if (!platform.getConfigBoolean("sync.speeds", true)) {
            data.walkSpeed = 0.2f;
            data.flySpeed = 0.1f;
        }
        if (!platform.getConfigBoolean("sync.fall_distance", true)) data.fallDistance = 0.0f;
    }

    public boolean isSyncInProgress(UUID uuid) {
        return syncInProgress.getOrDefault(uuid, false);
    }

    public void forceApply(PDSPlayer player, PlayerData data) {
        applyData(player, data, System.currentTimeMillis());
        appliedSnapshots.add(player.getUniqueId());
    }

    public Storage getStorage() {
        return storage;
    }

    public VersionHandler getVersionHandler() {
        return versionHandler;
    }

    public CompletableFuture<Optional<PlayerData>> loadStoredData(UUID uuid) {
        return storage.load(uuid);
    }

    public SyncStats getStatsSnapshot() {
        return new SyncStats(
                syncInProgress.size(),
                loadAttempts.sum(),
                loadSuccess.sum(),
                loadFailed.sum(),
                saveAttempts.sum(),
                saveSuccess.sum(),
                saveFailed.sum(),
                skippedByWorld.sum(),
                skippedByCooldown.sum(),
                skippedByHash.sum(),
                lastLoadDurationMs.get(),
                lastSaveDurationMs.get(),
                lastErrorAt.get(),
                lastErrorMessage,
                Collections.unmodifiableSet(excludedWorlds)
        );
    }

    private void trackError(String context, Throwable throwable) {
        String message = throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName();
        lastErrorMessage = context + ": " + message;
        lastErrorAt.set(System.currentTimeMillis());
        logger.severe("Failed to " + context + ": " + message);
    }

    private boolean isWorldExcluded(String worldName) {
        return !excludedWorlds.isEmpty() && excludedWorlds.contains(normalizeWorldName(worldName));
    }

    private String normalizeWorldName(String worldName) {
        if (worldName == null) return "";
        return worldName.trim().toLowerCase(Locale.ROOT);
    }

    private long getConfigLong(String path, long defaultValue) {
        try {
            String value = platform.getConfigString(path, Long.toString(defaultValue));
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    public static final class SyncStats {
        public final int activeSyncs;
        public final long loadAttempts;
        public final long loadSuccess;
        public final long loadFailed;
        public final long saveAttempts;
        public final long saveSuccess;
        public final long saveFailed;
        public final long skippedByWorld;
        public final long skippedByCooldown;
        public final long skippedByHash;
        public final long lastLoadDurationMs;
        public final long lastSaveDurationMs;
        public final long lastErrorAtEpochMs;
        public final String lastErrorMessage;
        public final Set<String> excludedWorlds;

        public SyncStats(
                int activeSyncs,
                long loadAttempts,
                long loadSuccess,
                long loadFailed,
                long saveAttempts,
                long saveSuccess,
                long saveFailed,
                long skippedByWorld,
                long skippedByCooldown,
                long skippedByHash,
                long lastLoadDurationMs,
                long lastSaveDurationMs,
                long lastErrorAtEpochMs,
                String lastErrorMessage,
                Set<String> excludedWorlds
        ) {
            this.activeSyncs = activeSyncs;
            this.loadAttempts = loadAttempts;
            this.loadSuccess = loadSuccess;
            this.loadFailed = loadFailed;
            this.saveAttempts = saveAttempts;
            this.saveSuccess = saveSuccess;
            this.saveFailed = saveFailed;
            this.skippedByWorld = skippedByWorld;
            this.skippedByCooldown = skippedByCooldown;
            this.skippedByHash = skippedByHash;
            this.lastLoadDurationMs = lastLoadDurationMs;
            this.lastSaveDurationMs = lastSaveDurationMs;
            this.lastErrorAtEpochMs = lastErrorAtEpochMs;
            this.lastErrorMessage = lastErrorMessage;
            this.excludedWorlds = excludedWorlds;
        }
    }
}
