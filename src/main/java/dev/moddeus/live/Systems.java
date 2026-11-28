package dev.moddeus.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.moddeus.Moddeus;
import dev.moddeus.content.Gameplay;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

/**
 * Game systems for Moddeus mods, all data-driven (content JSON from tools/systems.mjs, loaded by ContentLoader) and
 * event-driven (no Block subclasses): "pad" (launch / elevator / teleport / conveyor / trap / cmd blocks),
 * "machine" (furnace-style GUI that processes items with its own recipes + fuel), "skill" (mcMMO-style skill XP from
 * vanilla stats, levels, attribute perks, level rewards), "economy" (scoreboard currency earned from stats; Java Systems.coins/addCoins),
 * "shop" (buy/sell GUI on a block or Systems.openShop(player, id)), "hud" (info panel with placeholders), "skyevent" (blood moon style
 * night/thunder events: tint, mob buffs, extra spawns, commands), "secret" (right-click a wall: the connected blocks
 * open, then close again), "jukebox_song" (live music disc songs). World state: <world>/moddeus/systems.json.
 * Guide: .claude/skills/mc-content/SYSTEMS.md
 */
public final class Systems {
	public static Map<String, JsonObject> SPECS;
	public static volatile boolean enabled = true;
	/** Client side bridge (SystemsClient): ("teleport", List<JsonObject>) | ("shop", id) | ("song", Identifier). */
	public static volatile java.util.function.BiConsumer<String, Object> client;
	static JsonObject data;
	static MinecraftServer dataServer;
	static boolean dirty;
	static Map<String, Machine> machines;
	static Map<String, Long> cool;
	static Map<UUID, Integer> jumps;
	static Map<UUID, Boolean> sneaks;
	static Map<String, Integer> seen; // uuid|spec|stat -> last stat value
	static Map<String, List<Object[]>> sources; // spec id -> [Stat[], amount]
	static Map<String, Integer> levels; // uuid|skill -> last known level
	/** uuid -> {skill id, System.nanoTime(), xp gained} for the HUD bar. */
	public static Map<UUID, Object[]> skillPing;
	/** Active sky event id (null = none) and when it started (nanoTime). */
	public static volatile String sky;
	public static volatile long skyStart;
	static long lastClock = -1;
	static boolean lastThunder;
	static final Set<String> TYPES = Set.of("pad", "machine", "skill", "economy", "shop", "hud", "skyevent", "secret", "jukebox_song");

	static void init() {
		if (SPECS == null) SPECS = new ConcurrentHashMap<>();
		if (machines == null) machines = new ConcurrentHashMap<>();
		if (cool == null) cool = new HashMap<>();
		if (jumps == null) jumps = new HashMap<>();
		if (sneaks == null) sneaks = new HashMap<>();
		if (seen == null) seen = new HashMap<>();
		if (sources == null) sources = new ConcurrentHashMap<>();
		if (levels == null) levels = new HashMap<>();
		if (skillPing == null) skillPing = new ConcurrentHashMap<>();
	}

	// ---- loading (ContentLoader) -------------------------------------------------------------------------------
	public static void begin() { init(); SPECS.clear(); sources.clear(); }

	public static boolean load(String id, JsonObject o) {
		init();
		if (!o.has("type") || !TYPES.contains(o.get("type").getAsString())) return false;
		String key = o.has("id") ? o.get("id").getAsString() : id;
		SPECS.put(key, o);
		MinecraftServer s = LiveRegistry.server; // songs before the disc item parses its jukebox_playable component
		if (s != null && "jukebox_song".equals(o.get("type").getAsString())) song(s, key, o);
		return true;
	}

	public static void end() {
		init();
		MinecraftServer s = LiveRegistry.server;
		if (s != null) s.execute(() -> { songs(s); objectives(s); });
	}

	public static List<Map.Entry<String, JsonObject>> of(String type) {
		init();
		List<Map.Entry<String, JsonObject>> r = new ArrayList<>();
		for (var e : SPECS.entrySet()) if (type.equals(str(e.getValue(), "type", ""))) r.add(e);
		return r;
	}

	// ---- hooks -------------------------------------------------------------------------------------------------
	@OnReload
	public static void hook() {
		init();
		enabled = true;
		if (System.getProperties().putIfAbsent("moddeus.systems", Boolean.TRUE) == null) {
			net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(Systems::tick);
			net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register(Systems::use);
			net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(Systems::started);
			net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(Systems::stopping);
		}
		MinecraftServer s = LiveRegistry.server;
		if (s != null) s.execute(() -> {
			songs(s);
			objectives(s);
		});
	}

	@OnUnload
	public static void unhook() {
		enabled = false;
		init();
		if (dataServer != null) save(dataServer);
		sky = null;
		skillPing.clear();
	}

	static void started(MinecraftServer s) { init(); songs(s); objectives(s); }

	static void stopping(MinecraftServer s) { if (dataServer == s) save(s); dataServer = null; data = null; machines.clear(); }

	static void tick(MinecraftServer s) {
		if (!enabled) return;
		init();
		if (SPECS.isEmpty()) return;
		if (dataServer != s) loadData(s);
		long t = s.overworld().getGameTime();
		try { pads(s, t); } catch (Throwable e) { Moddeus.LOG.warn("pads tick", e); }
		try { machines(s); } catch (Throwable e) { Moddeus.LOG.warn("machines tick", e); }
		try { if (t % 5 == 0) stats(s); } catch (Throwable e) { Moddeus.LOG.warn("skills tick", e); }
		try { if (t % 10 == 0) skyTick(s, t); } catch (Throwable e) { Moddeus.LOG.warn("sky tick", e); }
		try { secrets(s, t); } catch (Throwable e) { Moddeus.LOG.warn("secrets tick", e); }
		if (dirty && t % 600 == 0) save(s);
	}

	// ---- world data ----------------------------------------------------------------------------------------------
	static Path dataFile(MinecraftServer s) { return s.getWorldPath(LevelResource.ROOT).resolve("moddeus/systems.json"); }

	static void loadData(MinecraftServer s) {
		dataServer = s;
		machines.clear();
		data = new JsonObject();
		try {
			Path f = dataFile(s);
			if (Files.exists(f)) data = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
		} catch (Exception e) { Moddeus.LOG.warn("systems.json", e); }
		var ops = RegistryOps.create(JsonOps.INSTANCE, s.registryAccess());
		for (JsonElement el : arr(data, "machines")) {
			try {
				JsonObject o = el.getAsJsonObject();
				Machine m = new Machine();
				m.spec = o.get("spec").getAsString(); m.dim = o.get("dim").getAsString();
				m.pos = new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
				m.burn = o.get("burn").getAsInt(); m.burnMax = o.get("burnMax").getAsInt(); m.progress = o.get("progress").getAsInt();
				JsonArray items = o.getAsJsonArray("items");
				for (int i = 0; i < 3 && i < items.size(); i++) m.inv.setItem(i, ItemStack.OPTIONAL_CODEC.parse(ops, items.get(i)).result().orElse(ItemStack.EMPTY));
				machines.put(key(m.dim, m.pos), m);
			} catch (Exception e) { Moddeus.LOG.warn("machine entry", e); }
		}
	}

	static void save(MinecraftServer s) {
		if (data == null) return;
		var ops = RegistryOps.create(JsonOps.INSTANCE, s.registryAccess());
		JsonArray ms = new JsonArray();
		for (Machine m : machines.values()) {
			JsonObject o = new JsonObject();
			o.addProperty("spec", m.spec); o.addProperty("dim", m.dim);
			o.addProperty("x", m.pos.getX()); o.addProperty("y", m.pos.getY()); o.addProperty("z", m.pos.getZ());
			o.addProperty("burn", m.burn); o.addProperty("burnMax", m.burnMax); o.addProperty("progress", m.progress);
			JsonArray items = new JsonArray();
			for (int i = 0; i < 3; i++) items.add(ItemStack.OPTIONAL_CODEC.encodeStart(ops, m.inv.getItem(i)).result().orElse(new JsonObject()));
			o.add("items", items);
			ms.add(o);
		}
		data.add("machines", ms);
		try {
			Path f = dataFile(s);
			Files.createDirectories(f.getParent());
			Path tmp = f.resolveSibling("systems.json.tmp");
			Files.writeString(tmp, data.toString());
			Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			dirty = false;
		} catch (Exception e) { Moddeus.LOG.warn("systems.json save", e); }
	}

	// ---- pads ----------------------------------------------------------------------------------------------------
	static void pads(MinecraftServer s, long t) {
		var pads = of("pad");
		if (pads.isEmpty()) return;
		Set<Entity> done = new HashSet<>();
		boolean mobs = false;
		for (var p : pads) mobs |= bool(p.getValue(), "mobs");
		for (ServerPlayer p : s.getPlayerList().getPlayers()) {
			if (p.isSpectator()) continue;
			boolean sneak = p.isShiftKeyDown(), wasSneak = Boolean.TRUE.equals(sneaks.put(p.getUUID(), sneak));
			int j = p.getStats().getValue(Stats.CUSTOM.get(Stats.JUMP));
			Integer lastJ = jumps.put(p.getUUID(), j);
			boolean jumped = lastJ != null && j > lastJ, sneaked = sneak && !wasSneak;
			pad(p, pads, t, jumped, sneaked);
			done.add(p);
			if (!mobs) continue;
			for (Entity e : p.level().getEntities((Entity) null, p.getBoundingBox().inflate(24), x -> !(x instanceof Player)))
				if (done.add(e)) pad(e, pads, t, false, false);
		}
	}

	static void pad(Entity e, List<Map.Entry<String, JsonObject>> pads, long t, boolean jumped, boolean sneaked) {
		BlockPos at = e.blockPosition(), on = e.getOnPos();
		BlockState st = e.level().getBlockState(at);
		for (int k = 0; k < 2; k++) {
			BlockPos pos = k == 0 ? at : on;
			BlockState bs = k == 0 ? st : e.level().getBlockState(on);
			if (bs.isAir()) continue;
			for (var en : pads) {
				JsonObject o = en.getValue();
				if (!is(bs, str(o, "block", LiveRegistry.id(en.getKey()).toString()))) continue;
				if (!(e instanceof Player) && !bool(o, "mobs")) continue;
				if (k == 1 && !e.onGround() && e.getY() - on.getY() - 1 > 0.3) continue; // standing on it (creative flight hovers a bit)
				act(e, en.getKey(), o, pos, bs, t, jumped, sneaked);
				return;
			}
		}
	}

	static boolean cooled(Entity e, String id, long t, int ticks) {
		String k = e.getId() + "|" + id;
		Long until = cool.get(k);
		if (until != null && until > t) return false;
		cool.put(k, t + ticks);
		if (cool.size() > 4096) cool.entrySet().removeIf(x -> x.getValue() < t);
		return true;
	}

	static void act(Entity e, String id, JsonObject o, BlockPos pos, BlockState bs, long t, boolean jumped, boolean sneaked) {
		ServerLevel lvl = (ServerLevel) e.level();
		String action = str(o, "action", "cmd");
		switch (action) {
			case "launch" -> {
				if (!cooled(e, id, t, num(o, "cooldown", 10))) return;
				double up = dbl(o, "power", 1.4), fwd = dbl(o, "forward", 0);
				Vec3 dir = e instanceof Player ? e.getLookAngle().multiply(1, 0, 1).normalize() : facing(bs, e.getDeltaMovement().multiply(1, 0, 1).normalize());
				e.setDeltaMovement(dir.x * fwd, up, dir.z * fwd);
				e.needsSync = true;
				if (e instanceof ServerPlayer sp && !bool(o, "fall_damage")) Quick.safeLanding(sp);
				sound(lvl, e.position(), str(o, "sound", "minecraft:entity.breeze.wind_burst"), 1.2f);
				ring(lvl, ParticleTypes.CLOUD, Vec3.atCenterOf(pos).add(0, 0.6, 0), 0.6, 10);
			}
			case "elevator" -> {
				if (!(e instanceof ServerPlayer p) || !(jumped || sneaked) || !cooled(e, id, t, 6)) return;
				int range = num(o, "range", 32), dy = jumped ? 1 : -1;
				for (int i = 2; i <= range; i++) {
					BlockPos q = pos.above(dy * i);
					if (!is(lvl.getBlockState(q), str(o, "block", LiveRegistry.id(id).toString()))) continue;
					if (!lvl.getBlockState(q.above()).getCollisionShape(lvl, q.above()).isEmpty() || !lvl.getBlockState(q.above(2)).getCollisionShape(lvl, q.above(2)).isEmpty()) continue;
					ring(lvl, ParticleTypes.REVERSE_PORTAL, p.position().add(0, 0.2, 0), 0.5, 8);
					p.teleportTo(p.getX(), q.getY() + 1, p.getZ());
					p.setDeltaMovement(Vec3.ZERO); p.needsSync = true;
					sound(lvl, p.position(), str(o, "sound", "minecraft:block.note_block.chime"), dy > 0 ? 1.6f : 1.0f);
					ring(lvl, ParticleTypes.END_ROD, p.position().add(0, 0.2, 0), 0.5, 8);
					return;
				}
			}
			case "teleport" -> {
				if (!(e instanceof ServerPlayer p)) return;
				discover(p, id, o, pos);
				if (!sneaked || !cooled(e, id, t, 20)) return;
				List<JsonObject> list = waypoints(p.level().getServer(), id, p);
				if (list.size() < 2) { p.sendSystemMessage(Component.literal("§dNo other " + str(o, "name", "teleporter") + " discovered yet: step on another one first."), true); return; }
				var c = client;
				if (c != null && !bool(o, "cycle")) c.accept("teleport", list);
				else { int me = 0; for (int i = 0; i < list.size(); i++) if (list.get(i).get("here").getAsBoolean()) me = i; teleport(p, list.get((me + 1) % list.size())); }
			}
			case "conveyor" -> {
				Vec3 d = facing(bs, null);
				if (d == null) { Direction dir = Direction.byName(str(o, "dir", "north")); d = Vec3.atLowerCornerOf(dir == null ? Direction.NORTH.getUnitVec3i() : dir.getUnitVec3i()); }
				double sp = dbl(o, "speed", 0.15);
				Vec3 v = e.getDeltaMovement(), c = Vec3.atCenterOf(pos);
				double side = d.x != 0 ? (c.z - e.getZ()) : (c.x - e.getX());
				double nx = v.x + (d.x * sp - v.x) * 0.5 + (d.x == 0 ? side * 0.1 : 0), nz = v.z + (d.z * sp - v.z) * 0.5 + (d.z == 0 ? side * 0.1 : 0);
				e.setDeltaMovement(nx, v.y, nz);
				e.needsSync = true;			}
			case "trap" -> {
				if (!(e instanceof LivingEntity le) || !cooled(e, id, t, num(o, "cooldown", 20))) return;
				if (o.has("damage")) le.hurtServer(lvl, lvl.damageSources().cactus(), (float) dbl(o, "damage", 2));
				if (o.has("fire")) le.igniteForSeconds((float) dbl(o, "fire", 3));
				sound(lvl, e.position(), str(o, "sound", "minecraft:block.pointed_dripstone.land"), 0.8f);
			}
			default -> { if (!cooled(e, id, t, num(o, "cooldown", 20))) return; }
		}
		if (o.has("effects") && e instanceof LivingEntity le && cooled(e, id + "#fx", t, 40)) effects(le, o.get("effects").getAsString(), 1);
		if (o.has("cmd") && !"cmd".equals(action) ? cooled(e, id + "#cmd", t, num(o, "cooldown", 20)) : o.has("cmd")) Gameplay.run(e, o.get("cmd"));
	}

	/** Conveyor/launch direction from a facing block (GlazedTerracotta-style: FACING points at the placer, so move away). */
	static Vec3 facing(BlockState bs, Vec3 fallback) {
		if (bs.hasProperty(HorizontalDirectionalBlock.FACING)) return Vec3.atLowerCornerOf(bs.getValue(HorizontalDirectionalBlock.FACING).getOpposite().getUnitVec3i());
		return fallback;
	}

	// ---- teleport pads (waystones) -----------------------------------------------------------------------------
	static void discover(ServerPlayer p, String id, JsonObject o, BlockPos pos) {
		if (data == null) return;
		JsonArray list = arrOrNew(data, "pads");
		String dim = p.level().dimension().identifier().toString();
		for (JsonElement el : list) { JsonObject w = el.getAsJsonObject(); if (w.get("x").getAsInt() == pos.getX() && w.get("y").getAsInt() == pos.getY() && w.get("z").getAsInt() == pos.getZ() && w.get("dim").getAsString().equals(dim)) return; }
		String biome = p.level().getBiome(pos).unwrapKey().map(k -> k.identifier().getPath()).orElse("somewhere");
		JsonObject w = new JsonObject();
		w.addProperty("spec", id); w.addProperty("dim", dim); w.addProperty("x", pos.getX()); w.addProperty("y", pos.getY()); w.addProperty("z", pos.getZ());
		w.addProperty("name", title(biome) + " " + str(o, "name", "Waystone"));
		list.add(w);
		dirty = true;
		p.sendSystemMessage(Component.literal("§bDiscovered: " + w.get("name").getAsString() + " §7(sneak on it to travel)"), true);
		sound((ServerLevel) p.level(), p.position(), "minecraft:block.beacon.activate", 1.6f);
	}

	/** Discovered pads of this spec (dead ones pruned), with "here" + "dist" filled in for p. */
	public static List<JsonObject> waypoints(MinecraftServer s, String id, ServerPlayer p) {
		List<JsonObject> r = new ArrayList<>();
		if (data == null) return r;
		JsonArray list = arrOrNew(data, "pads"), keep = new JsonArray();
		for (JsonElement el : list) {
			JsonObject w = el.getAsJsonObject();
			ServerLevel l = s.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(w.get("dim").getAsString())));
			BlockPos q = new BlockPos(w.get("x").getAsInt(), w.get("y").getAsInt(), w.get("z").getAsInt());
			JsonObject spec = SPECS.get(w.get("spec").getAsString());
			if (l == null || spec == null || (l.isLoaded(q) && !is(l.getBlockState(q), str(spec, "block", LiveRegistry.id(w.get("spec").getAsString()).toString())))) { dirty = true; continue; }
			keep.add(w);
			if (!w.get("spec").getAsString().equals(id)) continue;
			JsonObject c = w.deepCopy();
			boolean same = l == p.level();
			c.addProperty("here", same && p.blockPosition().distManhattan(q) <= 2);
			c.addProperty("dist", same ? (int) Math.sqrt(p.blockPosition().distSqr(q)) : -1);
			r.add(c);
		}
		data.add("pads", keep);
		return r;
	}

	public static void teleport(ServerPlayer p, JsonObject w) {
		ServerLevel from = (ServerLevel) p.level();
		ring(from, ParticleTypes.REVERSE_PORTAL, p.position().add(0, 0.1, 0), 0.7, 14);
		sound(from, p.position(), "minecraft:entity.enderman.teleport", 0.8f);
		Gameplay.run(p, new com.google.gson.JsonPrimitive("execute in " + w.get("dim").getAsString() + " run tp @s " + (w.get("x").getAsInt() + 0.5) + " " + (w.get("y").getAsInt() + 1) + " " + (w.get("z").getAsInt() + 0.5)));
		ServerLevel to = (ServerLevel) p.level();
		ring(to, ParticleTypes.END_ROD, p.position().add(0, 0.1, 0), 0.7, 14);
		sound(to, p.position(), "minecraft:block.respawn_anchor.set_spawn", 1.4f);
		cool.put(p.getId() + "|" + w.get("spec").getAsString(), to.getGameTime() + 40);
	}

	// ---- machines --------------------------------------------------------------------------------------------------
	public static final class Machine {
		public String spec, dim;
		public BlockPos pos;
		public final SimpleContainer inv = new SimpleContainer(3);
		public int burn, burnMax, progress, total = 1;
	}

	public static final class MachineData implements ContainerData {
		final Machine m;
		MachineData(Machine m) { this.m = m; }
		@Override public int get(int i) { return switch (i) { case 0 -> m.burn; case 1 -> Math.max(1, m.burnMax); case 2 -> m.progress; default -> Math.max(1, m.total); }; }
		@Override public void set(int i, int v) {}
		@Override public int getCount() { return 4; }
	}

	static void machines(MinecraftServer s) {
		if (machines.isEmpty()) return;
		for (var it = machines.entrySet().iterator(); it.hasNext(); ) {
			Machine m = it.next().getValue();
			JsonObject spec = SPECS.get(m.spec);
			ServerLevel l = s.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(m.dim)));
			if (l == null || !l.isLoaded(m.pos)) continue;
			if (spec == null || !is(l.getBlockState(m.pos), str(spec, "block", LiveRegistry.id(m.spec).toString()))) { // broken: spill
				Containers.dropContents(l, m.pos, m.inv);
				it.remove(); dirty = true; continue;
			}
			boolean fuel = bool(spec, "fuel");
			JsonObject r = recipe(spec, m.inv.getItem(0));
			ItemStack out = r == null ? ItemStack.EMPTY : stack(str(r, "out", "minecraft:air"), num(r, "count", 1));
			boolean fits = r != null && !out.isEmpty() && (m.inv.getItem(2).isEmpty() || (ItemStack.isSameItemSameComponents(m.inv.getItem(2), out) && m.inv.getItem(2).getCount() + out.getCount() <= m.inv.getItem(2).getMaxStackSize()));
			if (fuel && m.burn > 0) m.burn--;
			if (fits) {
				m.total = num(r, "ticks", 100);
				if (fuel && m.burn <= 0) {
					int f = fuelValue(spec, m.inv.getItem(1));
					if (f > 0) {
						m.burn = m.burnMax = f;
						ItemStack fs = m.inv.getItem(1);
						if (fs.is(net.minecraft.world.item.Items.LAVA_BUCKET)) m.inv.setItem(1, new ItemStack(net.minecraft.world.item.Items.BUCKET)); else fs.shrink(1);
					}
				}
				if (!fuel || m.burn > 0) {
					if (!fuel) { m.burn = m.burnMax = 1; }
					if (++m.progress >= m.total) {
						m.progress = 0;
						m.inv.getItem(0).shrink(num(r, "in_count", 1));
						if (m.inv.getItem(2).isEmpty()) m.inv.setItem(2, out); else m.inv.getItem(2).grow(out.getCount());
						sound(l, Vec3.atCenterOf(m.pos), str(spec, "sound", "minecraft:block.smithing_table.use"), 1.2f);
						if (r.has("xp")) net.minecraft.world.entity.ExperienceOrb.award(l, Vec3.atCenterOf(m.pos).add(0, 0.6, 0), num(r, "xp", 0));
					}
					if (l.getGameTime() % 12 == 0) l.sendParticles(fuel ? ParticleTypes.SMOKE : ParticleTypes.ELECTRIC_SPARK, m.pos.getX() + 0.5, m.pos.getY() + 1.05, m.pos.getZ() + 0.5, 1, 0.2, 0, 0.2, 0.01);
				} else m.progress = Math.max(0, m.progress - 2);
				dirty = true;
			} else {
				if (m.progress != 0) dirty = true;
				m.progress = 0;
				if (!fuel) m.burn = 0;
			}
		}
	}

	static JsonObject recipe(JsonObject spec, ItemStack in) {
		if (in.isEmpty()) return null;
		for (JsonElement el : arr(spec, "recipes")) {
			JsonObject r = el.getAsJsonObject();
			if (itemIs(in, str(r, "in", "")) && in.getCount() >= num(r, "in_count", 1)) return r;
		}
		return null;
	}

	static int fuelValue(JsonObject spec, ItemStack s) {
		if (s.isEmpty()) return 0;
		if (spec.has("fuels")) for (var e : spec.getAsJsonObject("fuels").entrySet()) if (itemIs(s, e.getKey())) return e.getValue().getAsInt();
		if (itemIs(s, "minecraft:coal_block")) return 16000;
		if (itemIs(s, "minecraft:lava_bucket")) return 20000;
		if (itemIs(s, "minecraft:blaze_rod")) return 2400;
		if (itemIs(s, "minecraft:coal") || itemIs(s, "minecraft:charcoal")) return 1600;
		if (itemIs(s, "#minecraft:logs") || itemIs(s, "#minecraft:planks")) return 300;
		return s.has(net.minecraft.core.component.DataComponents.COOKING_FUEL) ? 200 : 0;
	}

	public static Machine machineAt(Level l, BlockPos pos, String spec) {
		String k = key(l.dimension().identifier().toString(), pos);
		Machine m = machines.get(k);
		if (m == null || !m.spec.equals(spec)) {
			m = new Machine(); m.spec = spec; m.dim = l.dimension().identifier().toString(); m.pos = pos.immutable();
			machines.put(k, m); dirty = true;
		}
		return m;
	}

	// ---- right-click: machines, shops, secret walls ---------------------------------------------------------------
	static InteractionResult use(Player p, Level l, InteractionHand hand, BlockHitResult hit) {
		if (!enabled || SPECS == null || SPECS.isEmpty() || p.isSpectator()) return InteractionResult.PASS;
		BlockPos pos = hit.getBlockPos();
		BlockState bs = l.getBlockState(pos);
		ItemStack held = p.getItemInHand(hand);
		for (var en : SPECS.entrySet()) {
			JsonObject o = en.getValue();
			String type = str(o, "type", "");
			if (!type.equals("machine") && !type.equals("shop") && !type.equals("secret")) continue;
			if (!is(bs, str(o, "block", LiveRegistry.id(en.getKey()).toString()))) continue;
			if (p.isSecondaryUseActive() && !held.isEmpty()) return InteractionResult.PASS; // sneak-place against it
			if (type.equals("secret") && o.has("key") && !itemIs(held, o.get("key").getAsString())) {
				if (!l.isClientSide() && hand == InteractionHand.MAIN_HAND) ((ServerPlayer) p).sendOverlayMessage(Component.literal("§7It won't budge. Something opens it..."));
				return InteractionResult.PASS;
			}
			if (l.isClientSide() || hand != InteractionHand.MAIN_HAND) return InteractionResult.SUCCESS;
			ServerPlayer sp = (ServerPlayer) p;
			switch (type) {
				case "machine" -> {
					if (dataServer == null) loadData(sp.level().getServer());
					Machine m = machineAt(l, pos, en.getKey());
					sp.openMenu(new SimpleMenuProvider((cid, inv, pl) -> new FurnaceMenu(cid, inv, m.inv, new MachineData(m)), Component.literal(str(o, "name", title(en.getKey())))));
				}
				case "shop" -> openShop(sp, en.getKey());
				case "secret" -> openSecret((ServerLevel) l, pos, bs, en.getKey(), o);
			}
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.PASS;
	}

	// ---- secret walls / hidden doors ----------------------------------------------------------------------------
	public static void openSecret(ServerLevel l, BlockPos start, BlockState bs, String id, JsonObject o) {
		int max = num(o, "max", 48);
		List<BlockPos> group = new ArrayList<>();
		Set<BlockPos> seenP = new HashSet<>(Set.of(start));
		java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>(List.of(start));
		while (!q.isEmpty() && group.size() < max) {
			BlockPos c = q.poll();
			group.add(c);
			for (Direction d : Direction.values()) { BlockPos n = c.relative(d); if (seenP.add(n) && n.distManhattan(start) <= 12 && l.getBlockState(n).getBlock() == bs.getBlock()) q.add(n); }
		}
		var ops = RegistryOps.create(JsonOps.INSTANCE, l.registryAccess());
		JsonObject open = new JsonObject();
		open.addProperty("dim", l.dimension().identifier().toString());
		open.addProperty("until", l.getGameTime() + num(o, "ticks", 100));
		JsonArray blocks = new JsonArray();
		for (BlockPos c : group) {
			JsonObject b = new JsonObject();
			b.addProperty("x", c.getX()); b.addProperty("y", c.getY()); b.addProperty("z", c.getZ());
			b.add("state", BlockState.CODEC.encodeStart(ops, l.getBlockState(c)).result().orElseThrow());
			blocks.add(b);
		}
		open.add("blocks", blocks);
		if (data == null) loadData(l.getServer());
		arrOrNew(data, "secrets").add(open);
		dirty = true;
		save(l.getServer()); // never lose a wall if the game closes while it is open
		for (int i = 0; i < group.size(); i++) {
			BlockPos c = group.get(i);
			l.setBlock(c, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
			if (i % 3 == 0) l.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, bs), c.getX() + 0.5, c.getY() + 0.5, c.getZ() + 0.5, 3, 0.3, 0.3, 0.3, 0.05);
		}
		sound(l, Vec3.atCenterOf(start), str(o, "sound", "minecraft:block.piston.contract"), 0.6f);
		sound(l, Vec3.atCenterOf(start), "minecraft:block.grindstone.use", 0.5f);
	}

	static void secrets(MinecraftServer s, long t) {
		if (data == null || !data.has("secrets")) return;
		JsonArray list = data.getAsJsonArray("secrets");
		if (list.isEmpty()) return;
		JsonArray keep = new JsonArray();
		for (JsonElement el : list) {
			JsonObject o = el.getAsJsonObject();
			ServerLevel l = s.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(o.get("dim").getAsString())));
			if (l == null) continue;
			if (o.get("until").getAsLong() > l.getGameTime()) { keep.add(o); continue; }
			var ops = RegistryOps.create(JsonOps.INSTANCE, l.registryAccess());
			boolean blocked = false;
			for (JsonElement b : o.getAsJsonArray("blocks")) {
				JsonObject bo = b.getAsJsonObject();
				BlockPos c = new BlockPos(bo.get("x").getAsInt(), bo.get("y").getAsInt(), bo.get("z").getAsInt());
				if (!l.getEntities((Entity) null, new net.minecraft.world.phys.AABB(c), x -> x instanceof LivingEntity).isEmpty()) blocked = true;
			}
			if (blocked) { o.addProperty("until", l.getGameTime() + 20); keep.add(o); continue; }
			BlockPos first = null;
			for (JsonElement b : o.getAsJsonArray("blocks")) {
				JsonObject bo = b.getAsJsonObject();
				BlockPos c = new BlockPos(bo.get("x").getAsInt(), bo.get("y").getAsInt(), bo.get("z").getAsInt());
				if (first == null) first = c;
				if (l.getBlockState(c).canBeReplaced()) l.setBlock(c, BlockState.CODEC.parse(ops, bo.get("state")).result().orElse(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()), 2);
			}
			if (first != null) sound(l, Vec3.atCenterOf(first), "minecraft:block.piston.extend", 0.6f);
			dirty = true;
		}
		if (keep.size() != list.size()) data.add("secrets", keep);
	}

	// ---- skills + economy (from vanilla stats: works for any action the game counts) ----------------------------
	static void stats(MinecraftServer s) {
		var skills = of("skill"); var econ = of("economy");
		if (skills.isEmpty() && econ.isEmpty()) return;
		for (ServerPlayer p : s.getPlayerList().getPlayers()) {
			for (var en : skills) {
				int gained = gain(p, en.getKey(), en.getValue());
				String obj = "sk_" + en.getKey();
				Objective xo = objective(s, obj, str(en.getValue(), "name", title(en.getKey())) + " XP");
				int xp = gained > 0 ? s.getScoreboard().getOrCreatePlayerScore(p, xo).add(gained) : score(s, p, obj);
				level(p, en.getKey(), en.getValue(), xp, gained);
			}
			for (var en : econ) {
				String obj = str(en.getValue(), "objective", "coins");
				Objective co = objective(s, obj, str(en.getValue(), "name", "Coins"));
				if (s.getScoreboard().getPlayerScoreInfo(p, co) == null) s.getScoreboard().getOrCreatePlayerScore(p, co).set(num(en.getValue(), "start", 0));
				int gained = gain(p, en.getKey(), en.getValue());
				if (gained > 0) {
					int now = s.getScoreboard().getOrCreatePlayerScore(p, co).add(gained);
					p.sendSystemMessage(Component.literal("§6+" + gained + " " + str(en.getValue(), "symbol", str(en.getValue(), "name", "Coins")) + " §7(" + now + ")"), true);
				}
			}
		}
	}

	/** Sum of (stat delta x amount) since the last check; first sight only records. */
	static int gain(ServerPlayer p, String id, JsonObject o) {
		List<Object[]> src = sources.computeIfAbsent(id, k -> parseSources(o));
		int total = 0;
		for (int i = 0; i < src.size(); i++) {
			Object[] e = src.get(i);
			int v = 0;
			for (Stat<?> st : (List<Stat<?>>) e[0]) v += p.getStats().getValue(st);
			String k = p.getUUID() + "|" + id + "|" + i;
			Integer last = seen.put(k, v);
			if (last != null && v > last) total += (int) Math.round((v - last) * (double) e[1]);
		}
		return total;
	}

	static List<Object[]> parseSources(JsonObject o) {
		List<Object[]> r = new ArrayList<>();
		if (!o.has("earn")) return r;
		for (var e : o.getAsJsonObject("earn").entrySet()) {
			try { r.add(new Object[] { stats(e.getKey()), e.getValue().getAsDouble() }); }
			catch (Exception x) { Moddeus.LOG.warn("Systems: bad stat source {}: {}", e.getKey(), x.toString()); }
		}
		return r;
	}

	/** "mined:stone" | "mined:#minecraft:logs" | "killed:zombie" | "crafted:bread" | "used:apple" | "picked:x" | "custom:fish_caught" | fish | breed | trade | walk. */
	static List<Stat<?>> stats(String spec) {
		String alias = switch (spec) { case "fish" -> "custom:fish_caught"; case "breed" -> "custom:animals_bred"; case "trade" -> "custom:traded_with_villager"; case "walk" -> "custom:walk_one_cm"; case "jump" -> "custom:jump"; case "enchant" -> "custom:enchant_item"; default -> spec; };
		int i = alias.indexOf(':');
		String kind = alias.substring(0, i), what = alias.substring(i + 1);
		List<Stat<?>> r = new ArrayList<>();
		switch (kind) {
			case "mined", "break" -> { for (Block b : list(BuiltInRegistries.BLOCK, what)) r.add(Stats.BLOCK_MINED.get(b)); }
			case "killed", "kill" -> { for (var t : list(BuiltInRegistries.ENTITY_TYPE, what)) r.add(Stats.ENTITY_KILLED.get(t)); }
			case "crafted", "craft" -> { for (var t : list(BuiltInRegistries.ITEM, what)) r.add(Stats.ITEM_CRAFTED.get(t)); }
			case "used", "eat", "use" -> { for (var t : list(BuiltInRegistries.ITEM, what)) r.add(Stats.ITEM_USED.get(t)); }
			case "picked" -> { for (var t : list(BuiltInRegistries.ITEM, what)) r.add(Stats.ITEM_PICKED_UP.get(t)); }
			case "custom" -> r.add(Stats.CUSTOM.get(Identifier.parse(what.contains(":") ? what : "minecraft:" + what)));
			default -> throw new IllegalArgumentException("unknown stat kind " + kind);
		}
		if (r.isEmpty()) throw new IllegalArgumentException("nothing matches " + what);
		return r;
	}

	static <T> List<T> list(net.minecraft.core.Registry<T> reg, String what) {
		List<T> r = new ArrayList<>();
		if (what.startsWith("#")) { for (var h : reg.getTagOrEmpty(TagKey.create(reg.key(), Identifier.parse(what.substring(1))))) r.add(h.value()); }
		else reg.getOptional(Identifier.parse(what.contains(":") ? what : "minecraft:" + what)).ifPresent(r::add);
		return r;
	}

	/** Level from total xp: level n -> n+1 costs base * growth^n. */
	public static int[] levelOf(JsonObject o, int xp) {
		double base = dbl(o, "base", 50), growth = dbl(o, "growth", 1.15);
		int max = num(o, "max", 50), lv = 0;
		double need = base;
		while (xp >= need && lv < max) { xp -= (int) need; lv++; need = base * Math.pow(growth, lv); }
		return new int[] { lv, xp, (int) need };
	}

	static void level(ServerPlayer p, String id, JsonObject o, int xp, int gained) {
		int lv = levelOf(o, xp)[0];
		String k = p.getUUID() + "|" + id;
		Integer last = levels.put(k, lv);
		if (gained > 0) skillPing.put(p.getUUID(), new Object[] { id, System.nanoTime(), gained });
		if (last != null && last == lv) return;
		MinecraftServer s = p.level().getServer();
		s.getScoreboard().getOrCreatePlayerScore(p, objective(s, "sk_" + id + "_lv", str(o, "name", title(id)) + " Level")).set(lv);
		if (o.has("attributes")) for (var e : o.getAsJsonObject("attributes").entrySet()) {
			var attr = BuiltInRegistries.ATTRIBUTE.get(Identifier.parse(e.getKey().contains(":") ? e.getKey() : "minecraft:" + e.getKey()));
			if (attr.isEmpty()) continue;
			AttributeInstance ai = p.getAttribute(attr.get());
			if (ai == null) continue;
			Identifier mid = Identifier.fromNamespaceAndPath("moddeus", "skill_" + id);
			ai.removeModifier(mid);
			if (lv > 0) ai.addOrUpdateTransientModifier(new AttributeModifier(mid, e.getValue().getAsDouble() * lv, AttributeModifier.Operation.ADD_VALUE));
		}
		if (last == null || lv <= last) return; // first sight: perks only re-applied, no fanfare
		String name = str(o, "name", title(id));
		Gameplay.run(p, new com.google.gson.JsonPrimitive("title @s actionbar {\"text\":\"" + name + " level " + lv + "!\",\"color\":\"" + str(o, "color", "gold") + "\",\"bold\":true}"));
		sound((ServerLevel) p.level(), p.position(), "minecraft:entity.player.levelup", 1.3f);
		ring((ServerLevel) p.level(), ParticleTypes.TOTEM_OF_UNDYING, p.position().add(0, 1, 0), 0.8, 12);
		if (o.has("perks")) for (var e : o.getAsJsonObject("perks").entrySet()) {
			int at = Integer.parseInt(e.getKey());
			if (at > last && at <= lv) Gameplay.run(p, e.getValue());
		}
	}

	// ---- economy API (Java mods + datapacks via scoreboard) -------------------------------------------------------
	static String currency() { var e = of("economy"); return e.isEmpty() ? "coins" : str(e.get(0).getValue(), "objective", "coins"); }
	public static int coins(ServerPlayer p) { return score(p.level().getServer(), p, currency()); }
	public static boolean addCoins(ServerPlayer p, int n) {
		MinecraftServer s = p.level().getServer();
		var sc = s.getScoreboard().getOrCreatePlayerScore(p, objective(s, currency(), "Coins"));
		if (sc.get() + n < 0) return false;
		sc.add(n);
		return true;
	}
	public static int skillLevel(ServerPlayer p, String id) { JsonObject o = SPECS.get(id); return o == null ? 0 : levelOf(o, score(p.level().getServer(), p, "sk_" + id))[0]; }
	public static void addSkillXp(ServerPlayer p, String id, int n) {
		JsonObject o = SPECS.get(id); if (o == null) return;
		MinecraftServer s = p.level().getServer();
		int xp = s.getScoreboard().getOrCreatePlayerScore(p, objective(s, "sk_" + id, str(o, "name", title(id)) + " XP")).add(n);
		level(p, id, o, xp, n);
	}

	public static int score(ServerPlayer p, String obj) { return score(p.level().getServer(), p, obj); }
	public static int skillLevelXp(ServerPlayer p, String id) { return score(p.level().getServer(), p, "sk_" + id); }
	public static String nice(String s) { return title(s); }
	static int score(MinecraftServer s, ServerPlayer p, String obj) {
		Objective o = s.getScoreboard().getObjective(obj);
		if (o == null) return 0;
		var info = s.getScoreboard().getPlayerScoreInfo(p, o);
		return info == null ? 0 : info.value();
	}

	static Objective objective(MinecraftServer s, String name, String display) {
		Scoreboard sb = s.getScoreboard();
		Objective o = sb.getObjective(name);
		return o != null ? o : sb.addObjective(name, ObjectiveCriteria.DUMMY, Component.literal(display), ObjectiveCriteria.RenderType.INTEGER, false, null);
	}

	static void objectives(MinecraftServer s) {
		for (var en : of("economy")) objective(s, str(en.getValue(), "objective", "coins"), str(en.getValue(), "name", "Coins"));
	}

	// ---- shops ---------------------------------------------------------------------------------------------------
	public static void openShop(ServerPlayer p, String id) {
		JsonObject o = SPECS.get(id);
		if (o == null) { p.sendSystemMessage(Component.literal("No shop " + id)); return; }
		var c = client;
		if (c != null) { c.accept("shop", id); return; }
		p.sendSystemMessage(Component.literal("§6" + str(o, "name", title(id)))); // no client GUI (server): list only, trades go through Systems.trade
		int i = 0;
		for (JsonElement el : arr(o, "items")) { JsonObject it = el.getAsJsonObject(); p.sendSystemMessage(Component.literal(" " + i++ + ": " + num(it, "count", 1) + "x " + str(it, "item", "?") + (it.has("buy") ? "  buy " + num(it, "buy", 0) : "") + (it.has("sell") ? "  sell " + num(it, "sell", 0) : ""))); }
	}

	/** Buys (sell=false) or sells one row of a shop. Returns a message for the player. */
	public static String trade(ServerPlayer p, String id, int row, boolean sell) {
		JsonObject o = SPECS.get(id);
		JsonArray items = arr(o, "items");
		if (o == null || row < 0 || row >= items.size()) return "No such offer";
		JsonObject it = items.get(row).getAsJsonObject();
		ItemStack st = stack(str(it, "item", "minecraft:air"), num(it, "count", 1));
		String sym = symbol();
		ServerLevel l = (ServerLevel) p.level();
		if (!sell) {
			if (!it.has("buy")) return "Not for sale";
			int price = num(it, "buy", 0);
			if (!addCoins(p, -price)) { sound(l, p.position(), "minecraft:entity.villager.no", 1f); return "Need " + price + " " + sym + " (have " + coins(p) + ")"; }
			if (!p.getInventory().add(st.copy())) Containers.dropItemStack(l, p.getX(), p.getY(), p.getZ(), st.copy());
			sound(l, p.position(), "minecraft:entity.experience_orb.pickup", 1.4f);
			return "Bought " + st.getCount() + "x " + st.getHoverName().getString() + " for " + price + " " + sym;
		}
		if (!it.has("sell")) return "They don't buy that";
		int have = 0;
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) if (ItemStack.isSameItem(inv.getItem(i), st)) have += inv.getItem(i).getCount();
		if (have < st.getCount()) { sound(l, p.position(), "minecraft:entity.villager.no", 1f); return "You need " + st.getCount() + "x " + st.getHoverName().getString(); }
		int left = st.getCount();
		for (int i = 0; i < inv.getContainerSize() && left > 0; i++) if (ItemStack.isSameItem(inv.getItem(i), st)) { int n = Math.min(left, inv.getItem(i).getCount()); inv.getItem(i).shrink(n); left -= n; }
		addCoins(p, num(it, "sell", 0));
		sound(l, p.position(), "minecraft:block.amethyst_block.chime", 1.2f);
		return "Sold " + st.getCount() + "x " + st.getHoverName().getString() + " for " + num(it, "sell", 0) + " " + sym;
	}

	public static String symbol() { var e = of("economy"); return e.isEmpty() ? "coins" : str(e.get(0).getValue(), "symbol", str(e.get(0).getValue(), "name", "coins")); }

	// ---- sky events (blood moon, eclipse, storms) ----------------------------------------------------------------
	static void skyTick(MinecraftServer s, long t) {
		var evs = of("skyevent");
		if (evs.isEmpty()) { sky = null; return; }
		ServerLevel ow = s.overworld();
		long clock = Math.floorMod(ow.getDefaultClockTime(), 24000L);
		boolean thunder = ow.isThundering(), night = clock >= 13000 && clock < 23000;
		boolean nightfall = lastClock >= 0 && lastClock < 13000 && clock >= 13000 && clock < 23000, dawn = lastClock >= 0 && night != (lastClock >= 13000 && lastClock < 23000) && !night;
		boolean storm = thunder && !lastThunder, calm = !thunder && lastThunder;
		lastClock = clock; lastThunder = thunder;
		String cur = sky;
		JsonObject a = cur == null ? null : SPECS.get(cur);
		if (cur != null && a == null) { sky = null; return; }
		if (a != null) {
			String when = str(a, "when", "night");
			if ((when.equals("night") && dawn) || (when.equals("day") && nightfall) || (when.equals("thunder") && calm)) { stopSky(s); return; }
			if (t % 100 == 0) for (ServerPlayer p : s.getPlayerList().getPlayers()) {
				if (a.has("mob_effects")) for (Entity e : p.level().getEntities((Entity) null, p.getBoundingBox().inflate(48), x -> x instanceof Monster)) effects((LivingEntity) e, a.get("mob_effects").getAsString(), 0);
				if (a.has("player_effects")) effects(p, a.get("player_effects").getAsString(), 0);
			}
			if (a.has("spawn") && t % num(a, "spawn_every", 200) == 0) for (ServerPlayer p : s.getPlayerList().getPlayers()) {
				for (int i = 0; i < num(a, "spawn_count", 2); i++) {
					double ang = Math.random() * Math.PI * 2, r = 12 + Math.random() * 12;
					Gameplay.run(p, new com.google.gson.JsonPrimitive("execute at @s positioned ~" + (int) (Math.cos(ang) * r) + " ~ ~" + (int) (Math.sin(ang) * r) + " positioned over motion_blocking_no_leaves run summon " + a.get("spawn").getAsString() + " ~ ~ ~"));
				}
			}
			return;
		}
		for (var en : evs) {
			String when = str(en.getValue(), "when", "night");
			boolean edge = (when.equals("night") && nightfall) || (when.equals("day") && dawn) || (when.equals("thunder") && storm);
			if (edge && Math.random() < dbl(en.getValue(), "chance", 0.2)) { startSky(s, en.getKey()); return; }
		}
	}

	public static void startSky(MinecraftServer s, String id) {
		JsonObject o = SPECS.get(id);
		if (o == null) return;
		if (sky != null) stopSky(s);
		sky = id; skyStart = System.nanoTime();
		var src = s.createCommandSourceStack().withSuppressedOutput().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER);
		String name = str(o, "name", title(id));
		String col = str(o, "color", "dark_red");
		for (ServerPlayer p : s.getPlayerList().getPlayers()) {
			Gameplay.run(p, new com.google.gson.JsonPrimitive("title @s times 10 60 20"));
			Gameplay.run(p, new com.google.gson.JsonPrimitive("title @s subtitle {\"text\":\"" + str(o, "subtitle", "") + "\",\"color\":\"gray\"}"));
			Gameplay.run(p, new com.google.gson.JsonPrimitive("title @s title {\"text\":\"" + name + "\",\"color\":\"" + col + "\",\"bold\":true}"));
			sound((ServerLevel) p.level(), p.position(), str(o, "sound", "minecraft:entity.wither.spawn"), 0.6f);
			if (o.has("start")) Gameplay.run(p, o.get("start"));
		}
		if (o.has("weather")) s.getCommands().performPrefixedCommand(src, "weather " + o.get("weather").getAsString());
	}

	public static void stopSky(MinecraftServer s) {
		String id = sky;
		sky = null;
		JsonObject o = id == null ? null : SPECS.get(id);
		if (o == null) return;
		for (ServerPlayer p : s.getPlayerList().getPlayers()) {
			p.sendSystemMessage(Component.literal("§7" + str(o, "name", title(id)) + " is over."), true);
			if (o.has("end")) Gameplay.run(p, o.get("end"));
		}
		if (o.has("weather")) s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER), "weather clear");
	}

	// ---- jukebox songs (dynamic registry, live) ----------------------------------------------------------------
	static void songs(MinecraftServer s) {
		for (var en : of("jukebox_song")) song(s, en.getKey(), en.getValue());
	}

	static void song(MinecraftServer s, String key, JsonObject o) {
		var reg = s.registryAccess().lookupOrThrow(Registries.JUKEBOX_SONG);
		var ops = RegistryOps.create(JsonOps.INSTANCE, s.registryAccess());
		{
			Identifier id = LiveRegistry.id(key);
			if (reg.containsKey(id)) return;
			try {
				JsonObject j = o.getAsJsonObject("song");
				Identifier snd = Identifier.parse(j.get("sound_event").getAsString());
				if (!BuiltInRegistries.SOUND_EVENT.containsKey(snd)) LiveRegistry.register(BuiltInRegistries.SOUND_EVENT, snd, k -> SoundEvent.createVariableRangeEvent(snd)); // mc sound registers it on the next content pass
				JukeboxSong song = JukeboxSong.DIRECT_CODEC.parse(ops, j).getOrThrow();
				LiveRegistry.register(reg, id, k -> song);
				var c = client;
				if (c != null) c.accept("song", new Object[] { id, song });
			} catch (Exception e) { Moddeus.LOG.warn("jukebox song {} failed", id, e); }
		}
	}

	// ---- small helpers -------------------------------------------------------------------------------------------
	static void effects(LivingEntity e, String list, int dur) {
		for (String part : list.split(",")) {
			String[] a = part.trim().split(":");
			if (a[0].isEmpty()) continue;
			String id = a[0].contains(".") || a.length > 3 ? a[0] : "minecraft:" + a[0];
			int amp = a.length > 1 ? Integer.parseInt(a[1]) : 0, ticks = a.length > 2 ? Integer.parseInt(a[2]) : (dur > 0 ? 100 : 140);
			BuiltInRegistries.MOB_EFFECT.get(Identifier.parse(id)).ifPresent(h -> e.addEffect(new MobEffectInstance(h, ticks, amp, true, true)));
		}
	}

	static void ring(ServerLevel l, ParticleOptions p, Vec3 c, double r, int n) {
		for (int i = 0; i < n; i++) { double a = Math.PI * 2 * i / n; l.sendParticles(p, c.x + Math.cos(a) * r, c.y, c.z + Math.sin(a) * r, 1, 0, 0, 0, 0.02); }
	}

	static void sound(ServerLevel l, Vec3 at, String id, float pitch) {
		SoundEvent ev = BuiltInRegistries.SOUND_EVENT.getValue(Identifier.parse(id));
		if (ev != null) l.playSound(null, at.x, at.y, at.z, ev, SoundSource.BLOCKS, 1f, pitch);
	}

	public static boolean is(BlockState s, String id) {
		if (id.startsWith("#")) return s.is(TagKey.create(Registries.BLOCK, Identifier.parse(id.substring(1))));
		return BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString().equals(id.contains(":") ? id : "minecraft:" + id);
	}

	static boolean itemIs(ItemStack s, String id) {
		if (s.isEmpty() || id.isEmpty()) return false;
		if (id.startsWith("#")) return s.is(TagKey.create(Registries.ITEM, Identifier.parse(id.substring(1))));
		return BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals(id.contains(":") ? id : "minecraft:" + id);
	}

	static ItemStack stack(String id, int n) {
		return BuiltInRegistries.ITEM.getOptional(Identifier.parse(id.contains(":") ? id : "minecraft:" + id)).map(i -> new ItemStack(i, n)).orElse(ItemStack.EMPTY);
	}

	static String key(String dim, BlockPos p) { return dim + "|" + p.getX() + "|" + p.getY() + "|" + p.getZ(); }
	static String title(String s) { StringBuilder b = new StringBuilder(); for (String w : s.split("_")) if (!w.isEmpty()) b.append(b.length() > 0 ? " " : "").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)); return b.toString(); }
	static String str(JsonObject o, String k, String d) { JsonElement e = o == null ? null : o.get(k); return e == null || e.isJsonNull() ? d : e.getAsString(); }
	static int num(JsonObject o, String k, int d) { JsonElement e = o == null ? null : o.get(k); return e == null ? d : e.getAsInt(); }
	static double dbl(JsonObject o, String k, double d) { JsonElement e = o == null ? null : o.get(k); return e == null ? d : e.getAsDouble(); }
	static boolean bool(JsonObject o, String k) { JsonElement e = o == null ? null : o.get(k); return e != null && e.getAsBoolean(); }
	static JsonArray arr(JsonObject o, String k) { return o != null && o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray(); }
	static JsonArray arrOrNew(JsonObject o, String k) { if (!o.has(k) || !o.get(k).isJsonArray()) o.add(k, new JsonArray()); return o.getAsJsonArray(k); }
}
