package dev.moddeus.live;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.moddeus.Moddeus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

/**
 * Data-driven content: every JSON in resources/moddeus/content/ becomes a block or item.
 * Written by tools/mc.mjs. Runs at startup and live whenever those files change; new entries
 * are handed to players so they can be tried right away.
 */
public final class ContentLoader {
	private ContentLoader() {}

	public static void loadAll() {
		// every root of the mod (portable mode adds the workspace's src/main/resources in front): first one wins per id
		Map<String, Path> byName = new java.util.TreeMap<>();
		for (Path r : FabricLoader.getInstance().getModContainer(Moddeus.MOD_ID).map(m -> m.getRootPaths()).orElse(List.of())) {
			Path d = r.resolve("moddeus/content");
			if (!Files.isDirectory(d)) continue;
			try (Stream<Path> fs = Files.list(d)) { fs.filter(f -> f.toString().endsWith(".json")).forEach(f -> byName.putIfAbsent(f.getFileName().toString(), f)); }
			catch (Exception e) { Moddeus.LOG.warn("content dir {}", d, e); }
		}
		if (byName.isEmpty()) return;
		List<Item> fresh = new ArrayList<>();
		dev.moddeus.content.Vfx.DEFS.clear(); // re-filled below; deleted vfx disappear
		dev.moddeus.content.ModTab.tabs().clear(); // item -> creative tab, re-filled below
		try {
			for (Path p : byName.values()) {
				String id = p.getFileName().toString().replace(".json", "");
				try {
					JsonObject o = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
					boolean isNew = !LiveRegistry.exists(BuiltInRegistries.ITEM, LiveRegistry.id(id));
					Item item = switch (str(o, "type", "block")) {
						case "block" -> block(id, o);
						case "item" -> item(id, o);
						case "mob" -> dev.moddeus.content.Mobs.register(id, o);
						case "particle" -> { dev.moddeus.content.Particles.register(id, o); yield null; }
						case "physics" -> { dev.moddeus.content.Physics.SPECS.put(LiveRegistry.id(id), o); yield null; }
						case "vfx" -> { dev.moddeus.content.Vfx.DEFS.put(LiveRegistry.id(id), o); yield null; }
						default -> throw new IllegalArgumentException("unknown type " + o.get("type"));
					};
					if (o.has("vfx") && o.get("vfx").isJsonObject()) dev.moddeus.content.Vfx.ITEM.put(LiveRegistry.id(id), o.getAsJsonObject("vfx"));
					else dev.moddeus.content.Vfx.ITEM.remove(LiveRegistry.id(id));
					if (o.has("throws") && o.get("throws").isJsonObject()) dev.moddeus.content.Physics.THROW.put(LiveRegistry.id(id), o.getAsJsonObject("throws"));
					else dev.moddeus.content.Physics.THROW.remove(LiveRegistry.id(id));
					if (o.has("tab")) dev.moddeus.content.ModTab.tabs().put(LiveRegistry.id(id), o.get("tab").getAsString());
					if (isNew && item != null) fresh.add(item);
				} catch (Exception e) {
					Moddeus.LOG.error("Content {} failed", p, e);
				}
			}
		} catch (Exception e) {
			Moddeus.LOG.error("Content scan failed", e);
		}
		var server = LiveRegistry.server;
		if (server != null) purgeRemoved(server, byName.keySet());
		dev.moddeus.content.ModTab.sync();
		dev.moddeus.content.ModTab.refresh();
		if (server != null && !fresh.isEmpty()) {
			server.getPlayerList().getPlayers().forEach(pl -> fresh.forEach(i -> pl.getInventory().add(new ItemStack(i, i.getDefaultMaxStackSize()))));
		}
	}

	/**
	 * Content whose JSON was deleted (mc remove) is removed LIVE: wiped from player inventories,
	 * loaded chunks (blocks, containers, dropped items), then unregistered from the running
	 * registries. Sounds whose .ogg is gone are unregistered too.
	 */
	private static void purgeRemoved(net.minecraft.server.MinecraftServer server, java.util.Set<String> dir) {
		java.util.Set<Item> items = new java.util.HashSet<>();
		java.util.Set<Block> blocks = new java.util.HashSet<>();
		List<Identifier> itemIds = new ArrayList<>(), blockIds = new ArrayList<>(), soundIds = new ArrayList<>();
		for (var e : BuiltInRegistries.ITEM.entrySet()) {
			Identifier id = e.getKey().identifier();
			if (id.getNamespace().equals(Moddeus.MOD_ID) && !dir.contains(id.getPath() + ".json")
					&& !dir.contains(id.getPath().replaceFirst("_spawn_egg$", "") + ".json")) { items.add(e.getValue()); itemIds.add(id); }
		}
		for (var e : BuiltInRegistries.BLOCK.entrySet()) {
			Identifier id = e.getKey().identifier();
			if (id.getNamespace().equals(Moddeus.MOD_ID) && !dir.contains(id.getPath() + ".json")) { blocks.add(e.getValue()); blockIds.add(id); }
		}
		var mod = FabricLoader.getInstance().getModContainer(Moddeus.MOD_ID).orElseThrow();
		for (Identifier id : BuiltInRegistries.SOUND_EVENT.keySet()) {
			if (id.getNamespace().equals(Moddeus.MOD_ID) && mod.findPath("assets/moddeus/sounds/" + id.getPath() + ".ogg").isEmpty()) soundIds.add(id);
		}
		if (items.isEmpty() && blocks.isEmpty() && soundIds.isEmpty()) return;

		int stacks = 0, placed = 0, dropped = 0;
		var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
		int radius = server.getPlayerList().getViewDistance() + 2;
		java.util.Set<Long> done = new java.util.HashSet<>();
		for (var player : server.getPlayerList().getPlayers()) {
			var inv = player.getInventory();
			for (int i = 0; i < inv.getContainerSize(); i++) {
				if (items.contains(inv.getItem(i).getItem())) { inv.setItem(i, ItemStack.EMPTY); stacks++; }
			}
		}
		// every chunk the server holds in memory (not just near players): a removed block left in one breaks the
		// next chunk packet ("No value with id -1") and kicks the player to the title screen.
		for (var level : server.getAllLevels()) {
			for (var chunk : loadedChunks(level)) {
				int cx = chunk.getPos().x(), cz = chunk.getPos().z();
				if (!done.add(((long) cx << 32) ^ (cz & 0xffffffffL) ^ ((long) level.dimension().hashCode() << 48))) continue;
					for (var be : chunk.getBlockEntities().values()) {
						if (be instanceof net.minecraft.world.Container c) {
							for (int i = 0; i < c.getContainerSize(); i++) {
								if (items.contains(c.getItem(i).getItem())) { c.setItem(i, ItemStack.EMPTY); stacks++; }
							}
						}
					}
					var sections = chunk.getSections();
					for (int si = 0; si < sections.length; si++) {
						var sec = sections[si];
						if (sec.hasOnlyAir() || !sec.maybeHas(s -> blocks.contains(s.getBlock()))) continue;
						int baseY = chunk.getSectionYFromSectionIndex(si) << 4;
						for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
							if (blocks.contains(sec.getBlockState(x, y, z).getBlock())) {
								level.setBlock(new net.minecraft.core.BlockPos((cx << 4) + x, baseY + y, (cz << 4) + z), air, 3);
								placed++;
							}
						}
					}
			}
		}
		for (var level : server.getAllLevels()) {
			for (var entity : level.getAllEntities()) {
				if (entity instanceof net.minecraft.world.entity.item.ItemEntity ie && items.contains(ie.getItem().getItem())) { ie.discard(); dropped++; }
			}
		}
		java.util.Set<Object> gone = new java.util.HashSet<>(items);
		gone.addAll(blocks);
		for (var player : server.getPlayerList().getPlayers()) {
			var ender = player.getEnderChestInventory();
			for (int i = 0; i < ender.getContainerSize(); i++) {
				if (items.contains(ender.getItem(i).getItem())) { ender.setItem(i, ItemStack.EMPTY); stacks++; }
			}
			scrubStats(player.getStats(), gone);
		}
		scrubStatTypes(gone);
		itemIds.forEach(id -> LiveRegistry.unregister(BuiltInRegistries.ITEM, id));
		blockIds.forEach(id -> LiveRegistry.unregister(BuiltInRegistries.BLOCK, id));
		soundIds.forEach(id -> LiveRegistry.unregister(BuiltInRegistries.SOUND_EVENT, id));
		Moddeus.LOG.info("Removed live: {} items, {} blocks, {} sounds; wiped {} stacks, {} placed, {} dropped",
			itemIds.size(), blockIds.size(), soundIds.size(), stacks, placed, dropped);
		server.getPlayerList().getPlayers().forEach(p -> p.sendSystemMessage(net.minecraft.network.chat.Component.literal(
			"[Moddeus] removed " + (itemIds.size() + blockIds.size()) + " entries live")));
	}

	/** Stats (mined/used/picked up...) reference items/blocks; saving them crashes once those are unregistered. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	/** All full chunks the server has in memory (ChunkMap.allChunksWithAtLeastStatus is package-private). */
	public static java.util.List<net.minecraft.world.level.chunk.LevelChunk> loadedChunks(net.minecraft.server.level.ServerLevel level) {
		java.util.List<net.minecraft.world.level.chunk.LevelChunk> out = new java.util.ArrayList<>();
		try {
			var m = net.minecraft.server.level.ChunkMap.class.getDeclaredMethod("allChunksWithAtLeastStatus", net.minecraft.world.level.chunk.status.ChunkStatus.class);
			m.setAccessible(true);
			@SuppressWarnings("unchecked")
			var holders = (java.util.stream.Stream<net.minecraft.server.level.ChunkHolder>) m.invoke(level.getChunkSource().chunkMap, net.minecraft.world.level.chunk.status.ChunkStatus.FULL);
			holders.forEach(h -> { var c = h.getFullChunkFuture().getNow(net.minecraft.server.level.ChunkHolder.UNLOADED_LEVEL_CHUNK).orElse(null); if (c != null) out.add(c); });
		} catch (ReflectiveOperationException e) {
			Moddeus.LOG.error("loaded chunk scan failed", e);
		}
		return out;
	}

	private static void scrubStats(Object counter, java.util.Set<Object> gone) {
		try {
			for (Class<?> c = counter.getClass(); c != Object.class; c = c.getSuperclass()) {
				for (var f : c.getDeclaredFields()) {
					if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
					f.setAccessible(true);
					Object v = f.get(counter);
					if (v instanceof java.util.Map m) m.keySet().removeIf(k -> k instanceof net.minecraft.stats.Stat<?> s && gone.contains(s.getValue()));
					else if (v instanceof java.util.Collection col) col.removeIf(k -> k instanceof net.minecraft.stats.Stat<?> s && gone.contains(s.getValue()));
				}
			}
		} catch (ReflectiveOperationException e) {
			Moddeus.LOG.error("stat scrub failed", e);
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void scrubStatTypes(java.util.Set<Object> gone) {
		try {
			var f = net.minecraft.stats.StatType.class.getDeclaredField("map");
			f.setAccessible(true);
			for (var type : BuiltInRegistries.STAT_TYPE) ((java.util.Map) f.get(type)).keySet().removeIf(gone::contains);
		} catch (ReflectiveOperationException e) {
			Moddeus.LOG.error("stat type scrub failed", e);
		}
	}

	private static Item block(String id, JsonObject o) {
		SoundType base = soundType(str(o, "sound", "stone"));
		SoundType st = new SoundType(base.getVolume(), base.getPitch(),
			sound(o, "breakSound", base.getBreakSound()), sound(o, "stepSound", base.getStepSound()),
			sound(o, "placeSound", base.getPlaceSound()), sound(o, "hitSound", base.getHitSound()), base.getFallSound());
		int light = num(o, "light", 0).intValue();
		BlockBehaviour.Properties props = BlockBehaviour.Properties.of()
			.mapColor(MapColor.STONE)
			.strength(num(o, "hardness", 1.5).floatValue(), num(o, "resistance", 6).floatValue())
			.sound(st)
			.lightLevel(s -> light)
			.friction(num(o, "friction", 0.6).floatValue())
			.speedFactor(num(o, "speed", 1).floatValue())
			.jumpFactor(num(o, "jump", 1).floatValue());
		if (bool(o, "requiresTool")) props.requiresCorrectToolForDrops();
		if (bool(o, "transparent")) props.noOcclusion();
		if (bool(o, "instabreak")) props.instabreak();
		String shape = str(o, "shape", "cube");
		if (o.has("cubes")) dev.moddeus.content.ModelBlock.setCubes(LiveRegistry.id(id), o.getAsJsonArray("cubes"));
		Block block = LiveRegistry.register(BuiltInRegistries.BLOCK, LiveRegistry.id(id), k -> shape(shape, props.setId(k)));
		return LiveRegistry.register(BuiltInRegistries.ITEM, LiveRegistry.id(id), k -> new BlockItem(block,
			itemProps(o).useBlockDescriptionPrefix().setId(k)));
	}

	/** Vanilla block classes for non-cube shapes; mc.mjs copies the matching vanilla blockstate/models. */
	private static Block shape(String shape, BlockBehaviour.Properties p) {
		return switch (shape) {
			case "slab" -> new net.minecraft.world.level.block.SlabBlock(p);
			case "stairs" -> new net.minecraft.world.level.block.StairBlock(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), p);
			case "wall" -> new net.minecraft.world.level.block.WallBlock(p);
			case "fence" -> new net.minecraft.world.level.block.FenceBlock(p);
			case "fence_gate" -> new net.minecraft.world.level.block.FenceGateBlock(net.minecraft.world.level.block.state.properties.WoodType.OAK, p);
			case "door" -> new net.minecraft.world.level.block.DoorBlock(net.minecraft.world.level.block.state.properties.BlockSetType.OAK, p.noOcclusion());
			case "trapdoor" -> new net.minecraft.world.level.block.TrapDoorBlock(net.minecraft.world.level.block.state.properties.BlockSetType.OAK, p.noOcclusion());
			case "button" -> new net.minecraft.world.level.block.ButtonBlock(net.minecraft.world.level.block.state.properties.BlockSetType.OAK, 30, p.noCollision());
			case "pressure_plate" -> new net.minecraft.world.level.block.PressurePlateBlock(net.minecraft.world.level.block.state.properties.BlockSetType.OAK, p.noCollision());
			case "pane" -> new net.minecraft.world.level.block.IronBarsBlock(p.noOcclusion());
			case "pillar" -> new net.minecraft.world.level.block.RotatedPillarBlock(p);
			case "carpet" -> new net.minecraft.world.level.block.CarpetBlock(p);
			case "glass" -> new net.minecraft.world.level.block.TransparentBlock(p.noOcclusion());
			case "model" -> new dev.moddeus.content.ModelBlock(p.noOcclusion());
			default -> new Block(p);
		};
	}

	private static Item item(String id, JsonObject o) {
		Item.Properties props = itemProps(o);
		if (o.has("food")) {
			JsonObject f = o.getAsJsonObject("food");
			props.food(new FoodProperties(num(f, "nutrition", 4).intValue(), num(f, "saturation", 0.3).floatValue(), bool(f, "alwaysEat")));
		}
		components(props, o);
		// tools/armor resolve block tags via the bootstrap lookup, which only works while LiveRegistry has the registries thawed
		return LiveRegistry.register(BuiltInRegistries.ITEM, LiveRegistry.id(id), k -> LiveRegistry.thawed(BuiltInRegistries.BLOCK, () -> { toolAndArmor(props, o); return new Item(props.setId(k)); }));
	}

	private static void toolAndArmor(Item.Properties props, JsonObject o) {
		if (o.has("tool")) {
			JsonObject t = o.getAsJsonObject("tool");
			var m = toolMaterial(str(t, "material", "iron"));
			String kind = str(t, "type", "sword");
			float[] d = switch (kind) { case "pickaxe" -> new float[]{1, -2.8f}; case "axe" -> new float[]{6, -3.1f}; case "shovel" -> new float[]{1.5f, -3}; case "hoe" -> new float[]{-2, -1}; default -> new float[]{3, -2.4f}; };
			float dmg = num(t, "damage", d[0]).floatValue(), spd = num(t, "speed", d[1]).floatValue();
			switch (kind) {
				case "pickaxe" -> props.pickaxe(m, dmg, spd);
				case "axe" -> props.axe(m, dmg, spd);
				case "shovel" -> props.shovel(m, dmg, spd);
				case "hoe" -> props.hoe(m, dmg, spd);
				default -> props.sword(m, dmg, spd);
			}
		}
		if (o.has("armor")) {
			JsonObject a = o.getAsJsonObject("armor");
			var base = armorMaterial(str(a, "material", "iron"));
			var type = net.minecraft.world.item.equipment.ArmorType.valueOf(str(a, "type", "helmet").toUpperCase());
			var mat = !a.has("asset") ? base : new net.minecraft.world.item.equipment.ArmorMaterial(base.durability(), base.defense(), base.enchantmentValue(),
				base.equipSound(), base.toughness(), base.knockbackResistance(), base.repairIngredient(),
				net.minecraft.resources.ResourceKey.create(net.minecraft.world.item.equipment.EquipmentAssets.ROOT_ID, Identifier.parse(str(a, "asset", ""))));
			props.humanoidArmor(mat, type);
		}
	}

	private static Item.Properties itemProps(JsonObject o) {
		Item.Properties p = new Item.Properties();
		if (o.has("durability")) p.durability(num(o, "durability", 100).intValue());
		else if (o.has("stack")) p.stacksTo(num(o, "stack", 64).intValue());
		if (o.has("rarity")) p.rarity(Rarity.valueOf(str(o, "rarity", "common").toUpperCase()));
		if (bool(o, "fireResistant")) p.fireResistant();
		return p;
	}

	private static net.minecraft.world.item.ToolMaterial toolMaterial(String n) {
		return switch (n) {
			case "wood", "wooden" -> net.minecraft.world.item.ToolMaterial.WOOD;
			case "stone" -> net.minecraft.world.item.ToolMaterial.STONE;
			case "copper" -> net.minecraft.world.item.ToolMaterial.COPPER;
			case "gold", "golden" -> net.minecraft.world.item.ToolMaterial.GOLD;
			case "diamond" -> net.minecraft.world.item.ToolMaterial.DIAMOND;
			case "netherite" -> net.minecraft.world.item.ToolMaterial.NETHERITE;
			default -> net.minecraft.world.item.ToolMaterial.IRON;
		};
	}

	private static net.minecraft.world.item.equipment.ArmorMaterial armorMaterial(String n) {
		return switch (n) {
			case "leather" -> net.minecraft.world.item.equipment.ArmorMaterials.LEATHER;
			case "copper" -> net.minecraft.world.item.equipment.ArmorMaterials.COPPER;
			case "chainmail" -> net.minecraft.world.item.equipment.ArmorMaterials.CHAINMAIL;
			case "gold", "golden" -> net.minecraft.world.item.equipment.ArmorMaterials.GOLD;
			case "diamond" -> net.minecraft.world.item.equipment.ArmorMaterials.DIAMOND;
			case "netherite" -> net.minecraft.world.item.equipment.ArmorMaterials.NETHERITE;
			default -> net.minecraft.world.item.equipment.ArmorMaterials.IRON;
		};
	}

	/** "components": {"minecraft:enchantments": {...}, ...}: any data component, parsed with its vanilla codec. */
	private static void components(Item.Properties p, JsonObject o) {
		if (!o.has("components")) return;
		var server = LiveRegistry.server;
		com.mojang.serialization.DynamicOps<JsonElement> ops = server != null
			? net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, server.registryAccess())
			: com.mojang.serialization.JsonOps.INSTANCE; // ponytail: registry-backed components (enchantments) need a loaded world
		for (var e : o.getAsJsonObject("components").entrySet()) {
			var type = BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(Identifier.parse(e.getKey()));
			if (type == null) { Moddeus.LOG.error("Unknown component {}", e.getKey()); continue; }
			component(p, type, ops, e.getKey(), e.getValue());
		}
	}

	private static <T> void component(Item.Properties p, net.minecraft.core.component.DataComponentType<T> type, com.mojang.serialization.DynamicOps<JsonElement> ops, String name, JsonElement v) {
		type.codecOrThrow().parse(ops, v).resultOrPartial(err -> Moddeus.LOG.error("Component {}: {}", name, err)).ifPresent(val -> p.component(type, val));
	}

	private static SoundEvent sound(JsonObject o, String key, SoundEvent fallback) {
		if (!o.has(key)) return fallback;
		Identifier id = Identifier.parse(str(o, key, ""));
		if (id.getNamespace().equals(Moddeus.MOD_ID)) {
			return LiveRegistry.register(BuiltInRegistries.SOUND_EVENT, id, k -> SoundEvent.createVariableRangeEvent(id));
		}
		return BuiltInRegistries.SOUND_EVENT.getOptional(id).orElse(fallback);
	}

	private static SoundType soundType(String name) {
		return switch (name) {
			case "wood" -> SoundType.WOOD;
			case "gravel" -> SoundType.GRAVEL;
			case "grass" -> SoundType.GRASS;
			case "metal" -> SoundType.METAL;
			case "glass" -> SoundType.GLASS;
			case "wool" -> SoundType.WOOL;
			case "sand" -> SoundType.SAND;
			case "snow" -> SoundType.SNOW;
			case "slime" -> SoundType.SLIME_BLOCK;
			case "amethyst" -> SoundType.AMETHYST;
			default -> SoundType.STONE;
		};
	}

	private static String str(JsonObject o, String k, String d) { JsonElement e = o.get(k); return e == null ? d : e.getAsString(); }
	private static Number num(JsonObject o, String k, Number d) { JsonElement e = o.get(k); return e == null ? d : e.getAsNumber(); }
	private static boolean bool(JsonObject o, String k) { JsonElement e = o.get(k); return e != null && e.getAsBoolean(); }
}
