package de.craftingstudiopro.playerDataSyncReloaded.common.storage;

import de.craftingstudiopro.playerDataSyncReloaded.api.PlayerData;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface Storage {
    void init();
    void close();

    CompletableFuture<Void> save(PlayerData data);
    CompletableFuture<Optional<PlayerData>> load(UUID uuid);
    CompletableFuture<Optional<PlayerData>> loadLegacy(UUID uuid);

    CompletableFuture<java.util.List<UUID>> getAllStoredUUIDs();

    CompletableFuture<Void> saveDailySnapshot(String serverId, PlayerData data);
    CompletableFuture<Optional<InventorySnapshot>> findSnapshotAtOrBefore(UUID uuid, String serverId, java.time.Instant target);
    CompletableFuture<java.util.List<InventorySnapshot>> listSnapshots(UUID uuid, String serverId);
    CompletableFuture<Integer> pruneSnapshotsOlderThanDays(int days);
}
