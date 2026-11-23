package dev.moddeus.live;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;

/**
 * Instant actions for voice (no compile): node tools/mc.mjs game quick <action> [args]. Also callable from
 * game run snippets (Quick.launch(player, 3)). Add an action = add a case + a method; it hot-swaps.
 */
public final class Quick {
	static Set<UUID> safe; // players protected from fall damage until they land

	public static final String HELP = String.join("\n",
		"launch [up=2.5]            launch the player into the air",
		"push <x> <y> <z>           add velocity to the player",
		"safe_landing               no fall damage until the player next lands",
		"no_fall <on|off>           permanent: fall damage off/on",
		"kill_hostiles [radius=64]  kill hostile mobs near the player",
		"heal                       full health, food, no fire",
		"stop_mobs [radius=32]      hostile mobs nearby forget their target");

	public static String run(String action, List<String> a, ServerPlayer p) {
		return switch (action.toLowerCase().replace('-', '_')) {
			case "launch" -> { launch(p, num(a, 0, 2.5)); yield "launched"; }
			case "push" -> { push(p, num(a, 0, 0), num(a, 1, 0), num(a, 2, 0)); yield "pushed"; }
			case "safe_landing" -> { safeLanding(p); yield "no fall damage until you land"; }
			case "no_fall" -> { boolean off = a.isEmpty() || !a.get(0).equalsIgnoreCase("off"); fallDamage(p, off ? 0 : 1); yield off ? "fall damage off" : "fall damage on"; }
			case "kill_hostiles" -> { int n = 0; for (Mob m : hostiles(p, num(a, 0, 64))) { m.kill((ServerLevel) m.level()); n++; } yield "killed " + n; }
			case "heal" -> { p.setHealth(p.getMaxHealth()); p.getFoodData().setFoodLevel(20); p.getFoodData().setSaturation(20); p.clearFire(); yield "healed"; }
			case "stop_mobs" -> { int n = 0; for (Mob m : hostiles(p, num(a, 0, 32))) { m.setTarget(null); m.getNavigation().stop(); n++; } yield "calmed " + n; }
			default -> throw new IllegalArgumentException("unknown action " + action + "\n" + HELP);
		};
	}

	static double num(List<String> a, int i, double def) {
		try { return i < a.size() ? Double.parseDouble(a.get(i)) : def; } catch (NumberFormatException e) { return def; }
	}

	public static void launch(Entity e, double up) { push(e, 0, up, 0); }

	public static void push(Entity e, double x, double y, double z) {
		e.setDeltaMovement(e.getDeltaMovement().add(x, y, z));
		e.needsSync = true;
		if (e instanceof ServerPlayer sp) sp.connection.send(new ClientboundSetEntityMotionPacket(sp));
	}

	/** 0 = no fall damage, 1 = normal. Persistent (attribute base value). */
	public static void fallDamage(LivingEntity e, double mult) {
		var a = e.getAttribute(Attributes.FALL_DAMAGE_MULTIPLIER);
		if (a != null) a.setBaseValue(mult);
	}

	public static void safeLanding(ServerPlayer p) {
		if (safe == null) safe = new HashSet<>();
		safe.add(p.getUUID());
		airborne.remove(p.getUUID());
	}

	public static List<Mob> hostiles(Entity around, double r) {
		return around.level().getEntitiesOfClass(Mob.class, new AABB(around.blockPosition()).inflate(r), m -> m instanceof Enemy && m.isAlive());
	}

	static Set<UUID> airborne = new HashSet<>();

	// ---- helpers for game run snippets (statically imported there) -------------------------------------------------

	/** The current player (a respawn makes a new ServerPlayer object, so tasks should call me() each time). */
	/** Plays a cutscene defined with Cutscene.define(name, ...) (client code); callable from server code in singleplayer. */
	/** A key bind for a mod (own section in Controls, rebindable): runs onPress on the server thread as the player. See client/Keys. */
	public static void key(String mod, String label, String key, java.util.function.Consumer<net.minecraft.server.level.ServerPlayer> onPress) {
		try { Class.forName("dev.moddeus.client.Keys").getMethod("bind", String.class, String.class, String.class, java.util.function.Consumer.class).invoke(null, mod, label, key, onPress); }
		catch (Throwable t) { dev.moddeus.Moddeus.LOG.warn("key bind {}: {}", label, t.toString()); }
	}

	/** Is that mod key held right now (client state; singleplayer). */
	public static boolean keyDown(String mod, String label) {
		try { return (boolean) Class.forName("dev.moddeus.client.Keys").getMethod("down", String.class, String.class).invoke(null, mod, label); }
		catch (Throwable t) { return false; }
	}

	public static boolean cutscene(String name) {
		try { return (Boolean) Class.forName("dev.moddeus.client.Cutscene").getMethod("play", String.class).invoke(null, name); }
		catch (Throwable t) { return false; }
	}

	public static ServerPlayer me() {
		MinecraftServer s = LiveRegistry.server;
		return s == null || s.getPlayerList().getPlayers().isEmpty() ? null : s.getPlayerList().getPlayers().get(0);
	}


	public static ServerLevel world() { ServerPlayer p = me(); return p == null ? null : (ServerLevel) p.level(); }

	/** Runs a vanilla command (no slash) as the player, with op rights. Returns its feedback. */
	public static String cmd(String command) {
		ServerPlayer p = me();
		StringBuilder out = new StringBuilder();
		var src = p.createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER).withSource(new net.minecraft.commands.CommandSource() {
			public void sendSystemMessage(net.minecraft.network.chat.Component c) { out.append(c.getString()).append('\n'); }
			public boolean acceptsSuccess() { return true; }
			public boolean acceptsFailure() { return true; }
			public boolean shouldInformAdmins() { return false; }
		});
		p.level().getServer().getCommands().performPrefixedCommand(src, command.startsWith("/") ? command.substring(1) : command);
		return out.toString().trim();
	}

	/** Turns the connected build at pos (or the block you look at) into one rigid body, like a Create Aeronautics assembly. */
	public static dev.moddeus.content.PhysicsBlock assemble(net.minecraft.core.BlockPos pos) { return dev.moddeus.content.Physics.assemble(world(), pos, 4096); }
	public static dev.moddeus.content.PhysicsBlock assemble() {
		var h = me().pick(20, 1, false);
		if (!(h instanceof net.minecraft.world.phys.BlockHitResult b) || h.getType() == net.minecraft.world.phys.HitResult.Type.MISS) throw new IllegalStateException("look at a block");
		return assemble(b.getBlockPos());
	}

	/** Spawns any block as a rigid physics body (bottom centre at pos). Mine it to break it; place blocks onto it to build. */
	public static dev.moddeus.content.PhysicsBlock physics(net.minecraft.world.level.block.state.BlockState state, net.minecraft.world.phys.Vec3 pos) {
		return physics(state, pos, net.minecraft.world.phys.Vec3.ZERO);
	}

	public static dev.moddeus.content.PhysicsBlock physics(net.minecraft.world.level.block.state.BlockState state, net.minecraft.world.phys.Vec3 pos, net.minecraft.world.phys.Vec3 vel) {
		return dev.moddeus.content.Physics.spawn(world(), "moddeus:block#" + net.minecraft.world.level.block.Block.getId(state), pos, vel);
	}

	/** The physics block the player is looking at (8 blocks), or null. looking().set("bounce", 1.2) */
	public static dev.moddeus.content.PhysicsBlock looking() {
		ServerPlayer p = me();
		net.minecraft.world.phys.Vec3 eye = p.getEyePosition(), end = eye.add(p.getLookAngle().scale(8));
		dev.moddeus.content.PhysicsBlock best = null;
		double bd = Double.MAX_VALUE;
		for (var b : p.level().getEntitiesOfClass(dev.moddeus.content.PhysicsBlock.class, p.getBoundingBox().inflate(9))) {
			var hit = b.clipRotated(eye, end, 0.2);
			if (hit.isPresent() && hit.get().distanceToSqr(eye) < bd) { bd = hit.get().distanceToSqr(eye); best = b; }
		}
		return best;
	}

	// ---- repeating behaviour: every("gold_floor", () -> ...) until stop("gold_floor") ------------------------------
	static java.util.Map<String, Object[]> tasks; // name -> {Runnable, period, counter, remainingRuns(-1 = forever)}

	static java.util.Map<String, Object[]> tasks() { if (tasks == null) tasks = new java.util.LinkedHashMap<>(); return tasks; }

	public static void every(String name, Runnable r) { every(name, 1, r); }
	public static void every(String name, int ticks, Runnable r) { tasks().put(name, new Object[] { r, Math.max(1, ticks), 0, -1 }); }
	/** Runs once after the delay (20 ticks = 1 s). */
	public static void after(int ticks, Runnable r) { tasks().put("after#" + System.nanoTime(), new Object[] { r, Math.max(1, ticks), 0, 1 }); }
	public static void stop(String name) { tasks().remove(name); clientTasks().remove(name); }
	public static void stopAll() { tasks().clear(); clientTasks().clear(); }
	public static java.util.Set<String> running() { var s = new java.util.LinkedHashSet<>(tasks().keySet()); s.addAll(clientTasks().keySet()); return s; }

	// Client tasks: the player's own movement (climbing, jumping, speed, flight feel) is computed on the client,
	// so abilities like "climb walls" must run there, every client tick, with mc.player.
	static java.util.Map<String, Object[]> clientTasks;
	static java.util.Map<String, Object[]> clientTasks() { if (clientTasks == null) clientTasks = new java.util.LinkedHashMap<>(); return clientTasks; }
	public static void everyClient(String name, Runnable r) { clientTasks().put(name, new Object[] { r, 1, 0, -1 }); }
	/** Called by the client tick (ModdeusClient). */
	public static void runClientTasks() { if (clientTasks != null && !clientTasks.isEmpty()) runTasks(clientTasks); }

	static void runTasks() { if (tasks != null && !tasks.isEmpty()) runTasks(tasks); }

	static void runTasks(java.util.Map<String, Object[]> tasks) {
		for (var e : new java.util.ArrayList<>(tasks.entrySet())) {
			Object[] t = e.getValue();
			t[2] = (int) t[2] + 1;
			if ((int) t[2] < (int) t[1]) continue;
			t[2] = 0;
			try { ((Runnable) t[0]).run(); }
			catch (Throwable x) { // a broken task stops instead of spamming every tick
				tasks.remove(e.getKey());
				dev.moddeus.Moddeus.LOG.warn("task {} stopped", e.getKey(), x);
				ServerPlayer p = me();
				if (p != null) p.level().getServer().execute(() -> p.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c[Moddeus] task " + e.getKey() + " stopped: " + x)));
				continue;
			}
			if ((int) t[3] > 0 && (int) (t[3] = (int) t[3] - 1) == 0) tasks.remove(e.getKey());
		}
	}

	@OnReload
	public static void hook() {
		if (airborne == null) airborne = new HashSet<>(); // statics added by a swap start null
		if (System.getProperties().putIfAbsent("moddeus.quick", Boolean.TRUE) != null) return;
		ServerTickEvents.END_SERVER_TICK.register(Quick::tick);
	}

	static void tick(MinecraftServer s) {
		dev.moddeus.content.Collisions.serverTick();
		runTasks();
		if (safe == null || safe.isEmpty()) return;
		if (airborne == null) airborne = new HashSet<>();
		for (ServerPlayer p : s.getPlayerList().getPlayers()) {
			UUID u = p.getUUID();
			if (!safe.contains(u)) continue;
			p.resetFallDistance();
			if (!p.onGround()) airborne.add(u);
			else if (airborne.remove(u)) safe.remove(u); // landed after the launch: protection used up
		}
	}
}
