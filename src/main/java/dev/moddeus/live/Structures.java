package dev.moddeus.live;

import com.google.common.base.Suppliers;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.RandomSource;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Structures + terrain + live worldgen (guide: .claude/skills/mc-content/STRUCTURES.md, CLI: tools/structures.mjs).
 * Structures.run("tower size=9 palette=stone decay=0.2") from game run; builders also run inside worldgen (BuildFeature).
 * Every in-world edit records the replaced blocks (+ block entity NBT) so "undo" puts the world back.
 */
public final class Structures {
	static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS; // no drops, no container spill

	// ================================================= undo ==========================================================
	public static final class Op {
		final String name; final ServerLevel level;
		final LinkedHashMap<Long, Object[]> old = new LinkedHashMap<>();
		int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
		Op(String name, ServerLevel level) { this.name = name; this.level = level; }
		void record(BlockPos p) {
			long k = p.asLong();
			if (old.containsKey(k)) return;
			BlockState s = level.getBlockState(p);
			CompoundTag nbt = null;
			if (s.hasBlockEntity()) { BlockEntity be = level.getBlockEntity(p); if (be != null) nbt = be.saveWithFullMetadata(level.registryAccess()); }
			old.put(k, new Object[] { s, nbt });
			x0 = Math.min(x0, p.getX()); y0 = Math.min(y0, p.getY()); z0 = Math.min(z0, p.getZ());
			x1 = Math.max(x1, p.getX()); y1 = Math.max(y1, p.getY()); z1 = Math.max(z1, p.getZ());
		}
		void rebound() { x0 = y0 = z0 = Integer.MAX_VALUE; x1 = y1 = z1 = Integer.MIN_VALUE; for (long k : old.keySet()) { int x = BlockPos.getX(k), y = BlockPos.getY(k), z = BlockPos.getZ(k); x0 = Math.min(x0, x); y0 = Math.min(y0, y); z0 = Math.min(z0, z); x1 = Math.max(x1, x); y1 = Math.max(y1, y); z1 = Math.max(z1, z); } }
		boolean fluids; BitSet dry; int dx0, dy0, dz0, dxs, dys, dzs;
		/** Remembers which blocks around the edit were dry, so undo can also remove water/lava that flowed out of it. */
		void markDry(int m) {
			dx0 = x0 - m; dy0 = y0 - m; dz0 = z0 - m; dxs = x1 - x0 + 2 * m + 1; dys = y1 - y0 + 2 * m + 1; dzs = z1 - z0 + 2 * m + 1;
			dry = new BitSet(dxs * dys * dzs);
			BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
			for (int i = 0, x = 0; x < dxs; x++) for (int y = 0; y < dys; y++) for (int z = 0; z < dzs; z++, i++) if (level.getFluidState(p.set(dx0 + x, dy0 + y, dz0 + z)).isEmpty() || old.containsKey(p.asLong())) dry.set(i);
		}
		void unflow() {
			if (dry == null) return;
			BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
			for (int i = 0, x = 0; x < dxs; x++) for (int y = 0; y < dys; y++) for (int z = 0; z < dzs; z++, i++) {
				if (!dry.get(i) || old.containsKey(p.set(dx0 + x, dy0 + y, dz0 + z).asLong())) continue;
				BlockState s = level.getBlockState(p);
				if (s.getFluidState().isEmpty()) continue;
				var WL = net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED;
				level.setBlock(p, s.hasProperty(WL) ? s.setValue(WL, false) : Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
			}
		}
		String box() { return old.isEmpty() ? "nothing" : x0 + " " + y0 + " " + z0 + " -> " + x1 + " " + y1 + " " + z1; }
	}
	static ArrayDeque<Op> undo, redo;
	static ArrayDeque<Op> undos() { if (undo == null) undo = new ArrayDeque<>(); return undo; }
	static ArrayDeque<Op> redos() { if (redo == null) redo = new ArrayDeque<>(); return redo; }
	static int opCount;

	static void push(Op op) {
		if (op.old.isEmpty()) return;
		undos().push(op); redos().clear();
		long total = 0; for (Op o : undos()) total += o.old.size();
		while (undos().size() > 1 && (undos().size() > 64 || total > 4_000_000)) total -= undos().removeLast().old.size();
	}

	/** Puts the recorded blocks back; returns the inverse op (for redo). */
	static Op restore(Op op) {
		Op inv = new Op(op.name, op.level);
		List<Map.Entry<Long, Object[]>> es = new ArrayList<>(op.old.entrySet());
		Collections.reverse(es);
		for (var e : es) inv.record(BlockPos.of(e.getKey()));
		for (var e : es) {
			BlockPos p = BlockPos.of(e.getKey());
			op.level.setBlock(p, (BlockState) e.getValue()[0], FLAGS);
			CompoundTag nbt = (CompoundTag) e.getValue()[1];
			if (nbt != null) {
				BlockEntity be = op.level.getBlockEntity(p);
				if (be != null) { be.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, op.level.registryAccess(), nbt)); be.setChanged(); }
			}
		}
		op.unflow(); // after the restore, so the river's own water can't flow back into the cleared spots
		return inv;
	}

	// ================================================= palettes ======================================================
	static final String[][] PALETTES = {
		{ "stone", "wall=stone_bricks*5,mossy_stone_bricks,cracked_stone_bricks;base=cobblestone*3,mossy_cobblestone;floor=polished_andesite;pillar=chiseled_stone_bricks;roof=deepslate_tiles;stairs=deepslate_tile_stairs;slab=stone_brick_slab;trim=polished_andesite;window=glass_pane;light=lantern;fence=stone_brick_wall;door=spruce_door;path=gravel*2,cobblestone" },
		{ "deepslate", "wall=deepslate_bricks*4,cracked_deepslate_bricks,deepslate_tiles;base=cobbled_deepslate;floor=polished_deepslate;pillar=chiseled_deepslate;roof=deepslate_tiles;stairs=deepslate_tile_stairs;slab=deepslate_brick_slab;trim=polished_deepslate;window=tinted_glass;light=soul_lantern;fence=deepslate_brick_wall;door=dark_oak_door;path=cobbled_deepslate" },
		{ "sandstone", "wall=cut_sandstone*4,sandstone,smooth_sandstone;base=sandstone;floor=smooth_sandstone;pillar=chiseled_sandstone;roof=cut_sandstone;stairs=sandstone_stairs;slab=sandstone_slab;trim=orange_terracotta;window=air;light=lantern;fence=sandstone_wall;door=birch_door;path=sand" },
		{ "nether", "wall=nether_bricks*4,cracked_nether_bricks,red_nether_bricks;base=blackstone;floor=polished_blackstone_bricks;pillar=chiseled_nether_bricks;roof=red_nether_bricks;stairs=nether_brick_stairs;slab=nether_brick_slab;trim=gilded_blackstone*1,polished_blackstone*6;window=iron_bars;light=shroomlight;fence=nether_brick_fence;door=crimson_door;path=soul_soil" },
		{ "end", "wall=end_stone_bricks*4,purpur_block;base=end_stone;floor=purpur_block;pillar=purpur_pillar;roof=purpur_block;stairs=purpur_stairs;slab=purpur_slab;trim=obsidian;window=magenta_stained_glass_pane;light=end_rod;fence=end_stone_brick_wall;door=warped_door;path=end_stone" },
		{ "quartz", "wall=quartz_block*3,quartz_bricks;base=smooth_quartz;floor=smooth_quartz;pillar=quartz_pillar;roof=smooth_quartz;stairs=quartz_stairs;slab=quartz_slab;trim=gold_block*1,chiseled_quartz_block*4;window=white_stained_glass_pane;light=sea_lantern;fence=diorite_wall;door=birch_door;path=smooth_quartz" },
		{ "prismarine", "wall=prismarine_bricks*3,prismarine,dark_prismarine;base=dark_prismarine;floor=prismarine_bricks;pillar=dark_prismarine;roof=dark_prismarine;stairs=dark_prismarine_stairs;slab=prismarine_brick_slab;trim=sea_lantern*1,dark_prismarine*5;window=light_blue_stained_glass_pane;light=sea_lantern;fence=prismarine_wall;door=warped_door;path=prismarine" },
		{ "oak", "wall=oak_planks*4,stripped_oak_log[axis=y];base=cobblestone*3,stone_bricks;floor=spruce_planks;pillar=oak_log[axis=y];roof=spruce_planks;stairs=spruce_stairs;slab=spruce_slab;trim=stripped_oak_log[axis=y];window=glass_pane;light=lantern;fence=oak_fence;door=oak_door;path=dirt_path" },
		{ "spruce", "wall=spruce_planks*4,stripped_spruce_log[axis=y];base=cobblestone*2,mossy_cobblestone;floor=dark_oak_planks;pillar=spruce_log[axis=y];roof=dark_oak_planks;stairs=dark_oak_stairs;slab=dark_oak_slab;trim=stripped_spruce_log[axis=y];window=glass_pane;light=lantern;fence=spruce_fence;door=spruce_door;path=coarse_dirt*2,gravel" },
		{ "blackstone", "wall=polished_blackstone_bricks*4,cracked_polished_blackstone_bricks,blackstone;base=blackstone;floor=polished_blackstone;pillar=chiseled_polished_blackstone;roof=blackstone;stairs=blackstone_stairs;slab=polished_blackstone_brick_slab;trim=gilded_blackstone*1,polished_blackstone*4;window=iron_bars;light=soul_lantern;fence=blackstone_wall;door=crimson_door;path=basalt" },
		{ "mossy", "wall=mossy_stone_bricks*3,mossy_cobblestone*2,cracked_stone_bricks,moss_block;base=mossy_cobblestone*2,cobblestone;floor=moss_block*2,coarse_dirt,mossy_cobblestone;pillar=mossy_stone_bricks;roof=moss_block;stairs=mossy_stone_brick_stairs;slab=mossy_stone_brick_slab;trim=mossy_cobblestone;window=air;light=lantern;fence=mossy_stone_brick_wall;door=jungle_door;path=moss_block*2,rooted_dirt" },
		{ "dungeon", "wall=mossy_cobblestone*3,cobblestone*3,cracked_stone_bricks;base=cobblestone;floor=mossy_cobblestone*2,cobblestone*2,gravel;pillar=stone_bricks;roof=cobblestone;stairs=cobblestone_stairs;slab=cobblestone_slab;trim=mossy_stone_bricks;window=iron_bars;light=soul_lantern;fence=cobblestone_wall;door=iron_door;path=gravel" },
		{ "hedge", "wall=oak_leaves[persistent=true]*3,azalea_leaves[persistent=true],flowering_azalea_leaves[persistent=true];base=dirt;floor=grass_block;pillar=oak_log[axis=y];roof=oak_leaves[persistent=true];stairs=oak_stairs;slab=oak_slab;trim=stone_bricks;window=air;light=lantern;fence=oak_fence;door=oak_door;path=dirt_path*3,coarse_dirt" },
		{ "ice", "wall=packed_ice*3,blue_ice,snow_block;base=packed_ice;floor=snow_block;pillar=blue_ice;roof=snow_block;stairs=quartz_stairs;slab=snow;trim=blue_ice;window=light_blue_stained_glass_pane;light=sea_lantern;fence=diorite_wall;door=birch_door;path=snow_block" },
		{ "gold", "wall=gold_block*3,yellow_terracotta;base=smooth_sandstone;floor=smooth_quartz;pillar=gold_block;roof=orange_terracotta;stairs=smooth_sandstone_stairs;slab=smooth_sandstone_slab;trim=lapis_block;window=yellow_stained_glass_pane;light=glowstone;fence=sandstone_wall;door=birch_door;path=sandstone" },
	};
	static Map<String, Map<String, BlockState[]>> palCache;

	static Map<String, BlockState[]> palette(String name, HolderLookup<Block> blocks) {
		if (palCache == null) palCache = new HashMap<>();
		return palCache.computeIfAbsent(name, n -> {
			String spec = null;
			for (String[] p : PALETTES) if (p[0].equals(n)) spec = p[1];
			if (spec == null) spec = "wall=" + n; // a single block id = everything in that block
			Map<String, BlockState[]> m = new HashMap<>();
			for (String part : spec.split(";")) { String[] kv = part.split("=", 2); m.put(kv[0], states(kv[1], blocks)); }
			return m;
		});
	}

	/** "stone_bricks*3,mossy_stone_bricks" -> weighted state array. */
	static BlockState[] states(String spec, HolderLookup<Block> blocks) {
		List<BlockState> out = new ArrayList<>();
		for (String s : spec.split(",(?![^\\[]*\\])")) {
			int w = 1; String id = s.trim();
			int star = id.lastIndexOf('*');
			if (star > 0 && id.indexOf(']', star) < 0) { w = Integer.parseInt(id.substring(star + 1)); id = id.substring(0, star); }
			BlockState st = state(id, blocks);
			for (int i = 0; i < w; i++) out.add(st);
		}
		return out.toArray(new BlockState[0]);
	}

	static BlockState state(String id, HolderLookup<Block> blocks) {
		try { return BlockStateParser.parseForBlock(blocks, id.contains(":") ? id : "minecraft:" + id, false).blockState(); }
		catch (Exception e) { throw new IllegalArgumentException("bad block '" + id + "': " + e.getMessage()); }
	}

	static final Map<String, String> FALLBACK = Map.of("base", "wall", "floor", "base", "pillar", "wall", "trim", "pillar", "roof", "wall", "path", "floor", "slab", "roof", "fence", "wall", "window", "wall", "light", "trim");

	// ================================================= build context =================================================
	/** Local frame: +Z = away from the player (facing), entrance at -Z. */
	public static final class Ctx {
		final WorldGenLevel lv; final Op op; final RandomSource rnd; final HolderLookup<Block> blocks;
		Map<String, BlockState[]> pal; Map<String, String> args;
		double decay; int ox, oy, oz; Rotation rot = Rotation.NONE; Heightmap.Types hm = Heightmap.Types.MOTION_BLOCKING_NO_LEAVES; int count;
		Ctx(WorldGenLevel lv, Op op, RandomSource rnd, HolderLookup<Block> blocks) { this.lv = lv; this.op = op; this.rnd = rnd; this.blocks = blocks; }

		BlockPos w(int x, int y, int z) {
			return switch (rot) {
				case NONE -> new BlockPos(ox + x, oy + y, oz + z);
				case CLOCKWISE_90 -> new BlockPos(ox - z, oy + y, oz + x);
				case CLOCKWISE_180 -> new BlockPos(ox - x, oy + y, oz - z);
				case COUNTERCLOCKWISE_90 -> new BlockPos(ox + z, oy + y, oz - x);
			};
		}
		void raw(BlockPos p, BlockState s) {
			if (lv.getBlockState(p) == s) return;
			if (op != null) { op.record(p); if (!s.getFluidState().isEmpty()) op.fluids = true; }
			lv.setBlock(p, s, FLAGS);
			count++;
		}
		void set(int x, int y, int z, BlockState s) { raw(w(x, y, z), s.rotate(rot)); }
		BlockState pick(String role) {
			BlockState[] a = pal.get(role);
			String r = role;
			while (a == null && FALLBACK.containsKey(r)) { r = FALLBACK.get(r); a = pal.get(r); }
			if (a == null) a = pal.values().iterator().next();
			return a[rnd.nextInt(a.length)];
		}
		BlockState role(String role) { String o = args.get(role); return o != null ? state(o, blocks) : pick(role); }
		boolean decayed(int y) { return decay > 0 && rnd.nextFloat() < decay * Math.min(1.6, 0.35 + Math.max(0, y) / 12.0); }
		void put(int x, int y, int z, String role) { if (!decayed(y)) set(x, y, z, args.containsKey(role) ? role(role) : pick(role)); }
		void air(int x, int y, int z) { BlockPos p = w(x, y, z); if (!lv.getBlockState(p).isAir()) raw(p, Blocks.AIR.defaultBlockState()); }
		void box(int x0, int y0, int z0, int x1, int y1, int z1, String role) {
			for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++)
				if (role == null) air(x, y, z); else put(x, y, z, role);
		}
		/** Fills under the footprint down to solid ground (max 16) so builds don't float on slopes. */
		void foundation(int x0, int z0, int x1, int z1, String role) {
			for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++)
				for (int y = -1; y >= -16; y--) { BlockState s = lv.getBlockState(w(x, y, z)); if (!s.canBeReplaced() && s.getFluidState().isEmpty()) break; set(x, y, z, pick(role)); }
		}
		int ground(int x, int z) { BlockPos p = w(x, 0, z); return lv.getHeight(hm, p.getX(), p.getZ()) - oy; }
		void shift(int dx, int dy, int dz, Runnable r) {
			int sx = ox, sy = oy, sz = oz; BlockPos o = w(dx, dy, dz); ox = o.getX(); oy = o.getY(); oz = o.getZ();
			try { r.run(); } finally { ox = sx; oy = sy; oz = sz; }
		}
		int i(String k, int d) { try { return args.containsKey(k) ? (int) Math.round(Double.parseDouble(args.get(k))) : d; } catch (NumberFormatException e) { return d; } }
		double d(String k, double d) { try { return args.containsKey(k) ? Double.parseDouble(args.get(k)) : d; } catch (NumberFormatException e) { return d; } }
		String s(String k, String d) { return args.getOrDefault(k, d); }
		void chest(int x, int y, int z, Direction face, String loot) {
			BlockPos p = w(x, y, z);
			raw(p, Blocks.CHEST.defaultBlockState().setValue(net.minecraft.world.level.block.ChestBlock.FACING, rot.rotate(face)));
			if (loot != null && !loot.equals("none"))
				RandomizableContainer.setBlockEntityLootTable(lv, rnd, p, ResourceKey.create(Registries.LOOT_TABLE, Identifier.parse(loot.contains(":") ? loot : "minecraft:" + loot)));
		}
		void spawner(int x, int y, int z, String mob) {
			BlockPos p = w(x, y, z);
			raw(p, Blocks.SPAWNER.defaultBlockState());
			var type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(mob.contains(":") ? mob : "minecraft:" + mob));
			if (lv.getBlockEntity(p) instanceof SpawnerBlockEntity sp) { sp.setEntityId(type, rnd); sp.setChanged(); }
		}
		BlockState with(String id) { return state(id, blocks); }
	}

	// ================================================= builders ======================================================
	public static final List<String> KINDS = List.of("tower", "castle", "dungeon", "ruins", "temple", "house", "bridge", "arena", "statue", "portal", "maze", "chest", "spawner");
	static final Map<String, String> DEF_PALETTE = Map.of("dungeon", "dungeon", "temple", "sandstone", "house", "oak", "maze", "hedge", "ruins", "mossy", "portal", "blackstone", "statue", "quartz");

	/** Rough half-size of the footprint, to place a build in front of the player without touching them. */
	static int reach(String kind, Ctx c) {
		return switch (kind) {
			case "castle" -> c.i("size", 27) / 2 + 3;
			case "tower" -> c.i("size", 7) / 2 + 1;
			case "dungeon" -> 4; case "chest", "spawner" -> 1;
			case "temple", "ruins" -> c.i("size", kind.equals("temple") ? 21 : 15) / 2;
			case "house" -> Math.max(c.i("width", 7), c.i("depth", 9)) / 2 + 1;
			case "bridge" -> 1;
			case "arena" -> c.i("size", 14) + 6;
			case "statue" -> c.i("size", 16) / 3;
			case "portal" -> 2;
			case "maze" -> 1;
			default -> 8;
		};
	}

	static void build(Ctx c, String kind) {
		switch (kind) {
			case "tower" -> tower(c, c.i("size", 7) / 2, c.i("height", 16), c.s("roof", "cone"));
			case "castle" -> castle(c);
			case "dungeon" -> dungeon(c);
			case "ruins" -> ruins(c);
			case "temple", "pyramid" -> temple(c);
			case "house" -> house(c);
			case "bridge" -> bridge(c);
			case "arena" -> arena(c);
			case "statue" -> statue(c);
			case "portal" -> portal(c);
			case "maze" -> maze(c);
			case "chest" -> c.chest(0, 0, 0, Direction.NORTH, c.s("loot", "minecraft:chests/simple_dungeon"));
			case "spawner" -> c.spawner(0, 0, 0, c.s("mob", "zombie"));
			default -> throw new IllegalArgumentException("unknown build " + kind + " (" + KINDS + ")");
		}
	}

	static void tower(Ctx c, int r, int h, String roof) {
		r = Math.max(2, r);
		c.foundation(-r, -r, r, r, "base");
		for (int y = 0; y < h; y++) for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
			double d = Math.sqrt(x * x + z * z);
			if (d > r + 0.5) continue;
			boolean wall = d > r - 0.5;
			if (wall) {
				boolean win = y % 5 == 2 && (x == 0 || z == 0) && y > 1;
				if (win) c.set(x, y, z, c.pick("window")); else c.put(x, y, z, y % 5 == 0 ? "trim" : "wall");
			} else if (y % 5 == 0) c.put(x, y, z, "floor");
			else c.air(x, y, z);
		}
		c.air(0, 1, -r); c.air(0, 2, -r);
		door(c, 0, 1, -r);
		BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(net.minecraft.world.level.block.LadderBlock.FACING, Direction.NORTH);
		for (int y = 1; y <= h; y++) c.set(0, y, r - 1, ladder);
		for (int y = 0; y < h; y += 5) if (r >= 3) c.set(-(r - 1), y + 1, 0, c.pick("light"));
		for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) if (x * x + z * z <= (r + 0.5) * (r + 0.5) && !(x == 0 && z == r - 1)) c.put(x, h, z, "floor");
		if (roof.equals("cone")) {
			int rh = r + 3;
			for (int i = 0; i < rh; i++) {
				double rad = (r + 1.2) * (1 - i / (double) rh);
				for (int x = -r - 1; x <= r + 1; x++) for (int z = -r - 1; z <= r + 1; z++) {
					double d = Math.sqrt(x * x + z * z);
					if (d <= rad + 0.5 && d > rad - 1.1) c.put(x, h + 1 + i, z, "roof");
				}
			}
			c.set(0, h + 1 + rh, 0, c.pick("light"));
		} else {
			for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
				double d = Math.sqrt(x * x + z * z);
				if (d <= r + 0.5 && d > r - 0.5 && ((x + z) & 1) == 0) c.put(x, h + 1, z, "wall");
			}
		}
	}

	static void door(Ctx c, int x, int y, int z) {
		BlockState d = c.pick("door");
		if (!(d.getBlock() instanceof net.minecraft.world.level.block.DoorBlock)) return;
		var F = net.minecraft.world.level.block.DoorBlock.FACING; var H = net.minecraft.world.level.block.DoorBlock.HALF;
		c.set(x, y, z, d.setValue(F, Direction.NORTH).setValue(H, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER));
		c.set(x, y + 1, z, d.setValue(F, Direction.NORTH).setValue(H, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
	}

	static void castle(Ctx c) {
		int h = c.i("size", 27) / 2, wh = c.i("height", 7);
		c.foundation(-h, -h, h, h, "base");
		for (int x = -h; x <= h; x++) for (int z = -h; z <= h; z++) {
			int m = Math.max(Math.abs(x), Math.abs(z));
			if (m >= h - 1) {
				for (int y = 0; y < wh; y++) c.put(x, y, z, y == 0 ? "base" : "wall");
				c.put(x, wh, z, "floor");
				if (m == h && ((x + z) & 1) == 0) c.put(x, wh + 1, z, "wall");
			} else {
				c.put(x, -1, z, (Math.abs(x) <= 1 || Math.abs(z) <= 1) ? "path" : "floor");
				for (int y = 0; y < wh + 3; y++) c.air(x, y, z);
			}
		}
		for (int x = -2; x <= 2; x++) for (int y = 0; y <= 4; y++) for (int z = -h; z <= -h + 1; z++) if (Math.abs(x) <= 1 || y <= 3) c.air(x, y, z);
		for (int x = -1; x <= 1; x++) c.set(x, 4, -h - 1, c.pick("fence"));
		int th = wh + 5;
		for (int sx = -1; sx <= 1; sx += 2) for (int sz = -1; sz <= 1; sz += 2) { final int fx = sx * h, fz = sz * h; c.shift(fx, 0, fz, () -> tower(c, 3, th, c.s("roof", "flat"))); }
		for (int sx = -1; sx <= 1; sx += 2) { final int fx = sx * 4; c.shift(fx, 0, -h, () -> tower(c, 2, wh + 3, "flat")); }
		for (int x = -2; x <= 2; x++) for (int y = 0; y <= 4; y++) for (int z = -h - 1; z <= -h + 2; z++) if (Math.abs(x) <= 1 && y >= 1 && y <= 3) c.air(x, y, z);
		// keep
		int kx = Math.max(3, h / 3), kz0 = 0, kz1 = Math.max(6, h - 4), kh = wh + 6;
		for (int x = -kx; x <= kx; x++) for (int z = kz0; z <= kz1; z++) for (int y = 0; y <= kh; y++) {
			boolean edge = Math.abs(x) == kx || z == kz0 || z == kz1;
			if (edge) { boolean corner = Math.abs(x) == kx && (z == kz0 || z == kz1); c.put(x, y, z, corner ? "pillar" : (y % 4 == 2 && (x & 1) == 0 && !corner ? "window" : "wall")); }
			else if (y == 0 || y == kh / 2 || y == kh) c.put(x, y, z, "floor"); else c.air(x, y, z);
		}
		for (int x = -kx; x <= kx; x++) for (int z = kz0; z <= kz1; z++) if ((Math.abs(x) == kx || z == kz0 || z == kz1) && ((x + z) & 1) == 0) c.put(x, kh + 1, z, "wall");
		c.air(0, 1, kz0); c.air(0, 2, kz0); door(c, 0, 1, kz0);
		c.set(0, 1, kz1 - 1, c.pick("light")); c.chest(1, 1, kz1 - 1, Direction.NORTH, c.s("loot", "minecraft:chests/village/village_weaponsmith"));
		for (int i = -h + 3; i <= h - 3; i += 6) { c.set(i, wh + 1, -h + 1, c.pick("light")); c.set(i, wh + 1, h - 1, c.pick("light")); c.set(-h + 1, wh + 1, i, c.pick("light")); c.set(h - 1, wh + 1, i, c.pick("light")); }
		BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(net.minecraft.world.level.block.LadderBlock.FACING, Direction.SOUTH);
		for (int y = 0; y <= wh; y++) c.set(h - 4, y, -h + 2, ladder);
	}

	static void dungeon(Ctx c) {
		int w = c.i("size", 9) / 2, hg = c.i("height", 5), rooms = c.i("rooms", 3), gap = 6;
		String[] mobs = c.s("mob", "zombie,skeleton,spider").split(",");
		String loot = c.s("loot", "minecraft:chests/simple_dungeon");
		for (int i = 0; i < rooms; i++) {
			int cz = i * (2 * w + 1 + gap), ww = i == rooms - 1 && rooms > 1 ? w + 2 : w;
			for (int x = -ww - 1; x <= ww + 1; x++) for (int z = cz - ww - 1; z <= cz + ww + 1; z++) for (int y = -1; y <= hg; y++) {
				boolean shell = Math.abs(x) == ww + 1 || Math.abs(z - cz) == ww + 1 || y == -1 || y == hg;
				if (shell) c.set(x, y, z, c.pick(y == -1 ? "floor" : "wall")); else c.air(x, y, z);
			}
			for (int sx = -1; sx <= 1; sx += 2) for (int sz = -1; sz <= 1; sz += 2) for (int y = 0; y < hg; y++) c.set(sx * ww, y, cz + sz * ww, c.pick("pillar"));
			c.spawner(0, 0, cz, mobs[i % mobs.length].trim());
			if (i == rooms - 1 && rooms > 1) { c.spawner(-2, 0, cz + 2, mobs[(i + 1) % mobs.length].trim()); c.spawner(2, 0, cz + 2, mobs[(i + 2) % mobs.length].trim()); }
			c.chest(-ww, 0, cz, Direction.EAST, loot);
			if (i % 2 == 1 || i == rooms - 1) c.chest(ww, 0, cz, Direction.WEST, i == rooms - 1 ? c.s("bossloot", "minecraft:chests/ancient_city") : loot);
			c.set(0, hg - 1, cz - ww, c.pick("light"));
			if (i < rooms - 1) {
				int z0 = cz + w + 1, z1 = cz + w + gap + 1;
				for (int z = z0; z <= z1; z++) for (int x = -2; x <= 2; x++) for (int y = -1; y <= 3; y++) {
					boolean shell = Math.abs(x) == 2 || y == -1 || y == 3;
					if (shell) c.set(x, y, z, c.pick(y == -1 ? "floor" : "wall")); else c.air(x, y, z);
				}
				c.set(0, 2, (z0 + z1) / 2, c.pick("light"));
			}
		}
		// staircase up to the surface, toward the player (-Z)
		BlockState st = c.pick("stairs");
		if (!st.hasProperty(net.minecraft.world.level.block.StairBlock.FACING)) st = Blocks.COBBLESTONE_STAIRS.defaultBlockState();
		st = st.setValue(net.minecraft.world.level.block.StairBlock.FACING, Direction.NORTH);
		for (int x = -1; x <= 1; x++) for (int y = 0; y <= 2; y++) c.air(x, y, -w - 1);
		for (int k = 0; k < 64; k++) {
			int z = -w - 2 - k, y = k, g = c.ground(0, z);
			for (int x = -2; x <= 2; x++) for (int yy = y - 1; yy <= y + 4; yy++) {
				boolean shell = Math.abs(x) == 2 || yy == y - 1 || yy == y + 4;
				if (shell) { if (!c.lv.getBlockState(c.w(x, yy, z)).isSolidRender()) c.set(x, yy, z, c.pick("wall")); }
				else if (yy == y) c.set(x, yy, z, st); else c.air(x, yy, z);
			}
			if (k % 6 == 3) c.set(-1, y + 3, z, c.pick("light"));
			if (y + 1 >= g) { for (int x = -2; x <= 2; x++) for (int yy = y + 1; yy <= y + 5; yy++) c.air(x, yy, z); break; }
		}
	}

	static void ruins(Ctx c) {
		int h = c.i("size", 15) / 2;
		if (!c.args.containsKey("decay")) c.decay = 0.4;
		for (int x = -h; x <= h; x++) for (int z = -h; z <= h; z++) if (c.rnd.nextFloat() < 0.45) c.set(x, c.ground(x, z) - 1, z, c.pick(c.rnd.nextBoolean() ? "floor" : "path"));
		int segs = Math.max(3, h / 2 + 2);
		for (int i = 0; i < segs; i++) {
			boolean alongX = c.rnd.nextBoolean();
			int sx = c.rnd.nextInt(2 * h + 1) - h, sz = c.rnd.nextInt(2 * h + 1) - h, len = 3 + c.rnd.nextInt(6), hh = 2 + c.rnd.nextInt(4);
			for (int k = 0; k < len; k++) {
				int x = alongX ? sx + k : sx, z = alongX ? sz : sz + k;
				if (Math.abs(x) > h || Math.abs(z) > h) continue;
				int g = c.ground(x, z), top = hh - Math.abs(k - len / 2) / 2;
				for (int y = 0; y < top; y++) c.put(x, g + y, z, y == 1 && k % 3 == 1 ? "window" : "wall");
			}
		}
		for (int i = 0; i < Math.max(2, h / 3); i++) {
			int x = c.rnd.nextInt(2 * h - 1) - h + 1, z = c.rnd.nextInt(2 * h - 1) - h + 1, g = c.ground(x, z), ph = 2 + c.rnd.nextInt(5);
			for (int y = 0; y < ph; y++) c.put(x, g + y, z, "pillar");
			if (ph > 4) c.put(x, g + ph, z, "slab");
		}
		int x = c.rnd.nextInt(Math.max(1, h)) - h / 2, z = c.rnd.nextInt(Math.max(1, h)) - h / 2;
		c.chest(x, c.ground(x, z), z, Direction.NORTH, c.s("loot", "minecraft:chests/simple_dungeon"));
	}

	static void temple(Ctx c) {
		int h = c.i("size", 21) / 2;
		c.foundation(-h, -h, h, h, "base");
		for (int y = 0; y <= h; y++) {
			int rad = h - y;
			for (int x = -rad; x <= rad; x++) for (int z = -rad; z <= rad; z++) {
				int m = Math.max(Math.abs(x), Math.abs(z));
				if (y == 0) c.put(x, 0, z, m >= rad - 1 ? "wall" : "floor");
				else if (m >= rad - 1) c.put(x, y, z, y % 3 == 0 && m == rad ? "trim" : "wall");
				else c.air(x, y, z);
			}
		}
		for (int x = -1; x <= 1; x++) for (int y = 1; y <= 3; y++) for (int z = -h; z <= -h + 3; z++) c.air(x, y, z);
		c.set(-2, 2, -h + 1, c.pick("light")); c.set(2, 2, -h + 1, c.pick("light"));
		BlockState st = c.pick("stairs");
		if (st.hasProperty(net.minecraft.world.level.block.StairBlock.FACING)) {
			st = st.setValue(net.minecraft.world.level.block.StairBlock.FACING, Direction.SOUTH);
			for (int y = 4; y <= h; y++) for (int x = -1; x <= 1; x++) c.set(x, y, -(h - y) - 1, st);
		}
		int ip = Math.max(2, h / 3);
		for (int sx = -1; sx <= 1; sx += 2) for (int sz = -1; sz <= 1; sz += 2) for (int y = 1; y < h - ip; y++) c.put(sx * ip, y, sz * ip, "pillar");
		for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) c.put(x, 1, z, "trim");
		c.set(0, 2, 0, c.pick("light"));
		c.chest(0, 1, Math.max(2, h - 3), Direction.NORTH, c.s("loot", "minecraft:chests/desert_pyramid"));
		c.chest(0, -1, 0, Direction.NORTH, c.s("loot", "minecraft:chests/desert_pyramid"));
		for (int sx = -1; sx <= 1; sx += 2) for (int sz = -1; sz <= 1; sz += 2) for (int y = 1; y <= 3; y++) c.put(sx, h + y, sz, "pillar");
		for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) c.put(x, h + 4, z, "slab");
		c.set(0, h + 1, 0, c.pick("light"));
	}

	static void house(Ctx c) {
		int hw = c.i("width", 7) / 2, hd = c.i("depth", 9) / 2, wh = c.i("height", 4);
		c.foundation(-hw, -hd, hw, hd, "base");
		for (int x = -hw; x <= hw; x++) for (int z = -hd; z <= hd; z++) {
			c.put(x, 0, z, Math.abs(x) == hw || Math.abs(z) == hd ? "base" : "floor");
			for (int y = 1; y <= wh; y++) {
				boolean ex = Math.abs(x) == hw, ez = Math.abs(z) == hd;
				if (ex && ez) c.put(x, y, z, "pillar");
				else if (ex || ez) {
					boolean win = (y == 2 || (y == 3 && wh >= 4)) && ((ex && Math.abs(z) % 3 == 1) || (ez && Math.abs(x) % 3 == 2));
					c.put(x, y, z, win ? "window" : y == wh ? "trim" : "wall");
				} else c.air(x, y, z);
			}
		}
		c.air(0, 1, -hd); c.air(0, 2, -hd); door(c, 0, 1, -hd);
		BlockState st = c.pick("stairs");
		var F = net.minecraft.world.level.block.StairBlock.FACING;
		for (int i = 0; ; i++) {
			int xl = -hw - 1 + i, xr = hw + 1 - i, y = wh + 1 + i;
			if (xl > xr) break;
			for (int z = -hd - 1; z <= hd + 1; z++) {
				if (xl == xr) c.put(xl, y, z, "slab");
				else if (st.hasProperty(F)) { c.set(xl, y, z, st.setValue(F, Direction.EAST)); c.set(xr, y, z, st.setValue(F, Direction.WEST)); }
				else { c.put(xl, y, z, "roof"); c.put(xr, y, z, "roof"); }
			}
			for (int x = xl + 1; x < xr; x++) { c.put(x, y, -hd, "wall"); c.put(x, y, hd, "wall"); for (int z = -hd + 1; z < hd; z++) c.air(x, y, z); }
			if (xr - xl <= 2 && xl != xr) { for (int x = xl + 1; x < xr; x++) for (int z = -hd - 1; z <= hd + 1; z++) c.put(x, y + 1, z, "slab"); break; }
		}
		c.set(hw - 1, 1, hd - 1, Blocks.CRAFTING_TABLE.defaultBlockState());
		c.set(hw - 1, 1, hd - 2, Blocks.FURNACE.defaultBlockState().setValue(net.minecraft.world.level.block.FurnaceBlock.FACING, Direction.WEST));
		c.chest(-hw + 1, 1, hd - 1, Direction.EAST, c.s("loot", "minecraft:chests/village/village_plains_house"));
		BlockState lamp = c.pick("light");
		if (lamp.hasProperty(net.minecraft.world.level.block.LanternBlock.HANGING)) lamp = lamp.setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true);
		c.set(0, wh, 0, lamp);
		for (int z = -hd - 3; z < -hd; z++) c.set(0, c.ground(0, z) - 1, z, c.pick("path"));
		for (int y = 1; y <= wh + hw + 2; y++) c.set(hw - 1, y, hd, c.pick("base"));
	}

	static void bridge(Ctx c) {
		int len = c.i("length", 24), hw = c.i("width", 5) / 2;
		for (int z = 0; z <= len; z++) {
			for (int x = -hw; x <= hw; x++) { c.put(x, 0, z, "floor"); for (int y = 1; y <= 3; y++) c.air(x, y, z); }
			for (int sx = -1; sx <= 1; sx += 2) {
				c.put(sx * (hw + 1), 0, z, "trim");
				c.set(sx * (hw + 1), 1, z, c.pick("fence"));
				if (z % 6 == 0) c.set(sx * (hw + 1), 2, z, c.pick("light"));
			}
			if (z % 8 == 4 && z > 0 && z < len) for (int x = -hw; x <= hw; x++) for (int y = -1; y >= -48; y--) {
				BlockState s = c.lv.getBlockState(c.w(x, y, z));
				if (!s.canBeReplaced() && s.getFluidState().isEmpty()) break;
				c.put(x, y, z, Math.abs(x) == hw ? "pillar" : "wall");
			}
			int ph = z % 8 - 4, arch = 3 - (int) Math.round(Math.sqrt(Math.max(0, 16 - ph * ph)) * 0.75);
			if (z > 4 && z < len - 4) for (int y = -1; y >= -Math.max(1, arch); y--) { c.put(-hw, y, z, "wall"); c.put(hw, y, z, "wall"); }
		}
	}

	static void arena(Ctx c) {
		int r = c.i("size", 14);
		for (int x = -r - 6; x <= r + 6; x++) for (int z = -r - 6; z <= r + 6; z++) {
			double d = Math.sqrt(x * x + z * z);
			if (d > r + 5.5) continue;
			for (int y = -1; y >= -10; y--) { BlockState s = c.lv.getBlockState(c.w(x, y, z)); if (!s.canBeReplaced() && s.getFluidState().isEmpty()) break; c.set(x, y, z, c.pick("base")); }
			if (d <= r - 0.5) {
				c.set(x, -1, z, (Math.abs(x) <= 1 || Math.abs(z) <= 1 || Math.abs(d - r / 2.0) < 0.6) ? c.pick("trim") : c.with("sand"));
				for (int y = 0; y < 10; y++) c.air(x, y, z);
			} else if (d <= r + 0.5) { for (int y = -1; y <= 2; y++) c.put(x, y, z, y == 2 ? "trim" : "wall"); for (int y = 3; y < 10; y++) c.air(x, y, z); }
			else if (d <= r + 4.5) { int t = (int) Math.round(d - r); for (int y = -1; y <= t + 1; y++) c.put(x, y, z, y == t + 1 ? "floor" : "base"); for (int y = t + 2; y < 10; y++) c.air(x, y, z); }
			else { for (int y = -1; y <= 8; y++) c.put(x, y, z, "wall"); int a = (int) Math.round(Math.atan2(z, x) * r); if ((a & 1) == 0) c.put(x, 9, z, "wall"); }
		}
		for (Direction dir : new Direction[] { Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST })
			for (int k = r; k <= r + 6; k++) for (int s = -1; s <= 1; s++) for (int y = 0; y <= 3; y++) {
				int x = dir.getStepX() * k + (dir.getStepX() == 0 ? s : 0), z = dir.getStepZ() * k + (dir.getStepZ() == 0 ? s : 0);
				if (y == 0) c.set(x, -1, z, c.pick("path"));
				c.air(x, y, z);
			}
		for (int i = 0; i < 16; i++) { double a = i * Math.PI / 8; int x = (int) Math.round(Math.cos(a) * (r + 5)), z = (int) Math.round(Math.sin(a) * (r + 5)); c.set(x, 10, z, c.pick("light")); }
		for (int y = -1; y <= 1; y++) c.put(0, y, 0, "pillar");
		c.set(0, 2, 0, c.pick("light"));
	}

	static void statue(Ctx c) {
		int H = c.i("size", 16); double u = H / 32.0;
		String pose = c.s("pose", "stand");
		List<int[]> parts = new ArrayList<>(); // x0 x1 y0 y1 z0 z1 (pixels) + role index
		parts.add(new int[] { -4, 0, 0, 12, -2, 2, 0 }); parts.add(new int[] { 0, 4, 0, 12, -2, 2, 0 });
		parts.add(new int[] { -4, 4, 12, 24, -2, 2, 1 });
		parts.add(new int[] { -4, 4, 24, 32, -4, 4, 2 });
		switch (pose) {
			case "raise" -> { parts.add(new int[] { -8, -4, 12, 24, -2, 2, 1 }); parts.add(new int[] { 4, 8, 22, 36, -2, 2, 1 }); }
			case "wide" -> { parts.add(new int[] { -16, -4, 20, 24, -2, 2, 1 }); parts.add(new int[] { 4, 16, 20, 24, -2, 2, 1 }); }
			default -> { parts.add(new int[] { -8, -4, 12, 24, -2, 2, 1 }); parts.add(new int[] { 4, 8, 12, 24, -2, 2, 1 }); }
		}
		String[] roles = { "pillar", "wall", "trim" };
		int pr = (int) Math.ceil(10 * u) + 1, base = 3;
		for (int x = -pr; x <= pr; x++) for (int z = -pr; z <= pr; z++) for (int y = -base; y < 0; y++) c.put(x, y, z, y == -1 ? "trim" : "base");
		c.foundation(-pr, -pr, pr, pr, "base");
		for (int i = 0; i < 4; i++) c.set(i < 2 ? -pr : pr, 0, i % 2 == 0 ? -pr : pr, c.pick("light"));
		int span = (int) Math.ceil(17 * u), top = (int) Math.ceil(37 * u);
		BlockState eye = c.with(c.s("eyes", "black_concrete"));
		BlockState mat = c.args.containsKey("block") ? c.with(c.s("block", "stone")) : null;
		for (int bx = -span; bx <= span; bx++) for (int by = 0; by <= top; by++) for (int bz = -span; bz <= span; bz++) {
			double px = (bx + 0.5) / u, py = (by + 0.5) / u, pz = (bz + 0.5) / u;
			for (int[] p : parts) if (px >= p[0] && px < p[1] && py >= p[2] && py < p[3] && pz >= p[4] && pz < p[5]) {
				boolean face = p[6] == 2 && pz < -4 + 1 / u && py >= 26 && py < 28 && ((px >= -3 && px < -1) || (px >= 1 && px < 3));
				c.set(bx, by, bz, face ? eye : mat != null ? mat : c.pick(roles[p[6]]));
				break;
			}
		}
	}

	static void portal(Ctx c) {
		String style = c.s("style", "nether");
		if (style.equals("ring")) {
			int r = c.i("size", 4);
			BlockState fill = c.args.containsKey("fill") ? c.with(c.s("fill", "air")) : null;
			for (int x = -r - 1; x <= r + 1; x++) for (int y = 0; y <= 2 * r + 2; y++) {
				double d = Math.sqrt(x * x + (y - r - 1) * (y - r - 1));
				if (Math.abs(d - r) < 0.7) c.put(x, y, 0, (y == 0 || y == 2 * r + 2 || x == 0) ? "trim" : "wall");
				else if (d < r - 0.3) { if (fill != null) c.set(x, y, 0, fill); else c.air(x, y, 0); }
			}
			for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) c.put(x, -1, z, "floor");
			c.set(-r - 2, 0, 0, c.pick("pillar")); c.set(r + 2, 0, 0, c.pick("pillar")); c.set(-r - 2, 1, 0, c.pick("light")); c.set(r + 2, 1, 0, c.pick("light"));
			return;
		}
		int w = Math.max(4, c.i("width", 4)), h = Math.max(5, c.i("height", 5)), x0 = -w / 2, x1 = x0 + w - 1;
		BlockState frame = c.with(c.s("block", "obsidian"));
		for (int x = x0; x <= x1; x++) for (int y = 0; y < h; y++) {
			boolean edge = x == x0 || x == x1 || y == 0 || y == h - 1;
			if (edge) c.set(x, y, 0, frame);
			else if (!c.s("lit", "true").equals("false")) c.set(x, y, 0, Blocks.NETHER_PORTAL.defaultBlockState().setValue(net.minecraft.world.level.block.NetherPortalBlock.AXIS, Direction.Axis.X));
			else c.air(x, y, 0);
		}
		for (int x = x0 - 2; x <= x1 + 2; x++) for (int z = -3; z <= 2; z++) c.put(x, -1, z, Math.abs(z) <= 1 && x >= x0 && x <= x1 ? "trim" : "floor");
		for (int s = 0; s < 2; s++) { int x = s == 0 ? x0 - 1 : x1 + 1; for (int y = 0; y < h - 1; y++) c.put(x, y, 0, "pillar"); c.set(x, h - 1, 0, c.pick("light")); }
		BlockState st = c.pick("stairs");
		if (st.hasProperty(net.minecraft.world.level.block.StairBlock.FACING)) for (int x = x0 - 1; x <= x1 + 1; x++) c.set(x, h, 0, st.setValue(net.minecraft.world.level.block.StairBlock.FACING, Direction.NORTH).setValue(net.minecraft.world.level.block.StairBlock.HALF, net.minecraft.world.level.block.state.properties.Half.TOP));
	}

	static void maze(Ctx c) {
		int n = Math.max(3, Math.min(40, c.i("size", 9))), wh = c.i("height", 3), G = 2 * n + 1;
		boolean[][] open = new boolean[G][G];
		Deque<int[]> stack = new ArrayDeque<>(); stack.push(new int[] { 1, 1 }); open[1][1] = true;
		int[][] dirs = { { 2, 0 }, { -2, 0 }, { 0, 2 }, { 0, -2 } };
		while (!stack.isEmpty()) {
			int[] cur = stack.peek(); List<int[]> nb = new ArrayList<>();
			for (int[] d : dirs) { int nx = cur[0] + d[0], nz = cur[1] + d[1]; if (nx > 0 && nz > 0 && nx < G - 1 && nz < G - 1 && !open[nx][nz]) nb.add(new int[] { nx, nz, d[0] / 2, d[1] / 2 }); }
			if (nb.isEmpty()) { stack.pop(); continue; }
			int[] p = nb.get(c.rnd.nextInt(nb.size()));
			open[cur[0] + p[2]][cur[1] + p[3]] = true; open[p[0]][p[1]] = true; stack.push(new int[] { p[0], p[1] });
		}
		open[1][0] = true; open[G - 2][G - 1] = true;
		int[] off = new int[G + 1];
		for (int g = 0; g < G; g++) off[g + 1] = off[g] + (g % 2 == 1 ? 2 : 1);
		int total = off[G], x0 = -total / 2;
		for (int gx = 0; gx < G; gx++) for (int gz = 0; gz < G; gz++) for (int bx = off[gx]; bx < off[gx + 1]; bx++) for (int bz = off[gz]; bz < off[gz + 1]; bz++) {
			int x = x0 + bx, z = bz;
			c.put(x, -1, z, open[gx][gz] ? "path" : "base");
			for (int y = 0; y < wh; y++) if (open[gx][gz]) c.air(x, y, z); else c.put(x, y, z, "wall");
			if (!open[gx][gz] && gx % 4 == 0 && gz % 4 == 0) c.set(x, wh, z, c.pick("light"));
		}
		int m = (n / 2) * 2 + 1;
		c.chest(x0 + off[m], 0, off[m], Direction.NORTH, c.s("loot", "minecraft:chests/simple_dungeon"));
	}

	// ================================================= terrain =======================================================
	static SimplexNoise noise(Ctx c) { return new SimplexNoise(RandomSource.create(c.i("seed", 1234))); }
	static boolean soft(BlockState s) { return s.canBeReplaced() || !s.getFluidState().isEmpty() || s.is(net.minecraft.tags.BlockTags.LEAVES) || s.is(net.minecraft.tags.BlockTags.LOGS); }

	/** Top solid y of a world column (ignoring leaves/logs/plants). */
	static int top(Ctx c, int x, int z) {
		int y = c.lv.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c.ox + x, c.oz + z) - 1;
		while (y > c.lv.getMinY() && soft(c.lv.getBlockState(new BlockPos(c.ox + x, y, c.oz + z)))) y--;
		return y;
	}

	static void column(Ctx c, int x, int z, int from, int to, BlockState topB, BlockState fill) {
		// world-absolute heights: raise/lower a column's surface from 'from' to 'to'
		if (to > from) { for (int y = from + 1; y < to; y++) c.raw(new BlockPos(c.ox + x, y, c.oz + z), fill); c.raw(new BlockPos(c.ox + x, to, c.oz + z), topB); }
		else if (to < from) { for (int y = from + 12; y > to; y--) { BlockPos p = new BlockPos(c.ox + x, y, c.oz + z); if (!c.lv.getBlockState(p).isAir()) c.raw(p, Blocks.AIR.defaultBlockState()); } c.raw(new BlockPos(c.ox + x, to, c.oz + z), topB); }
	}

	static BlockState surfaceOf(Ctx c, int x, int y, int z) {
		BlockState s = c.lv.getBlockState(new BlockPos(c.ox + x, y, c.oz + z));
		return s.isAir() || !s.getFluidState().isEmpty() ? Blocks.GRASS_BLOCK.defaultBlockState() : s;
	}
	static BlockState under(BlockState top) { return top.is(Blocks.GRASS_BLOCK) || top.is(Blocks.PODZOL) || top.is(Blocks.MYCELIUM) ? Blocks.DIRT.defaultBlockState() : top.is(Blocks.SAND) ? Blocks.SANDSTONE.defaultBlockState() : top; }

	static void terrain(Ctx c, String op) {
		int r = c.i("r", c.i("size", 10));
		BlockState blk = c.args.containsKey("block") ? c.with(c.args.get("block")) : null;
		switch (op) {
			case "sphere", "ball" -> {
				boolean hollow = c.args.containsKey("hollow");
				BlockState b = blk != null ? blk : Blocks.STONE.defaultBlockState();
				for (int x = -r; x <= r; x++) for (int y = -r; y <= r; y++) for (int z = -r; z <= r; z++) {
					double d = Math.sqrt(x * x + y * y + z * z);
					if (d <= r + 0.5 && (!hollow || d > r - 0.5)) c.raw(new BlockPos(c.ox + x, c.oy + y, c.oz + z), b);
				}
			}
			case "cylinder" -> {
				int h = c.i("height", r); boolean hollow = c.args.containsKey("hollow");
				BlockState b = blk != null ? blk : Blocks.STONE.defaultBlockState();
				for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) { double d = Math.sqrt(x * x + z * z); if (d <= r + 0.5 && (!hollow || d > r - 0.5)) for (int y = 0; y < h; y++) c.raw(new BlockPos(c.ox + x, c.oy + y, c.oz + z), b); }
			}
			case "crater" -> {
				int depth = c.i("depth", Math.max(3, r / 2));
				BlockState[] scorch = c.args.containsKey("block") ? new BlockState[] { blk } : new BlockState[] { Blocks.BLACKSTONE.defaultBlockState(), Blocks.BASALT.defaultBlockState(), Blocks.COARSE_DIRT.defaultBlockState(), Blocks.MAGMA_BLOCK.defaultBlockState(), Blocks.TUFF.defaultBlockState() };
				SimplexNoise nz = noise(c);
				int R = (int) (r * 1.45);
				for (int x = -R; x <= R; x++) for (int z = -R; z <= R; z++) {
					double d = Math.sqrt(x * x + z * z) * (1 + 0.12 * nz.get(x * 0.15, z * 0.15));
					int t = top(c, x, z);
					if (d < r) {
						int bowl = (int) Math.round(depth * (1 - (d / r) * (d / r)));
						int to = c.oy - 1 - bowl;
						column(c, x, z, Math.max(t, c.oy - 1), to, scorch[c.rnd.nextInt(scorch.length)], Blocks.STONE.defaultBlockState());
						if (to < t) for (int y = to + 1; y <= t + 12; y++) { BlockPos p = new BlockPos(c.ox + x, y, c.oz + z); if (!c.lv.getBlockState(p).isAir()) c.raw(p, Blocks.AIR.defaultBlockState()); }
					} else if (d < R) {
						int rim = (int) Math.round(depth * 0.45 * (1 - (d - r) / (R - r)) * (1 + 0.3 * nz.get(x * 0.3, z * 0.3)));
						if (rim > 0) column(c, x, z, t, t + rim, c.rnd.nextFloat() < 0.3 ? scorch[c.rnd.nextInt(scorch.length)] : surfaceOf(c, x, t, z), under(surfaceOf(c, x, t, z)));
					}
				}
			}
			case "mountain", "hill" -> {
				int h = c.i("height", op.equals("hill") ? r / 2 : r);
				SimplexNoise nz = noise(c);
				int snow = (int) (h * c.d("snowline", 0.7));
				for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
					double d = Math.sqrt(x * x + z * z) / r;
					double n = nz.get(x * 0.06, z * 0.06) * 0.35 + nz.get(x * 0.18, z * 0.18) * 0.12;
					double f = d + n * 0.5;
					if (f >= 1) continue;
					int add = (int) Math.round(h * Math.pow(1 - f, 1.6) * (1 + n));
					if (add <= 0) continue;
					int t = top(c, x, z), to = t + add;
					BlockState tb = blk != null ? blk : add > snow ? Blocks.SNOW_BLOCK.defaultBlockState() : add > h * 0.4 ? (c.rnd.nextFloat() < 0.3 ? Blocks.ANDESITE.defaultBlockState() : Blocks.STONE.defaultBlockState()) : Blocks.GRASS_BLOCK.defaultBlockState();
					for (int y = t + 1; y < to; y++) c.raw(new BlockPos(c.ox + x, y, c.oz + z), to - y <= 3 && tb.is(Blocks.GRASS_BLOCK) ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState());
					c.raw(new BlockPos(c.ox + x, to, c.oz + z), tb);
				}
			}
			case "river" -> {
				int len = c.i("length", 48), w = c.i("width", 4), depth = c.i("depth", 3);
				SimplexNoise nz = noise(c);
				int water = top(c, 0, 0);
				Direction f = c.rot.rotate(Direction.SOUTH), side = f.getClockWise();
				Set<Long> done = new HashSet<>();
				for (int s = 0; s <= len; s++) {
					double lat = nz.get(s * 0.04, 0.5) * w * 2.2;
					for (int k = -w - 3; k <= w + 3; k++) {
						double kk = k - lat; int ki = (int) Math.round(kk + lat);
						int x = f.getStepX() * s + side.getStepX() * ki, z = f.getStepZ() * s + side.getStepZ() * ki;
						if (!done.add(BlockPos.asLong(x, 0, z))) continue;
						double a = Math.abs(kk);
						int t = top(c, x, z);
						if (a <= w) {
							int bed = water - Math.max(1, (int) Math.round(depth * (1 - (a / w) * (a / w))));
							for (int y = t + 10; y > water; y--) { BlockPos p = new BlockPos(c.ox + x, y, c.oz + z); if (!c.lv.getBlockState(p).isAir()) c.raw(p, Blocks.AIR.defaultBlockState()); }
							for (int y = water; y > bed; y--) c.raw(new BlockPos(c.ox + x, y, c.oz + z), Blocks.WATER.defaultBlockState());
							c.raw(new BlockPos(c.ox + x, bed, c.oz + z), c.rnd.nextFloat() < 0.6 ? Blocks.GRAVEL.defaultBlockState() : Blocks.SAND.defaultBlockState());
						} else {
							int bank = water + (int) Math.round((a - w) * 0.8);
							if (t > bank) column(c, x, z, t, bank, a - w < 1.5 ? Blocks.SAND.defaultBlockState() : surfaceOf(c, x, t, z), Blocks.DIRT.defaultBlockState());
							else if (t < water) column(c, x, z, t, water, Blocks.SAND.defaultBlockState(), Blocks.DIRT.defaultBlockState());
						}
					}
				}
			}
			case "flatten" -> {
				int ty = c.args.containsKey("y") ? c.i("y", c.oy) : c.oy - 1;
				boolean square = c.args.containsKey("square");
				for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
					if (!square && x * x + z * z > (r + 0.5) * (r + 0.5)) continue;
					int t = top(c, x, z);
					BlockState s = surfaceOf(c, x, t, z);
					if (t > ty) { for (int y = t + 24; y > ty; y--) { BlockPos p = new BlockPos(c.ox + x, y, c.oz + z); if (!c.lv.getBlockState(p).isAir()) c.raw(p, Blocks.AIR.defaultBlockState()); } c.raw(new BlockPos(c.ox + x, ty, c.oz + z), blk != null ? blk : s); }
					else if (t < ty) column(c, x, z, t, ty, blk != null ? blk : s, under(s));
					else if (blk != null) c.raw(new BlockPos(c.ox + x, ty, c.oz + z), blk);
				}
			}
			case "noise", "bumps" -> {
				int amp = c.i("amp", 5); double sc = c.d("scale", 14);
				SimplexNoise nz = noise(c);
				for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
					double d = Math.sqrt(x * x + z * z) / r; if (d > 1) continue;
					double fade = 1 - d * d;
					int delta = (int) Math.round(amp * fade * (nz.get(x / sc, z / sc) + 0.5 * nz.get(x / sc * 2.3, z / sc * 2.3)));
					if (delta == 0) continue;
					int t = top(c, x, z); BlockState s = blk != null ? blk : surfaceOf(c, x, t, z);
					column(c, x, z, t, t + delta, s, under(s));
				}
			}
			case "smooth" -> {
				int R = r + 2; int[][] hm = new int[2 * R + 1][2 * R + 1];
				for (int x = -R; x <= R; x++) for (int z = -R; z <= R; z++) hm[x + R][z + R] = top(c, x, z);
				for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
					if (x * x + z * z > r * r) continue;
					double sum = 0; int n = 0;
					for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) { sum += hm[x + R + dx][z + R + dz]; n++; }
					int t = hm[x + R][z + R], to = (int) Math.round(sum / n);
					if (to != t) { BlockState s = surfaceOf(c, x, t, z); column(c, x, z, t, to, s, under(s)); }
				}
			}
			case "overlay" -> {
				BlockState b = blk != null ? blk : Blocks.GRASS_BLOCK.defaultBlockState();
				for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) if (x * x + z * z <= r * r) c.raw(new BlockPos(c.ox + x, top(c, x, z), c.oz + z), b);
			}
			case "replace" -> {
				BlockState from = c.with(c.s("from", "stone")), to = blk != null ? blk : c.with(c.s("to", "air"));
				for (int x = -r; x <= r; x++) for (int y = -r; y <= r; y++) for (int z = -r; z <= r; z++) {
					BlockPos p = new BlockPos(c.ox + x, c.oy + y, c.oz + z);
					if (x * x + y * y + z * z <= r * r && c.lv.getBlockState(p).is(from.getBlock())) c.raw(p, to);
				}
			}
			case "drain" -> {
				for (int x = -r; x <= r; x++) for (int y = -r; y <= r; y++) for (int z = -r; z <= r; z++) {
					BlockPos p = new BlockPos(c.ox + x, c.oy + y, c.oz + z);
					BlockState s = c.lv.getBlockState(p);
					if (x * x + y * y + z * z <= r * r && !s.getFluidState().isEmpty()) c.raw(p, s.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED) ? s.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, false) : Blocks.AIR.defaultBlockState());
				}
			}
			case "island" -> {
				SimplexNoise nz = noise(c);
				int depth = c.i("depth", r);
				for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
					double d = Math.sqrt(x * x + z * z) / r + 0.25 * nz.get(x * 0.12, z * 0.12); if (d >= 1) continue;
					int down = (int) Math.round(depth * Math.pow(1 - d, 0.7));
					for (int y = -down; y <= 0; y++) c.raw(new BlockPos(c.ox + x, c.oy + y, c.oz + z), y == 0 ? (blk != null ? blk : Blocks.GRASS_BLOCK.defaultBlockState()) : y > -3 ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState());
				}
			}
			default -> throw new IllegalArgumentException("unknown terrain op " + op + " (sphere cylinder crater mountain hill river flatten noise smooth overlay replace drain island)");
		}
	}

	// ================================================= command line =================================================
	static BlockPos pos1, pos2;
	static StructureTemplate clipboard;

	static Map<String, String> parseArgs(String[] t, int from) {
		Map<String, String> m = new LinkedHashMap<>();
		for (int i = from; i < t.length; i++) { int e = t[i].indexOf('='); if (e > 0) m.put(t[i].substring(0, e).toLowerCase(), t[i].substring(e + 1)); else m.put(t[i].toLowerCase(), "true"); }
		return m;
	}

	static ServerPlayer player() { return Quick.me(); }

	/** Where to build: at=x,y,z | at=look | at=here | at=front (default: in front of the player, on the ground). */
	static BlockPos where(Ctx c, String kind, ServerPlayer p, boolean ground) {
		String at = c.s("at", "front");
		BlockPos b;
		if (at.equals("here")) b = p.blockPosition();
		else if (at.equals("look")) {
			HitResult hr = p.pick(160, 0, false);
			if (!(hr instanceof BlockHitResult bh) || hr.getType() == HitResult.Type.MISS) throw new IllegalArgumentException("not looking at a block");
			b = bh.getBlockPos().relative(bh.getDirection());
			ground = false;
		} else if (at.contains(",")) {
			String[] s = at.split(","); BlockPos me = p.blockPosition();
			int[] v = new int[3];
			for (int i = 0; i < 3; i++) { String q = s[i].trim(); int base = i == 0 ? me.getX() : i == 1 ? me.getY() : me.getZ(); if (q.equals("_")) { v[i] = base; continue; } v[i] = q.startsWith("~") ? base + (q.length() > 1 ? Integer.parseInt(q.substring(1)) : 0) : Integer.parseInt(q); }
			b = new BlockPos(v[0], v[1], v[2]); ground = s[1].trim().equals("_"); // y "_" = on the ground there
		} else {
			Vec3 look = p.getLookAngle(); Vec3 hz = new Vec3(look.x, 0, look.z);
			hz = hz.lengthSqr() < 1e-4 ? new Vec3(0, 0, 1) : hz.normalize();
			int dist = c.i("dist", reach(kind, c) + 4);
			b = BlockPos.containing(p.position().add(hz.scale(dist)));
		}
		if (ground) b = new BlockPos(b.getX(), top(new Ctx(c.lv, null, c.rnd, c.blocks), b.getX(), b.getZ()) + 1, b.getZ());
		return b;
	}

	static Rotation rotFor(Direction facing) {
		return switch (facing) { case WEST -> Rotation.CLOCKWISE_90; case NORTH -> Rotation.CLOCKWISE_180; case EAST -> Rotation.COUNTERCLOCKWISE_90; default -> Rotation.NONE; };
	}

	/** One entry for the CLI and snippets: Structures.run("tower size=9 palette=stone decay=0.2"). Server thread. */
	public static String run(String line) {
		String[] t = line.trim().split("\\s+");
		String cmd = t[0].toLowerCase();
		ServerPlayer p = player();
		if (p == null) return "no player";
		ServerLevel lv = (ServerLevel) p.level();
		Map<String, String> a = parseArgs(t, cmd.equals("terrain") || cmd.equals("worldgen") || cmd.equals("sel") ? 2 : 1);
		String sub = t.length > 1 ? t[1].toLowerCase() : "";
		switch (cmd) {
			case "undo", "redo" -> {
				int n = a.isEmpty() ? 1 : Integer.parseInt(t[1]);
				StringBuilder sb = new StringBuilder();
				for (int i = 0; i < n; i++) {
					ArrayDeque<Op> from = cmd.equals("undo") ? undos() : redos(), to = cmd.equals("undo") ? redos() : undos();
					if (from.isEmpty()) { sb.append("nothing to ").append(cmd); break; }
					Op op = from.pop(); to.push(restore(op));
					sb.append(cmd).append(" ").append(op.name).append(" (").append(op.old.size()).append(" blocks)\n");
				}
				return sb.toString().trim();
			}
			case "ops", "history" -> {
				StringBuilder sb = new StringBuilder();
				for (Op o : undos()) sb.append(o.name).append("  ").append(o.old.size()).append(" blocks  ").append(o.box()).append('\n');
				return sb.length() == 0 ? "no undo history" : sb.toString().trim();
			}
			case "terrain" -> { return edit(lv, p, "terrain " + sub, a, c -> terrain(c, sub), true); }
			case "pos1", "pos2" -> {
				Ctx c = ctx(lv, null, a); c.args.putIfAbsent("at", "look");
				BlockPos b = where(c, cmd, p, false);
				if (!c.s("at", "").contains(",") && !c.s("at", "").equals("here") && c.s("at", "look").equals("look")) { HitResult hr = p.pick(160, 0, false); if (hr instanceof BlockHitResult bh) b = bh.getBlockPos(); }
				if (cmd.equals("pos1")) pos1 = b; else pos2 = b;
				return cmd + " = " + b.toShortString() + sel();
			}
			case "sel" -> {
				if (pos1 == null || pos2 == null) return "set pos1 and pos2 first (struct pos1 / pos2, at=look by default)";
				BlockPos mn = BlockPos.min(pos1, pos2), mx = BlockPos.max(pos1, pos2);
				return switch (sub) {
					case "set", "walls", "outline", "hollow" -> edit(lv, p, "sel " + sub, a, c -> {
						BlockState b = c.with(c.s("block", t.length > 2 && !t[2].contains("=") ? t[2] : "stone"));
						for (int x = mn.getX(); x <= mx.getX(); x++) for (int y = mn.getY(); y <= mx.getY(); y++) for (int z = mn.getZ(); z <= mx.getZ(); z++) {
							boolean ex = x == mn.getX() || x == mx.getX(), ey = y == mn.getY() || y == mx.getY(), ez = z == mn.getZ() || z == mx.getZ();
							boolean on = switch (sub) { case "walls" -> ex || ez; case "outline" -> ex || ey || ez; case "hollow" -> !(ex || ey || ez); default -> true; };
							if (on) c.raw(new BlockPos(x, y, z), sub.equals("hollow") ? Blocks.AIR.defaultBlockState() : b);
						}
					}, false);
					case "replace" -> edit(lv, p, "sel replace", a, c -> {
						BlockState from = c.with(t[2]), to = c.with(t[3]);
						for (BlockPos q : BlockPos.betweenClosed(mn, mx)) if (lv.getBlockState(q).is(from.getBlock())) c.raw(q.immutable(), to);
					}, false);
					case "copy" -> { clipboard = new StructureTemplate(); clipboard.fillFromWorld(lv, mn, mx.subtract(mn).offset(1, 1, 1), true, List.of(Blocks.STRUCTURE_VOID)); yield "copied " + clipboard.getSize().toShortString(); }
					case "save" -> {
						Identifier id = Identifier.parse(t[2].contains(":") ? t[2] : "moddeus:" + t[2]);
						var mgr = lv.getStructureTemplateManager();
						StructureTemplate st = mgr.getOrCreate(id);
						st.fillFromWorld(lv, mn, mx.subtract(mn).offset(1, 1, 1), !a.containsKey("noentities"), List.of(Blocks.STRUCTURE_VOID));
						st.setAuthor(p.getName().getString());
						if (!mgr.save(id)) yield "save failed";
						yield "saved " + id + " " + st.getSize().toShortString() + " -> " + lv.getServer().getWorldPath(LevelResource.GENERATED_DIR).resolve(id.getNamespace()).resolve("structure").resolve(id.getPath() + ".nbt").toAbsolutePath().normalize();
					}
					default -> "sel set|walls|outline|hollow <block> | replace <from> <to> | copy | save <name>  (" + mn.toShortString() + " -> " + mx.toShortString() + ")";
				};
			}
			case "paste", "place", "load" -> {
				StructureTemplate st;
				String name = cmd.equals("paste") ? null : t[1];
				if (name == null) { if (clipboard == null) return "clipboard empty (struct sel copy)"; st = clipboard; }
				else st = lv.getStructureTemplateManager().get(Identifier.parse(name.contains(":") ? name : "moddeus:" + name)).orElse(null);
				if (st == null) return "no template " + name + " (struct list)";
				Map<String, String> aa = parseArgs(t, name == null ? 1 : 2);
				final StructureTemplate tpl = st;
				return edit(lv, p, (name == null ? "paste" : "place " + name), aa, c -> placeTemplate(c, tpl, aa), true);
			}
			case "list", "templates" -> {
				String f = sub.isEmpty() ? "" : sub;
				return lv.getStructureTemplateManager().listTemplates().map(Identifier::toString).filter(s -> f.isEmpty() ? !s.startsWith("minecraft:") : s.contains(f)).sorted().collect(Collectors.joining("\n"));
			}
			case "feature" -> {
				String fid = t[1];
				Map<String, String> aa = parseArgs(t, 2);
				return edit(lv, p, "feature " + fid, aa, c -> {
					BlockPos at = c.w(0, 0, 0);
					var reg = lv.registryAccess();
					boolean ok;
					if (fid.startsWith("placed:")) ok = reg.lookupOrThrow(Registries.PLACED_FEATURE).getOrThrow(ResourceKey.create(Registries.PLACED_FEATURE, Identifier.parse(fid.substring(7)))).value().place(lv, lv.getChunkSource().getGenerator(), c.rnd, at);
					else ok = reg.lookupOrThrow(Registries.FEATURE).getOrThrow(ResourceKey.create(Registries.FEATURE, Identifier.parse(fid))).value().place(lv, lv.getChunkSource().getGenerator(), c.rnd, at);
					if (!ok) throw new IllegalStateException("feature didn't fit here (try another spot)");
				}, true, Integer.parseInt(aa.getOrDefault("r", "16")), Integer.parseInt(aa.getOrDefault("dy", "32")));
			}
			case "vanilla" -> { // vanilla <r> <command...>: any command, with undo
				int r = Integer.parseInt(t[1]);
				String command = String.join(" ", Arrays.copyOfRange(t, 2, t.length));
				Map<String, String> aa = new HashMap<>(); aa.put("at", "here");
				String[] fb = new String[1];
				String res = edit(lv, p, "vanilla " + command, aa, c -> fb[0] = command(p, command), false, r, Math.min(r, 48));
				return (fb[0] == null ? "" : fb[0].trim() + "\n") + res;
			}
			case "worldgen" -> { return worldgen(lv.getServer(), sub, t, p); }
			case "palettes" -> { return Arrays.stream(PALETTES).map(x -> x[0]).collect(Collectors.joining(" ")) + "  (or palette=<any block id>; role overrides: wall= roof= floor= pillar= trim= light= ...)"; }
			default -> {
				if (!KINDS.contains(cmd) && !cmd.equals("pyramid")) return "unknown: " + cmd + "\nbuilds: " + KINDS + "\nother: terrain <op> | pos1 | pos2 | sel ... | paste | place <name> | list | feature <id> | vanilla <r> <cmd> | undo [n] | redo | ops | worldgen sync|list|test <id> | palettes";
				return edit(lv, p, cmd, a, c -> build(c, cmd), !cmd.equals("dungeon"));
			}
		}
	}

	/** Runs a command as the player with full rights; returns its chat feedback. */
	static String command(ServerPlayer p, String command) {
		Feedback fb = new Feedback();
		var src = p.createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER).withSource(fb);
		p.level().getServer().getCommands().performPrefixedCommand(src, command.startsWith("/") ? command.substring(1) : command);
		return fb.out.toString().trim();
	}
	static final class Feedback implements net.minecraft.commands.CommandSource {
		final StringBuilder out = new StringBuilder();
		public void sendSystemMessage(net.minecraft.network.chat.Component c) { out.append(c.getString()).append('\n'); }
		public boolean acceptsSuccess() { return true; }
		public boolean acceptsFailure() { return true; }
		public boolean shouldInformAdmins() { return false; }
	}

	static String sel() { return pos1 != null && pos2 != null ? "  (selection " + (Math.abs(pos1.getX() - pos2.getX()) + 1) + "x" + (Math.abs(pos1.getY() - pos2.getY()) + 1) + "x" + (Math.abs(pos1.getZ() - pos2.getZ()) + 1) + ")" : ""; }

	static Ctx ctx(WorldGenLevel lv, Op op, Map<String, String> a) {
		long seed = a.containsKey("seed") ? Long.parseLong(a.get("seed")) : System.nanoTime();
		Ctx c = new Ctx(lv, op, RandomSource.create(seed), lv.registryAccess().lookupOrThrow(Registries.BLOCK));
		c.args = new HashMap<>(a);
		return c;
	}

	interface Job { void run(Ctx c) throws Exception; }

	static String edit(ServerLevel lv, ServerPlayer p, String name, Map<String, String> a, Job job, boolean ground) { return edit(lv, p, name, a, job, ground, -1, 0); }

	/** Runs an edit with undo. diffR >= 0: also snapshot a box (for code we can't intercept: features, vanilla commands). */
	static String edit(ServerLevel lv, ServerPlayer p, String name, Map<String, String> a, Job job, boolean ground, int diffR, int diffY) {
		Op op = new Op(name + "#" + (++opCount), lv);
		Ctx c = ctx(lv, op, a);
		String kind = name.contains(" ") ? name.substring(name.indexOf(' ') + 1) : name;
		c.pal = palette(c.s("palette", DEF_PALETTE.getOrDefault(kind, "stone")), c.blocks);
		c.decay = c.d("decay", 0);
		Direction facing = a.containsKey("facing") ? Direction.byName(a.get("facing")) : p.getDirection();
		if (facing == null || facing.getAxis().isVertical()) facing = p.getDirection();
		c.rot = rotFor(facing);
		BlockPos at = where(c, kind, p, ground);
		if (kind.equals("dungeon") && !a.containsKey("at")) at = at.below(c.i("depth", 6) + c.i("height", 5) + (at.getY() - (top(c, at.getX(), at.getZ()) + 1)));
		c.ox = at.getX(); c.oy = at.getY(); c.oz = at.getZ();
		BlockState[] snap = null; Map<Long, CompoundTag> snapBe = null;
		int sy0 = Math.max(lv.getMinY(), at.getY() - diffY), sy1 = Math.min(lv.getMaxY(), at.getY() + diffY), sd = 2 * diffR + 1;
		if (diffR >= 0) { // cheap snapshot (states in an array, block entities by NBT); changed positions become the undo record
			snap = new BlockState[sd * sd * (sy1 - sy0 + 1)]; snapBe = new HashMap<>();
			BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
			for (int y = sy0, i = 0; y <= sy1; y++) for (int x = 0; x < sd; x++) for (int z = 0; z < sd; z++, i++) {
				m.set(at.getX() - diffR + x, y, at.getZ() - diffR + z);
				BlockState s = lv.getBlockState(m); snap[i] = s;
				if (s.hasBlockEntity()) { BlockEntity be = lv.getBlockEntity(m); if (be != null) snapBe.put(m.asLong(), be.saveWithFullMetadata(lv.registryAccess())); }
			}
		}
		long t0 = System.nanoTime();
		try { job.run(c); }
		catch (Exception e) { if (snap != null) { BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(); for (int y = sy0, i = 0; y <= sy1; y++) for (int x = 0; x < sd; x++) for (int z = 0; z < sd; z++, i++) { m.set(at.getX() - diffR + x, y, at.getZ() - diffR + z); if (lv.getBlockState(m) != snap[i]) op.old.putIfAbsent(m.asLong(), new Object[] { snap[i], snapBe.get(m.asLong()) }); } } if (!op.old.isEmpty()) restore(op); return "failed: " + e.getMessage(); }
		if (snap != null) {
			BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
			for (int y = sy0, i = 0; y <= sy1; y++) for (int x = 0; x < sd; x++) for (int z = 0; z < sd; z++, i++) {
				m.set(at.getX() - diffR + x, y, at.getZ() - diffR + z);
				if (lv.getBlockState(m) != snap[i] && !op.old.containsKey(m.asLong())) op.old.put(m.asLong(), new Object[] { snap[i], snapBe.get(m.asLong()) });
			}
		}
		c.count = op.old.size();
		op.rebound();
		if (op.fluids || diffR >= 0) op.markDry(op.fluids ? 16 : 4);
		push(op);
		return op.name + ": " + c.count + " blocks at " + at.toShortString() + " facing " + facing.getSerializedName() + " in " + (System.nanoTime() - t0) / 1_000_000 + " ms, box " + op.box() + "  (undo: struct undo)";
	}

	static void placeTemplate(Ctx c, StructureTemplate st, Map<String, String> a) {
		Rotation r = switch (a.getOrDefault("rotate", "0")) { case "90" -> Rotation.CLOCKWISE_90; case "180" -> Rotation.CLOCKWISE_180; case "270", "-90" -> Rotation.COUNTERCLOCKWISE_90; default -> Rotation.NONE; };
		Mirror m = switch (a.getOrDefault("mirror", "none")) { case "lr", "x" -> Mirror.LEFT_RIGHT; case "fb", "z" -> Mirror.FRONT_BACK; default -> Mirror.NONE; };
		StructurePlaceSettings set = new StructurePlaceSettings().setRotation(r).setMirror(m).setIgnoreEntities(a.containsKey("noentities"));
		BlockPos at = new BlockPos(c.ox, c.oy + c.i("dy", 0), c.oz);
		if (c.op != null) {
			var bb = st.getBoundingBox(set, at);
			for (BlockPos q : BlockPos.betweenClosed(bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ())) c.op.record(q.immutable());
		}
		st.placeInWorld((net.minecraft.world.level.ServerLevelAccessor) c.lv, at, at, set, c.rnd, FLAGS);
		c.count = st.getSize().getX() * st.getSize().getY() * st.getSize().getZ();
		if (c.op != null) c.op.old.entrySet().removeIf(e -> c.lv.getBlockState(BlockPos.of(e.getKey())) == e.getValue()[0] && e.getValue()[1] == null);
	}

	// ================================================= live worldgen =================================================
	/**
	 * Rules = JSON files in moddeus/worldgen/*.json (every mod's resources; in dev src/main/resources) and in
	 * <world>/datapacks/<pack>/moddeus_worldgen/*.json (enabled packs). Each becomes a PlacedFeature appended to the
	 * matching biomes' generation step, LIVE: new chunks generate with it, no world reload.
	 * {"step":"underground_ores","biomes":["#minecraft:is_overworld"],"placed":{feature, placement}}   (vanilla JSON)
	 * {"step":"surface_structures","biomes":[...],"structure":{"build":"tower","args":"size=7 palette=stone"} | {"template":"ns:id"},"placement":[...]}
	 */
	static Map<BiomeGenerationSettings, List<HolderSet<PlacedFeature>>> orig;
	static Map<String, Holder<PlacedFeature>> live;
	static Map<String, JsonObject> rules;

	static Map<String, JsonObject> loadRules(MinecraftServer s) {
		Map<String, JsonObject> out = new TreeMap<>();
		List<Path> dirs = new ArrayList<>();
		for (var mod : FabricLoader.getInstance().getAllMods()) mod.findPath("moddeus/worldgen").ifPresent(dirs::add);
		Path src = FabricLoader.getInstance().getGameDir().toAbsolutePath().getParent().resolve("src/main/resources/moddeus/worldgen");
		if (Files.isDirectory(src)) dirs.add(src); // dev: newest copy wins
		try {
			Path dp = s.getWorldPath(LevelResource.DATAPACK_DIR);
			var on = s.getPackRepository().getSelectedIds();
			if (Files.isDirectory(dp)) try (var ps = Files.list(dp)) { for (Path pack : ps.toList()) if (on.contains("file/" + pack.getFileName()) && Files.isDirectory(pack.resolve("moddeus_worldgen"))) dirs.add(pack.resolve("moddeus_worldgen")); }
		} catch (Exception e) { dev.moddeus.Moddeus.LOG.warn("worldgen: datapack scan {}", e.toString()); }
		for (Path d : dirs) {
			try (var fs = Files.list(d)) {
				for (Path f : fs.toList()) {
					String n = f.getFileName().toString();
					if (!n.endsWith(".json")) continue;
					try { JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject(); if (!o.has("enabled") || o.get("enabled").getAsBoolean()) out.put(n.substring(0, n.length() - 5), o); else out.remove(n.substring(0, n.length() - 5)); }
					catch (Exception e) { dev.moddeus.Moddeus.LOG.warn("worldgen rule {}: {}", f, e.toString()); }
				}
			} catch (Exception ignored) {}
		}
		return out;
	}

	static Field field(Class<?> c, String n) throws Exception { Field f = c.getDeclaredField(n); f.setAccessible(true); return f; }

	/** Re-applies all rules to the live biomes + chunk generators. Returns a summary. */
	public static synchronized String sync(MinecraftServer s) {
		StringBuilder sb = new StringBuilder();
		try {
			if (orig == null) orig = new IdentityHashMap<>();
			if (live == null) live = new LinkedHashMap<>();
			Field fFeatures = field(BiomeGenerationSettings.class, "features"), fSet = field(BiomeGenerationSettings.class, "featureSet");
			for (var e : orig.entrySet()) { fFeatures.set(e.getKey(), e.getValue()); fSet.set(e.getKey(), memoSet(e.getValue())); }
			live.clear();
			rules = loadRules(s);
			var ops = RegistryOps.create(JsonOps.INSTANCE, s.registryAccess());
			var biomes = s.registryAccess().lookupOrThrow(Registries.BIOME);
			var placedReg = s.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE);
			Map<BiomeGenerationSettings, List<List<Holder<PlacedFeature>>>> add = new IdentityHashMap<>();
			for (var r : rules.entrySet()) {
				String id = r.getKey(); JsonObject o = r.getValue();
				try {
					if (o.has("vanilla") && (placedReg.get(ResourceKey.create(Registries.PLACED_FEATURE, Identifier.parse(o.get("vanilla").getAsString()))).isPresent() || s.registryAccess().lookupOrThrow(Registries.STRUCTURE).get(ResourceKey.create(Registries.STRUCTURE, Identifier.parse(o.get("vanilla").getAsString()))).isPresent())) { sb.append(id).append(": loaded by its datapack\n"); continue; }
					PlacedFeature pf;
					if (o.has("structure")) {
						List<PlacementModifier> pl = o.has("placement") ? PlacementModifier.CODEC.listOf().parse(ops, o.get("placement")).getOrThrow() : List.of();
						pf = new PlacedFeature(Holder.direct(new BuildFeature(id, o.getAsJsonObject("structure"))), pl);
					} else pf = PlacedFeature.DIRECT_CODEC.parse(ops, o.get("placed")).getOrThrow();
					Holder<PlacedFeature> h = Holder.direct(pf);
					int step = net.minecraft.world.level.levelgen.GenerationStep.Decoration.valueOf(o.has("step") ? o.get("step").getAsString().toUpperCase() : "VEGETAL_DECORATION").ordinal();
					List<String> want = new ArrayList<>();
					if (o.has("biomes")) for (JsonElement b : o.getAsJsonArray("biomes")) want.add(b.getAsString()); else want.add("#minecraft:is_overworld");
					int n = 0;
					for (var bh : biomes.listElements().toList()) {
						if (!matches(bh, want)) continue;
						BiomeGenerationSettings g = bh.value().getGenerationSettings();
						var lists = add.computeIfAbsent(g, k -> new ArrayList<>());
						while (lists.size() <= step) lists.add(new ArrayList<>());
						lists.get(step).add(h); n++;
					}
					live.put(id, h);
					sb.append(id).append(": live in ").append(n).append(" biomes\n");
				} catch (Exception e) { sb.append(id).append(": ERROR ").append(e.getMessage()).append('\n'); }
			}
			for (var e : add.entrySet()) {
				BiomeGenerationSettings g = e.getKey();
				orig.putIfAbsent(g, g.features());
				List<HolderSet<PlacedFeature>> base = orig.get(g), now = new ArrayList<>(base);
				var lists = e.getValue();
				for (int i = 0; i < lists.size(); i++) {
					if (lists.get(i).isEmpty()) continue;
					while (now.size() <= i) now.add(HolderSet.direct());
					List<Holder<PlacedFeature>> l = new ArrayList<>(now.get(i).stream().toList()); l.addAll(lists.get(i));
					now.set(i, HolderSet.direct(l));
				}
				List<HolderSet<PlacedFeature>> fin = List.copyOf(now);
				fFeatures.set(g, fin); fSet.set(g, memoSet(fin));
			}
			Field fSteps = field(ChunkGenerator.class, "featuresPerStep");
			for (ServerLevel l : s.getAllLevels()) {
				ChunkGenerator gen = l.getChunkSource().getGenerator();
				Supplier<List<FeatureSorter.StepFeatureData>> sup = Suppliers.memoize(() -> FeatureSorter.buildFeaturesPerStep(List.copyOf(gen.getBiomeSource().possibleBiomes()), b -> gen.getBiomeGenerationSettings(b).features(), true));
				sup.get();
				fSteps.set(gen, sup);
			}
		} catch (Throwable t) { dev.moddeus.Moddeus.LOG.error("worldgen sync", t); return "worldgen sync failed: " + t; }
		return sb.length() == 0 ? "no worldgen rules" : sb.toString().trim();
	}

	static Supplier<Set<PlacedFeature>> memoSet(List<HolderSet<PlacedFeature>> l) {
		Set<PlacedFeature> set = l.stream().flatMap(HolderSet::stream).map(Holder::value).collect(Collectors.toSet());
		return () -> set;
	}

	static boolean matches(Holder.Reference<Biome> b, List<String> want) {
		for (String w : want) {
			if (w.equals("*")) return true;
			if (w.startsWith("#")) { if (b.is(TagKey.create(Registries.BIOME, Identifier.parse(w.substring(1))))) return true; }
			else if (b.key().identifier().equals(Identifier.parse(w))) return true;
		}
		return false;
	}

	static String worldgen(MinecraftServer s, String sub, String[] t, ServerPlayer p) {
		switch (sub) {
			case "sync", "" -> { return sync(s); }
			case "list" -> { if (rules == null) sync(s); return rules.isEmpty() ? "no rules" : String.join("\n", rules.keySet().stream().map(k -> k + (live.containsKey(k) ? "  live" : "  (datapack)")).toList()); }
			case "test" -> { // places one instance next to the player (with undo)
				if (live == null || !live.containsKey(t[2])) sync(s);
				Holder<PlacedFeature> h = live.get(t[2]);
				if (h == null) return "no live rule " + t[2];
				ServerLevel lv = (ServerLevel) p.level();
				JsonObject o = rules.get(t[2]);
				boolean struct = o.has("structure");
				Map<String, String> a = parseArgs(t, 3);
				return edit(lv, p, "worldgen test " + t[2], a, c -> {
					BlockPos at = c.w(0, 0, 0);
					if (struct) { ((BuildFeature) h.value().feature().value()).placeAt(lv, c.rnd, at, c.op); return; }
					BlockPos chunk = new BlockPos(at.getX() & ~15, lv.getMinY(), at.getZ() & ~15);
					int n = 0;
					for (int i = 0; i < c.i("tries", 8); i++) {
						if (placeNoBiome(lv, h.value(), c.rnd, chunk)) n++;
					}
					if (n == 0) throw new IllegalStateException("nothing placed in this chunk (wrong host block/height?)");
				}, true, struct ? -1 : 24, 400);
			}
			case "datapack" -> { // datapack <rule> <packDir> <ns:id>: writes biome overrides that reference ns:id (vanilla worldgen on next world load)
				return writeBiomeOverrides(s, t[2], Path.of(t[3].replace("%20", " ")), t[4]);
			}
			default -> { return "worldgen sync | list | test <id> | datapack <id> <packDir> <ns:id>"; }
		}
	}

	/** Runs a placed feature without its biome filter (that filter only works during chunk decoration). */
	static boolean placeNoBiome(ServerLevel lv, PlacedFeature pf, RandomSource rnd, BlockPos origin) {
		List<PlacementModifier> mods = pf.placement().stream().filter(m -> !(m instanceof net.minecraft.world.level.levelgen.placement.BiomeFilter)).toList();
		return new PlacedFeature(pf.feature(), mods).place(lv, lv.getChunkSource().getGenerator(), rnd, origin);
	}

	static String writeBiomeOverrides(MinecraftServer s, String rule, Path pack, String placedId) {
		if (rules == null) sync(s);
		JsonObject o = rules.get(rule);
		if (o == null) return "no rule " + rule;
		List<String> want = new ArrayList<>();
		if (o.has("biomes")) for (JsonElement b : o.getAsJsonArray("biomes")) want.add(b.getAsString()); else want.add("#minecraft:is_overworld");
		int step = net.minecraft.world.level.levelgen.GenerationStep.Decoration.valueOf(o.has("step") ? o.get("step").getAsString().toUpperCase() : "VEGETAL_DECORATION").ordinal();
		var ops = RegistryOps.create(JsonOps.INSTANCE, s.registryAccess());
		int n = 0;
		try {
			Field fFeatures = field(BiomeGenerationSettings.class, "features");
			for (var bh : s.registryAccess().lookupOrThrow(Registries.BIOME).listElements().toList()) {
				if (!matches(bh, want)) continue;
				Identifier bid = bh.key().identifier();
				Path f = pack.resolve("data/" + bid.getNamespace() + "/worldgen/biome/" + bid.getPath() + ".json");
				JsonObject json;
				if (Files.exists(f)) json = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
				else {
					BiomeGenerationSettings g = bh.value().getGenerationSettings();
					Object cur = fFeatures.get(g);
					List<HolderSet<PlacedFeature>> base = orig != null && orig.containsKey(g) ? orig.get(g) : g.features();
					synchronized (Structures.class) { fFeatures.set(g, base); try { json = Biome.DIRECT_CODEC.encodeStart(ops, bh.value()).getOrThrow().getAsJsonObject(); } finally { fFeatures.set(g, cur); } }
				}
				var steps = json.getAsJsonArray("features");
				while (steps.size() <= step) steps.add(new com.google.gson.JsonArray());
				var arr = steps.get(step).getAsJsonArray();
				boolean has = false; for (JsonElement e : arr) if (e.isJsonPrimitive() && e.getAsString().equals(placedId)) has = true;
				if (!has) arr.add(placedId);
				Files.createDirectories(f.getParent());
				Files.writeString(f, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(json));
				n++;
			}
		} catch (Exception e) { return "datapack biomes failed: " + e; }
		return "wrote " + n + " biome overrides in " + pack + " (vanilla worldgen uses them after the world is reopened)";
	}

	/** A worldgen feature that runs one of our builders or a saved template (live rules of kind "structure"). */
	public static final class BuildFeature implements Feature {
		final String id; final JsonObject spec;
		BuildFeature(String id, JsonObject spec) { this.id = id; this.spec = spec; }
		@Override public MapCodec<? extends Feature> codec() { return MapCodec.unit(this); }
		@Override public boolean place(WorldGenLevel level, ChunkGenerator gen, RandomSource random, BlockPos origin) {
			try {
				int cx = (origin.getX() & ~15) + 8, cz = (origin.getZ() & ~15) + 8;
				int surf = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, cx, cz), floor = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, cx, cz);
				if (surf != floor && !(spec.has("water") && spec.get("water").getAsBoolean())) return false; // no builds floating on oceans/rivers
				return placeAt(level, random, new BlockPos(cx, surf, cz), null);
			} catch (Throwable t) { dev.moddeus.Moddeus.LOG.warn("worldgen {}: {}", id, t.toString()); return false; }
		}
		boolean placeAt(WorldGenLevel level, RandomSource random, BlockPos at, Op op) {
			Map<String, String> a = new HashMap<>();
			if (spec.has("args")) for (String kv : spec.get("args").getAsString().trim().split("\\s+")) { int e = kv.indexOf('='); if (e > 0) a.put(kv.substring(0, e), kv.substring(e + 1)); else if (!kv.isEmpty()) a.put(kv, "true"); }
			Ctx c = new Ctx(level, op, random, level.registryAccess().lookupOrThrow(Registries.BLOCK));
			c.args = a;
			c.hm = op == null ? Heightmap.Types.WORLD_SURFACE_WG : Heightmap.Types.MOTION_BLOCKING_NO_LEAVES;
			c.rot = Rotation.getRandom(random);
			c.ox = at.getX(); c.oy = at.getY() + (spec.has("dy") ? spec.get("dy").getAsInt() : 0); c.oz = at.getZ();
			if (spec.has("template")) {
				var mgr = level.getLevel().getStructureTemplateManager();
				StructureTemplate st = mgr.get(Identifier.parse(spec.get("template").getAsString())).orElse(null);
				if (st == null) return false;
				var sz = st.getSize();
				StructurePlaceSettings set = new StructurePlaceSettings().setRotation(c.rot).setRotationPivot(new BlockPos(sz.getX() / 2, 0, sz.getZ() / 2));
				BlockPos p = new BlockPos(c.ox - sz.getX() / 2, c.oy, c.oz - sz.getZ() / 2);
				if (op != null) { var bb = st.getBoundingBox(set, p); for (BlockPos q : BlockPos.betweenClosed(bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ())) op.record(q.immutable()); }
				return st.placeInWorld(level, p, p, set, random, op == null ? Block.UPDATE_CLIENTS : FLAGS);
			}
			String kind = spec.get("build").getAsString();
			c.pal = palette(a.getOrDefault("palette", DEF_PALETTE.getOrDefault(kind, "stone")), c.blocks);
			c.decay = a.containsKey("decay") ? Double.parseDouble(a.get("decay")) : 0;
			if (kind.equals("dungeon")) c.oy -= Integer.parseInt(a.getOrDefault("depth", "6")) + Integer.parseInt(a.getOrDefault("height", "5"));
			build(c, kind);
			return true;
		}
	}

	// ================================================= lifecycle =====================================================
	@OnReload
	public static void hook() {
		palCache = null; // palettes may have changed
		if (System.getProperties().putIfAbsent("moddeus.structures", Boolean.TRUE) == null) {
			ServerLifecycleEvents.SERVER_STARTED.register(Structures::started);
			ServerLifecycleEvents.END_DATA_PACK_RELOAD.register(Structures::reloaded);
			ServerLifecycleEvents.SERVER_STOPPED.register(Structures::stopped);
		}
		MinecraftServer s = LiveRegistry.server;
		if (s != null && orig != null) s.execute(() -> sync(s)); // a swap: re-apply with the new code
	}

	static void started(MinecraftServer s) { dev.moddeus.Moddeus.LOG.info("worldgen: {}", sync(s).replace('\n', ';')); }
	static void reloaded(MinecraftServer s, net.minecraft.server.packs.resources.CloseableResourceManager rm, boolean ok) { if (ok) sync(s); }
	static void stopped(MinecraftServer s) { orig = null; live = null; rules = null; undo = null; redo = null; clipboard = null; pos1 = pos2 = null; }
}
