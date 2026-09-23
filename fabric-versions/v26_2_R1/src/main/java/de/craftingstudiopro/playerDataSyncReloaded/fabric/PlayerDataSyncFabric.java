package de.craftingstudiopro.playerDataSyncReloaded.fabric;

import com.mojang.brigadier.arguments.StringArgumentType;
import de.craftingstudiopro.playerDataSyncReloaded.api.PlayerData;
import de.craftingstudiopro.playerDataSyncReloaded.common.PeriodParser;
import de.craftingstudiopro.playerDataSyncReloaded.common.RollbackService;
import de.craftingstudiopro.playerDataSyncReloaded.common.SyncManager;
import de.craftingstudiopro.playerDataSyncReloaded.common.storage.SqlStorage;
import de.craftingstudiopro.playerDataSyncReloaded.common.storage.Storage;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class PlayerDataSyncFabric implements ModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("PlayerDataSync");
    private static volatile PlayerDataSyncFabric INSTANCE;

    private SyncManager syncManager;
    private Storage storage;
    private FabricPlatform platform;
    private static volatile FabricPlatform PLATFORM;
    private MinecraftServer server;

    static FabricPlatform platform() {
        return PLATFORM;
    }
    private FabricVersionHandler versionHandler;
    private ScheduledExecutorService scheduler;
    private volatile int lastSnapshotDay = -1;

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.serverboundPlay().register(PdsSyncC2SPayload.PACKET_ID, PdsSyncC2SPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(PdsSyncC2SPayload.PACKET_ID, (payload, context) -> {
            String msg = payload.message();
            MinecraftServer srv = context.server();
            if (srv == null) {
                return;
            }
            srv.execute(() -> {
                if (syncManager == null) {
                    return;
                }
                if (msg.startsWith("save:")) {
                    syncManager.handleQuit(new FabricPDSPlayer(context.player()), false, true);
                } else if (msg.startsWith("load:")) {
                    syncManager.handleJoin(new FabricPDSPlayer(context.player()));
                }
            });
        });

        ServerLifecycleEvents.SERVER_STARTING.register(this::setup);
        ServerLifecycleEvents.SERVER_STOPPING.register(srv -> {
            if (syncManager != null) {
                int n = 0;
                for (ServerPlayer player : srv.getPlayerList().getPlayers()) {
                    syncManager.handleQuit(new FabricPDSPlayer(player), false, true);
                    n++;
                }
                if (n > 0) {
                    LOG.info("Flushing PlayerDataSync data for {} online player(s) before stop.", n);
                    syncManager.awaitPendingSaves(15_000L);
                }
            }
            INSTANCE = null;
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
            if (storage != null) {
                storage.close();
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> {
            if (syncManager != null) {
                syncManager.handleJoin(new FabricPDSPlayer(handler.player));
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> {
            if (syncManager != null) {
                syncManager.handleQuit(new FabricPDSPlayer(handler.player), false, true);
                syncManager.awaitPendingSave(handler.player.getUUID(), 2_000L);
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("rollbackplayer")
                    .requires(source -> Commands.LEVEL_OWNERS.check(source.permissions()))
                    .then(Commands.argument("player", StringArgumentType.word())
                            .then(Commands.argument("server", StringArgumentType.word())
                                    .then(Commands.argument("period", StringArgumentType.word())
                                            .executes(ctx -> {
                                                runRollback(
                                                        ctx.getSource().getServer(),
                                                        StringArgumentType.getString(ctx, "player"),
                                                        StringArgumentType.getString(ctx, "server"),
                                                        StringArgumentType.getString(ctx, "period"),
                                                        msg -> ctx.getSource().sendSuccess(() -> Component.literal(msg), false)
                                                );
                                                return 1;
                                            })))));
            dispatcher.register(Commands.literal("pds")
                    .requires(source -> Commands.LEVEL_OWNERS.check(source.permissions()))
                    .then(Commands.literal("snapshot")
                            .executes(ctx -> {
                                runDailySnapshots();
                                ctx.getSource().sendSuccess(() -> Component.literal("Queued fabric inventory snapshots."), false);
                                return 1;
                            })));
        });
    }

    private void setup(MinecraftServer server) {
        this.server = server;
        this.platform = new FabricPlatform(server);
        PLATFORM = this.platform;
        this.versionHandler = new FabricVersionHandler();

        this.storage = new SqlStorage(
                platform.getLogger(),
                platform.getConfigString("storage.type", "mariadb"),
                platform.getConfigString("storage.host", "127.0.0.1"),
                parsePort(platform.getConfigString("storage.port", "3306")),
                platform.getConfigString("storage.database", "playerdatasync"),
                platform.getConfigString("storage.username", "playerdatasync"),
                platform.getConfigString("storage.password", ""));
        try {
            this.storage.init();
        } catch (Exception e) {
            LOG.error("Failed to initialize storage", e);
        }

        this.syncManager = new SyncManager(platform, storage, versionHandler);
        INSTANCE = this;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PlayerDataSync-Snapshots");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleAtFixedRate(this::maybeRunDailySnapshots, 60, 60, TimeUnit.SECONDS);
        startAutoSave();
        platform.getLogger().info("PlayerDataSync Fabric initialized!");
    }

    /**
     * Capture + SQL wait so SkyWorlds can persist ender chest / inventory before
     * Velocity switches the player to Paper.
     */
    public static void flushPlayerNow(ServerPlayer player) {
        PlayerDataSyncFabric inst = INSTANCE;
        if (inst == null || inst.syncManager == null || player == null) {
            return;
        }
        inst.syncManager.handleQuit(new FabricPDSPlayer(player), false, true);
        inst.syncManager.awaitPendingSave(player.getUUID(), 3_000L);
    }

    private void startAutoSave() {
        if (platform == null || !platform.getConfigBoolean("autosave.enabled", true)) {
            return;
        }
        int seconds;
        try {
            seconds = Integer.parseInt(platform.getConfigString("autosave.interval", "60").trim());
        } catch (NumberFormatException e) {
            seconds = 60;
        }
        if (seconds <= 0) {
            return;
        }
        this.scheduler.scheduleAtFixedRate(() -> {
            if (server == null || syncManager == null) {
                return;
            }
            server.execute(() -> {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    syncManager.handleQuit(new FabricPDSPlayer(player), true, false);
                }
            });
        }, seconds, seconds, TimeUnit.SECONDS);
        LOG.info("PlayerDataSync Fabric autosave every {}s", seconds);
    }

    private void maybeRunDailySnapshots() {
        if (platform == null || !platform.getConfigBoolean("snapshots.enabled", true)) {
            return;
        }
        int hour = Integer.parseInt(platform.getConfigString("snapshots.hour_utc", "5"));
        int minute = Integer.parseInt(platform.getConfigString("snapshots.minute", "15"));
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        if (now.getHour() == hour && now.getMinute() == minute && now.getDayOfYear() != lastSnapshotDay) {
            lastSnapshotDay = now.getDayOfYear();
            runDailySnapshots();
        }
    }

    private void runDailySnapshots() {
        if (server == null || storage == null || versionHandler == null) {
            return;
        }
        String serverId = platform.getConfigString("server-id", "fabric");
        int retainDays = Integer.parseInt(platform.getConfigString("snapshots.retain_days", "7"));
        server.execute(() -> {
            int n = 0;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                PlayerData data = versionHandler.capture(new FabricPDSPlayer(player));
                storage.saveDailySnapshot(serverId, data);
                n++;
            }
            LOG.info("Queued daily fabric snapshots for {} online player(s)", n);
        });
        storage.pruneSnapshotsOlderThanDays(retainDays).thenAccept(removed ->
                LOG.info("Pruned {} inventory snapshots older than {} day(s)", removed, retainDays));
    }

    private void runRollback(MinecraftServer srv, String playerName, String serverRaw, String periodRaw, java.util.function.Consumer<String> reply) {
        if (syncManager == null || storage == null) {
            reply.accept("PlayerDataSync is not ready.");
            return;
        }
        final String serverId;
        final Duration ago;
        try {
            serverId = RollbackService.normalizeServerId(serverRaw);
            ago = PeriodParser.parse(periodRaw);
        } catch (IllegalArgumentException ex) {
            reply.accept(ex.getMessage());
            return;
        }
        resolveUuid(srv, playerName).thenAccept(uuid -> {
            if (uuid == null) {
                srv.execute(() -> reply.accept("Unknown player: " + playerName));
                return;
            }
            storage.findSnapshotAtOrBefore(uuid, serverId, Instant.now().minus(ago)).thenAccept(optional -> srv.execute(() -> {
                if (optional.isEmpty()) {
                    reply.accept("No " + serverId + " snapshot at or before " + periodRaw + " for " + playerName + ".");
                    return;
                }
                RollbackService.apply(
                        syncManager,
                        storage,
                        optional.get(),
                        id -> {
                            ServerPlayer online = srv.getPlayerList().getPlayer(id);
                            return online != null ? new FabricPDSPlayer(online) : null;
                        },
                        (ok, message) -> reply.accept(message)
                );
            }));
        });
    }

    private java.util.concurrent.CompletableFuture<UUID> resolveUuid(MinecraftServer srv, String playerName) {
        ServerPlayer online = srv.getPlayerList().getPlayerByName(playerName);
        if (online != null) {
            return java.util.concurrent.CompletableFuture.completedFuture(online.getUUID());
        }
        try {
            return java.util.concurrent.CompletableFuture.completedFuture(UUID.fromString(playerName));
        } catch (IllegalArgumentException ignored) {
        }
        if (storage == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        return storage.getAllStoredUUIDs().thenCompose(uuids -> {
            java.util.concurrent.CompletableFuture<UUID> found = java.util.concurrent.CompletableFuture.completedFuture(null);
            for (UUID uuid : uuids) {
                found = found.thenCompose(current -> {
                    if (current != null) {
                        return java.util.concurrent.CompletableFuture.completedFuture(current);
                    }
                    return storage.load(uuid).thenApply(opt -> {
                        if (opt.isPresent() && playerName.equalsIgnoreCase(opt.get().name)) {
                            return uuid;
                        }
                        return null;
                    });
                });
            }
            return found;
        });
    }

    private int parsePort(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            LOG.warn("storage.port is not a number ('{}'), using 3306", raw);
            return 3306;
        }
    }
}
