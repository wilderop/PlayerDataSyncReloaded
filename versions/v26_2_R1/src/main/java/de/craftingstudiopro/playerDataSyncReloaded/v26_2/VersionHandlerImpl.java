package de.craftingstudiopro.playerDataSyncReloaded.v26_2;

import de.craftingstudiopro.playerDataSyncReloaded.api.PlayerData;
import de.craftingstudiopro.playerDataSyncReloaded.api.PDSPlayer;
import de.craftingstudiopro.playerDataSyncReloaded.common.BukkitBaseVersionHandler;
import de.craftingstudiopro.playerDataSyncReloaded.common.SlotDataFormat;
import de.craftingstudiopro.playerDataSyncReloaded.common.util.SerializationUtil;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class VersionHandlerImpl extends BukkitBaseVersionHandler {

    @Override
    public PlayerData capture(PDSPlayer pdsPlayer) {
        PlayerData data = super.capture(pdsPlayer);
        Player player = (Player) pdsPlayer.getHandle();
        data.inventoryContents = encodeItems(player.getInventory().getContents());
        data.enderChestContents = encodeItems(player.getEnderChest().getContents());
        data.healthScale = player.getHealthScale();
        if (data.healthScale <= 0) {
            data.healthScale = 20.0;
        }
        if (data.health <= 0) {
            data.health = Math.min(20.0, getMaxHealth(player));
        }

        Map<String, Double> attrMap = new HashMap<>();
        org.bukkit.Registry.ATTRIBUTE.forEach(attr -> {
            AttributeInstance inst = player.getAttribute(attr);
            if (inst != null) {
                String key = attr.getKey().asString();
                attrMap.put(key, sanitizeSpeedAttribute(key, inst.getBaseValue()));
            }
        });
        data.attributes = attrMap;

        return data;
    }

    @Override
    public void apply(PDSPlayer pdsPlayer, PlayerData data) {
        if (data.healthScale <= 0) {
            data.healthScale = 20.0;
        }
        if (data.health <= 0) {
            data.health = 20.0;
        }
        super.apply(pdsPlayer, data);
        Player player = (Player) pdsPlayer.getHandle();
        if (data.healthScale > 0) {
            player.setHealthScale(data.healthScale);
        }

        if (data.attributes != null) {
            data.attributes.forEach((key, val) -> {
                try {
                    if (val == null) {
                        return;
                    }
                    org.bukkit.attribute.Attribute attr = org.bukkit.Registry.ATTRIBUTE.get(org.bukkit.NamespacedKey.fromString(key));
                    if (attr != null) {
                        AttributeInstance inst = player.getAttribute(attr);
                        if (inst != null) {
                            inst.setBaseValue(sanitizeSpeedAttribute(key, val));
                        }
                    }
                } catch (Exception ignored) {
                }
            });
        }
    }

    @Override
    protected double getMaxHealth(Player player) {
        AttributeInstance inst = player.getAttribute(Attribute.MAX_HEALTH);
        return inst != null ? inst.getValue() : 20.0;
    }

    @Override
    protected void applyEnderChest(PDSPlayer pdsPlayer, String payload) {
        if (payload == null || payload.isBlank()) {
            return;
        }
        Player player = (Player) pdsPlayer.getHandle();
        ItemStack[] ec = decodeItems(payload, player.getEnderChest().getSize());
        if (ec != null) {
            player.getEnderChest().setContents(filterItems(ec));
        }
    }

    @Override
    public String serializeInventory(PDSPlayer pdsPlayer) {
        Player player = (Player) pdsPlayer.getHandle();
        return encodeItems(player.getInventory().getContents());
    }

    @Override
    public void deserializeInventory(PDSPlayer pdsPlayer, String inventory) {
        Player player = (Player) pdsPlayer.getHandle();
        ItemStack[] contents = decodeItems(inventory, player.getInventory().getContents().length);
        if (contents != null) {
            player.getInventory().setContents(filterItems(contents));
        }
    }

    private String encodeItems(ItemStack[] contents) {
        Map<Integer, byte[]> slots = new LinkedHashMap<>();
        if (contents != null) {
            for (int i = 0; i < contents.length; i++) {
                ItemStack stack = contents[i];
                if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
                    continue;
                }
                slots.put(i, stack.serializeAsBytes());
            }
        }
        return SlotDataFormat.encode(slots);
    }

    private ItemStack[] decodeItems(String payload, int size) {
        SlotDataFormat.Kind kind = SlotDataFormat.kind(payload);
        ItemStack[] contents = new ItemStack[size];
        switch (kind) {
            case EMPTY -> {
                return contents;
            }
            case V2 -> {
                SlotDataFormat.decodeV2(payload, (slot, bytes) -> {
                    if (slot >= 0 && slot < contents.length) {
                        contents[slot] = ItemStack.deserializeBytes(bytes);
                    }
                });
                return contents;
            }
            case LEGACY_BUKKIT -> {
                try {
                    return (ItemStack[]) SerializationUtil.fromBase64(payload);
                } catch (Exception e) {
                    throw new IllegalArgumentException("Could not read legacy Bukkit inventory", e);
                }
            }
            default -> throw new IllegalArgumentException("Unsupported inventory payload on Paper: " + kind);
        }
    }
}
