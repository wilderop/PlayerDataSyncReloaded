package de.craftingstudiopro.playerDataSyncReloaded.common;

import de.craftingstudiopro.playerDataSyncReloaded.api.PlayerData;
import de.craftingstudiopro.playerDataSyncReloaded.api.VersionHandler;
import de.craftingstudiopro.playerDataSyncReloaded.common.util.PortableJson;
import de.craftingstudiopro.playerDataSyncReloaded.common.util.SerializationUtil;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import de.craftingstudiopro.playerDataSyncReloaded.api.PDSPlayer;

import java.util.*;

public abstract class BukkitBaseVersionHandler implements VersionHandler {
    protected List<String> itemExclusions = new ArrayList<>();

    private static boolean syncEnabled(String key, boolean def) {
        org.bukkit.plugin.Plugin p = Bukkit.getPluginManager().getPlugin("PlayerDataSyncReloaded");
        if (p == null) {
            return def;
        }
        return p.getConfig().getBoolean("sync." + key, def);
    }

    @Override
    public void setItemExclusions(List<String> materials) {
        this.itemExclusions = materials;
    }

    @Override
    public PlayerData capture(PDSPlayer pdsPlayer) {
        Player player = (Player) pdsPlayer.getHandle();
        PlayerData data = new PlayerData();
        data.uuid = player.getUniqueId();
        data.name = player.getName();

        // Items
        data.inventoryContents = serializeInventory(pdsPlayer);
        data.enderChestContents = SerializationUtil.toBase64(filterItems(player.getEnderChest().getContents()));
        data.selectedSlot = player.getInventory().getHeldItemSlot();

        // Stats
        data.health = player.getHealth();
        data.foodLevel = player.getFoodLevel();
        data.saturation = player.getSaturation();
        data.exhaustion = player.getExhaustion();
        data.airLevel = player.getRemainingAir();
        data.fireTicks = player.getFireTicks();
        data.freezeTicks = player.getFreezeTicks();
        data.arrowsInBody = player.getArrowsInBody();
        data.absorptionAmount = player.getAbsorptionAmount();

        // Exp
        data.level = player.getLevel();
        data.exp = player.getExp();
        data.totalExperience = player.getTotalExperience();

        // Status
        data.gameMode = player.getGameMode().name();
        
        try {
            data.isFlying = player.isFlying();
            data.canFly = player.getAllowFlight();
            data.walkSpeed = player.getWalkSpeed();
            data.flySpeed = player.getFlySpeed();
            data.fallDistance = player.getFallDistance();
            if (data.walkSpeed <= 0.0f || data.walkSpeed > 1.0f) {
                data.walkSpeed = 0.2f;
            }
            if (data.flySpeed <= 0.0f || data.flySpeed > 1.0f) {
                data.flySpeed = 0.1f;
            }
        } catch (NoSuchMethodError ignored) {}

        // Effects (JSON so Fabric can apply the same payload)
        data.potionEffects = effectsToJson(player);

        // Location
        Location loc = player.getLocation();
        data.worldName = loc.getWorld().getName();
        data.x = loc.getX();
        data.y = loc.getY();
        data.z = loc.getZ();
        data.yaw = loc.getYaw();
        data.pitch = loc.getPitch();
        data.playerTime = player.getPlayerTime();
        data.playerWeather = player.getPlayerWeather() != null ? player.getPlayerWeather().name() : null;

        // PDC
        try {
            // Using reflection to support varied API versions for getValues()
            java.lang.reflect.Method getValuesMethod = player.getPersistentDataContainer().getClass().getMethod("getValues");
            Map<org.bukkit.NamespacedKey, Object> values = (Map<org.bukkit.NamespacedKey, Object>) getValuesMethod.invoke(player.getPersistentDataContainer());
            data.persistentDataContainer = SerializationUtil.toBase64(values);
        } catch (Exception ignored) {
            // Fallback for older versions or issues
        }

        // Attributes
        data.attributes = captureAttributes(player);

        // Stats & Advancements. Walking the full advancement tree on the main
        // thread freezes every online player for seconds on busy survival joins.
        if (syncEnabled("statistics", true)) {
            data.statistics = PortableJson.statsToJson(captureStatistics(player));
        }
        if (syncEnabled("advancements", true)) {
            data.advancements = PortableJson.advancementsToJson(captureAdvancements(player));
        }

        return data;
    }

    /**
     * Legacy Bukkit-object payloads only. Paper 26.2 writes SlotDataFormat v2
     * JSON here; {@code fromBase64} throws and used to be swallowed, so Fabric
     * ender-chest edits never appeared on Paper. 26.2 overrides this.
     */
    protected void applyEnderChest(PDSPlayer pdsPlayer, String payload) {
        if (payload == null || payload.isBlank()) {
            return;
        }
        if (SlotDataFormat.kind(payload) == SlotDataFormat.Kind.V2
                || SlotDataFormat.kind(payload) == SlotDataFormat.Kind.EMPTY) {
            return;
        }
        try {
            Player player = (Player) pdsPlayer.getHandle();
            ItemStack[] ec = (ItemStack[]) SerializationUtil.fromBase64(payload);
            player.getEnderChest().setContents(filterItems(ec));
        } catch (Exception e) {
            Bukkit.getLogger().warning("[PlayerDataSync] Could not apply ender chest for "
                    + pdsPlayer.getName() + ": " + e.getMessage());
        }
    }

    @Override
    public void apply(PDSPlayer pdsPlayer, PlayerData data) {
        Player player = (Player) pdsPlayer.getHandle();
        // Stats
        player.setHealth(Math.min(data.health, getMaxHealth(player)));
        player.setFoodLevel(data.foodLevel);
        player.setSaturation(data.saturation);
        player.setExhaustion(data.exhaustion);
        player.setRemainingAir(data.airLevel);
        player.setFireTicks(data.fireTicks);
        player.setFreezeTicks(data.freezeTicks);
        player.setArrowsInBody(data.arrowsInBody);
        player.setAbsorptionAmount(data.absorptionAmount);

        // Exp
        player.setLevel(data.level);
        player.setExp(data.exp);
        player.setTotalExperience(data.totalExperience);

        // Status
        player.setGameMode(GameMode.valueOf(data.gameMode));
        
        try {
            player.setAllowFlight(data.canFly);
            player.setFlying(data.isFlying);
            // walkSpeed/flySpeed default to 0f on a fresh PlayerData; applying that
            // zeroes minecraft:movement_speed and freezes WASD.
            if (data.walkSpeed > 0.0f && data.walkSpeed <= 1.0f) {
                player.setWalkSpeed(data.walkSpeed);
            } else {
                player.setWalkSpeed(0.2f);
            }
            if (data.flySpeed > 0.0f && data.flySpeed <= 1.0f) {
                player.setFlySpeed(data.flySpeed);
            } else {
                player.setFlySpeed(0.1f);
            }
            player.setFallDistance(data.fallDistance);
        } catch (NoSuchMethodError ignored) {}

        // Effects
        player.getActivePotionEffects().forEach(e -> player.removePotionEffect(e.getType()));
        applyPotionEffects(player, data.potionEffects);

        // Inventory
        deserializeInventory(pdsPlayer, data.inventoryContents);
        applyEnderChest(pdsPlayer, data.enderChestContents);
        
        player.getInventory().setHeldItemSlot(data.selectedSlot);

        // PDC
        if (data.persistentDataContainer != null) {
            try {
                Map<org.bukkit.NamespacedKey, Object> values = (Map<org.bukkit.NamespacedKey, Object>) SerializationUtil.fromBase64(data.persistentDataContainer);
                values.forEach((key, val) -> player.getPersistentDataContainer().set(key, (org.bukkit.persistence.PersistentDataType) getDataType(val), val));
            } catch (Exception ignored) {}
        }

        // Attributes
        applyAttributes(player, data.attributes);

        // Stats & Advancements
        try {
            if (syncEnabled("statistics", true)) {
                applyStatistics(player, readStatistics(data.statistics));
            }
            if (syncEnabled("advancements", true)) {
                applyAdvancements(player, readAdvancements(data.advancements));
            }
        } catch (Exception ignored) {}

        // Never apply a frozen personal clock. setPlayerTime(t, false) makes each
        // client see a different stuck time of day (sunrise vs night vs day).
        player.resetPlayerTime();
        player.resetPlayerWeather();
    }
    
    protected static boolean isSpeedAttribute(String id) {
        if (id == null) {
            return false;
        }
        String s = id.toLowerCase();
        return s.contains("movement_speed") || s.contains("flying_speed")
                || s.endsWith("walking_speed") || s.contains("generic.movement_speed")
                || s.contains("generic_movement_speed");
    }

    /** Vanilla movement_speed is 0.1; flying_speed is 0.05. Never persist or apply 0. */
    protected static double sanitizeSpeedAttribute(String id, double value) {
        if (!isSpeedAttribute(id) || value > 0.0) {
            return value;
        }
        String s = id.toLowerCase();
        return s.contains("flying") ? 0.05 : 0.1;
    }

    protected Map<String, Double> captureAttributes(Player player) {
        Map<String, Double> map = new HashMap<>();
        for (Attribute attr : Attribute.values()) {
            AttributeInstance inst = player.getAttribute(attr);
            if (inst != null) {
                map.put(attr.name(), sanitizeSpeedAttribute(attr.name(), inst.getBaseValue()));
            }
        }
        return map;
    }

    protected void applyAttributes(Player player, Map<String, Double> attributes) {
        if (attributes == null) return;
        attributes.forEach((name, value) -> {
            try {
                if (value == null) {
                    return;
                }
                Attribute attr = Attribute.valueOf(name);
                AttributeInstance inst = player.getAttribute(attr);
                if (inst != null) {
                    inst.setBaseValue(sanitizeSpeedAttribute(name, value));
                }
            } catch (Exception ignored) {}
        });
    }

    protected Map<String, Integer> captureStatistics(Player player) {
        Map<String, Integer> stats = new HashMap<>();
        for (org.bukkit.Statistic stat : org.bukkit.Statistic.values()) {
            try {
                if (stat.getType() == org.bukkit.Statistic.Type.UNTYPED) {
                    stats.put(stat.name(), player.getStatistic(stat));
                }
            } catch (Exception ignored) {}
        }
        return stats;
    }

    protected void applyStatistics(Player player, Map<String, Integer> stats) {
        if (stats == null) return;
        stats.forEach((name, val) -> {
            try {
                org.bukkit.Statistic stat = org.bukkit.Statistic.valueOf(name);
                if (stat.getType() != org.bukkit.Statistic.Type.UNTYPED) return;
                int current = player.getStatistic(stat);
                player.setStatistic(stat, Math.max(current, val));
            } catch (Exception ignored) {}
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> readStatistics(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        if (de.craftingstudiopro.playerDataSyncReloaded.common.util.PortableJson.isJsonObject(raw)) {
            return de.craftingstudiopro.playerDataSyncReloaded.common.util.PortableJson.statsFromJson(raw);
        }
        try {
            return (Map<String, Integer>) SerializationUtil.fromBase64(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private String effectsToJson(Player player) {
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (PotionEffect effect : player.getActivePotionEffects()) {
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            org.bukkit.NamespacedKey key = effect.getType().getKey();
            o.addProperty("id", key != null ? key.toString() : effect.getType().getName());
            o.addProperty("amplifier", effect.getAmplifier());
            int dur = effect.getDuration();
            o.addProperty("duration", dur < 0 ? -1 : dur);
            o.addProperty("ambient", effect.isAmbient());
            o.addProperty("particles", effect.hasParticles());
            o.addProperty("icon", effect.hasIcon());
            arr.add(o);
        }
        return arr.toString();
    }

    private void applyPotionEffects(Player player, String raw) {
        if (raw == null || raw.isEmpty()) return;
        if (de.craftingstudiopro.playerDataSyncReloaded.common.util.PortableJson.isJsonArray(raw)) {
            com.google.gson.JsonArray arr = de.craftingstudiopro.playerDataSyncReloaded.common.util.PortableJson.parseArray(raw);
            for (com.google.gson.JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                com.google.gson.JsonObject o = el.getAsJsonObject();
                if (!o.has("id")) continue;
                try {
                    org.bukkit.potion.PotionEffectType type = org.bukkit.potion.PotionEffectType.getByKey(
                            org.bukkit.NamespacedKey.fromString(o.get("id").getAsString()));
                    if (type == null) {
                        type = org.bukkit.potion.PotionEffectType.getByName(o.get("id").getAsString());
                    }
                    if (type == null) continue;
                    int duration = o.has("duration") ? o.get("duration").getAsInt() : 200;
                    if (duration < 0) duration = Integer.MAX_VALUE;
                    int amp = o.has("amplifier") ? o.get("amplifier").getAsInt() : 0;
                    boolean ambient = o.has("ambient") && o.get("ambient").getAsBoolean();
                    boolean particles = !o.has("particles") || o.get("particles").getAsBoolean();
                    boolean icon = !o.has("icon") || o.get("icon").getAsBoolean();
                    player.addPotionEffect(new PotionEffect(type, duration, amp, ambient, particles, icon));
                } catch (Exception ignored) {
                }
            }
            return;
        }
        try {
            Collection<PotionEffect> effects = (Collection<PotionEffect>) SerializationUtil.fromBase64(raw);
            player.addPotionEffects(effects);
        } catch (Exception ignored) {
        }
    }

    protected List<String> readAdvancements(String raw) {
        if (PortableJson.isJsonArray(raw)) {
            return PortableJson.advancementsFromJson(raw);
        }
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            Object o = SerializationUtil.fromBase64(raw);
            if (o instanceof List<?> list) {
                List<String> out = new ArrayList<>();
                for (Object e : list) {
                    if (e != null) out.add(e.toString());
                }
                return out;
            }
        } catch (Exception ignored) {
        }
        return List.of();
    }

    protected java.util.List<String> captureAdvancements(Player player) {
        java.util.List<String> list = new java.util.ArrayList<>();
        java.util.Iterator<org.bukkit.advancement.Advancement> it = Bukkit.advancementIterator();
        while (it.hasNext()) {
            org.bukkit.advancement.Advancement adv = it.next();
            if (player.getAdvancementProgress(adv).isDone()) {
                list.add(adv.getKey().toString());
            }
        }
        return list;
    }

    protected void applyAdvancements(Player player, java.util.List<String> advancements) {
        if (advancements == null) return;
        advancements.forEach(keyStr -> {
            org.bukkit.advancement.Advancement adv = Bukkit.getAdvancement(org.bukkit.NamespacedKey.fromString(keyStr));
            if (adv != null) {
                org.bukkit.advancement.AdvancementProgress progress = player.getAdvancementProgress(adv);
                if (!progress.isDone()) {
                    progress.getRemainingCriteria().forEach(progress::awardCriteria);
                }
            }
        });
    }

    private Object getDataType(Object val) {
        if (val instanceof String) return org.bukkit.persistence.PersistentDataType.STRING;
        if (val instanceof Integer) return org.bukkit.persistence.PersistentDataType.INTEGER;
        if (val instanceof Double) return org.bukkit.persistence.PersistentDataType.DOUBLE;
        if (val instanceof Byte) return org.bukkit.persistence.PersistentDataType.BYTE;
        return org.bukkit.persistence.PersistentDataType.STRING; // Fallback
    }

    protected abstract double getMaxHealth(Player player);

    @Override
    public String serializeInventory(PDSPlayer pdsPlayer) {
        Player player = (Player) pdsPlayer.getHandle();
        return SerializationUtil.toBase64(filterItems(player.getInventory().getContents()));
    }

    @Override
    public void deserializeInventory(PDSPlayer pdsPlayer, String inventory) {
        Player player = (Player) pdsPlayer.getHandle();
        try {
            ItemStack[] contents = (ItemStack[]) SerializationUtil.fromBase64(inventory);
            player.getInventory().setContents(filterItems(contents));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    protected ItemStack[] filterItems(ItemStack[] contents) {
        if (itemExclusions == null || itemExclusions.isEmpty()) return contents;
        ItemStack[] filtered = contents.clone();
        for (int i = 0; i < filtered.length; i++) {
            if (filtered[i] != null && itemExclusions.contains(filtered[i].getType().name())) {
                filtered[i] = null;
            }
        }
        return filtered;
    }
}
