package dev.moddeus.live;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mojang.authlib.GameProfile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A chat's private test stage, far from the player: overworld x = 3_000_000 + slot*4096, z = 3_000_000, floor at y=200
 * (33x33, barrier rim). Slots are stable per id (run/moddeus/stages.json) and never overlap, so many chats test at once.
 * Chunks are forceloaded only while the stage is open (clear() releases them). Nothing here touches the real player:
 * an actor (Fabric FakePlayer, not in the world or player list) uses/hits items, and while it acts Quick.me() returns
 * the actor and cutscenes/VFX/camera effects skip (Stage.quiet()). Guide: .claude/skills/mc-content/TESTING.md.
 * Shell: mc game stage <id> [report|clear|list]; Java (game run): Stage s = Stage.of("MyMod"); s.spawn("zombie"); ...
 * Coordinates in helpers are relative to the stage centre (dx, dy, dz; dy=0 = standing on the floor).
 */
public final class Stage {
	public static final int X0 = 3_000_000, Z0 = 3_000_000, Y = 200, SPACING = 4096, R = 16, H = 40;
	static Map<String, Stage> open;
	static Map<String, Integer> slots;
	/** The stage actor while it acts (use/hit/run): player-facing effects must skip, Quick.me() returns it. */
	public static volatile ServerPlayer acting;

	public final String id, tag;
	public final int slot;
	public final BlockPos origin;
	final ServerLevel level;
	boolean built, placed;
	ServerPlayer actor;
	final StringBuilder log = new StringBuilder();

	Stage(String id, int slot, ServerLevel level) {
		this.id = id;
		this.tag = id + "_test";
		this.slot = slot;
		this.level = level;
		this.origin = new BlockPos(X0 + slot * SPACING + 8, Y, Z0 + 8);
	}

	// ---- where stages are ------------------------------------------------------------------------------------------
	public static boolean isStage(double x, double z) { return x > X0 - 1024 && Math.abs(z - Z0) < 1024; }
	public static boolean isStage(Vec3 p) { return p != null && isStage(p.x, p.z); }
	public static boolean isStage(BlockPos p) { return p != null && isStage(p.getX(), p.getZ()); }
	public static boolean isStage(Entity e) { return e != null && isStage(e.getX(), e.getZ()); }
	/** True while a stage action runs: skip anything aimed at the real player (cutscenes, shake, titles, camera). */
	public static boolean quiet() { return acting != null; }
	public static boolean quiet(Vec3 pos) { return acting != null || isStage(pos); }

	/** Records an effect that a guard skipped because it came from a stage (shows in report()). */
	public static void note(Vec3 pos, String what) {
		if (open == null) return;
		for (Stage s : open.values()) if (pos == null ? acting == s.actor : Math.abs(pos.x - s.origin.getX()) < SPACING / 2.0) {
			s.log(pos == null ? what : String.format(java.util.Locale.ROOT, "%s @ %.1f %.1f %.1f", what, pos.x - s.origin.getX() - 0.5, pos.y - Y, pos.z - s.origin.getZ() - 0.5));
			return;
		}
	}

	// ---- open / ready / clear --------------------------------------------------------------------------------------
	/** The stage for a mod or chat (same id = same stage). Forceloads its chunks; check ready() before using it. */
	public static synchronized Stage of(String name) {
		MinecraftServer srv = LiveRegistry.server;
		if (srv == null) throw new IllegalStateException("no world open");
		boolean fresh = slots == null;
		load();
		if (fresh) for (var e : slots.entrySet()) { // forceloads are saved with the world: release stages left open by a crash/exit
			int cx = (X0 + e.getValue() * SPACING + 8) >> 4, cz = (Z0 + 8) >> 4;
			for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) srv.overworld().setChunkForced(cx + x, cz + z, false);
		}
		String id = name.trim().replaceAll("[^A-Za-z0-9_]", "_");
		if (id.isEmpty()) throw new IllegalArgumentException("stage needs a name (your mod id)");
		Stage s = open.get(id);
		if (s != null && s.level != srv.overworld()) { open.remove(id); s = null; } // another world was loaded since
		if (s == null) {
			Integer slot = slots.get(id);
			if (slot == null) {
				slot = 0;
				while (slots.containsValue(slot)) slot++;
				slots.put(id, slot);
				save();
			}
			s = new Stage(id, slot, srv.overworld());
			open.put(id, s);
		}
		s.force(true);
		return s;
	}

	void force(boolean on) {
		int cx = origin.getX() >> 4, cz = origin.getZ() >> 4;
		for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) level.setChunkForced(cx + x, cz + z, on);
	}

	/** Chunks loaded (async, a few seconds the first time) and floor built. */
	public boolean ready() {
		int cx = origin.getX() >> 4, cz = origin.getZ() >> 4;
		for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) if (!level.getChunkSource().hasChunk(cx + x, cz + z)) return false;
		if (!level.isPositionEntityTicking(origin)) return false;
		if (!built) build();
		return true;
	}

	void need() { if (!ready()) throw new IllegalStateException("stage " + id + " still loading: run node tools/mc.mjs game stage " + id + " first (waits until ready)"); }

	void build() {
		BlockState air = Blocks.AIR.defaultBlockState(), floor = Blocks.SMOOTH_STONE.defaultBlockState(), line = Blocks.POLISHED_ANDESITE.defaultBlockState(), rim = Blocks.BARRIER.defaultBlockState();
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) {
			boolean edge = Math.abs(dx) == R || Math.abs(dz) == R;
			level.setBlock(p.set(origin.getX() + dx, Y - 1, origin.getZ() + dz), dx % 4 == 0 || dz % 4 == 0 ? line : floor, 18); // 4-block grid: read distances in shots
			for (int dy = 0; dy <= H; dy++) {
				p.set(origin.getX() + dx, Y + dy, origin.getZ() + dz);
				BlockState want = edge && dy < 3 ? rim : air;
				if (level.getBlockState(p) != want) level.setBlock(p, want, 18);
			}
		}
		built = true;
	}

	/** Removes every entity, block and forceload of this stage (the slot stays reserved for this id). */
	public String clear() {
		int n = 0;
		for (Entity e : level.getEntities((Entity) null, box().inflate(48, 64, 48), e -> !(e instanceof Player))) { e.discard(); n++; }
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		int b = 0;
		if (built) for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) for (int dy = -1; dy <= H; dy++) {
			p.set(origin.getX() + dx, Y + dy, origin.getZ() + dz);
			if (!level.getBlockState(p).isAir()) { level.setBlock(p, air, 18); b++; }
		}
		force(false);
		built = placed = false;
		if (actor != null) { // Fabric caches the fake player: hand the next test a clean one
			actor.stopUsingItem();
			actor.getInventory().clearContent();
			actor.removeAllEffects();
			actor.setHealth(actor.getMaxHealth());
		}
		actor = null;
		log.setLength(0);
		synchronized (Stage.class) { open.remove(id); }
		return "stage " + id + " cleared: " + n + " entities, " + b + " blocks, forceload off";
	}

	public static String list() {
		load();
		StringBuilder sb = new StringBuilder();
		for (var e : new TreeMap<>(slots).entrySet()) {
			Stage s = open.get(e.getKey());
			sb.append(e.getKey()).append(" slot ").append(e.getValue()).append(" x=").append(X0 + e.getValue() * SPACING + 8)
				.append(s == null ? " (closed)" : s.built ? " (open)" : " (loading)").append('\n');
		}
		return sb.length() == 0 ? "no stages" : sb.toString().trim();
	}

	// ---- building the scene ----------------------------------------------------------------------------------------
	public BlockPos at(int dx, int dy, int dz) { return origin.offset(dx, dy, dz); }
	public Vec3 pos(double dx, double dy, double dz) { return new Vec3(origin.getX() + 0.5 + dx, Y + dy, origin.getZ() + 0.5 + dz); }
	public AABB box() { return new AABB(origin.getX() - R, Y - 1, origin.getZ() - R, origin.getX() + R + 1, Y + H, origin.getZ() + R + 1); }
	public ServerLevel level() { return level; }

	/** Runs a command at the stage centre (op, output returned). @a/@p/@r are refused: they would reach the real player. */
	public String cmd(String command) {
		need();
		String c = command.startsWith("/") ? command.substring(1) : command;
		if (c.matches("(?s).*@[apr](?![a-z]).*") || c.matches("(?s).*@[en](?!\\[[^\\]]*(tag=" + java.util.regex.Pattern.quote(tag) + "|distance=\\.\\.\\d)).*"))
			throw new IllegalArgumentException("stage cmd: that selector can reach the real player; use @e[tag=" + tag + "] (or @e[distance=..20]) or s.actor()");
		if (c.matches("(?is)\\s*(tick|time|weather|gamerule|difficulty|gamemode|defaultgamemode|setworldspawn|worldborder|stop|op|deop|kick|ban|tp|teleport|spreadplayers|forceload|reload|datapack)\\b.*"))
			throw new IllegalArgumentException("stage cmd: " + c.split("\\s")[0] + " changes the whole world or the player; test with your own code on the stage instead");
		StringBuilder out = new StringBuilder();
		var src = level.getServer().createCommandSourceStack().withLevel(level).withPosition(pos(0, 0, 0)).withPermission(LevelBasedPermissionSet.OWNER)
			.withSource(new CommandSource() {
				public void sendSystemMessage(Component m) { out.append(m.getString()).append('\n'); }
				public boolean acceptsSuccess() { return true; }
				public boolean acceptsFailure() { return true; }
				public boolean shouldInformAdmins() { return false; }
			});
		level.getServer().getCommands().performPrefixedCommand(src, c);
		return out.toString().trim();
	}

	/** setblock at stage-relative coords, e.g. place(0, 0, 4, "minecraft:target"). */
	public String place(int dx, int dy, int dz, String block) { BlockPos p = at(dx, dy, dz); return cmd("setblock " + p.getX() + " " + p.getY() + " " + p.getZ() + " " + block); }
	public String block(int dx, int dy, int dz) { return level.getBlockState(at(dx, dy, dz)).toString(); }

	/** summon at stage-relative coords with extra SNBT ("{...}" or ""), tagged <id>_test, persistent (no player nearby = instant despawn otherwise). */
	public Entity spawn(String type, double dx, double dy, double dz, String nbt) {
		need();
		String mark = "st_" + Long.toString(System.nanoTime(), 36), n = nbt == null ? "" : nbt.trim();
		if (n.contains("Tags:")) throw new IllegalArgumentException("spawn: leave Tags out of the NBT (add tags after: e.addTag(..))");
		if (n.startsWith("{")) {
			if (!n.endsWith("}")) throw new IllegalArgumentException("spawn: NBT must be {...}");
			n = n.substring(1, n.length() - 1).trim();
		}
		Vec3 p = pos(dx, dy, dz);
		String out = cmd(String.format(java.util.Locale.ROOT, "summon %s %.2f %.2f %.2f {Tags:[\"%s\",\"%s\"],PersistenceRequired:1b%s}", type, p.x, p.y, p.z, tag, mark, n.isEmpty() ? "" : "," + n));
		for (Entity e : level.getEntities((Entity) null, box().inflate(4), e -> e.entityTags().contains(mark))) { e.removeTag(mark); return e; }
		throw new IllegalArgumentException("summon " + type + " failed: " + out);
	}
	public Entity spawn(String type) { return spawn(type, 0, 0, 4, ""); }
	public Entity spawn(String type, String nbt) { return spawn(type, 0, 0, 4, nbt); }

	/** An npc/boss made with mc npc / mc boss, spawned on the stage (boss bars/intros only reach players within ~64 blocks: nobody). */
	public Entity spawnDef(String id, double dx, double dy, double dz) {
		need();
		Entity e = as(() -> dev.moddeus.content.Gameplay.spawn(id, pos(dx, dy, dz)));
		e.addTag(tag);
		return e;
	}

	/** A target dummy (mannequin) for mobs/abilities to hit: e.g. boss.setTarget(s.dummy(0, 0, -4)). */
	public LivingEntity dummy(double dx, double dy, double dz) { return (LivingEntity) spawn("minecraft:mannequin", dx, dy, dz, "{hide_description:1b}"); }

	// ---- the actor: a fake player that runs item code instead of the real player -------------------------------------
	public ServerPlayer actor() {
		need();
		if (actor == null) {
			String name = ("T_" + id).length() > 16 ? ("T_" + id).substring(0, 16) : "T_" + id;
			actor = FakePlayer.get(level, new GameProfile(UUID.nameUUIDFromBytes(("moddeus-stage-" + id).getBytes(StandardCharsets.UTF_8)), name));
		}
		if (!placed) { placed = true; moveTo(0, 0, -4); lookAt(pos(0, 1, 4)); }
		return actor;
	}
	public Stage moveTo(double dx, double dy, double dz) { Vec3 p = pos(dx, dy, dz); actor().setPos(p.x, p.y, p.z); return this; }
	public Stage lookAt(Vec3 p) { actor().lookAt(EntityAnchorArgument.Anchor.EYES, p); actor.setYHeadRot(actor.getYRot()); return this; }
	public Stage lookAt(Entity e) { return lookAt(e.getBoundingBox().getCenter()); }
	/** Puts an item in the actor's main hand: "moddeus:frost_blade" or an ItemStack. */
	public Stage hold(String item) { return hold(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(item)))); }
	public Stage hold(ItemStack stack) {
		actor().setItemInHand(InteractionHand.MAIN_HAND, stack);
		try { var m = LivingEntity.class.getDeclaredMethod("detectEquipmentUpdates"); m.setAccessible(true); m.invoke(actor); } // the actor doesn't tick: apply the item's attack damage/speed now
		catch (ReflectiveOperationException e) { log("equip: " + e); }
		return this;
	}

	/** Right-click with the held item (UseItemCallback + Item.use). */
	public String use() { return act(false, () -> actor.gameMode.useItem(actor, level, actor.getMainHandItem(), InteractionHand.MAIN_HAND)); }
	/** Sneak + right-click. */
	public String sneakUse() { return act(true, () -> actor.gameMode.useItem(actor, level, actor.getMainHandItem(), InteractionHand.MAIN_HAND)); }
	/** Right-click on the top face of a stage block. */
	public String useOn(int dx, int dy, int dz) {
		BlockPos p = at(dx, dy, dz);
		return act(false, () -> actor.gameMode.useItemOn(actor, level, actor.getMainHandItem(), InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(p).add(0, 0.5, 0), Direction.UP, p, false)));
	}
	/** Charge items (bows, spears): the actor doesn't tick, so this releases as if held for ticks. */
	public String release(int ticks) {
		return act(false, () -> {
			ItemStack s = actor.getMainHandItem();
			s.releaseUsing(level, actor, s.getUseDuration(actor) - ticks);
			actor.stopUsingItem();
			return "released";
		});
	}
	/** Left-click hit at full attack strength (AttackEntityCallback first, like a real swing). */
	public String hit(Entity target) {
		return act(false, () -> {
			lookAt(target);
			InteractionResult r = AttackEntityCallback.EVENT.invoker().interact(actor, level, InteractionHand.MAIN_HAND, target, null);
			if (r != InteractionResult.PASS) return r;
			try { var f = LivingEntity.class.getDeclaredField("attackStrengthTicker"); f.setAccessible(true); f.setInt(actor, 1000); } catch (ReflectiveOperationException ignored) {}
			actor.attack(target);
			actor.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
			return "hit";
		});
	}
	/** Runs any code as the actor (Quick.me() = actor, cutscenes/VFX to the player skip). */
	public String run(Runnable r) { return act(false, () -> { r.run(); return "ok"; }); }

	String act(boolean sneak, Supplier<Object> r) {
		actor();
		actor.setShiftKeyDown(sneak);
		try { return String.valueOf(as(r)); }
		catch (Throwable t) { log("EXCEPTION " + t); return "EXCEPTION " + t; }
		finally { actor.setShiftKeyDown(false); }
	}

	<T> T as(Supplier<T> r) {
		ServerPlayer was = acting;
		acting = actor();
		try { return r.get(); } finally { acting = was; }
	}

	/** For Quick.every/after: a task scheduled while a stage actor acts keeps acting as it. */
	public static Runnable wrap(Runnable r) {
		ServerPlayer a = acting;
		if (a == null) return r;
		return () -> {
			boolean live = false; // the stage was cleared: its tasks stop acting (and never fall back to the real player)
			if (open != null) for (Stage s : open.values()) live |= s.actor == a;
			if (!live) return;
			ServerPlayer was = acting;
			acting = a;
			try { r.run(); } finally { acting = was; }
		};
	}

	/** Runs r after ticks as the actor; exceptions land in report(). */
	public void after(int ticks, Runnable r) { Quick.after(ticks, () -> { try { as(() -> { r.run(); return null; }); } catch (Throwable t) { log("EXCEPTION in after: " + t); } }); }

	// ---- results ---------------------------------------------------------------------------------------------------
	public void log(Object o) { if (log.length() < 20000) log.append(o).append('\n'); }

	/** Entities (type, relative pos, health, effects, tags), non-floor blocks and the stage log. */
	public String report() {
		if (!ready()) return "stage " + id + " loading";
		StringBuilder sb = new StringBuilder("stage " + id + " (slot " + slot + ", centre " + origin.getX() + " " + Y + " " + origin.getZ() + ")\n");
		for (Entity e : level.getEntities((Entity) null, box().inflate(16), e -> true)) {
			Vec3 r = e.position().subtract(pos(0, 0, 0));
			sb.append(String.format(java.util.Locale.ROOT, "  %s%s @ %.1f %.1f %.1f", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()), e.hasCustomName() ? " \"" + e.getCustomName().getString() + "\"" : "", r.x, r.y, r.z));
			if (e instanceof LivingEntity l) {
				sb.append(String.format(java.util.Locale.ROOT, " hp %.1f/%.1f", l.getHealth(), l.getMaxHealth()));
				if (!l.isAlive()) sb.append(" DEAD");
				for (MobEffectInstance m : l.getActiveEffects()) sb.append(' ').append(m.getEffect().getRegisteredName()).append(':').append(m.getAmplifier());
			}
			if (e.isOnFire()) sb.append(" burning");
			var tags = new java.util.ArrayList<>(e.entityTags());
			tags.remove(tag);
			if (!tags.isEmpty()) sb.append(" tags ").append(tags);
			sb.append('\n');
		}
		Map<String, Integer> blocks = new TreeMap<>();
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int dx = -R + 1; dx < R; dx++) for (int dz = -R + 1; dz < R; dz++) for (int dy = 0; dy <= H; dy++) {
			BlockState s = level.getBlockState(p.set(origin.getX() + dx, Y + dy, origin.getZ() + dz));
			if (!s.isAir()) blocks.merge(BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString(), 1, Integer::sum);
		}
		if (!blocks.isEmpty()) sb.append("  blocks ").append(blocks).append('\n');
		if (actor != null) sb.append("  actor @ ").append(String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f", actor.getX() - origin.getX() - 0.5, actor.getY() - Y, actor.getZ() - origin.getZ() - 0.5)).append(" holding ").append(actor.getMainHandItem()).append('\n');
		if (log.length() > 0) sb.append("  log:\n    ").append(log.toString().trim().replace("\n", "\n    ")).append('\n');
		return sb.toString().trim();
	}

	// ---- slot file ---------------------------------------------------------------------------------------------------
	static Path file() { return Path.of("moddeus", "stages.json"); }

	static void load() {
		if (open == null) open = new LinkedHashMap<>();
		if (slots != null) return;
		slots = new LinkedHashMap<>();
		try { Map<String, Integer> m = new Gson().fromJson(Files.readString(file()), new TypeToken<Map<String, Integer>>() {}.getType()); if (m != null) slots.putAll(m); }
		catch (Exception ignored) {}
	}

	static void save() {
		try { Files.createDirectories(file().getParent()); Files.writeString(file(), new Gson().toJson(slots)); }
		catch (Exception e) { dev.moddeus.Moddeus.LOG.warn("stages.json: {}", e.toString()); }
	}
}
