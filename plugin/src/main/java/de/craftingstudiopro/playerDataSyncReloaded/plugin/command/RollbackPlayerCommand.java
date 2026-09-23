package de.craftingstudiopro.playerDataSyncReloaded.plugin.command;

import de.craftingstudiopro.playerDataSyncReloaded.PlayerDataSyncReloaded;
import de.craftingstudiopro.playerDataSyncReloaded.common.PeriodParser;
import de.craftingstudiopro.playerDataSyncReloaded.common.RollbackService;
import de.craftingstudiopro.playerDataSyncReloaded.plugin.BukkitPDSPlayer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

public class RollbackPlayerCommand implements CommandExecutor, TabCompleter {
    private final PlayerDataSyncReloaded plugin;

    public RollbackPlayerCommand(PlayerDataSyncReloaded plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("playerdatasync.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        String playerName;
        String serverRaw;
        String periodRaw;
        if (args.length == 2 && sender instanceof Player) {
            playerName = sender.getName();
            serverRaw = args[0];
            periodRaw = args[1];
        } else if (args.length >= 3) {
            playerName = args[0];
            serverRaw = args[1];
            periodRaw = args[2];
        } else {
            sender.sendMessage("§cUsage: /rollbackplayer <player> <survival|fabric> <period>");
            sender.sendMessage("§7Example: /rollbackplayer Steve fabric 7d");
            return true;
        }

        String serverId;
        Duration ago;
        try {
            serverId = RollbackService.normalizeServerId(serverRaw);
            ago = PeriodParser.parse(periodRaw);
        } catch (IllegalArgumentException ex) {
            sender.sendMessage("§c" + ex.getMessage());
            return true;
        }

        OfflinePlayer target = resolvePlayer(playerName);
        if (target == null || target.getUniqueId() == null) {
            sender.sendMessage("§cUnknown player: " + playerName);
            return true;
        }
        UUID uuid = target.getUniqueId();
        sender.sendMessage("§eLooking up " + serverId + " snapshot from " + periodRaw + " ago for " + playerName + "...");

        plugin.getSyncManager().getStorage().findSnapshotAtOrBefore(uuid, serverId, java.time.Instant.now().minus(ago))
                .thenAccept(optional -> plugin.getPlatform().runTask(() -> {
                    if (optional.isEmpty()) {
                        sender.sendMessage("§cNo " + serverId + " snapshot at or before " + periodRaw + " for " + playerName + ".");
                        return;
                    }
                    RollbackService.apply(
                            plugin.getSyncManager(),
                            plugin.getSyncManager().getStorage(),
                            optional.get(),
                            id -> {
                                Player online = Bukkit.getPlayer(id);
                                return online != null ? new BukkitPDSPlayer(online) : null;
                            },
                            (ok, message) -> sender.sendMessage((ok ? "§a" : "§c") + message)
                    );
                }))
                .exceptionally(ex -> {
                    plugin.getPlatform().runTask(() -> sender.sendMessage("§cRollback failed: " + ex.getMessage()));
                    return null;
                });
        return true;
    }

    private OfflinePlayer resolvePlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        for (OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
            if (offline.getName() != null && offline.getName().equalsIgnoreCase(name)) {
                return offline;
            }
        }
        return null;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> names = Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toCollection(ArrayList::new));
            names.add("survival");
            names.add("fabric");
            return names.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
        }
        if (args.length == 2) {
            return Arrays.asList("survival", "fabric", "7d", "1d", "24h").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        }
        if (args.length == 3) {
            return Arrays.asList("7d", "3d", "1d", "24h", "12h").stream()
                    .filter(s -> s.startsWith(args[2].toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
