package de.craftingstudiopro.playerDataSyncReloaded.common.storage;

import de.craftingstudiopro.playerDataSyncReloaded.api.PlayerData;

import java.time.Instant;
import java.util.UUID;

public final class InventorySnapshot {
    public final UUID uuid;
    public final String name;
    public final String serverId;
    public final Instant takenAt;
    public final PlayerData data;

    public InventorySnapshot(UUID uuid, String name, String serverId, Instant takenAt, PlayerData data) {
        this.uuid = uuid;
        this.name = name;
        this.serverId = serverId;
        this.takenAt = takenAt;
        this.data = data;
    }
}
