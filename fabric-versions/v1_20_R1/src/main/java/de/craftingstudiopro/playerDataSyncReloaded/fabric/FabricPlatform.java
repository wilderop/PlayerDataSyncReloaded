package de.craftingstudiopro.playerDataSyncReloaded.fabric;

import de.craftingstudiopro.playerDataSyncReloaded.common.Platform;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

public class FabricPlatform implements Platform {
    private final MinecraftServer server;
    private final Logger logger = Logger.getLogger("PlayerDataSync");

    public FabricPlatform(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public Logger getLogger() {
        return logger;
    }

    @Override
    public void runTask(Runnable task) {
        server.execute(task);
    }

    @Override
    public void runTaskAsync(Runnable task) {
        new Thread(task).start(); // Simple async for Fabric
    }

    @Override
    public boolean isOnline(UUID uuid) {
        return server.getPlayerManager().getPlayer(uuid) != null;
    }

    @Override
    public void sendMessage(UUID uuid, String message) {
        var player = server.getPlayerManager().getPlayer(uuid);
        if (player != null) {
            player.sendMessage(Text.literal(message.replace("&", "§")), false);
        }
    }

    @Override
    public String getConfigString(String path, String def) {
        // Simple config mock for now, Fabric needs a config lib like Cloth Config or Fiber
        return def;
    }

    @Override
    public boolean getConfigBoolean(String path, boolean def) {
        return def;
    }

    @Override
    public List<String> getConfigStringList(String path) {
        return Collections.emptyList();
    }
}
