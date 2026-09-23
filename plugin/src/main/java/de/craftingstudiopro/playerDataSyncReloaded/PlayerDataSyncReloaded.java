package de.craftingstudiopro.playerDataSyncReloaded;

import de.craftingstudiopro.playerDataSyncReloaded.api.PlayerDataSyncAPI;
import de.craftingstudiopro.playerDataSyncReloaded.api.VersionHandler;
import de.craftingstudiopro.playerDataSyncReloaded.common.Migrator;
import de.craftingstudiopro.playerDataSyncReloaded.common.SyncManager;
import de.craftingstudiopro.playerDataSyncReloaded.common.storage.MongoStorage;
import de.craftingstudiopro.playerDataSyncReloaded.common.storage.SqlStorage;
import de.craftingstudiopro.playerDataSyncReloaded.common.storage.Storage;
import de.craftingstudiopro.playerDataSyncReloaded.plugin.api.BukkitPlayerDataSyncAPI;
// import de.craftingstudiopro.playerDataSyncReloaded.v26_1.VersionHandlerImpl;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import dev.faststats.bukkit.BukkitMetrics;
import dev.faststats.core.Metrics;

import java.nio.charset.StandardCharsets;
import java.util.UUID;


public final class PlayerDataSyncReloaded extends JavaPlugin implements Listener {

    private VersionHandler versionHandler;
    private Storage storage;
    private SyncManager syncManager;
    private de.craftingstudiopro.playerDataSyncReloaded.plugin.BukkitPlatform platform;
    private de.craftingstudiopro.playerDataSyncReloaded.common.redis.RedisManager redisManager;
    private net.milkbowl.vault.economy.Economy economy;
    private de.craftingstudiopro.playerDataSyncReloaded.common.util.DiscordWebhookManager discordManager;
    private de.craftingstudiopro.playerDataSyncReloaded.common.BackupManager backupManager;
    private PlayerDataSyncAPI api;
    private BukkitTask autoSaveTask;
    private final Metrics metrics = BukkitMetrics.factory()
            .token("744d645fca7c2275b2986db7cd58da0c")
            .create(this);


    @Override
    public void onEnable() {
        sendBanner();
        saveDefaultConfig();
        
        if (!setupVersionHandler()) {
            getLogger().severe("§cCould not support your server version: " + Bukkit.getBukkitVersion());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        debug("Version handler " + versionHandler.getClass().getSimpleName() + " initialized.");

        if (!setupStorage()) {
            getLogger().severe("§cFailed to initialize storage. Disabling plugin.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        
        getLogger().info("§aSuccessfully connected to storage backend.");

        this.platform = new de.craftingstudiopro.playerDataSyncReloaded.plugin.BukkitPlatform(this);
        this.syncManager = new SyncManager(platform, storage, versionHandler);
        this.backupManager = new de.craftingstudiopro.playerDataSyncReloaded.common.BackupManager(getLogger(), storage, new java.io.File(getDataFolder(), "backups"));
        this.api = new BukkitPlayerDataSyncAPI(this);
        getServer().getServicesManager().register(PlayerDataSyncAPI.class, this.api, this, org.bukkit.plugin.ServicePriority.Normal);
        setupVault();
        setupRedis();
        setupDiscord();
        
        getServer().getMessenger().registerOutgoingPluginChannel(this, "pds:sync");
        getServer().getMessenger().registerIncomingPluginChannel(this, "pds:sync", (channel, player, message) -> {
            String msg = new String(message, StandardCharsets.UTF_8);
            org.bukkit.entity.Player target = resolvePdsTarget(msg, player);
            if (target == null) {
                return;
            }
            if (msg.startsWith("save:")) {
                syncManager.handleQuit(
                        new de.craftingstudiopro.playerDataSyncReloaded.plugin.BukkitPDSPlayer(target),
                        false,
                        true);
            } else if (msg.startsWith("load:")) {
                syncManager.handleJoin(new de.craftingstudiopro.playerDataSyncReloaded.plugin.BukkitPDSPlayer(target));
            }
        });

        Bukkit.getPluginManager().registerEvents(this, this);

        org.bukkit.command.PluginCommand command = getCommand("playerdatasync");
        if (command != null) {
            de.craftingstudiopro.playerDataSyncReloaded.plugin.command.PDSCommand handler =
                    new de.craftingstudiopro.playerDataSyncReloaded.plugin.command.PDSCommand(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        } else {
            getLogger().warning("Could not register /playerdatasync command because plugin.yml mapping is missing.");
        }

        // Initialize bStats
        new org.bstats.bukkit.Metrics(this, 30594);

        // Run Update Checker
        new de.craftingstudiopro.playerDataSyncReloaded.plugin.util.UpdateChecker(this).check();

        metrics.ready();


        startAutoSaveTask();
        startSnapshotSchedule();

        org.bukkit.command.PluginCommand rollback = getCommand("rollbackplayer");
        if (rollback != null) {
            de.craftingstudiopro.playerDataSyncReloaded.plugin.command.RollbackPlayerCommand rollbackHandler =
                    new de.craftingstudiopro.playerDataSyncReloaded.plugin.command.RollbackPlayerCommand(this);
            rollback.setExecutor(rollbackHandler);
            rollback.setTabCompleter(rollbackHandler);
        }

        Bukkit.getScheduler().runTaskLater(this, () -> {
            int n = 0;
            for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
                syncManager.handleQuit(new de.craftingstudiopro.playerDataSyncReloaded.plugin.BukkitPDSPlayer(player), true);
                n++;
            }
            getLogger().info("Seeded PlayerDataSync live rows for " + n + " online player(s).");
        }, 20L * 20);

        getLogger().info("PlayerDataSyncReloaded version " + getDescription().getVersion() + " enabled.");
    }

    private boolean setupVersionHandler() {
        String bukkitVersion = Bukkit.getBukkitVersion();
        
        getLogger().info("Detected Bukkit Version: " + bukkitVersion);

        try {
            if (bukkitVersion.startsWith("26.2")) {
                this.versionHandler = new de.craftingstudiopro.playerDataSyncReloaded.v26_2.VersionHandlerImpl();
            } else if (bukkitVersion.contains("1.21.4") || bukkitVersion.contains("26.1.1") || bukkitVersion.contains("26.1.2")) {
                this.versionHandler = new de.craftingstudiopro.playerDataSyncReloaded.v26_1.VersionHandlerImpl();
            } else if (bukkitVersion.contains("1.21.1") || bukkitVersion.startsWith("1.21")) {
                this.versionHandler = new de.craftingstudiopro.playerDataSyncReloaded.v1_21_R1.VersionHandlerImpl();
            } else if (bukkitVersion.startsWith("1.20")) {
                // Actually, v1_20_R1 might not be implemented yet or has a different name
                // For now we'll assume a standard naming or fallback to 1.21
                try {
                    this.versionHandler = new de.craftingstudiopro.playerDataSyncReloaded.v1_20_R1.VersionHandlerImpl();
                } catch (NoClassDefFoundError e) {
                    this.versionHandler = new de.craftingstudiopro.playerDataSyncReloaded.v1_21_R1.VersionHandlerImpl();
                    getLogger().warning("1.20 implementation not found, falling back to 1.21 handler.");
                }
            } else {
                // Default to 1.21 handler as it's the most stable modern version
                this.versionHandler = new de.craftingstudiopro.playerDataSyncReloaded.v1_21_R1.VersionHandlerImpl();
                getLogger().warning("Unsupported Bukkit version! Using 1.21 fallback. Might have issues.");
            }
            return true;
        } catch (NoClassDefFoundError | Exception e) {
            getLogger().log(java.util.logging.Level.SEVERE, "Version handler setup failed. This is likely due to missing version-specific code in the jar.", e);
            return false;
        }
    }

    private void setupRedis() {
        if (this.redisManager != null) {
            this.redisManager.close();
            this.redisManager = null;
        }
        this.syncManager.clearRedisManager();

        FileConfiguration config = getConfig();
        if (config.getBoolean("redis.enabled", false)) {
            String host = config.getString("redis.host", "localhost");
            int port = config.getInt("redis.port", 6379);
            String password = config.getString("redis.password", "");
            boolean ssl = config.getBoolean("redis.ssl", false);

            this.redisManager = new de.craftingstudiopro.playerDataSyncReloaded.common.redis.RedisManager(getLogger(), host, port, password, ssl);
            try {
                this.redisManager.init();
                this.syncManager.setRedisManager(this.redisManager);
                debug("Redis Pub/Sub subscription initialized.");
                getLogger().info("\u00A7aRedis synchronization enabled!");
            } catch (Exception e) {
                getLogger().severe("\u00A7cCould not connect to Redis! Synchronization might be delayed.");
                this.redisManager = null;
                this.syncManager.clearRedisManager();
            }
        }
    }
    private boolean setupStorage() {
        FileConfiguration config = getConfig();
        ConfigurationSection dbConfig = config.getConfigurationSection("storage");
        if (dbConfig == null) return false;

        String type = dbConfig.getString("type", "mysql").toLowerCase();
        String host = dbConfig.getString("host", "localhost");
        int port = dbConfig.getInt("port", 3306);
        String database = dbConfig.getString("database", "minecraft");
        String user = dbConfig.getString("username", "root");
        String password = dbConfig.getString("password", "");
        String connectionUrl = dbConfig.getString("connection_url", "");
        String encryptionKey = config.getString("security.encryption_key", "");

        if (type.equals("mongodb")) {
            MongoStorage mongo = new MongoStorage(getLogger(), connectionUrl, database);
            mongo.setEncryptionKey(encryptionKey);
            storage = mongo;
        } else {
            SqlStorage sql = new SqlStorage(getLogger(), type, host, port, database, user, password);
            sql.setEncryptionKey(encryptionKey);
            storage = sql;
        }

        try {
            storage.init();
            return true;
        } catch (Exception e) {
            getLogger().severe("§cCould not connect to the database!");
            getLogger().severe("§cPlease check your credentials in the §fconfig.yml§c.");
            // We only log the detailed error in debug mode or just keep it simple
            return false;
        }
    }

    private void sendBanner() {
        String version = getDescription().getVersion();
        String authors = "CraftingStudioPro, DerGamer09";
        
        Bukkit.getConsoleSender().sendMessage("§b");
        Bukkit.getConsoleSender().sendMessage("§b  ____  ____   ____    ____  _____ _      ___  ____  ____  _____ ____  ");
        Bukkit.getConsoleSender().sendMessage("§b |  _ \\|  _  | / ___|  |  _ \\| ____| |    / _ \\|  _ \\|  _ \\| ____|  _ \\ ");
        Bukkit.getConsoleSender().sendMessage("§b | |_) | | | | \\___ \\  | |_) |  _| | |   | | | | |_) | | | |  _| | | | |");
        Bukkit.getConsoleSender().sendMessage("§b |  __/| |_| |  ___) | |  _ <| |___| |___| |_| |  _ <| |_| | |___| |_| |");
        Bukkit.getConsoleSender().sendMessage("§b |_|   |_____/ |____/  |_| \\_\\_____|_____|\\___/|_| \\_\\____/|_____|____/ ");
        Bukkit.getConsoleSender().sendMessage("§b ");
        Bukkit.getConsoleSender().sendMessage("§8 > §fVersion: §d" + version);
        Bukkit.getConsoleSender().sendMessage("§8 > §fAuthors: §b" + authors);
        Bukkit.getConsoleSender().sendMessage("§8 > §fStatus:  §aRunning on " + Bukkit.getServer().getName());
        Bukkit.getConsoleSender().sendMessage("§8 > §fChannel: §aRelease");
        Bukkit.getConsoleSender().sendMessage("§b");
    }

    @Override
    public void onDisable() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
            autoSaveTask = null;
        }
        // Paper disables plugins before kicking players / saving player.dat.
        // Flush inventories to MariaDB first or the next join reloads a stale snapshot.
        if (syncManager != null) {
            int n = 0;
            for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
                syncManager.handleQuit(
                        new de.craftingstudiopro.playerDataSyncReloaded.plugin.BukkitPDSPlayer(player),
                        false,
                        true);
                n++;
            }
            if (n > 0) {
                getLogger().info("Flushing PlayerDataSync data for " + n + " online player(s) before disable.");
                syncManager.awaitPendingSaves(15_000L);
            }
        }
        if (storage != null) {
            storage.close();
        }
        if (redisManager != null) {
            redisManager.close();
        }
        if (syncManager != null) {
            syncManager.clearRedisManager();
            syncManager.clearDiscordManager();
        }
        getServer().getServicesManager().unregisterAll(this);
    }
    private void setupDiscord() {
        this.discordManager = null;
        this.syncManager.clearDiscordManager();

        FileConfiguration config = getConfig();
        if (config.getBoolean("discord.enabled", false)) {
            String webhookUrl = config.getString("discord.webhook_url", "");
            String username = config.getString("discord.username", "PlayerDataSync");
            String avatarUrl = config.getString("discord.avatar_url", "");

            this.discordManager = new de.craftingstudiopro.playerDataSyncReloaded.common.util.DiscordWebhookManager(
                getLogger(), webhookUrl, username, avatarUrl, true
            );
            this.syncManager.setDiscordManager(this.discordManager);
            getLogger().info("\u00A7aDiscord webhook integration enabled!");
        }
    }
    private void setupVault() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return;
        org.bukkit.plugin.RegisteredServiceProvider<net.milkbowl.vault.economy.Economy> rsp = getServer().getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
        if (rsp == null) return;
        this.economy = rsp.getProvider();
        this.syncManager.setEconomy(this.economy);
        getLogger().info("§aVault Economy detected and linked!");
    }

    public void debug(String message) {
        if (getConfig().getBoolean("debug", false)) {
            getLogger().info("§7[DEBUG] " + message);
        }
    }

    public SyncManager getSyncManager() {
        return syncManager;
    }

    public de.craftingstudiopro.playerDataSyncReloaded.plugin.BukkitPlatform getPlatform() {
        return platform;
    }

    public void runDailySnapshots(org.bukkit.command.CommandSender sender) {
        if (syncManager == null || storage == null) {
            if (sender != null) sender.sendMessage("§cPlayerDataSync is not ready.");
            return;
        }
        String serverId = getConfig().getString("server-id", "survival");
        int retainDays = getConfig().getInt("snapshots.retain_days", 7);
        int captured = 0;
        for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
            de.craftingstudiopro.playerDataSyncReloaded.api.PlayerData data =
                    versionHandler.capture(new de.craftingstudiopro.playerDataSyncReloaded.plugin.BukkitPDSPlayer(player));
            storage.saveDailySnapshot(serverId, data);
            captured++;
        }
        if (getConfig().getBoolean("snapshots.include_offline_from_db", true)) {
            storage.getAllStoredUUIDs().thenAccept(uuids -> {
                for (java.util.UUID uuid : uuids) {
                    if (Bukkit.getPlayer(uuid) != null) {
                        continue;
                    }
                    storage.load(uuid).thenAccept(opt -> opt.ifPresent(data -> storage.saveDailySnapshot(serverId, data)));
                }
            });
        }
        storage.pruneSnapshotsOlderThanDays(retainDays).thenAccept(removed ->
                getLogger().info("Pruned " + removed + " inventory snapshots older than " + retainDays + " day(s)."));
        if (sender != null) {
            sender.sendMessage("§aQueued daily snapshots for " + captured + " online player(s) on " + serverId + ".");
        } else {
            getLogger().info("Queued daily snapshots for " + captured + " online player(s) on " + serverId + ".");
        }
    }

    private void startSnapshotSchedule() {
        if (!getConfig().getBoolean("snapshots.enabled", true)) {
            return;
        }
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            int hour = getConfig().getInt("snapshots.hour_utc", 5);
            int minute = getConfig().getInt("snapshots.minute", 15);
            java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC);
            if (now.getHour() == hour && now.getMinute() == minute) {
                runDailySnapshots(null);
            }
        }, 20L * 60, 20L * 60);
    }

    private void startAutoSaveTask() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
            autoSaveTask = null;
        }

        FileConfiguration config = getConfig();
        if (!config.getBoolean("autosave.enabled", true)) return;

        long interval = config.getLong("autosave.interval", 300) * 20L; // Convert seconds to ticks

        autoSaveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            debug("Starting auto-save for all players...");
            for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
                // We capture on main thread
                Bukkit.getScheduler().runTask(this, () -> {
                    if (player.isOnline()) {
                        syncManager.handleQuit(new de.craftingstudiopro.playerDataSyncReloaded.plugin.BukkitPDSPlayer(player), true); // True = isAutosave
                    }
                });
            }
        }, interval, interval);
    }
    /**
     * Velocity {@code RegisteredServer.sendPluginMessage} uses some already-online
     * player as the messenger. The payload is {@code load:<uuid>} / {@code save:<uuid>}
     * of the player who actually switched; never apply that to the messenger.
     */
    private static org.bukkit.entity.Player resolvePdsTarget(String msg, org.bukkit.entity.Player messenger) {
        String uuidPart = null;
        if (msg.startsWith("load:") && msg.length() > 5) {
            uuidPart = msg.substring(5).trim();
        } else if (msg.startsWith("save:") && msg.length() > 5) {
            uuidPart = msg.substring(5).trim();
        }
        if (uuidPart == null || uuidPart.isEmpty()) {
            return messenger;
        }
        try {
            return Bukkit.getPlayer(UUID.fromString(uuidPart));
        } catch (IllegalArgumentException ignored) {
            return messenger;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        syncManager.handleJoin(new de.craftingstudiopro.playerDataSyncReloaded.plugin.BukkitPDSPlayer(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        syncManager.handleQuit(new de.craftingstudiopro.playerDataSyncReloaded.plugin.BukkitPDSPlayer(event.getPlayer()));
    }

    public void reloadPlugin() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
            autoSaveTask = null;
        }
        if (storage != null) {
            storage.close();
        }
        if (redisManager != null) {
            redisManager.close();
            redisManager = null;
        }

        reloadConfig();

        if (!setupVersionHandler()) {
            getLogger().severe("\u00A7cCould not support your server version during reload!");
            return;
        }

        if (!setupStorage()) {
            getLogger().severe("\u00A7cFailed to re-initialize storage!");
            return;
        }

        this.platform = new de.craftingstudiopro.playerDataSyncReloaded.plugin.BukkitPlatform(this);
        this.syncManager = new SyncManager(this.platform, storage, versionHandler);
        this.backupManager = new de.craftingstudiopro.playerDataSyncReloaded.common.BackupManager(getLogger(), storage, new java.io.File(getDataFolder(), "backups"));
        this.api = new BukkitPlayerDataSyncAPI(this);
        getServer().getServicesManager().unregisterAll(this);
        getServer().getServicesManager().register(PlayerDataSyncAPI.class, this.api, this, org.bukkit.plugin.ServicePriority.Normal);
        setupVault();
        setupRedis();
        setupDiscord();
        startAutoSaveTask();

        getLogger().info("\u00A7aPlugin successfully reloaded.");
    }
    public de.craftingstudiopro.playerDataSyncReloaded.common.BackupManager getBackupManager() {
        return backupManager;
    }

    public void startMigration(org.bukkit.command.CommandSender sender) {
        FileConfiguration config = getConfig();
        ConfigurationSection migConfig = config.getConfigurationSection("migration");
        if (migConfig == null) {
            sender.sendMessage("§cMigration target not configured in config.yml.");
            return;
        }

        Storage targetStorage;
        String type = migConfig.getString("type", "mysql").toLowerCase();
        String host = migConfig.getString("host", "localhost");
        int port = migConfig.getInt("port", 3306);
        String database = migConfig.getString("database", "minecraft");
        String user = migConfig.getString("username", "root");
        String password = migConfig.getString("password", "");
        String connectionUrl = migConfig.getString("connection_url", "");
        String encryptionKey = config.getString("security.encryption_key", "");

        if (type.equals("mongodb")) {
            targetStorage = new MongoStorage(getLogger(), connectionUrl, database);
        } else {
            targetStorage = new SqlStorage(getLogger(), type, host, port, database, user, password);
        }
        
        if (targetStorage instanceof MongoStorage) ((MongoStorage) targetStorage).setEncryptionKey(encryptionKey);
        if (targetStorage instanceof SqlStorage) ((SqlStorage) targetStorage).setEncryptionKey(encryptionKey);

        try {
            targetStorage.init();
            Migrator migrator = new Migrator(getLogger(), this.storage, targetStorage);
            migrator.setLegacy(config.getBoolean("migration.legacy", false));
            
            migrator.run().thenRun(() -> {
                sender.sendMessage("§aMigration finished! Please update your §fconfig.yml §ato the new storage and reload.");
                targetStorage.close();
            }).exceptionally(ex -> {
                sender.sendMessage("§cMigration failed: " + ex.getMessage());
                targetStorage.close();
                return null;
            });
        } catch (Exception e) {
            sender.sendMessage("§cCould not connect to the migration target database.");
        }
    }
    public PlayerDataSyncAPI getApi() {
        return api;
    }

    public void setDebugMode(boolean enabled) {
        getConfig().set("debug", enabled);
        saveConfig();
    }

    public boolean isRedisEnabled() {
        return redisManager != null;
    }

    public boolean isDiscordEnabled() {
        return discordManager != null;
    }

    public boolean isAutosaveEnabled() {
        return autoSaveTask != null && !autoSaveTask.isCancelled();
    }

    public String getStorageType() {
        String configured = getConfig().getString("storage.type", "unknown");
        return configured == null ? "unknown" : configured.toLowerCase(java.util.Locale.ROOT);
    }

    public Storage getStorage() {
        return storage;
    }
}
