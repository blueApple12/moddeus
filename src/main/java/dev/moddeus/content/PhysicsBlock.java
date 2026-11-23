package dev.moddeus.content;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3d;

/**
 * A rigid body made of real blocks (like a Create Aeronautics / Sable "sub-level"): one or many blocks in a grid with
 * mass, centre of mass and inertia from their materials, full 3D rotation, friction and bounce per block (ice slides,
 * slime bounces), buoyancy per block (wood floats, stone sinks, boats tip), impulse contacts against the world and other
 * bodies, and sleeping when still. Never punched or shoved: you mine its blocks (real break time, drops) and place
 * blocks onto it to build. Entity position = centre of mass. Options (looking().set(k, v)): see Physics.OPTIONS.
 */
public class PhysicsBlock extends Entity {
	static final EntityDataAccessor<String> SPEC = SynchedEntityData.defineId(PhysicsBlock.class, EntityDataSerializers.STRING);
	static final EntityDataAccessor<Quaternionfc> ROT = SynchedEntityData.defineId(PhysicsBlock.class, EntityDataSerializers.QUATERNION);
	static final EntityDataAccessor<String> OPTIONS = SynchedEntityData.defineId(PhysicsBlock.class, EntityDataSerializers.STRING);

	/** Current / previous-tick orientation (both sides; the renderer slerps between them). */
	public Quaternionf rot = new Quaternionf(), rotO = new Quaternionf();
	/** Server state: precise orientation, linear velocity (blocks/s) and angular velocity (rad/s, world axes). */
	public Quaterniond q = new Quaterniond();
	public Vector3d v = new Vector3d(), w = new Vector3d();
	int age, still, impactCd;
	public boolean asleep;
	private boolean warned;
	private Grid grid;
	private String gridSpec;
	private JsonObject merged;
	private String mergedKey;

	public PhysicsBlock(EntityType<?> type, Level level) { super(type, level); }

	@Override protected void defineSynchedData(SynchedEntityData.Builder b) { b.define(SPEC, ""); b.define(ROT, new Quaternionf()); b.define(OPTIONS, ""); }
	@Override protected InterpolationHandler createInterpolationHandler() { return level().isClientSide() ? new Predict() : InterpolationHandler.NO_OP; }

	// ================================================================== spec / options

	public String specId() { return entityData.get(SPEC); }
	public void setSpec(String id) { entityData.set(SPEC, id); grid = null; refreshDimensions(); }

	/** Spec JSON (content "physics" spec, or the built-in "block") merged with this body's own options. */
	public JsonObject spec() {
		String key = specId() + "|" + entityData.get(OPTIONS);
		if (merged != null && key.equals(mergedKey)) return merged;
		JsonObject o = Physics.spec(specId()).deepCopy();
		try { JsonObject opt = com.google.gson.JsonParser.parseString(entityData.get(OPTIONS)).getAsJsonObject(); for (var e : opt.entrySet()) o.add(e.getKey(), e.getValue()); } catch (Exception ignored) {}
		merged = o; mergedKey = key;
		return o;
	}
	public JsonObject options() { try { return com.google.gson.JsonParser.parseString(entityData.get(OPTIONS)).getAsJsonObject(); } catch (Exception e) { return new JsonObject(); } }
	public void setOption(String key, com.google.gson.JsonElement value) {
		JsonObject o = options();
		if (value == null) o.remove(key); else o.add(key, value);
		entityData.set(OPTIONS, o.toString());
		wake();
	}
	public PhysicsBlock set(String key, Object value) { setOption(key, Physics.parseValue(String.valueOf(value))); return this; }
	public void resetOptions() { entityData.set(OPTIONS, ""); wake(); }
	public double f(String k, double d) { JsonObject o = spec(); return o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsJsonPrimitive().isNumber() ? o.get(k).getAsDouble() : d; }
	public boolean b(String k, boolean d) { JsonObject o = spec(); return o.has(k) ? o.get(k).getAsBoolean() : d; }
	public String s(String k) { JsonObject o = spec(); return o.has(k) ? o.get(k).getAsString() : null; }

	/** The single block it carries (null for a textured spec block or a multi-block body). */
	public BlockState carried() { Grid g = grid(); return g != null && g.n == 1 ? g.st[0] : null; }

	@Override
	public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
		super.onSyncedDataUpdated(key);
		if (SPEC.equals(key)) { grid = null; refreshDimensions(); }
	}

	// ================================================================== the block grid

	/** Immutable block layout + mass properties, rebuilt whenever the spec string changes. Local = grid coords minus com. */
	public static final class Grid {
		public final int n;
		public final int[] x, y, z;
		public final BlockState[] st;
		final HashMap<Long, Integer> at = new HashMap<>();
		public final AABB[] box; // per cell collision bounds in cell space (0..1), null = no collision
		final double[] mass, mu, bounce;
		public final Vector3d com = new Vector3d();
		final double totalMass, radius;
		final Matrix3d invI = new Matrix3d();
		final double[] pts; // sample points: lx, ly, lz, cell (4 per point), local to com
		public final AABB local; // all cells, local to com

		Grid(List<int[]> cells, List<BlockState> states) {
			n = cells.size();
			x = new int[n]; y = new int[n]; z = new int[n]; st = new BlockState[n]; box = new AABB[n]; mass = new double[n]; mu = new double[n]; bounce = new double[n];
			double M = 0;
			for (int i = 0; i < n; i++) {
				x[i] = cells.get(i)[0]; y[i] = cells.get(i)[1]; z[i] = cells.get(i)[2]; st[i] = states.get(i);
				at.put(key(x[i], y[i], z[i]), i);
				box[i] = bounds(st[i]);
				double vol = box[i] == null ? 0.05 : box[i].getXsize() * box[i].getYsize() * box[i].getZsize();
				mass[i] = Math.max(0.02, density(st[i]) * vol);
				float fr = st[i] == null ? 0.6f : st[i].getBlock().getFriction();
				mu[i] = Mth.clamp((1 - fr) * 1.5, 0.02, 1.0);
				bounce[i] = st[i] != null && st[i].getBlock() instanceof net.minecraft.world.level.block.SlimeBlock ? 0.85 : 0.12;
				Vec3 c = center(i);
				com.add(c.x * mass[i], c.y * mass[i], c.z * mass[i]);
				M += mass[i];
			}
			totalMass = M;
			com.div(M);
			Matrix3d I = new Matrix3d().zero();
			double r = 0;
			AABB lb = null;
			for (int i = 0; i < n; i++) {
				Vec3 c = center(i).subtract(com.x, com.y, com.z);
				AABB b = box[i] == null ? new AABB(0.25, 0.25, 0.25, 0.75, 0.75, 0.75) : box[i];
				double a = b.getXsize(), bb = b.getYsize(), cc = b.getZsize(), m = mass[i];
				I.m00 += m * (bb * bb + cc * cc) / 12 + m * (c.y * c.y + c.z * c.z);
				I.m11 += m * (a * a + cc * cc) / 12 + m * (c.x * c.x + c.z * c.z);
				I.m22 += m * (a * a + bb * bb) / 12 + m * (c.x * c.x + c.y * c.y);
				I.m01 -= m * c.x * c.y; I.m10 -= m * c.x * c.y;
				I.m02 -= m * c.x * c.z; I.m20 -= m * c.x * c.z;
				I.m12 -= m * c.y * c.z; I.m21 -= m * c.y * c.z;
				AABB w = b.move(x[i] - com.x, y[i] - com.y, z[i] - com.z);
				lb = lb == null ? w : lb.minmax(w);
				r = Math.max(r, c.length() + 0.87);
			}
			invI.set(I).invert();
			radius = r;
			local = lb == null ? new AABB(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5) : lb;
			pts = samplePoints();
		}

		static long key(int x, int y, int z) { return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF); }
		public int cell(int cx, int cy, int cz) { Integer i = at.get(key(cx, cy, cz)); return i == null ? -1 : i; }
		boolean full(int cx, int cy, int cz) { int i = cell(cx, cy, cz); return i >= 0 && box[i] != null && box[i].getSize() > 0.999; }
		Vec3 center(int i) { AABB b = box[i] == null ? new AABB(0, 0, 0, 1, 1, 1) : box[i]; return new Vec3(x[i] + (b.minX + b.maxX) / 2, y[i] + (b.minY + b.maxY) / 2, z[i] + (b.minZ + b.maxZ) / 2); }
		public Vec3 cellCenterLocal(int i) { return center(i).subtract(com.x, com.y, com.z); }

		/** Corners, edge midpoints and face centres of every cell's box, minus the ones buried inside the body. */
		double[] samplePoints() {
			java.util.LinkedHashMap<Long, double[]> seen = new java.util.LinkedHashMap<>();
			List<double[]> extra = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				AABB b = box[i];
				if (b == null) continue;
				boolean fullCube = b.getSize() > 0.999;
				for (int a = 0; a <= 2; a++) for (int c = 0; c <= 2; c++) for (int d = 0; d <= 2; d++) {
					if (a == 1 && c == 1 && d == 1) continue;
					double px = b.minX + (b.maxX - b.minX) * a / 2, py = b.minY + (b.maxY - b.minY) * c / 2, pz = b.minZ + (b.maxZ - b.minZ) * d / 2;
					if (!fullCube) { extra.add(new double[] {x[i] + px - com.x, y[i] + py - com.y, z[i] + pz - com.z, i}); continue; }
					int hx = 2 * x[i] + a, hy = 2 * y[i] + c, hz = 2 * z[i] + d; // half-lattice coords
					long k = key(hx, hy, hz);
					if (seen.containsKey(k)) continue;
					if (buried(hx, hy, hz)) { seen.put(k, null); continue; }
					seen.put(k, new double[] {hx / 2.0 - com.x, hy / 2.0 - com.y, hz / 2.0 - com.z, i});
				}
			}
			List<double[]> all = new ArrayList<>();
			for (double[] p : seen.values()) if (p != null) all.add(p);
			all.addAll(extra);
			double[] out = new double[all.size() * 4];
			for (int i = 0; i < all.size(); i++) System.arraycopy(all.get(i), 0, out, i * 4, 4);
			return out;
		}

		/** A half-lattice point is buried when every full cell touching it is present. */
		boolean buried(int hx, int hy, int hz) {
			int[] xs = (hx & 1) == 1 ? new int[] {hx >> 1} : new int[] {(hx >> 1) - 1, hx >> 1};
			int[] ys = (hy & 1) == 1 ? new int[] {hy >> 1} : new int[] {(hy >> 1) - 1, hy >> 1};
			int[] zs = (hz & 1) == 1 ? new int[] {hz >> 1} : new int[] {(hz >> 1) - 1, hz >> 1};
			for (int a : xs) for (int b : ys) for (int c : zs) if (!full(a, b, c)) return false;
			return true;
		}

		/** Spec string for this layout. */
		public String spec() {
			StringBuilder sb = new StringBuilder("moddeus:body#");
			for (int i = 0; i < n; i++) { if (i > 0) sb.append(';'); sb.append(x[i]).append(',').append(y[i]).append(',').append(z[i]).append(',').append(st[i] == null ? -1 : Block.getId(st[i])); }
			return sb.toString();
		}

		/** Ray in grid coords: first cell box hit (cell index + face), or null. */
		public BlockHitResult ray(Vec3 from, Vec3 to, double grow) {
			BlockHitResult best = null;
			double bd = Double.MAX_VALUE;
			for (int i = 0; i < n; i++) {
				AABB b = (box[i] == null ? new AABB(0.3, 0.3, 0.3, 0.7, 0.7, 0.7) : box[i]).inflate(grow);
				BlockPos cp = new BlockPos(x[i], y[i], z[i]);
				BlockHitResult h = AABB.clip(List.of(b), from, to, cp);
				if (h == null || h.getType() == net.minecraft.world.phys.HitResult.Type.MISS) continue;
				double d = h.getLocation().distanceToSqr(from);
				if (d < bd) { bd = d; best = h; }
			}
			return best;
		}
	}

	static AABB bounds(BlockState s) {
		if (s == null) return new AABB(0, 0, 0, 1, 1, 1);
		VoxelShape sh = s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
		return sh.isEmpty() ? null : sh.bounds();
	}

	/** Tonnes per block (water = 1): what floats, what sinks, what is heavy. Like Sable's weight tags. */
	static double density(BlockState s) {
		if (s == null) return 1.0;
		String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
		String[][] kw = {{"netherite", "20"}, {"gold", "19"}, {"lead", "11"}, {"copper", "8.9"}, {"iron", "7.8"}, {"anvil", "7.8"}, {"chain", "7.8"}, {"diamond", "3.5"}, {"emerald", "3.5"},
			{"ore", "3"}, {"obsidian", "2.6"}, {"glass", "2.5"}, {"concrete", "2.4"}, {"terracotta", "2"}, {"brick", "1.9"}, {"tnt", "1.6"}, {"honey", "1.4"}, {"slime", "1.1"},
			{"ice", "0.9"}, {"snow", "0.3"}, {"sponge", "0.15"}, {"leaves", "0.2"}, {"wool", "0.1"}, {"carpet", "0.1"}, {"scaffolding", "0.3"}, {"bamboo", "0.4"},
			{"planks", "0.6"}, {"log", "0.7"}, {"wood", "0.7"}, {"stem", "0.7"}, {"hyphae", "0.7"}, {"barrel", "0.6"}, {"crafting", "0.6"}, {"chest", "0.6"}, {"fence", "0.6"}, {"door", "0.6"}};
		for (String[] k : kw) if (id.contains(k[0])) return Double.parseDouble(k[1]);
		SoundType t = s.getSoundType();
		if (t == SoundType.WOOL) return 0.1;
		if (t == SoundType.WOOD) return 0.6;
		if (t == SoundType.METAL) return 7.8;
		if (t == SoundType.GLASS) return 2.5;
		if (t == SoundType.SAND || t == SoundType.GRAVEL) return 1.6;
		if (t == SoundType.GRASS) return 1.2;
		if (t == SoundType.STONE) return 2.5;
		return 1.5;
	}

	public Grid grid() {
		String s = specId();
		if (grid != null && s.equals(gridSpec)) return grid;
		List<int[]> cells = new ArrayList<>();
		List<BlockState> states = new ArrayList<>();
		int h = s.indexOf('#');
		String head = h < 0 ? s : s.substring(0, h), tail = h < 0 ? "" : s.substring(h + 1);
		try {
			if (head.endsWith(":body")) {
				for (String c : tail.split(";")) {
					if (c.isBlank()) continue;
					String[] p = c.split(",");
					int sid = Integer.parseInt(p[3].trim());
					cells.add(new int[] {Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim())});
					states.add(sid < 0 ? null : Block.stateById(sid));
				}
			} else {
				cells.add(new int[] {0, 0, 0});
				states.add(h < 0 || tail.isBlank() ? null : Block.stateById(Integer.parseInt(tail.trim())));
			}
		} catch (RuntimeException e) {
			cells.clear(); states.clear();
			cells.add(new int[] {0, 0, 0}); states.add(null);
		}
		if (cells.isEmpty()) { cells.add(new int[] {0, 0, 0}); states.add(null); }
		grid = new Grid(cells, states);
		gridSpec = s;
		return grid;
	}

	// ================================================================== pose helpers (both sides)

	public Vec3 toWorld(Vec3 local) { org.joml.Vector3f r = rot().transform(new org.joml.Vector3f((float) local.x, (float) local.y, (float) local.z)); return position().add(r.x, r.y, r.z); }
	public Vec3 toLocal(Vec3 world) { Vec3 d = world.subtract(position()); org.joml.Vector3f r = new Quaternionf(rot()).conjugate().transform(new org.joml.Vector3f((float) d.x, (float) d.y, (float) d.z)); return new Vec3(r.x, r.y, r.z); }
	/** Old helper kept for callers: direction=true rotates a vector, false transforms a local point. */
	public Vec3 toWorld(Vec3 l, boolean direction) { if (!direction) return toWorld(l); org.joml.Vector3f r = rot().transform(new org.joml.Vector3f((float) l.x, (float) l.y, (float) l.z)); return new Vec3(r.x, r.y, r.z); }
	Quaternionf rot() { if (rot == null) rot = new Quaternionf(); return rot; }
	Vec3 gridToLocal(Vec3 g) { Grid gr = grid(); return g.subtract(gr.com.x, gr.com.y, gr.com.z); }
	Vec3 localToGrid(Vec3 l) { Grid gr = grid(); return l.add(gr.com.x, gr.com.y, gr.com.z); }

	/** True when the body is not axis aligned (players then collide through rotated boxes instead of vanilla AABBs). */
	public boolean tilted() {
		Quaternionf r = rot();
		org.joml.Vector3f[] ax = {r.transform(new org.joml.Vector3f(1, 0, 0)), r.transform(new org.joml.Vector3f(0, 1, 0))};
		for (org.joml.Vector3f a : ax) { float m = Math.max(Math.abs(a.x), Math.max(Math.abs(a.y), Math.abs(a.z))); if (m < 0.9995f) return true; }
		return false;
	}

	/** World boxes of the cells (exact when not tilted, enclosing when tilted). */
	public List<AABB> worldBoxes(AABB area) {
		Grid g = grid();
		List<AABB> out = new ArrayList<>();
		for (int i = 0; i < g.n; i++) {
			if (g.box[i] == null) continue;
			AABB lb = g.box[i].move(g.x[i] - g.com.x, g.y[i] - g.com.y, g.z[i] - g.com.z);
			AABB wb = enclose(lb);
			if (area == null || wb.intersects(area)) out.add(wb);
		}
		return out;
	}

	AABB enclose(AABB lb) {
		Vec3 a = null, b = null;
		for (int k = 0; k < 8; k++) {
			Vec3 c = toWorld(new Vec3((k & 1) == 0 ? lb.minX : lb.maxX, (k & 2) == 0 ? lb.minY : lb.maxY, (k & 4) == 0 ? lb.minZ : lb.maxZ));
			a = a == null ? c : new Vec3(Math.min(a.x, c.x), Math.min(a.y, c.y), Math.min(a.z, c.z));
			b = b == null ? c : new Vec3(Math.max(b.x, c.x), Math.max(b.y, c.y), Math.max(b.z, c.z));
		}
		return new AABB(a, b);
	}

	/** Oriented boxes for rotated collision: {centre x,y,z, half x,y,z} per cell; axes = rot. */
	public List<double[]> obbs(AABB area) {
		Grid g = grid();
		List<double[]> out = new ArrayList<>();
		for (int i = 0; i < g.n; i++) {
			if (g.box[i] == null) continue;
			AABB lb = g.box[i].move(g.x[i] - g.com.x, g.y[i] - g.com.y, g.z[i] - g.com.z);
			if (area != null && !enclose(lb).intersects(area)) continue;
			Vec3 c = toWorld(lb.getCenter());
			out.add(new double[] {c.x, c.y, c.z, lb.getXsize() / 2, lb.getYsize() / 2, lb.getZsize() / 2});
		}
		return out;
	}

	/** Raycast against the cells: world hit point. */
	public Optional<Vec3> clipRotated(Vec3 from, Vec3 to, double grow) {
		BlockHitResult h = rayCell(from, to, grow);
		return h == null ? Optional.empty() : Optional.of(toWorld(gridToLocal(h.getLocation())));
	}
	/** Raycast in grid space: getBlockPos() = cell coords, getDirection() = local face. */
	public BlockHitResult rayCell(Vec3 from, Vec3 to, double grow) { return grid().ray(localToGrid(toLocal(from)), localToGrid(toLocal(to)), grow); }
	public boolean containsRotated(Vec3 p, double grow) {
		Vec3 g = localToGrid(toLocal(p));
		Grid gr = grid();
		int i = gr.cell(Mth.floor(g.x), Mth.floor(g.y), Mth.floor(g.z));
		return i >= 0 && gr.box[i] != null && gr.box[i].inflate(grow).contains(g.x - gr.x[i], g.y - gr.y[i], g.z - gr.z[i]);
	}
	public AABB localBox(double grow) { return grid().local.inflate(grow); }
	public AABB enclosingBox() { return getBoundingBox(); }

	/** World position of a body-local point at the previous tick (carrying riders). */
	public Vec3 previousOf(Vec3 world) {
		Vec3 l = toLocal(world);
		org.joml.Vector3f r = new Quaternionf(rotO == null ? rot() : rotO).transform(new org.joml.Vector3f((float) l.x, (float) l.y, (float) l.z));
		return new Vec3(xo + r.x, yo + r.y, zo + r.z);
	}

	@Override
	protected AABB makeBoundingBox(Vec3 pos) {
		if (rot == null || getType() == null) return new AABB(pos.x - 0.5, pos.y - 0.5, pos.z - 0.5, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5);
		try {
			AABB lb = grid().local;
			Vec3 a = null, b = null;
			for (int k = 0; k < 8; k++) {
				org.joml.Vector3f c = rot.transform(new org.joml.Vector3f((float) ((k & 1) == 0 ? lb.minX : lb.maxX), (float) ((k & 2) == 0 ? lb.minY : lb.maxY), (float) ((k & 4) == 0 ? lb.minZ : lb.maxZ)));
				a = a == null ? new Vec3(c.x, c.y, c.z) : new Vec3(Math.min(a.x, c.x), Math.min(a.y, c.y), Math.min(a.z, c.z));
				b = b == null ? new Vec3(c.x, c.y, c.z) : new Vec3(Math.max(b.x, c.x), Math.max(b.y, c.y), Math.max(b.z, c.z));
			}
			return new AABB(a.add(pos), b.add(pos));
		} catch (RuntimeException e) {
			return new AABB(pos.x - 0.5, pos.y - 0.5, pos.z - 0.5, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5);
		}
	}
	@Override public EntityDimensions getDimensions(Pose pose) { return EntityDimensions.fixed(1, 1); }

	// ================================================================== tick

	@Override
	public void tick() {
		if (rot == null) rot = new Quaternionf();
		if (rotO == null) rotO = new Quaternionf();
		if (q == null) q = new Quaterniond(rot.x, rot.y, rot.z, rot.w);
		if (v == null) v = new Vector3d();
		if (w == null) w = new Vector3d(); // fields added by a hotswap start null on existing entities
		setOldPosAndRot();
		rotO.set(rot);
		try {
			if (level().isClientSide()) {
				rot.set(entityData.get(ROT));
				if (interpolationHandler instanceof Predict p && p.server != null) { setPos(p.server); p.server = null; }
				return;
			}
			age++;
			double life = f("lifetime", 0);
			if (life > 0 && age > life) { discard(); return; }
			simulate();
			if (!asleep) Collisions.active().add(this);
		} catch (RuntimeException e) {
			if (!warned || age % 200 == 0) { warned = true; dev.moddeus.Moddeus.LOG.error("PhysicsBlock {} tick failed", specId(), e); }
		}
	}

	public void wake() { asleep = false; still = 0; }

	/** Impulse in blocks/s * tonnes at a world point (explosions, other bodies, scripts). */
	public void applyImpulse(Vec3 at, Vec3 j) {
		Grid g = grid();
		double M = g.totalMass * Math.max(0.01, f("mass", 1));
		v.add(j.x / M, j.y / M, j.z / M);
		Vector3d r = new Vector3d(at.x - getX(), at.y - getY(), at.z - getZ()), jj = new Vector3d(j.x, j.y, j.z);
		w.add(invInertiaWorld(M / g.totalMass).transform(r.cross(jj, new Vector3d())));
		wake();
	}

	Matrix3d invInertiaWorld(double massMul) {
		Matrix3d R = new Matrix3d().rotation(q);
		return new Matrix3d(R).mul(new Matrix3d(grid().invI).scale(1 / massMul)).mul(new Matrix3d(R).transpose());
	}

	double massNow() { return grid().totalMass * Math.max(0.01, f("mass", 1)); }

	/** One server tick of rigid-body physics: forces, contacts (world + other bodies), impulse solve, integrate, correct. */
	void simulate() {
		Grid g = grid();
		ServerLevel level = (ServerLevel) level();
		if (asleep) {
			if (age % 10 == 0 && !supported(g, level)) wake();
			else { syncPose(); return; }
		}
		double massMul = Math.max(0.01, f("mass", 1)), M = g.totalMass * massMul;
		double grav = f("gravity", 0.045) * 400; // option in blocks/tick^2 like vanilla; 0.045 = 18 blocks/s^2
		double speed = v.length() + w.length() * g.radius;
		int S = Mth.clamp((int) Math.ceil(speed * 0.05 / 0.2), 4, 16);
		double h = 0.05 / S;
		List<PhysicsBlock> others = level.getEntitiesOfClass(PhysicsBlock.class, getBoundingBox().inflate(0.5 + speed * 0.05), o -> o != this && !o.isRemoved());
		double maxImpact = 0;
		Vec3 impactAt = null;
		int impactCell = 0;
		boolean touching = false;
		Vec3 pos = position();
		for (int s = 0; s < S; s++) {
			Matrix3d R = new Matrix3d().rotation(q);
			Matrix3d invIw = invInertiaWorld(massMul);
			v.y -= grav * h;
			buoyancy(g, level, pos, R, invIw, M, grav, h);
			double ld = Math.max(0, f("drag", 0.05)), ad = Math.max(0, f("angular_drag", 0.15));
			v.mul(Math.max(0, 1 - ld * h));
			w.mul(Math.max(0, 1 - ad * h));
			List<double[]> cs = new ArrayList<>(); // px,py,pz, nx,ny,nz, depth, mu, e, otherIndex, cell
			gather(g, level, pos, R, others, cs, 0);
			if (!cs.isEmpty()) touching = true;
			double mi = solve(cs, others, pos, invIw, M, h);
			if (mi > maxImpact) { maxImpact = mi; double[] c = cs.get(0); impactAt = new Vec3(c[0], c[1], c[2]); impactCell = (int) c[10]; }
			// integrate
			Vec3 before = pos;
			pos = pos.add(v.x * h, v.y * h, v.z * h);
			double wl = w.length();
			if (wl > 1e-9) q.premul(new Quaterniond().fromAxisAngleRad(w.x / wl, w.y / wl, w.z / wl, wl * h)).normalize();
			// position projection: push out of what is still penetrating (translation only)
			Vector3d corr = new Vector3d();
			cs.sort((a, b2) -> Double.compare(b2[6], a[6]));
			for (double[] c : cs) {
				double rx = c[0] - before.x, ry = c[1] - before.y, rz = c[2] - before.z;
				double mx = (v.x + (w.y * rz - w.z * ry)) * h, my = (v.y + (w.z * rx - w.x * rz)) * h, mz = (v.z + (w.x * ry - w.y * rx)) * h;
				double d = c[6] - ((mx + corr.x) * c[3] + (my + corr.y) * c[4] + (mz + corr.z) * c[5]);
				if (d > 0.01) { double k = Math.min(d - 0.01, 0.05) * 0.6; corr.add(c[3] * k, c[4] * k, c[5] * k); }
			}
			pos = pos.add(corr.x, corr.y, corr.z);
		}
		setPos(pos);
		syncPose();
		if (impactCd > 0) impactCd--;
		if (maxImpact > 3.5 && impactCd == 0 && impactAt != null) {
			BlockState st = g.st[Math.min(impactCell, g.n - 1)];
			SoundType t = st == null ? SoundType.STONE : st.getSoundType();
			level.playSound(null, impactAt.x, impactAt.y, impactAt.z, t.getPlaceSound(), SoundSource.BLOCKS, (float) Math.min(1.2, maxImpact / 10), t.getPitch() * 0.8f);
			impactCd = 4;
		}
		if (touching && v.length() < 0.12 && w.length() < 0.12 && f("sleep", 1) > 0) { if (++still > 25) { asleep = true; v.zero(); w.zero(); } }
		else still = 0;
		if (getY() < level.getMinY() - 64) discard();
	}

	void syncPose() {
		rot.set((float) q.x, (float) q.y, (float) q.z, (float) q.w);
		Quaternionfc cur = entityData.get(ROT);
		if (Math.abs(cur.x() - rot.x) + Math.abs(cur.y() - rot.y) + Math.abs(cur.z() - rot.z) + Math.abs(cur.w() - rot.w) > 1e-5) entityData.set(ROT, new Quaternionf(rot));
		setBoundingBox(makeBoundingBox(position()));
	}

	/** Is anything holding it up? (asleep bodies re-check every 10 ticks, so breaking the block under one makes it fall) */
	boolean supported(Grid g, ServerLevel level) {
		List<double[]> cs = new ArrayList<>();
		gather(g, level, position().add(0, -0.06, 0), new Matrix3d().rotation(q), level.getEntitiesOfClass(PhysicsBlock.class, getBoundingBox().inflate(0.3), o -> o != this), cs, 0);
		return !cs.isEmpty();
	}

	/** Water pushes up on each submerged block (density < 1 floats) at that block, so tall or lopsided bodies tip. */
	void buoyancy(Grid g, ServerLevel level, Vec3 pos, Matrix3d R, Matrix3d invIw, double M, double grav, double h) {
		double buoy = f("buoyancy", 1) * (b("float", false) ? 2.5 : 1), sub = 0;
		for (int i = 0; i < g.n; i++) {
			Vec3 lc = g.cellCenterLocal(i);
			Vector3d r = R.transform(new Vector3d(lc.x, lc.y, lc.z));
			double wx = pos.x + r.x, wy = pos.y + r.y, wz = pos.z + r.z;
			BlockPos bp = BlockPos.containing(wx, wy, wz);
			FluidState fs = level.getFluidState(bp);
			if (fs.isEmpty()) continue;
			double top = level.getFluidState(bp.above()).isEmpty() ? bp.getY() + fs.getHeight(level, bp) : bp.getY() + 1.5;
			double frac = Mth.clamp(top - (wy - 0.5), 0, 1);
			if (frac <= 0) continue;
			double vol = g.box[i] == null ? 0.05 : g.box[i].getXsize() * g.box[i].getYsize() * g.box[i].getZsize();
			double fluidDensity = fs.is(FluidTags.LAVA) ? 3.0 : 1.0;
			double F = fluidDensity * vol * frac * grav * buoy * h; // impulse
			v.y += F / M;
			w.add(invIw.transform(new Vector3d(-r.z * F, 0, r.x * F))); // torque = r x (0, F, 0)
			sub += frac;
		}
		if (sub > 0) {
			double k = Math.min(1, sub / g.n);
			v.mul(Math.max(0, 1 - 1.4 * k * h));
			w.mul(Math.max(0, 1 - 2.0 * k * h));
		}
	}

	/** Contacts: every surface sample point inside a world block or another body's block. */
	void gather(Grid g, Level level, Vec3 pos, Matrix3d R, List<PhysicsBlock> others, List<double[]> out, int unused) {
		Vector3d t = new Vector3d();
		for (int k = 0; k < g.pts.length; k += 4) {
			R.transform(t.set(g.pts[k], g.pts[k + 1], g.pts[k + 2]));
			double px = pos.x + t.x, py = pos.y + t.y, pz = pos.z + t.z;
			int cell = (int) g.pts[k + 3];
			BlockPos bp = BlockPos.containing(px, py, pz);
			BlockState s = level.getBlockState(bp);
			if (!s.isAir()) {
				VoxelShape shape = s.getCollisionShape(level, bp);
				if (!shape.isEmpty()) for (AABB a : shape.toAabbs()) {
					a = a.move(bp);
					if (px <= a.minX || px >= a.maxX || py <= a.minY || py >= a.maxY || pz <= a.minZ || pz >= a.maxZ) continue;
					double[] d = {px - a.minX, a.maxX - px, py - a.minY, a.maxY - py, pz - a.minZ, a.maxZ - pz};
					int best = -1;
					for (int f = 0; f < 6; f++) {
						Direction dir = dirs()[f];
						boolean edge = (f == 0 && a.minX <= bp.getX() + 1e-6) || (f == 1 && a.maxX >= bp.getX() + 1 - 1e-6) || (f == 2 && a.minY <= bp.getY() + 1e-6)
							|| (f == 3 && a.maxY >= bp.getY() + 1 - 1e-6) || (f == 4 && a.minZ <= bp.getZ() + 1e-6) || (f == 5 && a.maxZ >= bp.getZ() + 1 - 1e-6);
						if (edge) { BlockPos nb = bp.relative(dir); BlockState ns = level.getBlockState(nb); if (!ns.isAir() && ns.isCollisionShapeFullBlock(level, nb)) continue; }
						double bias = f == 3 ? 0.08 : 0; // resting contact: prefer up unless clearly a side hit
						if (best < 0 || d[f] - bias < d[best] - (best == 3 ? 0.08 : 0)) best = f;
					}
					if (best < 0) best = 3;
					Direction n = dirs()[best];
					double wmu = Mth.clamp((1 - s.getBlock().getFriction()) * 1.5, 0.02, 1.0);
					double we = s.getBlock() instanceof net.minecraft.world.level.block.SlimeBlock ? 0.85 : 0.12;
					out.add(new double[] {px, py, pz, n.getStepX(), n.getStepY(), n.getStepZ(), d[best], Math.sqrt(wmu * g.mu[cell]) * f("friction", 1), Math.max(we, f("bounce", g.bounce[cell])), -1, cell});
					break;
				}
			}
			for (int o = 0; o < others.size(); o++) {
				double[] c = others.get(o).penetration(px, py, pz);
				if (c != null) out.add(new double[] {px, py, pz, c[0], c[1], c[2], c[3], Math.sqrt(g.mu[cell] * c[4]) * f("friction", 1), Math.max(c[5], f("bounce", g.bounce[cell])), o, cell});
			}
		}
	}

	static Direction[] dirs;
	/** Lazy: a static added by a hotswap starts null. Order matches the exit-distance arrays: -x +x -y +y -z +z. */
	static Direction[] dirs() { if (dirs == null) dirs = new Direction[] {Direction.WEST, Direction.EAST, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH}; return dirs; }

	/** If world point p is inside one of this body's cells: {nx, ny, nz (world, pointing out of this body), depth, mu, bounce}. */
	double[] penetration(double px, double py, double pz) {
		if (!getBoundingBox().contains(px, py, pz)) return null;
		Grid g = grid();
		Vec3 gp = localToGrid(toLocal(new Vec3(px, py, pz)));
		int cx = Mth.floor(gp.x), cy = Mth.floor(gp.y), cz = Mth.floor(gp.z), i = g.cell(cx, cy, cz);
		if (i < 0 || g.box[i] == null) return null;
		AABB a = g.box[i];
		double lx = gp.x - cx, ly = gp.y - cy, lz = gp.z - cz;
		if (lx <= a.minX || lx >= a.maxX || ly <= a.minY || ly >= a.maxY || lz <= a.minZ || lz >= a.maxZ) return null;
		double[] d = {lx - a.minX, a.maxX - lx, ly - a.minY, a.maxY - ly, lz - a.minZ, a.maxZ - lz};
		int best = -1;
		for (int f = 0; f < 6; f++) {
			Direction dir = dirs()[f];
			if (g.full(cx + dir.getStepX(), cy + dir.getStepY(), cz + dir.getStepZ())) continue;
			if (best < 0 || d[f] < d[best]) best = f;
		}
		if (best < 0) return null;
		Vec3 n = toWorld(new Vec3(dirs()[best].getStepX(), dirs()[best].getStepY(), dirs()[best].getStepZ()), true);
		return new double[] {n.x, n.y, n.z, d[best], g.mu[i], g.bounce[i]};
	}

	/** Sequential impulses: normal (with restitution on hard hits) + Coulomb friction. Returns the hardest approach speed. */
	double solve(List<double[]> cs, List<PhysicsBlock> others, Vec3 pos, Matrix3d invIw, double M, double h) {
		int n = cs.size();
		if (n == 0) return 0;
		double[] jn = new double[n], target = new double[n];
		Vector3d[] jt = new Vector3d[n];
		Matrix3d[] oInv = new Matrix3d[others.size()];
		double[] oM = new double[others.size()];
		for (int o = 0; o < others.size(); o++) { PhysicsBlock b = others.get(o); oM[o] = b.massNow(); oInv[o] = b.invInertiaWorld(oM[o] / b.grid().totalMass); }
		double hardest = 0;
		Vector3d r = new Vector3d(), ro = new Vector3d(), nn = new Vector3d(), vr = new Vector3d(), tmp = new Vector3d();
		for (int i = 0; i < n; i++) {
			double[] c = cs.get(i);
			jt[i] = new Vector3d();
			relVel(c, pos, others, r, ro, vr, tmp);
			double vn = vr.x * c[3] + vr.y * c[4] + vr.z * c[5];
			if (-vn > hardest) hardest = -vn;
			target[i] = vn < -2.0 ? -c[8] * vn : 0;
		}
		for (int it = 0; it < 10; it++) {
			for (int i = 0; i < n; i++) {
				double[] c = cs.get(i);
				int o = (int) c[9];
				nn.set(c[3], c[4], c[5]);
				relVel(c, pos, others, r, ro, vr, tmp);
				double vn = vr.dot(nn);
				double k = effMass(nn, r, invIw, M) + (o >= 0 ? effMass(nn, ro, oInv[o], oM[o]) : 0);
				double dj = (target[i] - vn) / k;
				double nj = Math.max(0, jn[i] + dj);
				dj = nj - jn[i];
				jn[i] = nj;
				apply(nn.x * dj, nn.y * dj, nn.z * dj, r, ro, invIw, M, o, others, oInv, oM);
				// friction
				relVel(c, pos, others, r, ro, vr, tmp);
				vn = vr.dot(nn);
				Vector3d vt = new Vector3d(vr).sub(new Vector3d(nn).mul(vn));
				double vtl = vt.length();
				if (vtl < 1e-7) continue;
				Vector3d tdir = new Vector3d(vt).div(vtl);
				double kt = effMass(tdir, r, invIw, M) + (o >= 0 ? effMass(tdir, ro, oInv[o], oM[o]) : 0);
				Vector3d want = new Vector3d(tdir).mul(-vtl / kt);
				Vector3d acc = new Vector3d(jt[i]).add(want);
				double max = c[7] * jn[i];
				if (acc.length() > max) acc.normalize(max);
				Vector3d d = new Vector3d(acc).sub(jt[i]);
				jt[i].set(acc);
				apply(d.x, d.y, d.z, r, ro, invIw, M, o, others, oInv, oM);
			}
		}
		return hardest;
	}

	void relVel(double[] c, Vec3 pos, List<PhysicsBlock> others, Vector3d r, Vector3d ro, Vector3d out, Vector3d tmp) {
		r.set(c[0] - pos.x, c[1] - pos.y, c[2] - pos.z);
		out.set(v).add(w.cross(r, tmp));
		int o = (int) c[9];
		if (o >= 0) {
			PhysicsBlock b = others.get(o);
			ro.set(c[0] - b.getX(), c[1] - b.getY(), c[2] - b.getZ());
			out.sub(b.v).sub(b.w.cross(ro, tmp));
		}
	}

	static double effMass(Vector3d n, Vector3d r, Matrix3d invI, double M) {
		Vector3d rn = r.cross(n, new Vector3d());
		Vector3d x = invI.transform(rn, new Vector3d()).cross(r);
		return 1 / M + n.dot(x);
	}

	void apply(double jx, double jy, double jz, Vector3d r, Vector3d ro, Matrix3d invIw, double M, int o, List<PhysicsBlock> others, Matrix3d[] oInv, double[] oM) {
		Vector3d j = new Vector3d(jx, jy, jz);
		v.add(jx / M, jy / M, jz / M);
		w.add(invIw.transform(r.cross(j, new Vector3d())));
		if (o >= 0) {
			PhysicsBlock b = others.get(o);
			b.v.sub(jx / oM[o], jy / oM[o], jz / oM[o]);
			b.w.sub(oInv[o].transform(ro.cross(j, new Vector3d())));
			if (b.asleep && j.length() > 1e-3) b.wake();
		}
	}

	// ================================================================== mining / building (never punched)

	@Override public boolean isPushable() { return false; }
	@Override public boolean isPickable() { return !isRemoved(); }
	@Override public boolean canBeCollidedWith(Entity other) { return b("solid", true); }
	@Override public boolean isAttackable() { return false; }
	@Override public boolean skipAttackInteraction(Entity attacker) { return true; }
	@Override public boolean hurtServer(ServerLevel level, DamageSource source, float amount) { return false; }
	@Override public void push(Entity e) {}
	/** Explosions and scripts push it like a heavy object (heavier = less). Walking into it does nothing. */
	@Override public void push(double x, double y, double z) { if (!level().isClientSide()) applyImpulse(position(), new Vec3(x, y, z).scale(20 * 1.5)); }
	@Override public boolean ignoreExplosion(net.minecraft.world.level.Explosion e) { return false; }

	/** Breaks one cell (cell coords): drops like mining it, splits the body when it falls apart. */
	public void breakCell(int cx, int cy, int cz, ServerPlayer by) {
		Grid g = grid();
		int i = g.cell(cx, cy, cz);
		if (i < 0) return;
		ServerLevel level = (ServerLevel) level();
		BlockState st = g.st[i];
		Vec3 at = toWorld(g.cellCenterLocal(i));
		BlockPos bp = BlockPos.containing(at);
		if (st != null) {
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, st), at.x, at.y, at.z, 30, 0.3, 0.3, 0.3, 0.1);
			level.playSound(null, at.x, at.y, at.z, st.getSoundType().getBreakSound(), SoundSource.BLOCKS, 1, st.getSoundType().getPitch() * 0.8f);
			boolean creative = by != null && by.isCreative();
			if (!creative && (by == null || !st.requiresCorrectToolForDrops() || by.hasCorrectToolForDrops(st))) {
				ItemStack tool = by == null ? ItemStack.EMPTY : by.getMainHandItem();
				for (ItemStack drop : Block.getDrops(st, level, bp, null, by, tool)) {
					ItemEntity ie = new ItemEntity(level, at.x, at.y, at.z, drop);
					ie.setDefaultPickUpDelay();
					level.addFreshEntity(ie);
				}
			}
			if (by != null && !creative) by.getMainHandItem().mineBlock(level, st, bp, by);
		} else {
			String drop = s("drop");
			if (drop != null) level.addFreshEntity(new ItemEntity(level, at.x, at.y, at.z, new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(drop)))));
		}
		List<int[]> cells = new ArrayList<>();
		List<BlockState> states = new ArrayList<>();
		for (int k = 0; k < g.n; k++) if (k != i) { cells.add(new int[] {g.x[k], g.y[k], g.z[k]}); states.add(g.st[k]); }
		rebuild(cells, states);
	}

	/** Places a block onto the face of a cell (building on the body). */
	public boolean placeCell(int cx, int cy, int cz, BlockState st) {
		Grid g = grid();
		if (g.cell(cx, cy, cz) >= 0) return false;
		Vec3 at = toWorld(gridToLocal(new Vec3(cx + 0.5, cy + 0.5, cz + 0.5)));
		if (!level().getEntities(this, new AABB(at, at).inflate(0.45), e -> !(e instanceof PhysicsBlock) && !(e instanceof ItemEntity) && e.isAlive()).isEmpty()) return false; // not into a player
		List<int[]> cells = new ArrayList<>();
		List<BlockState> states = new ArrayList<>();
		for (int k = 0; k < g.n; k++) { cells.add(new int[] {g.x[k], g.y[k], g.z[k]}); states.add(g.st[k]); }
		cells.add(new int[] {cx, cy, cz});
		states.add(st);
		rebuild(cells, states);
		level().playSound(null, at.x, at.y, at.z, st.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1, st.getSoundType().getPitch() * 0.8f);
		return true;
	}

	/** New layout: keeps the world pose of every remaining block (the centre of mass moves) and splits disconnected parts off. */
	void rebuild(List<int[]> cells, List<BlockState> states) {
		if (cells.isEmpty()) { discard(); return; }
		List<List<Integer>> parts = components(cells);
		Vector3d oldCom = new Vector3d(grid().com);
		List<Grid> grids = new ArrayList<>();
		List<Vec3> poss = new ArrayList<>(), vels = new ArrayList<>();
		for (List<Integer> part : parts) { // compute every part's pose from the current pose first
			List<int[]> pc = new ArrayList<>();
			List<BlockState> ps = new ArrayList<>();
			for (int idx : part) { pc.add(cells.get(idx)); ps.add(states.get(idx)); }
			Grid ng = new Grid(pc, ps);
			Vec3 np = toWorld(new Vec3(ng.com.x - oldCom.x, ng.com.y - oldCom.y, ng.com.z - oldCom.z));
			Vector3d pv = new Vector3d(v).add(w.cross(new Vector3d(np.x - getX(), np.y - getY(), np.z - getZ()), new Vector3d()));
			grids.add(ng); poss.add(np); vels.add(new Vec3(pv.x, pv.y, pv.z));
		}
		for (int p = 0; p < grids.size(); p++) {
			PhysicsBlock t = this;
			if (p > 0) {
				t = new PhysicsBlock(getType(), level());
				t.entityData.set(OPTIONS, entityData.get(OPTIONS));
				t.q.set(q); t.rot.set(rot); t.w.set(w);
			}
			t.entityData.set(SPEC, grids.get(p).spec());
			t.grid = null;
			t.entityData.set(ROT, new Quaternionf(rot));
			t.setPos(poss.get(p));
			t.v.set(vels.get(p).x, vels.get(p).y, vels.get(p).z);
			t.wake();
			if (p > 0) level().addFreshEntity(t);
		}
	}

	static List<List<Integer>> components(List<int[]> cells) {
		HashMap<Long, Integer> at = new HashMap<>();
		for (int i = 0; i < cells.size(); i++) at.put(Grid.key(cells.get(i)[0], cells.get(i)[1], cells.get(i)[2]), i);
		boolean[] seen = new boolean[cells.size()];
		List<List<Integer>> out = new ArrayList<>();
		for (int s = 0; s < cells.size(); s++) {
			if (seen[s]) continue;
			List<Integer> part = new ArrayList<>();
			java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
			q.add(s); seen[s] = true;
			while (!q.isEmpty()) {
				int i = q.poll();
				part.add(i);
				int[] c = cells.get(i);
				for (Direction d : dirs()) {
					Integer j = at.get(Grid.key(c[0] + d.getStepX(), c[1] + d.getStepY(), c[2] + d.getStepZ()));
					if (j != null && !seen[j]) { seen[j] = true; q.add(j); }
				}
			}
			out.add(part);
		}
		out.sort((a, b) -> b.size() - a.size());
		return out;
	}

	/** Right-click with a block: build onto the face you look at. */
	@Override
	public InteractionResult interact(Player player, InteractionHand hand, Vec3 at) {
		ItemStack stack = player.getItemInHand(hand);
		if (!(stack.getItem() instanceof BlockItem bi)) return InteractionResult.PASS;
		BlockHitResult h = rayCell(player.getEyePosition(), player.getEyePosition().add(player.getLookAngle().scale(8)), 0);
		if (h == null) return InteractionResult.PASS;
		if (level().isClientSide()) return InteractionResult.SUCCESS;
		BlockPos c = h.getBlockPos().relative(h.getDirection());
		if (placeCell(c.getX(), c.getY(), c.getZ(), bi.getBlock().defaultBlockState()) && !player.isCreative()) stack.shrink(1);
		return InteractionResult.SUCCESS;
	}

	// ================================================================== client position (server authoritative)

	/** Remembers the newest server position; tick() applies it (render lerps old -> new like the rotation). */
	static final class Predict implements InterpolationHandler {
		Vec3 server;
		@Override public net.minecraft.core.PositionAndRotation target() { return null; }
		@Override public boolean interpolateTo(net.minecraft.world.entity.PositionPath p, float yRot, float xRot, boolean hasRot) { if (p != null) server = p.endPosition(); return true; }
		@Override public void interpolate() {}
		@Override public void applyPredictedMovement(Vec3 delta) {}
		@Override public boolean hasActiveInterpolation() { return false; }
		@Override public void cancel() {}
	}

	// ================================================================== save

	@Override
	protected void readAdditionalSaveData(ValueInput in) {
		setSpec(in.getStringOr("spec", ""));
		age = in.getIntOr("age", 0);
		entityData.set(OPTIONS, in.getStringOr("options", ""));
		try {
			String[] s = in.getStringOr("body", "").split(",");
			if (s.length == 10) {
				v.set(Double.parseDouble(s[0]), Double.parseDouble(s[1]), Double.parseDouble(s[2]));
				w.set(Double.parseDouble(s[3]), Double.parseDouble(s[4]), Double.parseDouble(s[5]));
				q.set(Double.parseDouble(s[6]), Double.parseDouble(s[7]), Double.parseDouble(s[8]), Double.parseDouble(s[9]));
				rot.set((float) q.x, (float) q.y, (float) q.z, (float) q.w);
				entityData.set(ROT, new Quaternionf(rot));
			}
		} catch (RuntimeException ignored) {}
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput out) {
		out.putString("spec", specId());
		out.putInt("age", age);
		out.putString("options", entityData.get(OPTIONS));
		out.putString("body", v.x + "," + v.y + "," + v.z + "," + w.x + "," + w.y + "," + w.z + "," + q.x + "," + q.y + "," + q.z + "," + q.w);
	}
}
