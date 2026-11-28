package dev.moddeus.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import dev.moddeus.Moddeus;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.attribute.EnvironmentAttributeMap;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;
import net.minecraft.world.level.gamerules.GameRuleType;
import net.minecraft.world.level.gamerules.GameRuleTypeVisitor;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * World feel + presentation for Moddeus mods, data-driven (content JSON from tools/world.mjs, loaded by ContentLoader), all live:
 * "ambience" = vanilla environment attributes (sky/fog/cloud/light colors, fog distances, background music, ambient loop/mood/
 * additions sounds, ambient particles, gameplay attributes like monsters_burn) + biome colors (water/grass/foliage) pushed into
 * the running biomes on server AND client (same code path vanilla biomes use, so biome borders blend);
 * "logic" = redstone components on a {@link LogicBlock} (gates, latches, clocks, pulse/delay, sensors, command outputs);
 * "banner_pattern" (live dynamic-registry entry + loom tags); "gamerule" (real /gamerule moddeus:x); "command" (custom
 * commands a mod defines for its player, only when asked); toasts via WorldFeel.toast. Client half: client/WorldFeelClient. Guide: mc-content WORLD.md.
 */
public final class WorldFeel {
	public static Map<String, JsonObject> SPECS;
	public static volatile boolean enabled = true;
	/** Client half (WorldFeelClient): ("ambience"|"toast"|"banner", arg). */
	public static volatile java.util.function.BiConsumer<String, Object> client;
	static Map<Object, Object[]> originals; // Biome -> {attributes, specialEffects} as registered
	static Map<Long, long[]> mem; // logic block memory: {lastInput, due game time}
	static Set<String> commandNames;
	static final Set<String> TYPES = Set.of("ambience", "logic", "banner_pattern", "gamerule", "command");

	static void init() {
		if (SPECS == null) SPECS = new ConcurrentHashMap<>();
		if (originals == null) originals = java.util.Collections.synchronizedMap(new IdentityHashMap<>());
		if (mem == null) mem = new ConcurrentHashMap<>();
		if (commandNames == null) commandNames = ConcurrentHashMap.newKeySet();
	}

	// ---- loading (ContentLoader) -------------------------------------------------------------------------------
	public static void begin() { init(); SPECS.clear(); }

	public static boolean load(String id, JsonObject o) {
		init();
		if (!o.has("type") || !TYPES.contains(o.get("type").getAsString())) return false;
		String key = o.has("id") ? o.get("id").getAsString() : id;
		SPECS.put(key, o);
		if ("gamerule".equals(o.get("type").getAsString())) gamerule(key, o);
		return true;
	}

	public static void end() {
		init();
		MinecraftServer s = LiveRegistry.server;
		if (s != null) s.execute(() -> refresh(s));
	}

	static void refresh(MinecraftServer s) {
		applyServer(s);
		for (var en : of("banner_pattern")) banner(s, en.getKey(), en.getValue());
		for (var en : of("gamerule")) ruleDefault(s, en.getKey());
		try { // removed rules leave the world's rule map, so the save never names a rule that is gone after a restart
			Set<Identifier> keep = new java.util.HashSet<>();
			for (var en : of("gamerule")) keep.add(LiveRegistry.id(str(en.getValue(), "rule", en.getKey())));
			Field f = net.minecraft.world.level.gamerules.GameRules.class.getDeclaredField("rules"); f.setAccessible(true);
			var map = (net.minecraft.world.level.gamerules.GameRuleMap) f.get(s.getGameRules());
			for (var r : List.copyOf(map.keySet())) { Identifier id = BuiltInRegistries.GAME_RULE.getKey(r); if (id != null && (id.getNamespace().equals("moddeus") || LiveRegistry.namespaces().containsValue(id.getNamespace())) && !keep.contains(id)) map.remove(r); }
		} catch (Exception e) { Moddeus.LOG.warn("gamerule cleanup failed", e); }
		commands(s.getCommands().getDispatcher(), null, null);
		for (ServerPlayer p : s.getPlayerList().getPlayers()) s.getCommands().sendCommands(p);
		var c = client;
		if (c != null) c.accept("ambience", null);
	}

	public static List<Map.Entry<String, JsonObject>> of(String type) {
		init();
		List<Map.Entry<String, JsonObject>> r = new ArrayList<>();
		for (var e : SPECS.entrySet()) if (type.equals(str(e.getValue(), "type", ""))) r.add(e);
		r.sort(Map.Entry.comparingByKey());
		return r;
	}

	// ---- hooks -------------------------------------------------------------------------------------------------
	@OnReload
	public static void hook() {
		init();
		enabled = true;
		if (System.getProperties().putIfAbsent("moddeus.worldfeel", Boolean.TRUE) == null) {
			net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(WorldFeel::started);
			net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(WorldFeel::commands);
		}
		MinecraftServer s = LiveRegistry.server;
		if (s != null) s.execute(() -> refresh(s));
	}

	@OnUnload
	public static void unhook() {
		enabled = false;
		MinecraftServer s = LiveRegistry.server;
		if (s != null) s.execute(() -> applyServer(s)); // restores the biomes
		var c = client;
		if (c != null) c.accept("ambience", null);
	}

	static void started(MinecraftServer s) { init(); refresh(s); }

	// ---- ambience: environment attributes + biome colors -------------------------------------------------------
	static void applyServer(MinecraftServer s) {
		List<Level> ls = new ArrayList<>();
		for (ServerLevel l : s.getAllLevels()) ls.add(l);
		applyAmbience(s.registryAccess(), ls);
	}

	/** Rebuilds every biome of this registry access from its original + the matching ambience specs, then the levels' attribute systems. Returns true when biome colors changed. */
	public static synchronized boolean applyAmbience(RegistryAccess ra, List<Level> levels) {
		init();
		var reg = ra.lookupOrThrow(Registries.BIOME);
		var ops = RegistryOps.create(JsonOps.INSTANCE, ra);
		var specs = enabled ? of("ambience") : List.<Map.Entry<String, JsonObject>>of();
		for (var en : specs) registerSounds(en.getValue().get("attributes"));
		boolean colors = false;
		for (Holder.Reference<Biome> h : reg.listElements().toList()) {
			Biome b = h.value();
			Object[] orig = originals.computeIfAbsent(b, x -> new Object[] { b.getAttributes(), b.getSpecialEffects() });
			EnvironmentAttributeMap attrs = (EnvironmentAttributeMap) orig[0];
			BiomeSpecialEffects fx = (BiomeSpecialEffects) orig[1];
			EnvironmentAttributeMap.Builder mb = null;
			for (var en : specs) {
				JsonObject o = en.getValue();
				if (!matches(o, h)) continue;
				if (o.has("attributes")) {
					try {
						if (mb == null) mb = EnvironmentAttributeMap.builder().putAll(attrs);
						mb.putAll(EnvironmentAttributeMap.CODEC.parse(ops, o.get("attributes")).getOrThrow());
					} catch (Exception e) { Moddeus.LOG.warn("ambience {}: bad attributes: {}", en.getKey(), e.getMessage()); }
				}
				if (o.has("effects")) fx = effects(o.getAsJsonObject("effects"), fx);
			}
			EnvironmentAttributeMap now = mb == null ? attrs : mb.build();
			if (b.getAttributes() != now) set(b, "attributes", now);
			if (!fx.equals(b.getSpecialEffects())) { set(b, "specialEffects", fx); colors = true; }
		}
		for (Level l : levels) rebuild(l);
		return colors;
	}

	/** Changes whenever the biome colors an ambience sets change (the client re-meshes chunks then). */
	public static String colorSignature() {
		StringBuilder sb = new StringBuilder();
		if (enabled) for (var en : of("ambience")) if (en.getValue().has("effects")) sb.append(en.getValue().get("biomes")).append(en.getValue().get("effects"));
		return sb.toString();
	}

	static boolean matches(JsonObject o, Holder<Biome> h) {
		JsonElement b = o.get("biomes");
		if (b == null || (b.isJsonPrimitive() && b.getAsString().equals("all"))) return true;
		JsonArray arr = b.isJsonArray() ? b.getAsJsonArray() : new JsonArray();
		if (b.isJsonPrimitive()) for (String s : b.getAsString().split(",")) arr.add(s.trim());
		for (JsonElement e : arr) {
			String s = e.getAsString();
			if (s.equals("all") || s.equals("*")) return true;
			if (s.startsWith("#") ? h.is(TagKey.create(Registries.BIOME, Identifier.parse(s.substring(1)))) : h.is(Identifier.parse(s))) return true;
		}
		return false;
	}

	static BiomeSpecialEffects effects(JsonObject e, BiomeSpecialEffects fx) {
		return new BiomeSpecialEffects(e.has("water_color") ? color(e.get("water_color")) : fx.waterColor(),
			e.has("foliage_color") ? Optional.of(color(e.get("foliage_color"))) : fx.foliageColorOverride(),
			e.has("dry_foliage_color") ? Optional.of(color(e.get("dry_foliage_color"))) : fx.dryFoliageColorOverride(),
			e.has("grass_color") ? Optional.of(color(e.get("grass_color"))) : fx.grassColorOverride(), fx.grassColorModifier());
	}

	static int color(JsonElement e) {
		if (e.getAsJsonPrimitive().isNumber()) return e.getAsInt();
		return (int) Long.parseLong(e.getAsString().replace("#", ""), 16);
	}

	/** Our sounds (moddeus: or a mod namespace) an ambience names must exist as sound events before the codec reads them. */
	static void registerSounds(JsonElement e) {
		if (e == null) return;
		if (e.isJsonObject()) for (var en : e.getAsJsonObject().entrySet()) {
			JsonElement v = en.getValue();
			if ((en.getKey().equals("sound") || en.getKey().equals("loop")) && v.isJsonPrimitive() && v.getAsString().contains(":") && !v.getAsString().startsWith("minecraft:")) {
				Identifier id = Identifier.parse(v.getAsString());
				if (!BuiltInRegistries.SOUND_EVENT.containsKey(id)) LiveRegistry.register(BuiltInRegistries.SOUND_EVENT, id, k -> SoundEvent.createVariableRangeEvent(id));
			} else registerSounds(v);
		}
		else if (e.isJsonArray()) for (JsonElement v : e.getAsJsonArray()) registerSounds(v);
	}

	/** New attribute system for a level, built exactly like its constructor does (client levels add their own layers). */
	static void rebuild(Level l) {
		try {
			EnvironmentAttributeSystem.Builder b = EnvironmentAttributeSystem.builder();
			Method m = find(l.getClass(), "addEnvironmentAttributeLayers", EnvironmentAttributeSystem.Builder.class);
			if (m != null) m.invoke(l, b); else b.addDefaultLayers(l);
			EnvironmentAttributeSystem sys = b.build();
			if (l instanceof ServerLevel sl) sl.setEnvironmentAttributes(sys); else set(l, "environmentAttributes", sys);
		} catch (Exception e) { Moddeus.LOG.warn("ambience: attribute rebuild failed for {}", l, e); }
	}

	static Method find(Class<?> c, String name, Class<?>... args) {
		for (; c != null; c = c.getSuperclass()) try { Method m = c.getDeclaredMethod(name, args); m.setAccessible(true); return m; } catch (NoSuchMethodException ignored) {}
		return null;
	}

	static void set(Object o, String field, Object v) {
		for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) try {
			Field f = c.getDeclaredField(field); f.setAccessible(true); f.set(o, v); return;
		} catch (NoSuchFieldException ignored) {
		} catch (Exception e) { throw new RuntimeException(e); }
		throw new IllegalStateException("no field " + field + " in " + o.getClass());
	}

	// ---- banner patterns (dynamic registry, live; loom tags bind on the next /reload) ------------------------
	static void banner(MinecraftServer s, String key, JsonObject o) {
		Identifier id = LiveRegistry.id(key);
		var reg = s.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN);
		BannerPattern bp = new BannerPattern(Identifier.parse(str(o, "asset_id", id.toString())), str(o, "translation_key", "block.moddeus.banner." + key));
		try { if (!reg.containsKey(id)) LiveRegistry.register(reg, id, k -> bp); } catch (Exception e) { Moddeus.LOG.warn("banner pattern {} failed", id, e); }
		var c = client;
		if (c != null) c.accept("banner", new Object[] { id, bp });
	}

	// ---- game rules (real /gamerule moddeus:<id>, saved with the world) --------------------------------------
	static void gamerule(String key, JsonObject o) {
		Identifier id = LiveRegistry.id(str(o, "rule", key));
		if (BuiltInRegistries.GAME_RULE.containsKey(id)) return;
		GameRuleCategory cat = switch (str(o, "category", "misc")) {
			case "player" -> GameRuleCategory.PLAYER; case "mobs" -> GameRuleCategory.MOBS; case "spawning" -> GameRuleCategory.SPAWNING;
			case "drops" -> GameRuleCategory.DROPS; case "updates" -> GameRuleCategory.UPDATES; case "chat" -> GameRuleCategory.CHAT; default -> GameRuleCategory.MISC; };
		try {
			if ("int".equals(str(o, "kind", "bool"))) {
				int def = o.has("default") ? o.get("default").getAsInt() : 0;
				LiveRegistry.register(BuiltInRegistries.GAME_RULE, id, k -> new GameRule<Integer>(cat, GameRuleType.INT, IntegerArgumentType.integer(o.has("min") ? o.get("min").getAsInt() : Integer.MIN_VALUE),
					new IntVisitor(), Codec.INT, i -> i, def, net.minecraft.world.flag.FeatureFlagSet.of()));
			} else {
				boolean def = !o.has("default") || o.get("default").getAsBoolean();
				LiveRegistry.register(BuiltInRegistries.GAME_RULE, id, k -> new GameRule<Boolean>(cat, GameRuleType.BOOL, BoolArgumentType.bool(),
					new BoolVisitor(), Codec.BOOL, v -> v ? 1 : 0, def, net.minecraft.world.flag.FeatureFlagSet.of()));
			}
		} catch (Exception e) { Moddeus.LOG.warn("gamerule {} failed", id, e); }
	}
	public static final class BoolVisitor implements net.minecraft.world.level.gamerules.GameRules.VisitorCaller<Boolean> {
		@Override public void call(GameRuleTypeVisitor v, GameRule<Boolean> r) { v.visitBoolean(r); }
	}
	public static final class IntVisitor implements net.minecraft.world.level.gamerules.GameRules.VisitorCaller<Integer> {
		@Override public void call(GameRuleTypeVisitor v, GameRule<Integer> r) { v.visitInteger(r); }
	}

	@SuppressWarnings("unchecked")
	static void ruleDefault(MinecraftServer s, String key) {
		GameRule<Object> r = (GameRule<Object>) BuiltInRegistries.GAME_RULE.getValue(LiveRegistry.id(str(SPECS.get(key), "rule", key)));
		if (r == null) return;
		try { // a live-registered rule is missing from the world's rule map: GameRules.get throws, set refuses ("invalid game rule")
			Field f = net.minecraft.world.level.gamerules.GameRules.class.getDeclaredField("rules"); f.setAccessible(true);
			var map = (net.minecraft.world.level.gamerules.GameRuleMap) f.get(s.getGameRules());
			if (!map.has(r)) map.set(r, r.defaultValue());
		} catch (Exception e) { Moddeus.LOG.warn("gamerule {} default failed", key, e); }
	}

	/** Value of a Moddeus game rule (Boolean or Integer), or null. Java mods: WorldFeel.rule(server, "my_rule"). */
	@SuppressWarnings("unchecked")
	public static Object rule(MinecraftServer s, String id) {
		GameRule<Object> r = (GameRule<Object>) BuiltInRegistries.GAME_RULE.getValue(id.contains(":") ? Identifier.parse(id) : LiveRegistry.id(id));
		if (r == null) return null;
		try { Object v = s.getGameRules().get(r); return v == null ? r.defaultValue() : v; } catch (Exception e) { return r.defaultValue(); }
	}
	public static boolean ruleOn(MinecraftServer s, String id) { Object v = rule(s, id); return v instanceof Boolean b ? b : v instanceof Integer i && i != 0; }

	// ---- custom /<name> [args] commands a MOD defines for its player (content "command": lines with $(arg)) ----------
	static void commands(CommandDispatcher<CommandSourceStack> d, Object ctx, Object env) {
		init();
		Set<String> names = new java.util.HashSet<>(commandNames);
		d.getRoot().getChildren().removeIf(c -> names.contains(c.getName()));
		commandNames.clear();
		if (!enabled) return;
		for (var en : of("command")) {
			JsonObject o = en.getValue();
			String name = str(o, "name", en.getKey());
			if (d.getRoot().getChild(name) != null) { Moddeus.LOG.warn("command /{} already exists, skipped", name); continue; }
			try { d.register(command(name, o)); commandNames.add(name); } catch (Exception e) { Moddeus.LOG.warn("command /{} failed", name, e); }
		}
	}

	/** Shows a toast (top right, like advancements) to this player. icon = item id; color = accent ARGB. */
	public static void toast(ServerPlayer p, String icon, String title, String text, int color) {
		var c = client;
		if (c != null) c.accept("toast", new Object[] { p.getUUID(), icon, title, text, color });
	}

	static LiteralArgumentBuilder<CommandSourceStack> command(String name, JsonObject o) {
		List<String[]> args = new ArrayList<>(); // {name, type}
		if (o.has("args")) for (JsonElement e : o.getAsJsonArray("args")) { String[] a = e.getAsString().split(":"); args.add(new String[] { a[0], a.length > 1 ? a[1] : "word" }); }
		int op = o.has("op") ? o.get("op").getAsInt() : 0;
		var root = Commands.literal(name);
		if (op >= 4) root.requires(Commands.hasPermission(Commands.LEVEL_OWNERS));
		else if (op == 3) root.requires(Commands.hasPermission(Commands.LEVEL_ADMINS));
		else if (op > 0) root.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));
		com.mojang.brigadier.Command<CommandSourceStack> run = c -> runLines(c, o, args);
		if (args.isEmpty()) return root.executes(run);
		ArgumentBuilder<CommandSourceStack, ?> tail = null;
		for (int i = args.size() - 1; i >= 0; i--) {
			String[] a = args.get(i);
			@SuppressWarnings("unchecked") var type = (com.mojang.brigadier.arguments.ArgumentType<Object>) (com.mojang.brigadier.arguments.ArgumentType<?>) switch (a[1]) {
				case "int" -> IntegerArgumentType.integer(); case "number", "double" -> DoubleArgumentType.doubleArg();
				case "bool" -> BoolArgumentType.bool(); case "player" -> EntityArgument.player(); case "players", "targets" -> EntityArgument.players();
				case "entity" -> EntityArgument.entity(); case "string" -> StringArgumentType.string(); case "text", "greedy" -> StringArgumentType.greedyString();
				default -> StringArgumentType.word(); };
			var node = Commands.argument(a[0], type);
			node.executes(run);
			if (tail != null) node.then(tail);
			tail = node;
		}
		if (bool(o, "optional_args")) root.executes(run);
		return root.then(tail);
	}

	static int runLines(CommandContext<CommandSourceStack> c, JsonObject o, List<String[]> args) {
		// lines run as the caller with permission 2 (the point of a custom command); "elevate": false = the caller's own permission
		CommandSourceStack src = o.has("elevate") && !o.get("elevate").getAsBoolean() ? c.getSource()
			: c.getSource().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER);
		int n = 0;
		for (JsonElement e : o.getAsJsonArray("run")) {
			// substitute resolved values only; a multi-player arg runs the line once per resolved player (UUID)
			List<String> lines = new java.util.ArrayList<>(List.of(e.getAsString()));
			for (String[] a : args) {
				String key = "$(" + a[0] + ")";
				List<String> vals = new java.util.ArrayList<>();
				try {
					switch (a[1]) {
						case "player", "entity" -> vals.add(EntityArgument.getEntity(c, a[0]).getStringUUID());
						case "players", "targets" -> { for (var p : EntityArgument.getPlayers(c, a[0])) vals.add(p.getStringUUID()); }
						case "int", "number", "bool" -> vals.add(String.valueOf(c.getArgument(a[0], Object.class)));
						// free text (messages): one argument's worth of text, no quotes/backslashes/line breaks/selectors/NBT/JSON
						case "text", "greedy" -> vals.add(String.valueOf(c.getArgument(a[0], Object.class)).replaceAll("[\"'\\\\\r\n@{}\\[\\]§]", "").trim());
						// word/string: strict allowlist, no spaces (can't add extra command arguments)
						default -> { String v = String.valueOf(c.getArgument(a[0], Object.class)); if (v.matches("[A-Za-z0-9_.:-]{1,64}")) vals.add(v); }
					}
				} catch (Exception ex) { /* optional arg not given */ }
				if (vals.isEmpty()) { if (o.has("optional_args") && bool(o, "optional_args")) vals.add(""); else { c.getSource().sendFailure(Component.literal("Invalid value for " + a[0])); return 0; } }
				List<String> next = new java.util.ArrayList<>();
				for (String l : lines) {
					if (!l.contains(key)) { next.add(l); continue; }
					for (String v : vals) next.add(l.replace(key, v));
				}
				lines = next;
				if (lines.size() > 256) { c.getSource().sendFailure(Component.literal("Too many targets")); return 0; }
			}
			for (String line : lines) { c.getSource().getServer().getCommands().performPrefixedCommand(src, line.startsWith("/") ? line.substring(1) : line); n++; }
		}
		return n;
	}

	// ---- logic blocks (redstone components) --------------------------------------------------------------------
	/** Spec of a logic block: content JSON "<id>_logic" ({"type":"logic","block":"<id>","mode":...}), read live every update. */
	static JsonObject logic(Block b) {
		init();
		String id = BuiltInRegistries.BLOCK.getKey(b).getPath();
		for (var en : SPECS.entrySet()) { JsonObject o = en.getValue(); if ("logic".equals(str(o, "type", "")) && id.equals(str(o, "block", ""))) return o; }
		return new JsonObject();
	}

	/**
	 * Redstone component driven by its spec (modes in tools/world.mjs header). FACING points at the back input (like a repeater),
	 * the output goes out the front; sensors and clocks power every side. POWER = output strength 0-15 (models show on/off).
	 */
	public static class LogicBlock extends Block {
		public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
		public static final IntegerProperty POWER = BlockStateProperties.POWER;

		public LogicBlock(BlockBehaviour.Properties p) {
			super(p);
			registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(POWER, 0));
		}

		@Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(FACING, POWER); }

		@Override public BlockState getStateForPlacement(BlockPlaceContext ctx) { return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite()); }

		String mode() { return str(logic(this), "mode", "or"); }
		boolean gate() { return Logic.GATES.contains(mode()); }

		@Override protected boolean isSignalSource(BlockState s) { return !"output".equals(mode()); }

		@Override protected int getSignal(BlockState s, BlockGetter l, BlockPos pos, Direction dir) {
			if ("output".equals(mode())) return 0;
			return gate() ? (dir == s.getValue(FACING) ? s.getValue(POWER) : 0) : s.getValue(POWER);
		}

		@Override protected int getDirectSignal(BlockState s, BlockGetter l, BlockPos pos, Direction dir) { return getSignal(s, l, pos, dir); }

		@Override protected void neighborChanged(BlockState s, Level l, BlockPos pos, Block from, Orientation o, boolean moved) {
			if (l instanceof ServerLevel sl && !sl.getBlockTicks().hasScheduledTick(pos, this)) sl.scheduleTick(pos, this, Math.max(1, "delay".equals(mode()) ? num(logic(this), "ticks", 4) : 1));
		}

		@Override protected void onPlace(BlockState s, Level l, BlockPos pos, BlockState old, boolean moved) {
			if (l instanceof ServerLevel sl && !old.is(this)) sl.scheduleTick(pos, this, 1);
		}

		@Override protected void tick(BlockState s, ServerLevel l, BlockPos pos, RandomSource rnd) {
			try { Logic.tick(this, s, l, pos, rnd); } catch (Exception e) { Moddeus.LOG.warn("logic block at {} failed", pos, e); }
		}
	}

	/** Logic block behaviour, kept out of the Block subclass so it hot-swaps as plain static code. */
	public static final class Logic {
		static final Set<String> GATES = Set.of("and", "or", "xor", "nand", "nor", "xnor", "not", "latch", "toggle", "pulse", "delay", "random", "repeater");

		static int in(ServerLevel l, BlockPos pos, Direction side) { return l.getSignal(pos.relative(side), side); }

		static void tick(LogicBlock b, BlockState s, ServerLevel l, BlockPos pos, RandomSource rnd) {
			if (!enabled) return;
			JsonObject o = logic(b);
			String mode = str(o, "mode", "or");
			Direction back = s.getValue(LogicBlock.FACING), left = back.getClockWise(), right = back.getCounterClockWise();
			int out = s.getValue(LogicBlock.POWER);
			long key = pos.asLong();
			long[] m = mem.computeIfAbsent(key, k -> new long[2]);
			long now = l.getGameTime();
			boolean A = in(l, pos, left) > 0, B = in(l, pos, right) > 0;
			int bk = in(l, pos, back);
			boolean BK = bk > 0;
			int period = Math.max(1, num(o, "ticks", 20));
			int next = out;
			int every = 0; // ticks until the next self-scheduled check (sensors, clocks, pulses)
			switch (mode) {
				case "and" -> next = A && B ? 15 : 0;
				case "or" -> next = A || B || BK ? 15 : 0;
				case "xor" -> next = A ^ B ? 15 : 0;
				case "nand" -> next = A && B ? 0 : 15;
				case "nor" -> next = A || B || BK ? 0 : 15;
				case "xnor" -> next = A == B ? 15 : 0;
				case "not" -> next = BK ? 0 : 15;
				case "repeater", "delay" -> next = bk;
				case "latch" -> { if (A) next = 15; else if (B) next = 0; } // left = set, right = reset
				case "toggle" -> { if (BK && m[0] == 0) next = out > 0 ? 0 : 15; }
				case "random" -> { if (BK && m[0] == 0) next = rnd.nextInt(16); }
				case "pulse" -> { if (out > 0 && m[1] > 0 && now >= m[1]) { next = 0; m[1] = 0; } else if (BK && m[0] == 0 && out == 0) { next = 15; m[1] = now + period; every = period; } }
				case "clock" -> { // only its own schedule toggles it; neighbor updates just re-check the disable input
					if (BK) { next = 0; m[1] = 0; }
					else if (now >= m[1]) { next = out > 0 ? 0 : 15; every = next > 0 ? Math.max(1, num(o, "on", 2)) : Math.max(1, period - num(o, "on", 2)); m[1] = now + every; }
					else every = (int) (m[1] - now); }
				case "output" -> { int any = l.getBestNeighborSignal(pos); next = any; output(o, l, pos, out > 0, any > 0); if (any > 0 && o.has("cmd_tick")) { if (out > 0) run(l, pos, str(o, "cmd_tick", "")); every = Math.max(1, num(o, "every", 20)); } }
				default -> { next = sensor(mode, o, l, pos); every = Math.max(1, num(o, "every", 10)); }
			}
			m[0] = BK ? 1 : 0;
			next = Math.max(0, Math.min(15, next));
			if (next != out) {
				l.setBlock(pos, s.setValue(LogicBlock.POWER, next), Block.UPDATE_ALL);
				Direction front = back.getOpposite();
				l.updateNeighborsAt(pos, b);
				l.updateNeighborsAt(pos.relative(front), b);
				if (!mode.equals("output") && o.has("sound")) sound(l, pos, str(o, "sound", ""), next > 0 ? 1.2f : 0.8f);
			}
			if (every > 0) l.scheduleTick(pos, b, every);
		}

		static int sensor(String mode, JsonObject o, ServerLevel l, BlockPos pos) {
			double r = num(o, "radius", 6);
			AABB box = new AABB(pos).inflate(r);
			Vec3 c = Vec3.atCenterOf(pos);
			return switch (mode) {
				case "player" -> { double best = Double.MAX_VALUE; for (Player p : l.players()) if (!p.isSpectator() && p.position().distanceTo(c) <= r) best = Math.min(best, p.position().distanceTo(c)); yield best == Double.MAX_VALUE ? 0 : bool(o, "analog") ? (int) Math.ceil(15 * (1 - best / (r + 1))) : 15; }
				case "mob", "entity" -> { String t = str(o, "entity", ""); int n = 0; for (Entity e : l.getEntities((Entity) null, box, e -> e.isAlive() && !(e instanceof Player) && !(e instanceof ItemEntity) && (t.isEmpty() || BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().equals(t.contains(":") ? t : "minecraft:" + t)))) n++; yield Math.min(15, n * Math.max(1, num(o, "per", 15))); }
				case "item" -> { int n = 0; for (ItemEntity e : l.getEntitiesOfClass(ItemEntity.class, box)) n += e.getItem().getCount(); yield Math.min(15, n); }
				case "light" -> l.getMaxLocalRawBrightness(pos.above());
				case "sky", "daylight" -> l.getBrightness(LightLayer.SKY, pos.above()) >= 15 && l.isBrightOutside() ? 15 : 0;
				case "night" -> l.isBrightOutside() ? 0 : 15;
				case "rain", "weather" -> l.isRainingAt(pos.above()) || l.isThundering() ? (l.isThundering() ? 15 : 8) : 0;
				case "time" -> (int) (((l.getOverworldClockTime() % 24000) + 24000) % 24000 * 16 / 24000);
				case "gamerule" -> ruleOn(l.getServer(), str(o, "rule", "")) ? 15 : 0;
				case "score" -> { var sb = l.getScoreboard(); var obj = sb.getObjective(str(o, "objective", "")); int best = 0; if (obj != null) for (Player p : l.players()) if (p.position().distanceTo(c) <= r) { var sc = sb.getPlayerScoreInfo(p, obj); if (sc != null) best = Math.max(best, sc.value()); } yield Math.min(15, best); }
				default -> 0;
			};
		}

		static void output(JsonObject o, ServerLevel l, BlockPos pos, boolean was, boolean now) {
			if (now && !was) { run(l, pos, str(o, "cmd", "")); if (o.has("sound")) sound(l, pos, str(o, "sound", ""), 1); if (o.has("vfx")) try { dev.moddeus.content.Vfx.play(dev.moddeus.content.Vfx.id(str(o, "vfx", "")), Vec3.atCenterOf(pos), Vec3.ZERO, null); } catch (Exception e) { Moddeus.LOG.warn("logic vfx: {}", e.getMessage()); } }
			if (!now && was) run(l, pos, str(o, "cmd_off", ""));
		}

		static void run(ServerLevel l, BlockPos pos, String cmd) {
			if (cmd.isBlank()) return;
			var src = l.getServer().createCommandSourceStack().withLevel(l).withPosition(Vec3.atCenterOf(pos)).withSuppressedOutput();
			for (String c : cmd.split(";;")) l.getServer().getCommands().performPrefixedCommand(src, c.trim().startsWith("/") ? c.trim().substring(1) : c.trim());
		}

		static void sound(ServerLevel l, BlockPos pos, String id, float pitch) {
			BuiltInRegistries.SOUND_EVENT.getOptional(Identifier.parse(id)).ifPresent(se -> l.playSound(null, pos, se, SoundSource.BLOCKS, 0.6f, pitch));
		}
	}

	// ---- json helpers ------------------------------------------------------------------------------------------
	static String str(JsonObject o, String k, String d) { return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : d; }
	static int num(JsonObject o, String k, int d) { return o != null && o.has(k) ? o.get(k).getAsInt() : d; }
	static double num(JsonObject o, String k, double d) { return o != null && o.has(k) ? o.get(k).getAsDouble() : d; }
	static boolean bool(JsonObject o, String k) { return o != null && o.has(k) && o.get(k).getAsBoolean(); }
}
