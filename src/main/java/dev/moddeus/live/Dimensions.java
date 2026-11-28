package dev.moddeus.live;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.moddeus.Moddeus;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.phys.Vec3;

/**
 * Live dimensions: adds and removes a ServerLevel in the running server, no world rejoin (like the Infiniverse mod).
 * create("mymod:realm", json) takes the same JSON as data/<ns>/dimension/<id>.json ("type" = a dimension_type id or an
 * inline object, which is registered live). Datapack / mod-resource dimensions load live on every datapack reload and
 * unload when their file goes away. go(player, id) / back(player) visit and return exactly where the player was.
 * Server thread only (game run snippets and server events already are). Guide: .claude/skills/mc-content/DIMENSIONS.md
 */
public final class Dimensions {
	static Map<UUID, Object[]> home;       // player -> {ResourceKey<Level>, Vec3, yRot, xRot}: where go() took them from
	static Set<Identifier> fromData;       // dimensions this class loaded from datapacks (unloaded when the file goes)
	static List<Object[]> pending;         // {id, JsonObject} asked for before the server started
	static boolean enabled;

	// ---- create / remove ------------------------------------------------------------------------------------------

	/** The level, creating it if needed. Before the server runs it is queued and created at server start (returns null). */
	public static ServerLevel create(String id, String stemJson) { return create(Identifier.parse(id), JsonParser.parseString(stemJson).getAsJsonObject()); }

	public static ServerLevel create(Identifier id, JsonObject stem) {
		MinecraftServer s = LiveRegistry.server;
		if (s == null) { pending().add(new Object[] { id, stem }); return null; }
		ServerLevel old = s.getLevel(key(id));
		if (old != null) return old;
		JsonObject j = stem.deepCopy();
		JsonElement t = j.get("type");
		if (t != null && t.isJsonObject()) { registerType(s, id, t.getAsJsonObject()); j.addProperty("type", id.toString()); }
		LevelStem ls = LevelStem.CODEC.parse(ops(s.registryAccess()), j).getOrThrow(e -> new IllegalArgumentException("dimension " + id + ": " + e));
		return create(id, ls.type(), ls.generator());
	}

	/** Programmatic form: an existing dimension type (type("minecraft:overworld")) + any ChunkGenerator. */
	public static ServerLevel create(Identifier id, Holder<DimensionType> type, ChunkGenerator generator) {
		MinecraftServer s = LiveRegistry.server;
		ResourceKey<Level> k = key(id);
		ServerLevel old = s.getLevel(k);
		if (old != null) return old;
		try {
			var executor = (java.util.concurrent.Executor) field(MinecraftServer.class, "executor").get(s);
			var storage = (LevelStorageSource.LevelStorageAccess) field(MinecraftServer.class, "storageSource").get(s);
			long zoom = BiomeManager.obfuscateSeed(s.getWorldGenSettings().options().seed());
			ServerLevel l = new ServerLevel(s, executor, storage, new DerivedLevelData(s.getWorldData(), s.getWorldData().overworldData()),
				k, new LevelStem(type, generator), false, zoom, List.of(), false);
			l.getWorldBorder().setAbsoluteMaxSize(s.getAbsoluteMaxWorldSize());
			s.getPlayerList().addWorldborderListener(l);
			putLevel(s, k, l);
			clientLevelKey(k, true);
			ServerLevelEvents.LOAD.invoker().onLevelLoad(s, l);
			Moddeus.LOG.info("Dimension {} created live", id);
			return l;
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("live dimension " + id, e);
		}
	}

	/** Moves players out (back where they came from), saves and closes the level. deleteFiles also wipes its region files. */
	public static boolean remove(String id, boolean deleteFiles) { return remove(Identifier.parse(id), deleteFiles); }

	public static boolean remove(Identifier id, boolean deleteFiles) {
		MinecraftServer s = LiveRegistry.server;
		ResourceKey<Level> k = key(id);
		if (s == null || id.getNamespace().equals("minecraft")) return false;
		ServerLevel l = s.getLevel(k);
		fromData().remove(id);
		if (l == null) return false;
		for (ServerPlayer p : new ArrayList<>(l.players())) back(p);
		putLevel(s, k, null); // stops ticking it
		ServerLevelEvents.UNLOAD.invoker().onLevelUnload(s, l);
		try {
			l.noSave = false;
			l.save(null, true, false);
			l.close();
		} catch (Exception e) {
			Moddeus.LOG.warn("closing dimension {}", id, e);
		}
		clientLevelKey(k, false);
		if (deleteFiles) try {
			Path dir = ((LevelStorageSource.LevelStorageAccess) field(MinecraftServer.class, "storageSource").get(s)).getDimensionPath(k);
			if (Files.isDirectory(dir)) try (var w = Files.walk(dir)) { w.sorted(Comparator.reverseOrder()).forEach(Dimensions::delete); }
		} catch (Exception e) {
			Moddeus.LOG.warn("deleting dimension files {}", id, e);
		}
		Moddeus.LOG.info("Dimension {} removed live{}", id, deleteFiles ? " (files deleted)" : "");
		return true;
	}

	static void delete(Path p) { try { Files.deleteIfExists(p); } catch (Exception e) { Moddeus.LOG.warn("delete {}: {}", p, e.toString()); } }

	public static ServerLevel get(String id) { return LiveRegistry.server == null ? null : LiveRegistry.server.getLevel(key(Identifier.parse(id))); }

	public static Holder<DimensionType> type(String id) {
		return LiveRegistry.server.registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE).get(ResourceKey.create(Registries.DIMENSION_TYPE, Identifier.parse(id))).orElseThrow();
	}

	public static String list() {
		MinecraftServer s = LiveRegistry.server;
		if (s == null) return "no server";
		StringBuilder b = new StringBuilder();
		for (ServerLevel l : s.getAllLevels()) b.append(l.dimension().identifier()).append(" players=").append(l.players().size()).append('\n');
		return b.toString().trim();
	}

	// ---- visit / return ---------------------------------------------------------------------------------------------

	/** Teleports to a safe spot near 0,0 of the dimension; remembers where the player came from for back(). */
	public static void go(ServerPlayer p, String id) {
		ServerLevel l = get(id);
		if (l == null) throw new IllegalArgumentException("no dimension " + id + " (" + list().replace('\n', ',') + ")");
		if (l == p.level()) return;
		if (!home().containsKey(p.getUUID()) || !isLive(p.level())) home().put(p.getUUID(), new Object[] { p.level().dimension(), p.position(), p.getYRot(), p.getXRot() });
		BlockPos spot = safeSpot(l);
		p.teleport(new TeleportTransition(l, Vec3.atBottomCenterOf(spot), Vec3.ZERO, p.getYRot(), p.getXRot(), TeleportTransition.DO_NOTHING));
	}

	/** Back to exactly where go() took the player from (position + look), else the world spawn. */
	public static void back(ServerPlayer p) {
		MinecraftServer s = p.level().getServer();
		Object[] h = home().remove(p.getUUID());
		ServerLevel to = h == null ? null : s.getLevel((ResourceKey<Level>) h[0]);
		if (to == null || to == p.level() && h == null) {
			ServerLevel w = s.findRespawnDimension();
			p.teleport(new TeleportTransition(w, Vec3.atBottomCenterOf(s.getRespawnData().pos()), Vec3.ZERO, p.getYRot(), p.getXRot(), TeleportTransition.DO_NOTHING));
			return;
		}
		p.teleport(new TeleportTransition(to, (Vec3) h[1], Vec3.ZERO, (float) h[2], (float) h[3], TeleportTransition.DO_NOTHING));
	}

	static boolean isLive(Level l) { return !l.dimension().identifier().getNamespace().equals("minecraft"); }

	/** Ground near 0,0: top block (no ceiling) or the lowest 2-high air pocket on solid floor (ceiling); else a glass pad at y 64. */
	static BlockPos safeSpot(ServerLevel l) {
		for (int r = 0; r <= 48; r += 8) for (int x = -r; x <= r; x += Math.max(8, 2 * r)) for (int z = -r; z <= r; z += Math.max(8, 2 * r)) {
			if (!l.dimensionType().hasCeiling()) {
				int y = l.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
				if (y > l.getMinY() && !l.getBlockState(new BlockPos(x, y - 1, z)).getFluidState().isSource()) return new BlockPos(x, y, z);
			} else {
				for (int y = l.getMinY() + 2, top = l.getMinY() + l.dimensionType().logicalHeight() - 2; y < top; y++) {
					BlockPos p = new BlockPos(x, y, z);
					if (l.getBlockState(p.below()).isSolid() && l.getBlockState(p).isAir() && l.getBlockState(p.above()).isAir()) return p;
				}
			}
		}
		BlockPos pad = new BlockPos(0, Math.max(l.getMinY() + 1, Math.min(64, l.getMaxY() - 3)), 0);
		for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) l.setBlockAndUpdate(pad.offset(x, -1, z), Blocks.GLASS.defaultBlockState());
		return pad;
	}

	// ---- datapack / mod-resource dimensions, live ------------------------------------------------------------------

	/** Loads data/<ns>/dimension/*.json that the server doesn't have yet; unloads ones whose file is gone (pack off). */
	public static void syncData(MinecraftServer s) {
		if (!enabled) return;
		var rm = s.getResourceManager();
		Set<Identifier> found = new HashSet<>();
		for (var e : rm.listResources("dimension", f -> f.getPath().endsWith(".json")).entrySet()) {
			Identifier f = e.getKey();
			Identifier id = Identifier.fromNamespaceAndPath(f.getNamespace(), f.getPath().substring("dimension/".length(), f.getPath().length() - 5));
			found.add(id);
			if (s.getLevel(key(id)) != null) continue;
			try (var r = e.getValue().openAsReader()) {
				JsonObject j = JsonParser.parseReader(r).getAsJsonObject();
				JsonElement t = j.get("type");
				if (t != null && t.isJsonPrimitive()) { // a dimension_type the world didn't load at start: register it live
					Identifier tid = Identifier.parse(t.getAsString());
					var reg = s.registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE);
					if (!reg.containsKey(tid)) {
						var res = rm.getResource(Identifier.fromNamespaceAndPath(tid.getNamespace(), "dimension_type/" + tid.getPath() + ".json"));
						if (res.isPresent()) try (var tr = res.get().openAsReader()) { registerType(s, tid, JsonParser.parseReader(tr).getAsJsonObject()); }
					}
				}
				create(id, j);
				fromData().add(id);
			} catch (Exception x) {
				Moddeus.LOG.warn("live dimension {} failed: {}", id, x.toString());
			}
		}
		for (ResourceKey<Level> k : new ArrayList<>(s.levelKeys())) { // pack switched off / file deleted
			Identifier id = k.identifier();
			boolean ours = fromData().contains(id) || s.registryAccess().lookupOrThrow(Registries.LEVEL_STEM).containsKey(id);
			if (ours && !id.getNamespace().equals("minecraft") && !found.contains(id)) remove(id, false);
		}
	}

	// ---- dimension types -------------------------------------------------------------------------------------------

	/** Registers a dimension_type JSON live, on the server and (singleplayer) the client's registry copy, so both ids match. */
	public static Holder<DimensionType> registerType(MinecraftServer s, Identifier id, JsonObject json) {
		Holder<DimensionType> h = putType(s.registryAccess(), id, json);
		try { // client copy (same JVM); dedicated servers have no client class
			Object mc = Class.forName("net.minecraft.client.Minecraft").getMethod("getInstance").invoke(null);
			Object conn = mc.getClass().getMethod("getConnection").invoke(mc);
			if (conn != null) putType((RegistryAccess) conn.getClass().getMethod("registryAccess").invoke(conn), id, json);
		} catch (ClassNotFoundException e) {
			// dedicated server: remote clients only know types from their join (see DIMENSIONS.md limits)
		} catch (Exception e) {
			Moddeus.LOG.warn("client dimension type {}: {}", id, e.toString());
		}
		return h;
	}

	// ponytail: the client copy is written from the server thread; fine for a registry the client only reads on respawn.
	static Holder<DimensionType> putType(RegistryAccess access, Identifier id, JsonObject json) {
		Registry<DimensionType> reg = access.lookupOrThrow(Registries.DIMENSION_TYPE);
		ResourceKey<DimensionType> k = ResourceKey.create(Registries.DIMENSION_TYPE, id);
		if (!reg.containsKey(k)) {
			DimensionType t = DimensionType.DIRECT_CODEC.parse(ops(access), json).getOrThrow(e -> new IllegalArgumentException("dimension_type " + id + ": " + e));
			LiveRegistry.register(reg, id, key -> t);
		}
		return reg.get(k).orElseThrow();
	}

	// ---- plumbing ----------------------------------------------------------------------------------------------------

	static RegistryOps<JsonElement> ops(RegistryAccess a) { return RegistryOps.create(JsonOps.INSTANCE, a); }

	static ResourceKey<Level> key(Identifier id) { return ResourceKey.create(Registries.DIMENSION, id); }

	static Map<UUID, Object[]> home() { if (home == null) home = new HashMap<>(); return home; }
	static Set<Identifier> fromData() { if (fromData == null) fromData = new HashSet<>(); return fromData; }
	static List<Object[]> pending() { if (pending == null) pending = new ArrayList<>(); return pending; }

	/** Copy-on-write swap of MinecraftServer.levels, so a level loop running right now (tickChildren) never sees a change. */
	@SuppressWarnings("unchecked")
	static void putLevel(MinecraftServer s, ResourceKey<Level> k, ServerLevel l) throws RuntimeException {
		try {
			Field f = field(MinecraftServer.class, "levels");
			Map<ResourceKey<Level>, ServerLevel> m = new LinkedHashMap<>((Map<ResourceKey<Level>, ServerLevel>) f.get(s));
			if (l == null) m.remove(k); else m.put(k, l);
			f.set(s, m);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Keeps the client's dimension list (command suggestions) in step; singleplayer only. */
	@SuppressWarnings("unchecked")
	static void clientLevelKey(ResourceKey<Level> k, boolean add) {
		try {
			Object mc = Class.forName("net.minecraft.client.Minecraft").getMethod("getInstance").invoke(null);
			Object conn = mc.getClass().getMethod("getConnection").invoke(mc);
			if (conn == null) return;
			Field f = conn.getClass().getDeclaredField("levels");
			f.setAccessible(true);
			Set<ResourceKey<Level>> set = new HashSet<>((Set<ResourceKey<Level>>) f.get(conn));
			if (add) set.add(k); else set.remove(k);
			f.set(conn, set);
		} catch (Throwable ignored) {
		}
	}

	static Field field(Class<?> c, String name) throws NoSuchFieldException {
		Field f = c.getDeclaredField(name);
		f.setAccessible(true);
		return f;
	}

	// ---- lifecycle ---------------------------------------------------------------------------------------------------

	@OnReload
	public static void hook() {
		enabled = true;
		if (System.getProperties().putIfAbsent("moddeus.dimensions", Boolean.TRUE) == null) {
			ServerLifecycleEvents.SERVER_STARTED.register(Dimensions::started);
			ServerLifecycleEvents.END_DATA_PACK_RELOAD.register(Dimensions::reloaded);
		}
	}

	static void started(MinecraftServer s) {
		if (LiveRegistry.server == null) LiveRegistry.server = s;
		List<Object[]> q = new ArrayList<>(pending());
		pending().clear();
		for (Object[] e : q) try { create((Identifier) e[0], (JsonObject) e[1]); } catch (Exception x) { Moddeus.LOG.warn("dimension {} failed: {}", e[0], x.toString()); }
	}

	static void reloaded(MinecraftServer s, CloseableResourceManager rm, boolean ok) { if (ok) syncData(s); }

	/** Off: players come home, live-made levels are saved and closed (their files stay). */
	@OnUnload
	public static void unload() {
		enabled = false;
		MinecraftServer s = LiveRegistry.server;
		if (s == null) return;
		for (ServerPlayer p : s.getPlayerList().getPlayers()) if (home().containsKey(p.getUUID())) back(p);
	}
}
