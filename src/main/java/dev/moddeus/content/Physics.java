package dev.moddeus.content;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.moddeus.live.LiveRegistry;
import dev.moddeus.live.OnReload;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Physics bodies (moddeus:physics_block): rigid bodies made of real blocks, Create Aeronautics / Sable style. See PhysicsBlock.
 * Spawn: Quick.physics(state, pos[, vel]) (one block), Physics.assemble(level, pos, max) (a whole connected build becomes one body),
 * or a "physics" content spec (custom textured block: texture / texture_top / texture_bottom, glow, drop).
 * Options per body (looking().set(k, v), or spec keys): mass (x, default 1 = real material weight), gravity (blocks/tick^2, 0.045),
 * friction (x), bounce (0..1, default per block: slime 0.85, others 0.12), buoyancy (x), float (true = always floats),
 * drag (per s, 0.05), angular_drag (0.15), solid (players collide, true), sleep (1), lifetime (ticks, 0 = forever).
 * Never punched or shoved: players mine its blocks (real break time, drops) and place blocks onto it.
 */
public final class Physics {
	public static Map<Identifier, JsonObject> SPECS = new ConcurrentHashMap<>();
	public static Map<Identifier, JsonObject> THROW = new ConcurrentHashMap<>();
	public static EntityType<PhysicsBlock> TYPE;
	private static final JsonObject EMPTY = new JsonObject();

	@OnReload
	public static void hook() {
		if (SPECS == null) SPECS = new ConcurrentHashMap<>();
		if (THROW == null) THROW = new ConcurrentHashMap<>();
		BLOCK = null; // built-in "block" spec: re-parse after edits
		@SuppressWarnings("unchecked")
		EntityType<PhysicsBlock> t = (EntityType<PhysicsBlock>) LiveRegistry.<EntityType<?>>register(BuiltInRegistries.ENTITY_TYPE, LiveRegistry.id("physics_block"), k ->
			EntityType.Builder.<PhysicsBlock>of(PhysicsBlock::new, MobCategory.MISC).sized(1, 1).clientTrackingRange(10).updateInterval(1).build(k));
		TYPE = t;
		if (System.getProperty("moddeus.physics.hooked") == null) {
			System.setProperty("moddeus.physics.hooked", "1");
			UseItemCallback.EVENT.register(Physics::onUse);
			CommandRegistrationCallback.EVENT.register(Physics::onCommands);
		}
		var s = LiveRegistry.server;
		if (s != null) {
			try {
				var root = s.getCommands().getDispatcher().getRoot();
				for (String f : new String[] {"children", "literals"}) {
					var fld = com.mojang.brigadier.tree.CommandNode.class.getDeclaredField(f);
					fld.setAccessible(true);
					((Map<?, ?>) fld.get(root)).remove("physics");
				}
			} catch (ReflectiveOperationException ignored) {}
			onCommands(s.getCommands().getDispatcher(), null, null);
			s.getPlayerList().getPlayers().forEach(pl -> s.getCommands().sendCommands(pl));
		}
		Runnable l = Mobs.clientListener;
		if (l != null) l.run(); // client registers the renderer
	}

	public static JsonObject spec(String id) {
		if (id == null || id.isEmpty()) return EMPTY;
		int h = id.indexOf('#');
		Identifier key = Vfx.id(h < 0 ? id : id.substring(0, h));
		JsonObject o = SPECS.get(key);
		if (o == null && key.getPath().equals("debris")) return debris();
		if (o == null && (key.getPath().equals("block") || key.getPath().equals("body"))) return block();
		return o == null ? EMPTY : o;
	}

	private static JsonObject BLOCK;
	/** Built-in spec "block#<stateId>" / "body#...": a real-block rigid body. Behaviour comes from the blocks themselves. */
	public static JsonObject block() {
		if (BLOCK == null) BLOCK = com.google.gson.JsonParser.parseString("{\"solid\":true}").getAsJsonObject();
		return BLOCK;
	}

	private static JsonObject DEBRIS;
	/** Built-in spec for blocks thrown by explosions: tumbles, then lands back as the block it carries. Override with a "debris" content spec. */
	public static JsonObject debris() {
		if (DEBRIS == null) DEBRIS = com.google.gson.JsonParser.parseString("""
			{"size":0.98,"bounce":0.2,"friction":0.55,"lands_as":"self","rest_ticks":3,"impact_sound":"block","impact_speed":0.3,
			 "spin_on_impact":0.6,"pushable":false,"solid":false,"lifetime":400,"hit_force":0.6}""").getAsJsonObject();
		return DEBRIS;
	}

	/** Spawns a body of spec id with its bottom centre at pos, moving at velocity (blocks/tick, like vanilla). */
	public static PhysicsBlock spawn(ServerLevel level, String id, Vec3 pos, Vec3 velocity) {
		if (spec(id).size() == 0) throw new IllegalArgumentException("no physics spec " + id + " (have: " + SPECS.keySet() + ")");
		PhysicsBlock b = new PhysicsBlock(TYPE, level);
		b.setSpec(id.contains("#") ? Vfx.id(id.substring(0, id.indexOf('#'))) + id.substring(id.indexOf('#')) : Vfx.id(id).toString());
		double half = -b.grid().local.minY; // com height above the bottom
		b.setPos(pos.x, pos.y + half, pos.z);
		// spawned inside terrain (e.g. "3 blocks where I look" while looking down): lift it out instead of letting it sink
		for (int i = 0; i < 6 && !level.noCollision(b.getBoundingBox().deflate(0.02)); i++) b.setPos(b.getX(), Math.floor(b.getY() - half) + 1 + half, b.getZ());
		b.v.set(velocity.x * 20, velocity.y * 20, velocity.z * 20);
		level.addFreshEntity(b);
		return b;
	}

	/** Turns the connected build at seed (6-way connected, no fluids / unbreakable / block-entity blocks) into one rigid body. */
	public static PhysicsBlock assemble(ServerLevel level, net.minecraft.core.BlockPos seed, int max) {
		java.util.LinkedHashSet<net.minecraft.core.BlockPos> found = new java.util.LinkedHashSet<>();
		java.util.ArrayDeque<net.minecraft.core.BlockPos> q = new java.util.ArrayDeque<>();
		if (!movable(level, seed)) throw new IllegalArgumentException("nothing movable at " + seed.toShortString());
		q.add(seed.immutable()); found.add(seed.immutable());
		while (!q.isEmpty()) {
			var p = q.poll();
			for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
				var n = p.relative(d);
				if (found.contains(n) || !movable(level, n)) continue;
				if (found.size() >= max) throw new IllegalArgumentException("build is bigger than " + max + " blocks (or touches the ground: assemble something standing free)");
				found.add(n); q.add(n);
			}
		}
		StringBuilder sb = new StringBuilder("moddeus:body#");
		for (var p : found) {
			if (sb.charAt(sb.length() - 1) != '#') sb.append(';');
			sb.append(p.getX() - seed.getX()).append(',').append(p.getY() - seed.getY()).append(',').append(p.getZ() - seed.getZ()).append(',').append(net.minecraft.world.level.block.Block.getId(level.getBlockState(p)));
		}
		PhysicsBlock b = new PhysicsBlock(TYPE, level);
		b.setSpec(sb.toString());
		var com = b.grid().com;
		b.setPos(seed.getX() + com.x, seed.getY() + com.y, seed.getZ() + com.z);
		for (var p : found) level.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2 | 16); // no drops, no neighbour pops
		level.addFreshEntity(b);
		return b;
	}

	static boolean movable(ServerLevel level, net.minecraft.core.BlockPos p) {
		var s = level.getBlockState(p);
		return !s.isAir() && s.getFluidState().isEmpty() && !s.hasBlockEntity() && s.getDestroySpeed(level, p) >= 0;
	}

	/** Client finished mining a cell (singleplayer: called on the server thread). */
	public static void mine(net.minecraft.server.MinecraftServer server, java.util.UUID player, int entityId, int cx, int cy, int cz) {
		var p = server.getPlayerList().getPlayer(player);
		if (p == null) return;
		if (p.level().getEntity(entityId) instanceof PhysicsBlock b && b.distanceTo(p) < 12 + b.getBoundingBox().getSize()) b.breakCell(cx, cy, cz, p);
	}

	/** The /physics command was removed (player's request). Kept as a no-op so the registered callback stays harmless. */
	public static void onCommands(CommandDispatcher<CommandSourceStack> d, CommandBuildContext ctx, Commands.CommandSelection sel) {}

	/** Every per-block option for /physics set (any spec key works; these are tab-completed). */
	public static final String[] OPTIONS = { "mass", "gravity", "friction", "bounce", "buoyancy", "float", "drag", "angular_drag", "solid", "sleep", "lifetime", "drop" };

	static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestOptions(CommandContext<CommandSourceStack> c, com.mojang.brigadier.suggestion.SuggestionsBuilder b) {
		return net.minecraft.commands.SharedSuggestionProvider.suggest(OPTIONS, b);
	}

	public static com.google.gson.JsonElement parseValue(String v) {
		v = v.trim();
		if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("on") || v.equalsIgnoreCase("yes")) return new com.google.gson.JsonPrimitive(true);
		if (v.equalsIgnoreCase("false") || v.equalsIgnoreCase("off") || v.equalsIgnoreCase("no")) return new com.google.gson.JsonPrimitive(false);
		try { return new com.google.gson.JsonPrimitive(Double.parseDouble(v)); } catch (NumberFormatException e) { return new com.google.gson.JsonPrimitive(v); }
	}

	/** /physics set <option> <value> on the looked-at block (e.g. set gravity 0.02, set bounce 1.2, set punchable off). */
	public static int setCommand(CommandContext<CommandSourceStack> c) {
		var src = c.getSource();
		try {
			PhysicsBlock b = lookedAt(src);
			if (b == null) { src.sendFailure(Component.literal("look at a physics block")); return 0; }
			String k = StringArgumentType.getString(c, "option");
			var v = parseValue(StringArgumentType.getString(c, "value"));
			b.setOption(k, v);
			src.sendSuccess(() -> Component.literal("physics block: " + k + " = " + v), false);
			return 1;
		} catch (Exception ex) { src.sendFailure(Component.literal("physics set: " + ex.getMessage())); return 0; }
	}

	public static int getCommand(CommandContext<CommandSourceStack> c) {
		var src = c.getSource();
		try {
			PhysicsBlock b = lookedAt(src);
			if (b == null) { src.sendFailure(Component.literal("look at a physics block")); return 0; }
			src.sendSuccess(() -> Component.literal("spec " + b.specId() + "\nown options: " + b.options() + "\neffective: " + b.spec()), false);
			return 1;
		} catch (Exception ex) { src.sendFailure(Component.literal("physics get: " + ex.getMessage())); return 0; }
	}

	static int resetCommand(CommandContext<CommandSourceStack> c, String key) {
		var src = c.getSource();
		try {
			PhysicsBlock b = lookedAt(src);
			if (b == null) { src.sendFailure(Component.literal("look at a physics block")); return 0; }
			if (key == null) b.resetOptions(); else b.setOption(key, null);
			src.sendSuccess(() -> Component.literal("physics block: " + (key == null ? "all options" : key) + " reset"), false);
			return 1;
		} catch (Exception ex) { src.sendFailure(Component.literal("physics reset: " + ex.getMessage())); return 0; }
	}

	/** The physics block the command source's entity is looking at (8 blocks), or null. */
	public static PhysicsBlock lookedAt(CommandSourceStack src) throws Exception {
		var e = src.getEntityOrException();
		Vec3 eye = e.getEyePosition(), end = eye.add(e.getLookAngle().scale(8));
		PhysicsBlock best = null;
		double bd = Double.MAX_VALUE;
		for (PhysicsBlock b : src.getLevel().getEntitiesOfClass(PhysicsBlock.class, e.getBoundingBox().inflate(8))) {
			var hit = b.getBoundingBox().inflate(0.2).clip(eye, end);
			if (hit.isPresent() && hit.get().distanceToSqr(eye) < bd) { bd = hit.get().distanceToSqr(eye); best = b; }
		}
		return best;
	}

	/** /physics punchable on|off on the looked-at block. */
	public static int punchableCommand(CommandContext<CommandSourceStack> c) {
		var src = c.getSource();
		try {
			PhysicsBlock b = lookedAt(src);
			if (b == null) { src.sendFailure(Component.literal("look at a physics block")); return 0; }
			boolean on = !StringArgumentType.getString(c, "on").matches("(?i)off|false|no|0");
			b.setOption("punchable", new com.google.gson.JsonPrimitive(on));
			src.sendSuccess(() -> Component.literal("physics block: punchable " + (on ? "on" : "off")), false);
			return 1;
		} catch (Exception ex) {
			src.sendFailure(Component.literal("usage: /physics punchable on|off"));
			return 0;
		}
	}

	/** /physics weight <push> [punch]: on the physics block you look at. push = how hard to shove, punch = how little it flies when hit. 0 = spec default. */
	public static int weightCommand(CommandContext<CommandSourceStack> c) {
		var src = c.getSource();
		try {
			String[] a = StringArgumentType.getString(c, "weights").trim().split("\\s+");
			float push = Float.parseFloat(a[0]), punch = a.length > 1 ? Float.parseFloat(a[1]) : push;
			PhysicsBlock best = lookedAt(src);
			if (best == null) { src.sendFailure(Component.literal("look at a physics block")); return 0; }
			best.setOption("push_weight", new com.google.gson.JsonPrimitive(push));
			best.setOption("punch_weight", new com.google.gson.JsonPrimitive(punch));
			src.sendSuccess(() -> Component.literal("physics block: push weight " + push + ", punch weight " + punch), false);
			return 1;
		} catch (Exception ex) {
			src.sendFailure(Component.literal("usage: /physics weight <push> [punch]  (1 = normal, 5 = heavy, 0.2 = light)"));
			return 0;
		}
	}

	/** /physics <id> [count]: spawns at the command position, thrown along its rotation with the spec's throw_speed. */
	public static int runCommand(CommandContext<CommandSourceStack> c) {
		var src = c.getSource();
		try {
			String[] a = StringArgumentType.getString(c, "id").trim().split("\\s+");
			int n = a.length > 1 ? Integer.parseInt(a[1]) : 1;
			double speed = spec(Vfx.id(a[0]).toString()).has("throw_speed") ? spec(Vfx.id(a[0]).toString()).get("throw_speed").getAsDouble() : 0;
			Vec3 dir = Vec3.directionFromRotation(src.getRotation());
			for (int i = 0; i < n; i++) spawn(src.getLevel(), a[0], src.getPosition().add(0, i * 1.05, 0), dir.scale(speed));
			return n;
		} catch (Exception e) {
			src.sendFailure(Component.literal("physics: " + e.getMessage()));
			return 0;
		}
	}

	public static InteractionResult onUse(Player p, Level level, InteractionHand hand) {
		var stack = p.getItemInHand(hand);
		JsonObject t = THROW.get(BuiltInRegistries.ITEM.getKey(stack.getItem()));
		if (t == null || !(level instanceof ServerLevel sl) || p.getCooldowns().isOnCooldown(stack)) return InteractionResult.PASS;
		try {
			double speed = t.has("speed") ? t.get("speed").getAsDouble() : 1.2;
			spawn(sl, t.get("physics").getAsString(), p.getEyePosition().add(p.getLookAngle().scale(1.2)).add(0, -0.5, 0), p.getLookAngle().scale(speed).add(p.getDeltaMovement()));
			if (t.has("cooldown")) p.getCooldowns().addCooldown(stack, t.get("cooldown").getAsInt());
			if (t.has("consume") && t.get("consume").getAsBoolean() && !p.getAbilities().instabuild) stack.shrink(1);
		} catch (Exception e) {
			p.sendSystemMessage(Component.literal("physics: " + e.getMessage()));
		}
		return InteractionResult.PASS;
	}
}
