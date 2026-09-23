package de.craftingstudiopro.playerDataSyncReloaded.fabric;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import de.craftingstudiopro.playerDataSyncReloaded.api.PDSPlayer;
import de.craftingstudiopro.playerDataSyncReloaded.api.PlayerData;
import de.craftingstudiopro.playerDataSyncReloaded.api.VersionHandler;
import de.craftingstudiopro.playerDataSyncReloaded.common.SlotDataFormat;
import de.craftingstudiopro.playerDataSyncReloaded.common.util.PortableJson;
import net.minecraft.SharedConstants;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class FabricVersionHandler implements VersionHandler {
    private List<String> itemExclusions = new ArrayList<>();

    private static boolean syncEnabled(String key, boolean def) {
        FabricPlatform platform = PlayerDataSyncFabric.platform();
        if (platform == null) {
            return def;
        }
        return platform.getConfigBoolean("sync." + key, def);
    }

    @Override
    public PlayerData capture(PDSPlayer pdsPlayer) {
        ServerPlayer player = (ServerPlayer) pdsPlayer.getHandle();
        PlayerData data = new PlayerData();
        data.uuid = player.getUUID();
        data.name = player.getName().getString();

        data.health = Math.max(player.getHealth(), 0.1f);
        data.healthScale = 20.0;
        data.foodLevel = player.getFoodData().getFoodLevel();
        data.saturation = player.getFoodData().getSaturationLevel();
        data.exp = player.experienceProgress;
        data.level = player.experienceLevel;
        data.totalExperience = player.totalExperience;

        data.inventoryContents = serializeContainer(player, player.getInventory());
        data.enderChestContents = serializeContainer(player, player.getEnderChestInventory());
        data.gameMode = player.gameMode.getGameModeForPlayer().name();
        data.canFly = player.getAbilities().mayfly;
        data.isFlying = player.getAbilities().flying;
        data.potionEffects = captureEffects(player);
        if (syncEnabled("statistics", true)) {
            data.statistics = captureStatistics(player);
        }
        if (syncEnabled("advancements", true)) {
            data.advancements = captureAdvancements(player);
        }

        return data;
    }

    @Override
    public void apply(PDSPlayer pdsPlayer, PlayerData data) {
        ServerPlayer player = (ServerPlayer) pdsPlayer.getHandle();

        float health = (float) data.health;
        if (health <= 0) {
            health = 20.0f;
        }
        player.setHealth(health);
        player.getFoodData().setFoodLevel(data.foodLevel);
        player.getFoodData().setSaturation(data.saturation);
        player.experienceProgress = data.exp;
        player.experienceLevel = data.level;
        player.totalExperience = data.totalExperience;

        if (data.inventoryContents != null) {
            deserializeInventory(pdsPlayer, data.inventoryContents);
        }
        if (data.enderChestContents != null) {
            applyContainer(player, player.getEnderChestInventory(), data.enderChestContents);
        }

        applyGameMode(player, data);
        applyEffects(player, data.potionEffects);
        if (syncEnabled("statistics", true)) {
            applyStatistics(player, data.statistics);
        }
        if (syncEnabled("advancements", true)) {
            applyAdvancements(player, data.advancements);
        }
    }

    private static void applyGameMode(ServerPlayer player, PlayerData data) {
        if (data.gameMode == null || data.gameMode.isBlank()) return;
        try {
            GameType type = GameType.byName(data.gameMode.toLowerCase(Locale.ROOT), null);
            if (type == null) {
                type = GameType.valueOf(data.gameMode.toUpperCase(Locale.ROOT));
            }
            player.setGameMode(type);
        } catch (Exception ignored) {
        }
        player.getAbilities().mayfly = data.canFly || player.getAbilities().mayfly;
        player.getAbilities().flying = data.isFlying && player.getAbilities().mayfly;
        player.onUpdateAbilities();
    }

    private static String captureEffects(ServerPlayer player) {
        JsonArray arr = new JsonArray();
        for (MobEffectInstance effect : player.getActiveEffects()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", effect.getEffect().getRegisteredName());
            o.addProperty("amplifier", effect.getAmplifier());
            o.addProperty("duration", effect.isInfiniteDuration() ? -1 : effect.getDuration());
            o.addProperty("ambient", effect.isAmbient());
            o.addProperty("particles", effect.isVisible());
            o.addProperty("icon", effect.showIcon());
            arr.add(o);
        }
        return arr.toString();
    }

    private static void applyEffects(ServerPlayer player, String raw) {
        player.removeAllEffects();
        if (raw == null || raw.isEmpty() || !PortableJson.isJsonArray(raw)) return;
        JsonArray arr = PortableJson.parseArray(raw);
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (!o.has("id")) continue;
            Identifier id = Identifier.tryParse(o.get("id").getAsString());
            if (id == null) continue;
            Holder.Reference<MobEffect> holder = BuiltInRegistries.MOB_EFFECT.get(id).orElse(null);
            if (holder == null) continue;
            int duration = o.has("duration") ? o.get("duration").getAsInt() : 200;
            if (duration < 0) duration = MobEffectInstance.INFINITE_DURATION;
            int amp = o.has("amplifier") ? o.get("amplifier").getAsInt() : 0;
            amp = Math.max(MobEffectInstance.MIN_AMPLIFIER, Math.min(MobEffectInstance.MAX_AMPLIFIER, amp));
            boolean ambient = o.has("ambient") && o.get("ambient").getAsBoolean();
            boolean particles = !o.has("particles") || o.get("particles").getAsBoolean();
            boolean icon = !o.has("icon") || o.get("icon").getAsBoolean();
            player.addEffect(new MobEffectInstance(holder, duration, amp, ambient, particles, icon));
        }
    }

    private static String captureAdvancements(ServerPlayer player) {
        try {
            MinecraftServer server = player.level().getServer();
            if (server == null) return null;
            ServerAdvancementManager manager = server.getAdvancements();
            PlayerAdvancements pa = player.getAdvancements();
            List<String> keys = new ArrayList<>();
            for (AdvancementHolder holder : manager.getAllAdvancements()) {
                AdvancementProgress progress = pa.getOrStartProgress(holder);
                if (progress.isDone()) {
                    keys.add(holder.id().toString());
                }
            }
            if (keys.isEmpty()) return null;
            return PortableJson.advancementsToJson(keys);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void applyAdvancements(ServerPlayer player, String raw) {
        List<String> keys = PortableJson.advancementsFromJson(raw);
        if (keys.isEmpty()) return;
        MinecraftServer server = player.level().getServer();
        if (server == null) return;
        ServerAdvancementManager manager = server.getAdvancements();
        PlayerAdvancements pa = player.getAdvancements();
        GameRules rules = player.level().getGameRules();
        boolean announce = Boolean.TRUE.equals(rules.get(GameRules.SHOW_ADVANCEMENT_MESSAGES));
        try {
            if (announce) {
                rules.set(GameRules.SHOW_ADVANCEMENT_MESSAGES, Boolean.FALSE, server);
            }
            for (String key : keys) {
                Identifier id = Identifier.tryParse(key);
                if (id == null) continue;
                AdvancementHolder holder = manager.get(id);
                if (holder == null) continue;
                AdvancementProgress progress = pa.getOrStartProgress(holder);
                if (progress.isDone()) continue;
                for (String criterion : progress.getRemainingCriteria()) {
                    pa.award(holder, criterion);
                }
            }
            pa.flushDirty(player, false);
        } catch (Throwable ignored) {
        } finally {
            if (announce) {
                try {
                    rules.set(GameRules.SHOW_ADVANCEMENT_MESSAGES, Boolean.TRUE, server);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String captureStatistics(ServerPlayer player) {
        try {
            ServerStatsCounter counter = player.getStats();
            Map<String, Integer> map = new LinkedHashMap<>();
            for (Stat<Identifier> stat : Stats.CUSTOM) {
                int v = counter.getValue(stat);
                if (v == 0) continue;
                map.put(toBukkitStat(stat.getValue()), v);
            }
            if (map.isEmpty()) return null;
            return PortableJson.statsToJson(map);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void applyStatistics(ServerPlayer player, String raw) {
        Map<String, Integer> stats;
        if (PortableJson.isJsonObject(raw)) {
            stats = PortableJson.statsFromJson(raw);
        } else {
            return;
        }
        if (stats.isEmpty()) return;
        try {
            ServerStatsCounter counter = player.getStats();
            for (Map.Entry<String, Integer> e : stats.entrySet()) {
                Identifier id = toVanillaStat(e.getKey());
                if (id == null || !Stats.CUSTOM.contains(id)) continue;
                Stat<Identifier> stat = Stats.CUSTOM.get(id);
                int current = counter.getValue(stat);
                int next = Math.max(current, e.getValue());
                if (next != current) {
                    counter.setValue(player, stat, next);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static String toBukkitStat(Identifier id) {
        if (id.equals(Stats.PLAY_TIME)) return "PLAY_ONE_MINUTE";
        return switch (id.getPath()) {
            case "eat_cake_slice" -> "CAKE_SLICES_EATEN";
            case "fill_cauldron" -> "CAULDRON_FILLED";
            case "use_cauldron" -> "CAULDRON_USED";
            case "clean_armor" -> "ARMOR_CLEANED";
            case "clean_banner" -> "BANNER_CLEANED";
            case "interact_with_brewingstand" -> "BREWINGSTAND_INTERACTION";
            case "interact_with_beacon" -> "BEACON_INTERACTION";
            case "inspect_dropper" -> "DROPPER_INSPECTED";
            case "inspect_hopper" -> "HOPPER_INSPECTED";
            case "inspect_dispenser" -> "DISPENSER_INSPECTED";
            case "play_noteblock" -> "NOTEBLOCK_PLAYED";
            case "tune_noteblock" -> "NOTEBLOCK_TUNED";
            case "pot_flower" -> "FLOWER_POTTED";
            case "trigger_trapped_chest" -> "TRAPPED_CHEST_TRIGGERED";
            case "open_enderchest" -> "ENDERCHEST_OPENED";
            case "enchant_item" -> "ITEM_ENCHANTED";
            case "play_record" -> "RECORD_PLAYED";
            case "interact_with_furnace" -> "FURNACE_INTERACTION";
            case "interact_with_crafting_table" -> "CRAFTING_TABLE_INTERACTION";
            case "open_chest" -> "CHEST_OPENED";
            case "open_shulker_box" -> "SHULKER_BOX_OPENED";
            case "open_barrel" -> "BARREL_OPENED";
            default -> id.getPath().toUpperCase(Locale.ROOT);
        };
    }

    private static Identifier toVanillaStat(String bukkit) {
        if (bukkit == null || bukkit.isBlank()) return null;
        String n = bukkit.toUpperCase(Locale.ROOT);
        if (n.equals("PLAY_ONE_MINUTE")) return Stats.PLAY_TIME;
        String path = switch (n) {
            case "CAKE_SLICES_EATEN" -> "eat_cake_slice";
            case "CAULDRON_FILLED" -> "fill_cauldron";
            case "CAULDRON_USED" -> "use_cauldron";
            case "ARMOR_CLEANED" -> "clean_armor";
            case "BANNER_CLEANED" -> "clean_banner";
            case "BREWINGSTAND_INTERACTION" -> "interact_with_brewingstand";
            case "BEACON_INTERACTION" -> "interact_with_beacon";
            case "DROPPER_INSPECTED" -> "inspect_dropper";
            case "HOPPER_INSPECTED" -> "inspect_hopper";
            case "DISPENSER_INSPECTED" -> "inspect_dispenser";
            case "NOTEBLOCK_PLAYED" -> "play_noteblock";
            case "NOTEBLOCK_TUNED" -> "tune_noteblock";
            case "FLOWER_POTTED" -> "pot_flower";
            case "TRAPPED_CHEST_TRIGGERED" -> "trigger_trapped_chest";
            case "ENDERCHEST_OPENED" -> "open_enderchest";
            case "ITEM_ENCHANTED" -> "enchant_item";
            case "RECORD_PLAYED" -> "play_record";
            case "FURNACE_INTERACTION" -> "interact_with_furnace";
            case "CRAFTING_TABLE_INTERACTION" -> "interact_with_crafting_table";
            case "CHEST_OPENED" -> "open_chest";
            case "SHULKER_BOX_OPENED" -> "open_shulker_box";
            case "BARREL_OPENED" -> "open_barrel";
            default -> n.toLowerCase(Locale.ROOT);
        };
        return Identifier.tryParse("minecraft:" + path);
    }

    @Override
    public String serializeInventory(PDSPlayer pdsPlayer) {
        ServerPlayer player = (ServerPlayer) pdsPlayer.getHandle();
        return serializeContainer(player, player.getInventory());
    }

    @Override
    public void deserializeInventory(PDSPlayer pdsPlayer, String inventory) {
        ServerPlayer player = (ServerPlayer) pdsPlayer.getHandle();
        applyContainer(player, player.getInventory(), inventory);
    }

    private String serializeContainer(ServerPlayer player, Container container) {
        RegistryAccess access = player.level().registryAccess();
        Map<Integer, byte[]> slots = new LinkedHashMap<>();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            slots.put(i, encodeStack(stack, access));
        }
        return SlotDataFormat.encode(slots);
    }

    private void applyContainer(ServerPlayer player, Container container, String payload) {
        SlotDataFormat.Kind kind = SlotDataFormat.kind(payload);
        RegistryAccess access = player.level().registryAccess();
        switch (kind) {
            case EMPTY -> {
                return;
            }
            case V2 -> {
                clearContainer(container);
                SlotDataFormat.decodeV2(payload, (slot, bytes) -> {
                    if (slot >= 0 && slot < container.getContainerSize()) {
                        try {
                            container.setItem(slot, decodeStack(bytes, access));
                        } catch (Exception e) {
                            System.getLogger("PlayerDataSync").log(
                                    System.Logger.Level.WARNING,
                                    "Skipping inventory slot " + slot + ": " + e.getMessage());
                            container.setItem(slot, ItemStack.EMPTY);
                        }
                    }
                });
            }
            case FABRIC_JSON -> applyLegacyFabricJson(player, container, payload);
            case LEGACY_BUKKIT -> throw new IllegalArgumentException(
                    "Paper Bukkit-object inventory payload cannot be read on Fabric; wait for a Paper save in v2 format");
            default -> throw new IllegalArgumentException("Unknown inventory payload");
        }
    }

    private void applyLegacyFabricJson(ServerPlayer player, Container container, String json) {
        RegistryAccess access = player.level().registryAccess();
        JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
        clearContainer(container);
        for (JsonElement el : arr) {
            JsonObject slot = el.getAsJsonObject();
            int idx = slot.get("slot").getAsInt();
            JsonElement itemEl = slot.get("item");
            if (itemEl == null || itemEl.isJsonNull() || idx < 0 || idx >= container.getContainerSize()) {
                continue;
            }
            DataResult<ItemStack> result =
                ItemStack.CODEC.parse(access.createSerializationContext(JsonOps.INSTANCE), itemEl);
            container.setItem(idx, result.getOrThrow());
        }
    }

    private static void clearContainer(Container container) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            container.setItem(i, ItemStack.EMPTY);
        }
    }

    /**
     * Paper {@code ItemStack.serializeAsBytes()} is gzip-compressed item NBT
     * ({@code DataVersion}, {@code id}, {@code count}, {@code components}), not a
     * network stream codec. Decoding those bytes as OPTIONAL_STREAM_CODEC turned
     * every stack into pale oak signs.
     */
    private static byte[] encodeStack(ItemStack stack, RegistryAccess access) {
        try {
            Tag tag = ItemStack.CODEC
                    .encodeStart(access.createSerializationContext(NbtOps.INSTANCE), stack)
                    .getOrThrow();
            if (!(tag instanceof CompoundTag compound)) {
                throw new IllegalStateException("Item codec did not produce a compound tag");
            }
            compound.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            NbtIo.writeCompressed(compound, out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Could not encode item stack", e);
        }
    }

    private static ItemStack decodeStack(byte[] bytes, RegistryAccess access) {
        try {
            CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
            tag.remove("DataVersion");
            return ItemStack.CODEC
                    .parse(access.createSerializationContext(NbtOps.INSTANCE), tag)
                    .getOrThrow();
        } catch (Throwable e) {
            throw new IllegalArgumentException("Could not decode Paper item bytes", e);
        }
    }

    @Override
    public void setItemExclusions(List<String> materials) {
        this.itemExclusions = materials == null ? new ArrayList<>() : materials;
    }
}
