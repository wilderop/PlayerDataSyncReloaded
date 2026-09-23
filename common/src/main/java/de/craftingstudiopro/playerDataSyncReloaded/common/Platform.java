package de.craftingstudiopro.playerDataSyncReloaded.common;

import java.util.UUID;
import java.util.logging.Logger;

public interface Platform {
    Logger getLogger();
    void runTask(Runnable task);
    void runTaskAsync(Runnable task);
    boolean isOnline(UUID uuid);
    void sendMessage(UUID uuid, String message);
    String getConfigString(String path, String def);
    boolean getConfigBoolean(String path, boolean def);
    java.util.List<String> getConfigStringList(String path);
}
