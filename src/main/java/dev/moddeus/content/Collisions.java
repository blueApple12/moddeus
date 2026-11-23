package dev.moddeus.content;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Entity collision shapes (called by LevelCollisionMixin instead of EntityGetter.getEntityCollisions). Vanilla gives
 * every entity its axis-aligned box; physics blocks give their rotated (diagonal) shape. Plain class: hot-swappable.
 */
public final class Collisions {
	public static List<VoxelShape> entities(Level level, Entity source, AABB area) {
		if (area.getSize() < 1.0E-7) return List.of();
		Predicate<Entity> can = source == null ? EntitySelector.CAN_BE_COLLIDED_WITH : EntitySelector.NO_SPECTATORS.and(source::canCollideWith);
		AABB a = area.inflate(1.0E-7);
		// physics block corners can stick out of their (block-wide) box when tilted: search a bit wider for them
		List<Entity> hits = level.getEntities(source, a.inflate(0.5), can);
		if (hits.isEmpty()) return List.of();
		List<VoxelShape> out = new ArrayList<>(hits.size());
		for (Entity e : hits) {
			if (e instanceof PhysicsBlock b) { if (!b.tilted()) for (AABB c : b.worldBoxes(a)) out.add(Shapes.create(c)); } // every cell; tilted ones: resolve()
			else if (e.getBoundingBox().intersects(a)) out.add(Shapes.create(e.getBoundingBox()));
		}
		return out;
	}

	/** ProjectileUtil.getEntityHitResult (crosshair, arrows) with physics blocks tested against their rotated box. Vanilla logic otherwise. */
	public static EntityHitResult pick(Entity shooter, Vec3 from, Vec3 to, AABB area, Predicate<Entity> filter, double maxSq) {
		double best = maxSq;
		Entity hitE = null;
		Vec3 hitP = null;
		for (Entity e : shooter.level().getEntities(shooter, area.inflate(0.5), filter)) {
			double r = e.getPickRadius();
			java.util.Optional<Vec3> clip;
			boolean inside;
			if (e instanceof PhysicsBlock b && b.rot != null) { clip = b.clipRotated(from, to, r); inside = b.containsRotated(from, r); }
			else { AABB bb = e.getBoundingBox().inflate(r); clip = bb.clip(from, to); inside = bb.contains(from); }
			if (inside) {
				if (best >= 0 && e.canBePickedFromInside()) { hitE = e; hitP = clip.orElse(from); best = 0; }
			} else if (clip.isPresent()) {
				Vec3 p = clip.get();
				double d = from.distanceToSqr(p);
				if (d < best || best == 0) {
					if (e.getRootVehicle() == shooter.getRootVehicle()) { if (best == 0) { hitE = e; hitP = p; } }
					else { hitE = e; hitP = p; best = d; }
				}
			}
		}
		return hitE == null ? null : new EntityHitResult(hitE, hitP);
	}

	// ---- tilted physics blocks: true rotated-box collision (separating axis test), the way Create's contraption collider
	// and Valkyrien Skies do it. Vanilla movement runs first, then the entity is pushed out along the real surface. ----

	/** Pushes e out of every tilted physics block it overlaps. Client: local player (ClientTasks), server: mobs/items (PhysicsBlock.tick). */
	static java.util.Set<PhysicsBlock> active; // tilted blocks this tick (server)
	static java.util.Set<PhysicsBlock> active() { if (active == null) active = new java.util.HashSet<>(); return active; }

	/** End of server tick (Quick.tick): mobs/items vs tilted blocks, after everything moved. Players resolve on their own client. */
	public static void serverTick() {
		if (active == null || active.isEmpty()) return;
		var blocks = new ArrayList<>(active);
		active.clear();
		java.util.Set<Entity> done = new java.util.HashSet<>();
		for (PhysicsBlock b : blocks) {
			if (b.isRemoved()) continue;
			for (Entity e : b.level().getEntities(b, b.getBoundingBox().inflate(1), x -> !(x instanceof net.minecraft.world.entity.player.Player) && !(x instanceof PhysicsBlock)))
				if (done.add(e)) resolve(e);
		}
	}

	public static boolean resolve(Entity e) {
		if (e == null || e.isSpectator() || e.noPhysics || e instanceof PhysicsBlock || e.isPassenger()) return false;
		boolean any = false;
		List<PhysicsBlock> near = e.level().getEntitiesOfClass(PhysicsBlock.class, e.getBoundingBox().inflate(3));
		if (near.isEmpty()) return false;
		// riding: standing on a body moves you with it (like walking on an Aeronautics ship)
		AABB feet = new AABB(e.getBoundingBox().minX + 0.05, e.getBoundingBox().minY - 0.15, e.getBoundingBox().minZ + 0.05, e.getBoundingBox().maxX - 0.05, e.getBoundingBox().minY + 0.05, e.getBoundingBox().maxZ - 0.05);
		for (PhysicsBlock b : near) {
			if (!b.canBeCollidedWith(e) || !touches(b, feet)) continue;
			Vec3 p = new Vec3(e.getX(), e.getBoundingBox().minY, e.getZ());
			Vec3 d = p.subtract(b.previousOf(p));
			if (d.lengthSqr() > 1e-8 && d.lengthSqr() < 4) e.setPos(e.position().add(d));
			break;
		}
		for (PhysicsBlock b : near) {
			if (!b.tilted() || !b.canBeCollidedWith(e)) continue;
			// sub-step from last tick's position so a fast fall can't pass through the block
			Vec3 now = e.position(), old = new Vec3(e.xo, e.yo, e.zo);
			double moved = now.distanceTo(old);
			int steps = moved > 5 ? 0 : Math.min(16, (int) Math.ceil(moved / 0.3));
			for (double[] o : b.obbs(e.getBoundingBox().inflate(moved + 0.5))) {
				Vec3 mtv = null;
				for (int i = 1; i <= steps && mtv == null; i++) {
					Vec3 at = old.lerp(now, (double) i / steps);
					mtv = sat(e.getBoundingBox().move(at.subtract(e.position())), o, b.rot);
					if (mtv != null && i < steps) e.setPos(at);
				}
				if (steps == 0) mtv = sat(e.getBoundingBox(), o, b.rot);
				if (mtv == null) continue;
				any = true;
				double len = mtv.length();
				Vec3 n = mtv.scale(1 / len), v = e.getDeltaMovement();
				if (n.y > 0.7) { // walkable face (< ~45 degrees): lift straight up, no creeping downhill
					e.setPos(e.getX(), e.getY() + len / n.y, e.getZ());
					if (v.y < 0) v = new Vec3(v.x, 0, v.z);
					e.setOnGround(true);
					e.resetFallDistance();
				} else { // steep face or side: push out along the face, keep the sliding part of the motion
					e.setPos(e.position().add(mtv));
					double into = v.dot(n);
					if (into < 0) v = v.subtract(n.scale(into));
				}
				e.setDeltaMovement(v);
				now = e.position(); old = now; steps = 0;
			}
		}
		return any;
	}

	static boolean touches(PhysicsBlock b, AABB box) {
		if (!b.tilted()) { for (AABB c : b.worldBoxes(box)) if (c.intersects(box)) return true; return false; }
		for (double[] o : b.obbs(box)) if (sat(box, o, b.rot) != null) return true;
		return false;
	}

	/** Minimum push that separates box a from an oriented box {cx,cy,cz, hx,hy,hz} with axes rot, or null (15-axis SAT). */
	static Vec3 sat(AABB a, double[] o, org.joml.Quaternionf rot) {
		Vec3 c = new Vec3(o[0], o[1], o[2]);
		org.joml.Vector3f[] ax = { rot.transform(new org.joml.Vector3f(1, 0, 0)), rot.transform(new org.joml.Vector3f(0, 1, 0)), rot.transform(new org.joml.Vector3f(0, 0, 1)) };
		double[] he = { o[3], o[4], o[5] };
		Vec3[] obb = { new Vec3(ax[0].x, ax[0].y, ax[0].z), new Vec3(ax[1].x, ax[1].y, ax[1].z), new Vec3(ax[2].x, ax[2].y, ax[2].z) };
		Vec3[] world = { new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1) };
		double ex = a.getXsize() / 2, ey = a.getYsize() / 2, ez = a.getZsize() / 2;
		Vec3 d = a.getCenter().subtract(c);
		List<Vec3> axes = new ArrayList<>(15);
		for (Vec3 w : world) axes.add(w);
		for (Vec3 ob : obb) axes.add(ob);
		for (Vec3 w : world) for (Vec3 ob : obb) axes.add(w.cross(ob));
		double best = Double.MAX_VALUE;
		Vec3 bestAxis = null;
		for (Vec3 l : axes) {
			double len = l.length();
			if (len < 1e-4) continue;
			l = l.scale(1 / len);
			double ra = ex * Math.abs(l.x) + ey * Math.abs(l.y) + ez * Math.abs(l.z);
			double rb = he[0] * Math.abs(obb[0].dot(l)) + he[1] * Math.abs(obb[1].dot(l)) + he[2] * Math.abs(obb[2].dot(l));
			double dist = d.dot(l), ov = ra + rb - Math.abs(dist);
			if (ov <= 1e-5) return null;
			if (ov < best) { best = ov; bestAxis = dist < 0 ? l.scale(-1) : l; }
		}
		return bestAxis == null ? null : bestAxis.scale(best + 1e-4);
	}
}
