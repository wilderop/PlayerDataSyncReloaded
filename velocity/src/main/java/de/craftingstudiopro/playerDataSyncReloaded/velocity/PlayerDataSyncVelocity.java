package de.craftingstudiopro.playerDataSyncReloaded.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;

@Plugin(
        id = "playerdatasync",
        name = "PlayerDataSync Velocity",
        version = PluginBuildInfo.VERSION,
        authors = {"CraftingStudioPro"}
)
public class PlayerDataSyncVelocity {

    private final ProxyServer server;
    private final Logger logger;
    public static final MinecraftChannelIdentifier IDENTIFIER = MinecraftChannelIdentifier.from("pds:sync");

    @Inject
    public PlayerDataSyncVelocity(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        server.getChannelRegistrar().register(IDENTIFIER);
        logger.info("PlayerDataSync Velocity Integration initialized.");
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        // When a player is switching servers, we notify the source server to save data immediately.
        // Fabric cannot decode Velocity plugin messages as vanilla custom payloads (it kicks the client).
        event.getPlayer().getCurrentServer().ifPresent(serverConnection -> {
            if (isFabric(serverConnection.getServerInfo().getName())) {
                return;
            }
            byte[] data = ("save:" + event.getPlayer().getUniqueId()).getBytes(StandardCharsets.UTF_8);
            serverConnection.sendPluginMessage(IDENTIFIER, data);
            logger.info("Triggered fast-save for " + event.getPlayer().getUsername() + " on " + serverConnection.getServerInfo().getName());
        });
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        if (isFabric(event.getServer().getServerInfo().getName())) {
            return;
        }
        byte[] data = ("load:" + event.getPlayer().getUniqueId()).getBytes(StandardCharsets.UTF_8);
        // Send through the connecting player. RegisteredServer.sendPluginMessage
        // picks some other online player as messenger; Paper then reloads THAT
        // player's inventory on every join.
        boolean sent = event.getPlayer().getCurrentServer()
                .map(conn -> conn.sendPluginMessage(IDENTIFIER, data))
                .orElse(false);
        if (!sent) {
            logger.warn("Could not send load notify for " + event.getPlayer().getUsername()
                    + " on " + event.getServer().getServerInfo().getName());
            return;
        }
        logger.info("Notified " + event.getServer().getServerInfo().getName() + " of connection for " + event.getPlayer().getUsername());
    }

    private static boolean isFabric(String name) {
        return name != null && name.equalsIgnoreCase("fabric");
    }
}
