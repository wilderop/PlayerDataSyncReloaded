package de.craftingstudiopro.playerDataSyncReloaded.common;

import de.craftingstudiopro.playerDataSyncReloaded.api.PDSPlayer;
import de.craftingstudiopro.playerDataSyncReloaded.api.PlayerData;
import de.craftingstudiopro.playerDataSyncReloaded.common.storage.InventorySnapshot;
import de.craftingstudiopro.playerDataSyncReloaded.common.storage.Storage;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Function;

public final class RollbackService {
    public static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    private RollbackService() {}

    public static String normalizeServerId(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("server is required");
        }
        String id = raw.trim().toLowerCase(Locale.ROOT);
        if (id.equals("surv") || id.equals("survival")) {
            return "survival";
        }
        if (id.equals("fab") || id.equals("fabric")) {
            return "fabric";
        }
        throw new IllegalArgumentException("server must be survival or fabric");
    }

    public static CompletableFuture<Optional<InventorySnapshot>> find(
            Storage storage, UUID uuid, String serverId, Duration ago
    ) {
        Instant target = Instant.now().minus(ago);
        return storage.findSnapshotAtOrBefore(uuid, serverId, target);
    }

    public static CompletableFuture<Void> apply(
            SyncManager syncManager,
            Storage storage,
            InventorySnapshot snapshot,
            Function<UUID, PDSPlayer> onlinePlayer,
            BiConsumer<Boolean, String> reply
    ) {
        PlayerData data = snapshot.data;
        if (data == null) {
            reply.accept(false, "Snapshot had no player data.");
            return CompletableFuture.completedFuture(null);
        }
        data.uuid = snapshot.uuid;
        return storage.save(data).thenRun(() -> {
            PDSPlayer online = onlinePlayer.apply(snapshot.uuid);
            if (online != null) {
                syncManager.forceApply(online, data);
                reply.accept(true, "Rolled back " + snapshot.name + " from " + snapshot.serverId
                        + " snapshot " + TIME_FMT.format(snapshot.takenAt)
                        + " and applied it online.");
            } else {
                reply.accept(true, "Rolled back " + snapshot.name + " from " + snapshot.serverId
                        + " snapshot " + TIME_FMT.format(snapshot.takenAt)
                        + ". Player is offline here; they will get it on next join/switch.");
            }
        });
    }
}
