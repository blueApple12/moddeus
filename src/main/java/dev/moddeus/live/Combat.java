package dev.moddeus.live;

import dev.moddeus.content.Vfx;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Combat and abilities toolkit for every mod (Java only: Combat.dash(player, 1.6) or Combat.exec(player, "dash", "1.6")).
 * Projectiles (homing, bouncing, piercing, boomerang, AoE, cluster, chain), beams, arrow rain, dash/blink/grapple/slam/super jump,
 * perks (double jump, glide, wall run: client side in CombatClient), shield/parry/i-frames, minions/turrets/decoys/totems,
 * zones/auras/mines/traps, energy + cooldowns + charge + combo (CombatClient draws only charge bar + combo; mods draw their own cooldown HUD), damage types with death messages.
 * Every ability plays its own combat_* VFX (soft: a missing VFX is skipped). Guide: .claude/skills/mc-content/COMBAT.md
 */
public final class Combat {
	public static volatile boolean enabled = true;
	static Map<UUID, Stats> stats;
	static List<Active> actives;
	static Map<UUID, UUID> allies; // summoned/decoy entity -> owner
	static Set<UUID> decoys;
	static int seq;

	/** Per-entity combat state (energy, cooldowns, defenses). Read by the HUD. */
	public static final class Stats {
		public volatile float energy = 100, max = 100, regen = 0.5f; // regen per tick (10/s)
		public final Map<String, long[]> cooldowns = new ConcurrentHashMap<>(); // name -> {ready tick, total ticks}
		public final Set<String> perks = ConcurrentHashMap.newKeySet();
		public volatile String charging;
		public volatile float charge;
		public volatile long iframes, parry, shield, lastSpend, comboUntil;
		public volatile int shieldHits, combo;
		public volatile String comboName;
	}

	/** Something that runs every server tick until tick returns false. */
	public interface Active { boolean tick(long now); default void end() {} }

	static Map<UUID, Stats> statsMap() { if (stats == null) stats = new ConcurrentHashMap<>(); return stats; }
	static List<Active> actives() { if (actives == null) actives = new CopyOnWriteArrayList<>(); return actives; }
	static Map<UUID, UUID> allyMap() { if (allies == null) allies = new ConcurrentHashMap<>(); return allies; }
	static Set<UUID> decoySet() { if (decoys == null) decoys = ConcurrentHashMap.newKeySet(); return decoys; }

	public static Stats stats(Entity e) { return statsMap().computeIfAbsent(e.getUUID(), k -> new Stats()); }
	/** Stats or null (HUD). */
	public static Stats peek(UUID u) { return stats == null ? null : stats.get(u); }
	public static long now() { var s = LiveRegistry.server; return s == null ? 0 : s.getTickCount(); }
	/** Runs a custom ability every tick until it returns false. */
	public static void run(Active a) { actives().add(a); }

	// ---- energy, cooldowns, combo, charge ----------------------------------------------------------------------

	/** Gate for an ability: false (with an actionbar hint) while cooling down or short on energy; else spends both. Mobs pay no energy. */
	public static boolean use(LivingEntity e, String name, int cooldownTicks, float cost) {
		Stats s = stats(e);
		long now = now();
		long[] cd = s.cooldowns.get(name);
		if (cd != null && cd[0] > now) {
			// readable label, never a raw id ("sp_shapeshift_v 0.1s" showed mid-screen): drop sp_/mod prefix, _ -> space,
			// a trailing one-letter key in caps; skip the hint for the last quarter second (key mashing spam)
			if (cd[0] - now > 5) {
				String label = name.replaceFirst("^sp_", "").replace('_', ' ').trim();
				int sp = label.lastIndexOf(' ');
				if (sp > 0 && label.length() - sp == 2) label = label.substring(0, sp) + " " + label.substring(sp + 1).toUpperCase(java.util.Locale.ROOT);
				if (!label.isEmpty()) label = Character.toUpperCase(label.charAt(0)) + label.substring(1);
				tell(e, "§7" + label + " ready in §f" + String.format("%.1fs", (cd[0] - now) / 20f));
			}
			return false;
		}
		boolean pays = e instanceof Player;
		if (pays && s.energy < cost) { tell(e, "§cNot enough energy §7(" + (int) s.energy + "/" + (int) cost + ")"); return false; }
		if (pays && cost > 0) { s.energy -= cost; s.lastSpend = now; }
		if (cooldownTicks > 0) s.cooldowns.put(name, new long[] { now + cooldownTicks, cooldownTicks });
		return true;
	}
	/** Remaining cooldown 0..1 (0 = ready). */
	public static float cooldown(LivingEntity e, String name) {
		long[] cd = stats(e).cooldowns.get(name);
		return cd == null ? 0 : Math.max(0, (cd[0] - now()) / (float) Math.max(1, cd[1]));
	}
	public static void resetCooldown(LivingEntity e, String name) { stats(e).cooldowns.remove(name); }
	/** Energy pool: max and regen per second (default 100, 10/s). */
	public static void energy(LivingEntity e, float max, float regenPerSecond) { Stats s = stats(e); s.max = max; s.regen = regenPerSecond / 20f; s.energy = Math.min(s.energy, max); }
	public static void addEnergy(LivingEntity e, float amount) { Stats s = stats(e); s.energy = Math.max(0, Math.min(s.max, s.energy + amount)); }
	/** Combo counter: 1, 2, 3... while calls come within windowTicks of each other, else back to 1. HUD shows x2, x3... */
	public static int combo(LivingEntity e, String name, int windowTicks) {
		Stats s = stats(e);
		long now = now();
		s.combo = name.equals(s.comboName) && now <= s.comboUntil ? s.combo + 1 : 1;
		s.comboName = name;
		s.comboUntil = now + windowTicks;
		return s.combo;
	}
	/** Hold to charge: fills 0..1 over maxTicks while holding() is true (HUD bar), then calls release(fraction). */
	public static void charge(LivingEntity e, String name, int maxTicks, BooleanSupplier holding, DoubleConsumer release) { run(new Charge(e, name, maxTicks, holding, release)); }

	static final class Charge implements Active {
		final LivingEntity e; final String name; final int max; final BooleanSupplier holding; final DoubleConsumer release; int t, age;
		Charge(LivingEntity e, String name, int max, BooleanSupplier holding, DoubleConsumer release) { this.e = e; this.name = name; this.max = Math.max(1, max); this.holding = holding; this.release = release; }
		public boolean tick(long now) {
			Stats s = stats(e);
			if (!e.isAlive() || ++age > 600) { end(); return false; }
			if (holding.getAsBoolean()) {
				t = Math.min(max, t + 1);
				s.charging = name; s.charge = t / (float) max;
				if (t == max && age % 4 == 0) fx("combat_charge_full", e.position(), Vec3.ZERO, e);
				else if (age % 10 == 1) fx("combat_charge", e.position(), Vec3.ZERO, e);
				return true;
			}
			end();
			if (t > 0) release.accept(t / (double) max);
			return false;
		}
		public void end() { Stats s = stats(e); if (name.equals(s.charging)) { s.charging = null; s.charge = 0; } }
	}

	// ---- damage ---------------------------------------------------------------------------------------------------

	/** Damage of a type: moddeus:arcane|laser|shock|shrapnel|slam|frost|void|blade (own death messages) or any ns:id; falls back to magic. */
	public static boolean damage(LivingEntity target, Entity attacker, float amount, String type) {
		if (!(target.level() instanceof ServerLevel l) || amount <= 0 || !target.isAlive()) return false;
		target.setInvulnerableTime(0); // abilities control their own hit rate
		return target.hurtServer(l, source(l, attacker, type), amount);
	}

	public static DamageSource source(ServerLevel l, Entity attacker, String type) {
		if (type != null && !type.isEmpty()) {
			Identifier id = type.contains(":") ? Identifier.parse(type) : LiveRegistry.id(type);
			var h = l.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).get(ResourceKey.create(Registries.DAMAGE_TYPE, id));
			if (h.isPresent()) return new DamageSource(h.get(), attacker, attacker);
		}
		return attacker == null ? l.damageSources().magic() : l.damageSources().indirectMagic(attacker, attacker);
	}

	/** AoE: damage falling off with distance + knockback away from c. Returns how many were hit. */
	public static int blast(Entity owner, Vec3 c, double r, float dmg, String type, double knock) {
		int n = 0;
		for (LivingEntity t : enemies(owner, c, r)) {
			double f = 1 - 0.5 * Math.min(1, t.position().distanceTo(c) / r);
			damage(t, owner, (float) (dmg * f), type);
			Vec3 away = t.position().subtract(c).multiply(1, 0, 1);
			away = away.lengthSqr() < 1e-4 ? Vec3.ZERO : away.normalize();
			push(t, away.scale(knock * f).add(0, 0.3 * knock * f, 0));
			n++;
		}
		return n;
	}

	// ---- teams ------------------------------------------------------------------------------------------------------

	static UUID owner(Entity e) { UUID o = allies == null ? null : allies.get(e.getUUID()); return o != null ? o : e.getUUID(); }
	static boolean playerSide(Entity e) { if (e instanceof Player) return true; UUID o = allies == null ? null : allies.get(e.getUUID()); return o != null && e.level().getPlayerByUUID(o) != null; }
	/** Same side: itself, its summons, its owner. */
	public static boolean friendly(Entity a, Entity b) { return a == b || owner(a).equals(owner(b)); }
	public static boolean enemy(Entity caster, LivingEntity t) {
		if (!t.isAlive() || t instanceof ArmorStand || friendly(caster, t)) return false;
		return playerSide(caster) ? !playerSide(t) : playerSide(t);
	}
	/** Living enemies of caster within r of c. */
	public static List<LivingEntity> enemies(Entity caster, Vec3 c, double r) {
		return caster.level().getEntitiesOfClass(LivingEntity.class, new AABB(c, c).inflate(r), t -> t.distanceToSqr(c) <= r * r && enemy(caster, t));
	}
	static LivingEntity nearestEnemy(Entity caster, Vec3 c, double r, Vec3 dir, double minDot) {
		LivingEntity best = null; double bd = Double.MAX_VALUE;
		for (LivingEntity t : enemies(caster, c, r)) {
			Vec3 to = t.getBoundingBox().getCenter().subtract(c);
			if (dir != null && to.normalize().dot(dir.normalize()) < minDot) continue;
			if (to.lengthSqr() < bd) { bd = to.lengthSqr(); best = t; }
		}
		return best;
	}

	// ---- shared helpers ------------------------------------------------------------------------------------------

	/** Plays a VFX if it exists (missing = silent). */
	public static void fx(String id, Vec3 pos, Vec3 dir, Entity follow) {
		if (id == null || id.isEmpty() || Vfx.DEFS == null) return;
		try { var k = Vfx.id(id); if (Vfx.DEFS.containsKey(k)) Vfx.play(k, pos, dir, follow); } catch (Exception ignored) {}
	}
	/** Draws a line of VFX beads from a to b (laser, rope, bolt): one fx every step blocks; jitter > 0 = zig-zag lightning. */
	public static void line(String id, Vec3 a, Vec3 b, double step, double jitter) {
		Vec3 d = b.subtract(a);
		int n = Math.max(1, (int) Math.min(80, d.length() / step));
		Vec3 off = Vec3.ZERO;
		for (int k = 0; k <= n; k++) {
			if (jitter > 0) off = k == n ? Vec3.ZERO : off.scale(0.6).add((Math.random() - 0.5) * jitter, (Math.random() - 0.5) * jitter, (Math.random() - 0.5) * jitter);
			fx(id, a.add(d.scale(k / (double) n)).add(off), d, null);
		}
	}
	/** Sets velocity (synced to players). */
	public static void velocity(Entity e, Vec3 v) {
		e.setDeltaMovement(v);
		e.needsSync = true;
		if (e instanceof ServerPlayer sp) sp.connection.send(new ClientboundSetEntityMotionPacket(sp));
	}
	public static void push(Entity e, Vec3 v) { velocity(e, e.getDeltaMovement().add(v)); }
	static void tell(Entity e, String s) { if (e instanceof ServerPlayer p) p.sendSystemMessage(Component.literal(s), true); }
	static void noFall(LivingEntity e) { e.resetFallDistance(); if (e instanceof ServerPlayer sp) Quick.safeLanding(sp); }
	/** Hand position (beams/ropes start here). */
	public static Vec3 hand(LivingEntity e) {
		Vec3 look = e.getLookAngle(), right = look.cross(new Vec3(0, 1, 0));
		right = right.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : right.normalize();
		return e.getEyePosition().add(look.scale(0.5)).add(right.scale(0.35)).add(0, -0.3, 0);
	}
	static BlockHitResult clip(Entity e, Vec3 a, Vec3 b) { return e.level().clip(new ClipContext(a, b, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, e)); }
	/** Point the entity looks at (block hit or range ahead). */
	public static Vec3 aim(LivingEntity e, double range) {
		Vec3 eye = e.getEyePosition(), end = eye.add(e.getLookAngle().scale(range));
		BlockHitResult h = clip(e, eye, end);
		return h.getType() == HitResult.Type.MISS ? end : h.getLocation();
	}
	/** Drops pos to the ground below (max 12 blocks). */
	public static Vec3 ground(net.minecraft.world.level.Level l, Vec3 p) {
		BlockHitResult h = l.clip(new ClipContext(p.add(0, 0.5, 0), p.add(0, -12, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty()));
		return h.getType() == HitResult.Type.MISS ? p : h.getLocation();
	}
	static void cmd(ServerLevel l, Vec3 pos, String c) {
		var s = l.getServer();
		s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withLevel(l).withPosition(pos).withSuppressedOutput(), c);
	}
	/** A glowing item_display (visual for projectiles, turrets, totems, mines). Killed with the ability. */
	public static Display.ItemDisplay display(ServerLevel l, Vec3 pos, String item, float scale, boolean billboard) {
		String tag = "moddeus_cmb" + (++seq) + "_" + (System.nanoTime() & 0xffff);
		cmd(l, pos, "summon item_display ~ ~ ~ {Tags:[\"" + tag + "\",\"moddeus_combat\"],item:{id:\"" + item + "\",count:1},billboard:\"" + (billboard ? "center" : "fixed")
			+ "\",teleport_duration:1,brightness:{sky:15,block:15},transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],scale:["
			+ scale + "f," + scale + "f," + scale + "f]}}");
		var list = l.getEntitiesOfClass(Display.ItemDisplay.class, new AABB(pos, pos).inflate(2), d -> d.entityTags().contains(tag));
		return list.isEmpty() ? null : list.get(0);
	}
	static void kill(Entity e) { if (e != null && !e.isRemoved()) e.discard(); }

	// ---- projectiles ------------------------------------------------------------------------------------------------

	/** Projectile spec. Shot.of().homing(0.2).bounces(2).radius(3)... or Shot.parse("speed=1.5 homing=0.2 cluster=6"). */
	public static final class Shot {
		public double speed = 1.2, gravity = 0, homing = 0, knockback = 0.4, radius = 0;
		public float damage = 6, scale = 0.5f, fire = 0;
		public int bounces = 0, pierce = 0, cluster = 0, chain = 0, life = 60;
		public boolean boomerang;
		public String item = "minecraft:fire_charge", trail = "combat_trail", hit = "combat_burst", type = "arcane", effect; // effect "slowness:60:1"
		public static Shot of() { return new Shot(); }
		public Shot speed(double v) { speed = v; return this; }
		public Shot gravity(double v) { gravity = v; return this; }
		public Shot homing(double v) { homing = v; return this; }
		public Shot knockback(double v) { knockback = v; return this; }
		public Shot radius(double v) { radius = v; return this; }
		public Shot damage(float v) { damage = v; return this; }
		public Shot scale(float v) { scale = v; return this; }
		public Shot fire(float seconds) { fire = seconds; return this; }
		public Shot bounces(int v) { bounces = v; return this; }
		public Shot pierce(int v) { pierce = v; return this; }
		public Shot cluster(int v) { cluster = v; return this; }
		public Shot chain(int v) { chain = v; return this; }
		public Shot life(int ticks) { life = ticks; return this; }
		public Shot boomerang() { boomerang = true; return this; }
		public Shot item(String v) { item = v; return this; }
		public Shot trail(String vfx) { trail = vfx; return this; }
		public Shot hit(String vfx) { hit = vfx; return this; }
		public Shot type(String damageType) { type = damageType; return this; }
		public Shot effect(String spec) { effect = spec; return this; }
		public Shot copy() { Shot s = new Shot(); for (var f : Shot.class.getFields()) try { if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) f.set(s, f.get(this)); } catch (IllegalAccessException ignored) {} return s; }
		/** "key=value ..." with the field names above (bounce = bounces); bare "boomerang" = flag. */
		public static Shot parse(String spec) {
			Shot s = new Shot();
			for (String tok : spec.trim().split("\\s+")) {
				if (tok.isEmpty()) continue;
				String[] kv = tok.split("=", 2);
				String k = kv[0].equals("bounce") ? "bounces" : kv[0];
				try {
					var f = Shot.class.getField(k);
					Class<?> t = f.getType();
					String v = kv.length > 1 ? kv[1] : "true";
					f.set(s, t == double.class ? Double.parseDouble(v) : t == float.class ? Float.parseFloat(v) : t == int.class ? Integer.parseInt(v) : t == boolean.class ? Boolean.parseBoolean(v) : v);
				} catch (ReflectiveOperationException | NumberFormatException e) { throw new IllegalArgumentException("bad shot option " + tok); }
			}
			return s;
		}
	}

	/** Fires from the hand along the look direction. */
	public static void shoot(LivingEntity caster, Shot s) { shoot(caster, hand(caster), caster.getLookAngle(), s); }
	public static void shoot(LivingEntity caster, Vec3 from, Vec3 dir, Shot s) { run(new Proj(caster, from, dir.normalize().scale(s.speed), s)); }

	static final class Proj implements Active {
		final LivingEntity caster; final ServerLevel l; final Shot s; final Display.ItemDisplay vis; final Set<UUID> hits = new HashSet<>();
		Vec3 pos, vel; int age, bounces, pierce; boolean back;
		Proj(LivingEntity caster, Vec3 from, Vec3 vel, Shot s) {
			this.caster = caster; this.l = (ServerLevel) caster.level(); this.s = s; this.pos = from; this.vel = vel; bounces = s.bounces; pierce = s.pierce;
			vis = display(l, from, s.item, s.scale, true);
			fx(s.trail, pos, vel, vis);
		}
		public boolean tick(long now) {
			age++;
			if (s.boomerang && !back && age > s.life / 2) { back = true; hits.clear(); }
			if (back) {
				if (!caster.isAlive()) { end(); return false; }
				Vec3 to = caster.getEyePosition().add(0, -0.4, 0).subtract(pos);
				if (to.length() < 1.3 || age > s.life * 3) { fx("combat_catch", pos, vel, null); end(); return false; }
				vel = to.normalize().scale(Math.max(s.speed, 0.8) * 1.1);
			} else if (s.homing > 0) {
				LivingEntity t = nearestEnemy(caster, pos, 18, vel, 0.2);
				if (t != null) vel = vel.normalize().lerp(t.getBoundingBox().getCenter().subtract(pos).normalize(), Math.min(1, s.homing)).normalize().scale(s.speed);
			}
			vel = vel.add(0, -s.gravity, 0);
			Vec3 next = pos.add(vel);
			EntityHitResult eh = ProjectileUtil.getEntityHitResult(l, caster, pos, next, new AABB(pos, next).inflate(1 + s.scale),
				e -> e instanceof LivingEntity le && !hits.contains(e.getUUID()) && enemy(caster, le), 0.25f + s.scale * 0.4f);
			BlockHitResult bh = back ? null : clip(caster, pos, next);
			boolean block = bh != null && bh.getType() != HitResult.Type.MISS && (eh == null || bh.getLocation().distanceToSqr(pos) < eh.getLocation().distanceToSqr(pos));
			if (eh != null && !block) {
				LivingEntity t = (LivingEntity) eh.getEntity();
				hits.add(t.getUUID());
				strike(t);
				if (!s.boomerang) {
					if (pierce-- <= 0) return burst(eh.getLocation());
					fx("combat_pierce", eh.getLocation(), vel, null);
				}
			}
			if (block) {
				if (bounces-- > 0) {
					Direction d = bh.getDirection();
					Vec3 n = new Vec3(d.getStepX(), d.getStepY(), d.getStepZ());
					vel = vel.subtract(n.scale(2 * vel.dot(n))).scale(0.85);
					pos = bh.getLocation().add(n.scale(0.1));
					fx("combat_bounce", pos, n, null);
					move();
					return true;
				}
				if (s.boomerang) { back = true; hits.clear(); return true; }
				return burst(bh.getLocation());
			}
			pos = next;
			move();
			if (age % 10 == 0) fx(s.trail, pos, vel, vis);
			if (!s.boomerang && age >= s.life) return burst(pos);
			return true;
		}
		void move() { if (vis != null) vis.setPos(pos.x, pos.y - s.scale * 0.5, pos.z); }
		void strike(LivingEntity t) {
			damage(t, caster, s.damage, s.type);
			if (s.knockback > 0) push(t, vel.normalize().scale(s.knockback).add(0, 0.15, 0));
			if (s.fire > 0) t.igniteForSeconds(s.fire);
			if (s.effect != null) effect(t, s.effect, caster);
			if (s.chain > 0) chain(caster, t, s.chain, 8, s.damage * 0.7f);
		}
		boolean burst(Vec3 at) {
			fx(s.hit, at, vel, null);
			if (s.radius > 0) blast(caster, at, s.radius, s.damage, s.type, s.knockback + 0.4);
			for (int i = 0; i < s.cluster; i++) {
				double a = Math.PI * 2 * i / s.cluster + Math.random() * 0.5;
				Shot c = s.copy().cluster(0).radius(Math.max(2, s.radius * 0.6)).damage(s.damage * 0.5f).gravity(0.06).speed(0.45).life(40).homing(0).bounces(0).pierce(0)
					.scale(s.scale * 0.6f).hit("combat_cluster").trail(null);
				run(new Proj(caster, at.add(0, 0.3, 0), new Vec3(Math.cos(a) * 0.35, 0.45 + Math.random() * 0.2, Math.sin(a) * 0.35), c));
			}
			end();
			return false;
		}
		public void end() { kill(vis); }
	}

	/** Applies "effect_id:ticks:amplifier" (e.g. slowness:60:1). */
	public static void effect(LivingEntity t, String spec, Entity source) {
		String[] p = spec.split(":");
		int i = p.length > 1 && p[0].equals("minecraft") ? 1 : 0;
		var id = Identifier.parse(p[i].contains(":") ? p[i] : "minecraft:" + p[i]);
		BuiltInRegistries.MOB_EFFECT.get(id).ifPresent(h -> t.addEffect(new MobEffectInstance(h, p.length > i + 1 ? Integer.parseInt(p[i + 1]) : 60, p.length > i + 2 ? Integer.parseInt(p[i + 2]) : 0), source));
	}

	// ---- chain lightning, beam, arrow rain --------------------------------------------------------------------------

	/** Chain lightning starting at what the caster looks at (or the nearest enemy in front). */
	public static boolean chain(LivingEntity caster, int jumps, double range, float damage) {
		Vec3 eye = caster.getEyePosition();
		EntityHitResult eh = ProjectileUtil.getEntityHitResult(caster, eye, eye.add(caster.getLookAngle().scale(24)), caster.getBoundingBox().expandTowards(caster.getLookAngle().scale(24)).inflate(1),
			e -> e instanceof LivingEntity le && enemy(caster, le), 24 * 24);
		LivingEntity first = eh != null ? (LivingEntity) eh.getEntity() : nearestEnemy(caster, eye, 20, caster.getLookAngle(), 0.6);
		if (first == null) { fx("combat_fizzle", hand(caster), caster.getLookAngle(), null); return false; }
		Vec3 h = hand(caster);
		line("combat_bolt_dot", h, first.getBoundingBox().getCenter(), 0.4, 0.3);
		chain(caster, first, jumps, range, damage);
		return true;
	}
	/** Chain lightning from first: hits it, then jumps to 'jumps' more enemies within range (damage decays 15% per jump). */
	public static void chain(LivingEntity caster, LivingEntity first, int jumps, double range, float damage) { run(new Chain(caster, first, jumps, range, damage)); }

	static final class Chain implements Active {
		final LivingEntity caster; final Set<UUID> done = new HashSet<>(); LivingEntity cur; int left, t; final double range; float dmg;
		Chain(LivingEntity caster, LivingEntity first, int jumps, double range, float dmg) {
			this.caster = caster; this.cur = first; this.left = jumps; this.range = range; this.dmg = dmg;
			done.add(first.getUUID());
			damage(first, caster, dmg, "shock");
			fx("combat_shock", first.position(), Vec3.ZERO, first);
		}
		public boolean tick(long now) {
			if (++t < 3) return true;
			t = 0;
			if (left-- <= 0 || cur == null) return false;
			Vec3 c = cur.getBoundingBox().getCenter();
			LivingEntity next = null; double bd = range * range;
			for (LivingEntity e : enemies(caster, c, range)) if (!done.contains(e.getUUID()) && e.distanceToSqr(c) < bd) { bd = e.distanceToSqr(c); next = e; }
			if (next == null) return false;
			line("combat_bolt_dot", c, next.getBoundingBox().getCenter(), 0.4, 0.3);
			dmg *= 0.85f;
			damage(next, caster, dmg, "shock");
			fx("combat_shock", next.position(), Vec3.ZERO, next);
			done.add(next.getUUID());
			cur = next;
			return true;
		}
	}

	/** Channelled laser: real raycast every tick from the hand along the look, damage dps (hits every 4 ticks), stops after ticks. */
	public static void beam(LivingEntity caster, int ticks, double range, float dps, String type) { beam(caster, ticks, range, dps, type, null); }
	/** Same, stops early when holding() turns false (e.g. use key released). */
	public static void beam(LivingEntity caster, int ticks, double range, float dps, String type, BooleanSupplier holding) {
		fx("combat_laser_start", hand(caster), caster.getLookAngle(), caster);
		run(new Beam(caster, ticks, range, dps, type, holding));
	}

	static final class Beam implements Active {
		final LivingEntity c; final int ticks; final double range; final float dps; final String type; final BooleanSupplier holding; int t;
		Beam(LivingEntity c, int ticks, double range, float dps, String type, BooleanSupplier holding) { this.c = c; this.ticks = ticks; this.range = range; this.dps = dps; this.type = type == null ? "laser" : type; this.holding = holding; }
		public boolean tick(long now) {
			if (!c.isAlive() || t++ >= ticks || (holding != null && !holding.getAsBoolean())) return false;
			Vec3 eye = c.getEyePosition(), look = c.getLookAngle(), end = eye.add(look.scale(range));
			BlockHitResult bh = clip(c, eye, end);
			if (bh.getType() != HitResult.Type.MISS) end = bh.getLocation();
			EntityHitResult eh = ProjectileUtil.getEntityHitResult(c, eye, end, new AABB(eye, end).inflate(1), e -> e instanceof LivingEntity le && enemy(c, le), eye.distanceToSqr(end));
			if (eh != null) end = eh.getLocation();
			Vec3 h = hand(c);
			if (t % 2 == 1) line("combat_laser_dot", h, end, 0.45, 0);
			if (t % 4 == 1) {
				fx("combat_laser_hit", end, look.scale(-1), null);
				if (eh != null) damage((LivingEntity) eh.getEntity(), c, dps / 5f, type);
			}
			return true;
		}
	}

	/** Rain of real arrows over center: count arrows spread over ticks, radius r. Arrows vanish after landing. */
	public static void rain(LivingEntity caster, Vec3 center, double r, int count, int ticks) { run(new Rain(caster, ground(caster.level(), center), r, count, ticks)); }

	static final class Rain implements Active {
		final LivingEntity c; final Vec3 at; final double r; final int count, ticks; int t, fired; final List<Arrow> arrows = new ArrayList<>();
		Rain(LivingEntity c, Vec3 at, double r, int count, int ticks) { this.c = c; this.at = at; this.r = r; this.count = count; this.ticks = Math.max(1, ticks); fx("combat_rain", at, Vec3.ZERO, null); }
		public boolean tick(long now) {
			t++;
			ServerLevel l = (ServerLevel) c.level();
			if (t > 10) {
				int want = Math.min(count, (int) Math.ceil(count * (t - 10) / (double) ticks));
				for (; fired < want; fired++) {
					double a = Math.random() * Math.PI * 2, d = Math.sqrt(Math.random()) * r;
					Arrow ar = new Arrow(l, at.x + Math.cos(a) * d, at.y + 22, at.z + Math.sin(a) * d, new ItemStack(Items.ARROW), null);
					ar.setOwner(c);
					ar.pickup = AbstractArrow.Pickup.DISALLOWED;
					ar.setCritArrow(true);
					ar.setBaseDamage(3);
					ar.setDeltaMovement((Math.random() - 0.5) * 0.1, -2.6, (Math.random() - 0.5) * 0.1);
					l.addFreshEntity(ar);
					arrows.add(ar);
				}
			}
			if (t > ticks + 70) { end(); return false; }
			return true;
		}
		public void end() { arrows.forEach(Combat::kill); }
	}

	// ---- movement ---------------------------------------------------------------------------------------------------

	/** Dash in the movement direction (look when standing still) with i-frames (dodge). Players take no fall damage after it. */
	public static void dash(LivingEntity e, double strength) { dash(e, strength, 8); }
	public static void dash(LivingEntity e, double strength, int iframes) {
		Vec3 d = moveDir(e);
		velocity(e, new Vec3(d.x * strength, e.onGround() ? 0.25 : 0.12, d.z * strength));
		if (iframes > 0) stats(e).iframes = now() + iframes;
		noFall(e);
		fx("combat_dash", e.position(), d, e);
	}
	static Vec3 moveDir(LivingEntity e) {
		double yaw = Math.toRadians(e.getYRot());
		Vec3 fwd = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw)), left = new Vec3(Math.cos(yaw), 0, Math.sin(yaw));
		if (e instanceof ServerPlayer sp) {
			var in = sp.getLastClientInput();
			double f = (in.forward() ? 1 : 0) - (in.backward() ? 1 : 0), s = (in.left() ? 1 : 0) - (in.right() ? 1 : 0);
			if (f != 0 || s != 0) return fwd.scale(f).add(left.scale(s)).normalize();
		}
		return fwd;
	}

	/** Teleports up to range blocks where the entity looks, to the last free spot before a wall. */
	public static boolean blink(LivingEntity e, double range) {
		Vec3 eye = e.getEyePosition(), look = e.getLookAngle();
		BlockHitResult bh = clip(e, eye, eye.add(look.scale(range)));
		Vec3 tip = bh.getType() == HitResult.Type.MISS ? eye.add(look.scale(range)) : bh.getLocation();
		boolean floor = bh.getType() != HitResult.Type.MISS && bh.getDirection() == Direction.UP;
		Vec3 from = e.position();
		for (double back = 0; back < range; back += 0.4) {
			Vec3 p = tip.subtract(look.scale(back));
			for (double dy : floor && back < 0.5 ? new double[] { 0 } : new double[] { -e.getBbHeight() / 2, -e.getBbHeight(), 0 }) {
				Vec3 feet = new Vec3(p.x, p.y + dy, p.z);
				if (e.level().noCollision(e, e.getBoundingBox().move(feet.subtract(from)))) {
					fx("combat_blink_out", from, look, null);
					e.teleportTo(feet.x, feet.y, feet.z);
					noFall(e);
					fx("combat_blink_in", feet, look, null);
					return true;
				}
			}
		}
		return false;
	}

	/** Grappling hook: fires a rope to the block looked at (range) and reels the entity in. Sneak to let go. */
	public static boolean grapple(LivingEntity e, double range, double speed) {
		Vec3 eye = e.getEyePosition();
		BlockHitResult bh = clip(e, eye, eye.add(e.getLookAngle().scale(range)));
		if (bh.getType() == HitResult.Type.MISS) { fx("combat_fizzle", hand(e), e.getLookAngle(), null); tell(e, "§7Nothing to hook"); return false; }
		fx("combat_hook", bh.getLocation(), e.getLookAngle().scale(-1), null);
		run(new Grapple(e, bh.getLocation(), speed));
		return true;
	}

	static final class Grapple implements Active {
		final LivingEntity e; final Vec3 anchor; final double speed; int t;
		Grapple(LivingEntity e, Vec3 anchor, double speed) { this.e = e; this.anchor = anchor; this.speed = speed; }
		public boolean tick(long now) {
			Vec3 to = anchor.subtract(e.position().add(0, e.getBbHeight() * 0.5, 0));
			boolean let = e instanceof ServerPlayer sp && sp.getLastClientInput().shift();
			if (!e.isAlive() || ++t > 80 || to.length() < 1.6 || let) { push(e, new Vec3(0, 0.35, 0)); noFall(e); return false; }
			velocity(e, e.getDeltaMovement().scale(0.3).add(to.normalize().scale(speed * 0.7)).add(0, 0.05, 0));
			e.resetFallDistance();
			Vec3 h = hand(e);
			if (t % 2 == 1) line("combat_rope_dot", h, anchor, 0.35, 0);
			return true;
		}
	}

	/** Ground pound: hop (if standing), slam down; the landing hits enemies in radius, stronger the higher you fell. */
	public static void slam(LivingEntity e, double radius, float damage) { run(new Slam(e, radius, damage)); }

	static final class Slam implements Active {
		final LivingEntity e; final double r; final float dmg; int t; double top; boolean down;
		Slam(LivingEntity e, double r, float dmg) { this.e = e; this.r = r; this.dmg = dmg; top = e.getY(); if (e.onGround()) velocity(e, new Vec3(0, 1.0, 0)); else { down = true; } }
		public boolean tick(long now) {
			if (!e.isAlive() || ++t > 100) return false;
			top = Math.max(top, e.getY());
			if (!down) { if (t >= 8) down = true; return true; }
			if (!e.onGround()) { velocity(e, new Vec3(e.getDeltaMovement().x * 0.5, -2.2, e.getDeltaMovement().z * 0.5)); e.resetFallDistance(); return true; }
			double power = 1 + Math.min(20, top - e.getY()) / 10;
			noFall(e);
			blast(e, e.position(), r * power, (float) (dmg * power), "slam", 0.6 * power);
			for (LivingEntity t2 : enemies(e, e.position(), r * power)) push(t2, new Vec3(0, 0.5, 0));
			fx(power > 1.6 ? "combat_slam_big" : "combat_slam", e.position(), Vec3.ZERO, null);
			return false;
		}
	}

	/** Super jump: hold sneak (or jump) to charge, release to launch. Not holding anything = instant 70% jump. */
	public static void superJump(LivingEntity e) {
		if (!holdingMove(e)) { superJumpRelease(e, 0.7); return; }
		charge(e, "Super jump", 30, () -> holdingMove(e), f -> superJumpRelease(e, f));
	}
	static boolean holdingMove(LivingEntity e) { return e instanceof ServerPlayer sp ? sp.getLastClientInput().shift() || sp.getLastClientInput().jump() : e.isShiftKeyDown(); }
	static void superJumpRelease(LivingEntity e, double f) {
		Vec3 look = e.getLookAngle();
		velocity(e, new Vec3(look.x * f * 0.8, 0.7 + 2.0 * f, look.z * f * 0.8));
		noFall(e);
		fx(f > 0.9 ? "combat_superjump_full" : "combat_superjump", e.position(), Vec3.ZERO, null);
	}

	/** Movement perks run by the client: double_jump, glide (hold jump while falling), wall_run (hold forward against a wall in the air, jump = wall jump). */
	public static void perk(Player p, String name, boolean on) { if (on) stats(p).perks.add(name); else stats(p).perks.remove(name); }

	// ---- defense ----------------------------------------------------------------------------------------------------

	/** Bubble shield: blocks the next 'hits' hits for ticks, reflects incoming projectiles back at their shooter. */
	public static void shield(LivingEntity e, int ticks, int hits) {
		Stats s = stats(e);
		s.shield = now() + ticks; s.shieldHits = Math.max(1, hits);
		fx("combat_shield_up", e.position(), Vec3.ZERO, e);
		run(new Shield(e));
	}

	static final class Shield implements Active {
		final LivingEntity e; int t;
		Shield(LivingEntity e) { this.e = e; }
		public boolean tick(long now) {
			Stats s = stats(e);
			if (!e.isAlive() || now >= s.shield || s.shieldHits <= 0) { if (e.isAlive() && s.shieldHits > 0) fx("combat_shield_end", e.position(), Vec3.ZERO, e); s.shield = 0; return false; }
			if (t++ % 18 == 0) fx("combat_shield", e.position(), Vec3.ZERO, e);
			for (Projectile pr : e.level().getEntitiesOfClass(Projectile.class, e.getBoundingBox().inflate(2.2))) {
				Entity o = pr.getOwner();
				if (o != null && friendly(o, e)) continue;
				if (pr.getDeltaMovement().dot(e.position().subtract(pr.position())) <= 0) continue; // already flying away
				pr.setDeltaMovement(pr.getDeltaMovement().scale(-0.9));
				pr.needsSync = true;
				pr.setOwner(e);
				fx("combat_shield_hit", pr.position(), pr.getDeltaMovement(), null);
			}
			return true;
		}
	}

	/** Parry window: a melee/projectile hit within ticks is cancelled, the attacker is staggered, energy refunded. */
	public static void parry(LivingEntity e, int ticks) { stats(e).parry = now() + ticks; fx("combat_parry_ready", e.position(), e.getLookAngle(), e); }
	/** I-frames: immune to damage for ticks. */
	public static void iframes(LivingEntity e, int ticks) { stats(e).iframes = now() + ticks; }

	static boolean allowDamage(LivingEntity e, DamageSource src, float amount) {
		if (!enabled) return true;
		Entity att = src.getEntity();
		if (att != null && att != e && friendly(att, e)) return false; // own summons, own arrows
		if (decoys != null && decoys.contains(e.getUUID())) { fx("combat_decoy_pop", e.position(), Vec3.ZERO, null); decoys.remove(e.getUUID()); e.discard(); return false; }
		Stats s = stats == null ? null : stats.get(e.getUUID());
		if (s == null) return true;
		long now = now();
		if (now < s.iframes) return false;
		if (now < s.parry && att instanceof LivingEntity a) {
			s.parry = 0;
			Vec3 away = a.position().subtract(e.position()).multiply(1, 0, 1);
			push(a, (away.lengthSqr() < 1e-4 ? e.getLookAngle() : away.normalize()).scale(1.2).add(0, 0.35, 0));
			a.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 2), e);
			a.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 60, 1), e);
			addEnergy(e, 25);
			fx("combat_parry", e.position().add(e.getLookAngle().multiply(0.8, 0, 0.8)), e.getLookAngle(), null);
			tell(e, "§6§lPARRY!");
			return false;
		}
		if (now < s.shield && s.shieldHits > 0) {
			s.shieldHits--;
			fx(s.shieldHits > 0 ? "combat_shield_hit" : "combat_shield_break", e.position(), Vec3.ZERO, e);
			if (s.shieldHits == 0) s.shield = 0;
			return false;
		}
		return true;
	}

	// ---- summons ----------------------------------------------------------------------------------------------------

	/** Allied minions (any entity id) that fight the owner's enemies, never the owner, and vanish after seconds. */
	public static List<Entity> summon(LivingEntity owner, String entityId, int count, int seconds) {
		ServerLevel l = (ServerLevel) owner.level();
		var type = BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(entityId.contains(":") ? entityId : "minecraft:" + entityId))
			.orElseThrow(() -> new IllegalArgumentException("no entity " + entityId));
		List<Entity> out = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			Entity m = type.create(l, EntitySpawnReason.MOB_SUMMONED);
			if (m == null) continue;
			double a = Math.PI * 2 * i / count + owner.getYRot();
			Vec3 p = ground(l, owner.position().add(Math.cos(a) * 2, 1, Math.sin(a) * 2));
			m.snapTo(p.x, p.y, p.z, owner.getYRot(), 0);
			if (m instanceof Mob mob) { tame(mob); mob.setPersistenceRequired(); }
			allyMap().put(m.getUUID(), owner.getUUID());
			l.addFreshEntity(m);
			fx("combat_summon", p, Vec3.ZERO, null);
			out.add(m);
		}
		run(new Minions(owner, out, seconds * 20));
		return out;
	}
	/** Strips a mob's own targeting so only Combat picks its targets. */
	static void tame(Mob m) {
		try { var f = Mob.class.getDeclaredField("targetSelector"); f.setAccessible(true); ((net.minecraft.world.entity.ai.goal.GoalSelector) f.get(m)).removeAllGoals(g -> true); }
		catch (ReflectiveOperationException ignored) {}
	}
	static LivingEntity targetFor(LivingEntity owner, Entity near) {
		LivingEntity t = owner.getLastHurtByMob();
		if (t != null && enemy(owner, t) && t.distanceToSqr(owner) < 32 * 32) return t;
		t = owner.getLastHurtMob();
		if (t != null && enemy(owner, t) && t.distanceToSqr(owner) < 32 * 32) return t;
		LivingEntity best = null; double bd = 16 * 16;
		for (LivingEntity e : enemies(owner, near.position(), 16)) if (e instanceof Enemy && e.distanceToSqr(near) < bd) { bd = e.distanceToSqr(near); best = e; }
		return best;
	}

	static final class Minions implements Active {
		final LivingEntity owner; final List<Entity> list; final int life; int t;
		Minions(LivingEntity owner, List<Entity> list, int life) { this.owner = owner; this.list = list; this.life = life; }
		public boolean tick(long now) {
			list.removeIf(m -> !m.isAlive());
			if (list.isEmpty()) return false;
			if (++t >= life || !owner.isAlive()) { end(); return false; }
			if (t % 10 == 0) for (Entity m : list) if (m instanceof Mob mob) {
				LivingEntity tg = targetFor(owner, m);
				mob.setTarget(tg);
				if (tg == null && m.distanceToSqr(owner) > 64) mob.getNavigation().moveTo(owner, 1.2);
			}
			return true;
		}
		public void end() { for (Entity m : list) { if (m.isAlive()) fx("combat_unsummon", m.position(), Vec3.ZERO, null); allyMap().remove(m.getUUID()); kill(m); } }
	}

	/** Turret at pos: shoots 'shot' (null = homing arcane bolts) at the nearest visible enemy every 12 ticks, for seconds. */
	public static Entity turret(LivingEntity owner, Vec3 pos, int seconds, Shot shot) {
		ServerLevel l = (ServerLevel) owner.level();
		Shot s = shot != null ? shot : Shot.of().speed(1.5).homing(0.12).damage(4).item("minecraft:amethyst_shard").scale(0.35f).hit("combat_spark").trail("combat_trail");
		Display.ItemDisplay vis = display(l, pos.add(0, 0.5, 0), "minecraft:dispenser", 0.8f, false);
		fx("combat_summon", pos, Vec3.ZERO, null);
		run(new Turret(owner, pos.add(0, 0.9, 0), seconds * 20, s, vis));
		return vis;
	}

	static final class Turret implements Active {
		final LivingEntity owner; final Vec3 muzzle; final int life; final Shot s; final Display.ItemDisplay vis; int t;
		Turret(LivingEntity owner, Vec3 muzzle, int life, Shot s, Display.ItemDisplay vis) { this.owner = owner; this.muzzle = muzzle; this.life = life; this.s = s; this.vis = vis; }
		public boolean tick(long now) {
			if (++t >= life || !owner.isAlive() || (vis != null && vis.isRemoved())) { end(); return false; }
			if (t % 12 != 0) return true;
			LivingEntity tg = null; double bd = 18 * 18;
			for (LivingEntity e : enemies(owner, muzzle, 18)) {
				Vec3 c = e.getBoundingBox().getCenter();
				if (e.distanceToSqr(muzzle) < bd && clip(owner, muzzle, c).getType() == HitResult.Type.MISS) { bd = e.distanceToSqr(muzzle); tg = e; }
			}
			if (tg == null) return true;
			Vec3 dir = tg.getBoundingBox().getCenter().subtract(muzzle).normalize();
			if (vis != null) vis.setYRot((float) Math.toDegrees(Math.atan2(-dir.x, dir.z)));
			fx("combat_muzzle", muzzle, dir, null);
			shoot(owner, muzzle.add(dir.scale(0.6)), dir, s.copy());
			return true;
		}
		public void end() { if (vis != null) fx("combat_unsummon", vis.position(), Vec3.ZERO, null); kill(vis); }
	}

	/** Decoy clone of the owner: nearby hostiles switch to it, the owner turns invisible for 3 s; it pops when hit. */
	public static Entity decoy(LivingEntity owner, int seconds) {
		ServerLevel l = (ServerLevel) owner.level();
		String tag = "moddeus_cmb" + (++seq) + "_" + (System.nanoTime() & 0xffff);
		String name = owner instanceof Player p ? p.getGameProfile().name() : "Steve";
		Vec3 pos = owner.position();
		cmd(l, pos, "summon mannequin ~ ~ ~ {Tags:[\"" + tag + "\",\"moddeus_combat\"],profile:{name:\"" + name + "\"},Rotation:[" + owner.getYRot() + "f,0f]}");
		var found = l.getEntitiesOfClass(LivingEntity.class, new AABB(pos, pos).inflate(2), d -> d.entityTags().contains(tag));
		if (found.isEmpty()) throw new IllegalStateException("decoy: could not summon a mannequin");
		LivingEntity d = found.get(0);
		allyMap().put(d.getUUID(), owner.getUUID());
		decoySet().add(d.getUUID());
		owner.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 60, 0, false, false));
		fx("combat_decoy", pos, Vec3.ZERO, null);
		run(new Decoy(owner, d, seconds * 20));
		return d;
	}

	static final class Decoy implements Active {
		final LivingEntity owner, d; final int life; int t;
		Decoy(LivingEntity owner, LivingEntity d, int life) { this.owner = owner; this.d = d; this.life = life; }
		public boolean tick(long now) {
			if (d.isRemoved() || !d.isAlive()) { allyMap().remove(d.getUUID()); return false; }
			if (++t >= life) { end(); return false; }
			if (t % 5 == 0) for (Mob m : d.level().getEntitiesOfClass(Mob.class, d.getBoundingBox().inflate(24), m -> m instanceof Enemy && m.getTarget() == owner)) m.setTarget(d);
			return true;
		}
		public void end() { if (!d.isRemoved()) fx("combat_decoy_pop", d.position(), Vec3.ZERO, null); decoySet().remove(d.getUUID()); allyMap().remove(d.getUUID()); kill(d); }
	}

	// ---- zones, auras, totems, mines, traps -------------------------------------------------------------------------

	/** Field at pos: kind heal|damage|slow|freeze|pull|push|fire, power (hp/s for heal/damage, strength otherwise), for seconds. */
	public static void zone(LivingEntity owner, Vec3 pos, String kind, double radius, int seconds, float power) { run(new Zone(owner, null, ground(owner.level(), pos), kind, radius, seconds * 20, power, null)); }
	/** Same field following the entity. */
	public static void aura(LivingEntity e, String kind, double radius, int seconds, float power) { run(new Zone(e, e, e.position(), kind, radius, seconds * 20, power, null)); }
	/** A totem (visible object) holding a zone. */
	public static Entity totem(LivingEntity owner, Vec3 pos, String kind, double radius, int seconds) {
		ServerLevel l = (ServerLevel) owner.level();
		Vec3 g = ground(l, pos);
		String item = switch (kind) { case "heal" -> "minecraft:totem_of_undying"; case "slow", "freeze" -> "minecraft:blue_ice"; case "pull", "push" -> "minecraft:lodestone"; case "fire" -> "minecraft:magma_block"; default -> "minecraft:crying_obsidian"; };
		Display.ItemDisplay vis = display(l, g.add(0, 1.0, 0), item, kind.equals("heal") ? 1.4f : 0.9f, kind.equals("heal"));
		fx("combat_summon", g, Vec3.ZERO, null);
		run(new Zone(owner, null, g, kind, radius, seconds * 20, defaultPower(kind), vis));
		return vis;
	}
	static float defaultPower(String kind) { return switch (kind) { case "heal" -> 4; case "damage" -> 4; case "pull", "push" -> 1; default -> 2; }; }

	static final class Zone implements Active {
		final LivingEntity owner; final Entity follow; Vec3 pos; final String kind; final double r; final int life; final float power; final Display.ItemDisplay vis; int t;
		Zone(LivingEntity owner, Entity follow, Vec3 pos, String kind, double r, int life, float power, Display.ItemDisplay vis) {
			this.owner = owner; this.follow = follow; this.pos = pos; this.kind = kind; this.r = r; this.life = life; this.power = power <= 0 ? defaultPower(kind) : power; this.vis = vis;
		}
		public boolean tick(long now) {
			if (++t >= life || (follow != null && !follow.isAlive())) { end(); return false; }
			if (follow != null) pos = follow.position();
			if (vis != null) vis.setYRot(vis.getYRot() + 4);
			if (t % 20 == 1) fx("combat_zone_" + kind, pos, Vec3.ZERO, follow);
			if (t % 10 != 0) return true;
			if (kind.equals("heal")) {
				for (LivingEntity a : owner.level().getEntitiesOfClass(LivingEntity.class, new AABB(pos, pos).inflate(r), a -> a.isAlive() && friendly(owner, a) && a.distanceToSqr(pos) <= r * r)) a.heal(power / 2);
				return true;
			}
			for (LivingEntity e : enemies(owner, pos, r)) {
				Vec3 to = pos.subtract(e.position()).multiply(1, 0, 1);
				Vec3 dir = to.lengthSqr() < 1e-4 ? Vec3.ZERO : to.normalize();
				switch (kind) {
					case "damage" -> damage(e, owner, power / 2, "void");
					case "slow" -> e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 25, (int) Math.max(0, power - 1)), owner);
					case "freeze" -> { e.setTicksFrozen(Math.min(300, e.getTicksFrozen() + 40)); e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 25, 3), owner); damage(e, owner, power / 4, "frost"); }
					case "pull" -> push(e, dir.scale(0.35 * power));
					case "push" -> push(e, dir.scale(-0.5 * power).add(0, 0.15, 0));
					case "fire" -> e.igniteForSeconds(2);
					default -> {}
				}
			}
			return true;
		}
		public void end() { kill(vis); }
	}

	/** Mine/trap on the ground at pos, armed after 1 s: kind blast (explodes, power = damage) | root | launch | freeze. Lasts 5 min. */
	public static Entity mine(LivingEntity owner, Vec3 pos, String kind, float power) {
		ServerLevel l = (ServerLevel) owner.level();
		Vec3 g = ground(l, pos);
		String item = switch (kind) { case "blast" -> "minecraft:tnt"; case "root" -> "minecraft:cobweb"; case "freeze" -> "minecraft:packed_ice"; default -> "minecraft:slime_block"; };
		Display.ItemDisplay vis = display(l, g.add(0, 0.12, 0), item, 0.35f, false);
		run(new Mine(owner, g, kind, power <= 0 ? 8 : power, vis));
		return vis;
	}

	static final class Mine implements Active {
		final LivingEntity owner; final Vec3 pos; final String kind; final float power; final Display.ItemDisplay vis; int t;
		Mine(LivingEntity owner, Vec3 pos, String kind, float power, Display.ItemDisplay vis) { this.owner = owner; this.pos = pos; this.kind = kind; this.power = power; this.vis = vis; }
		public boolean tick(long now) {
			if (++t > 6000 || (vis != null && vis.isRemoved())) { end(); return false; }
			if (t == 20) fx("combat_mine_arm", pos, Vec3.ZERO, null);
			if (t < 20) return true;
			var victims = enemies(owner, pos.add(0, 0.5, 0), 1.4);
			if (victims.isEmpty()) return true;
			switch (kind) {
				case "blast" -> { blast(owner, pos, 3.5, power, "shrapnel", 1.0); fx("combat_mine", pos, Vec3.ZERO, null); }
				case "root" -> { for (LivingEntity v : enemies(owner, pos, 2.5)) { v.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 80, 9), owner); velocity(v, Vec3.ZERO); } fx("combat_root", pos, Vec3.ZERO, null); }
				case "freeze" -> { for (LivingEntity v : enemies(owner, pos, 2.5)) { v.setTicksFrozen(300); v.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 100, 5), owner); damage(v, owner, 2, "frost"); } fx("combat_freeze", pos, Vec3.ZERO, null); }
				default -> { for (LivingEntity v : enemies(owner, pos, 2)) velocity(v, new Vec3(0, 1.2 + power * 0.05, 0)); fx("combat_launch", pos, Vec3.ZERO, null); }
			}
			end();
			return false;
		}
		public void end() { kill(vis); }
	}

	// ---- Java API: Combat.exec(caster, "dash", "1.6") runs one ability by name (list below); or call the method itself (Combat.dash) --

	public static final String HELP = String.join("\n",
		"shoot <k=v...>              projectile: speed homing bounces pierce damage radius cluster chain boomerang life gravity item trail hit type fire effect",
		"beam [ticks=40] [range=24] [dps=10] [type=laser]   chain [jumps=4] [range=8] [damage=6]   rain [radius=5] [count=40] [ticks=40]",
		"dash [strength=1.6] [iframes=8]   blink [range=12]   grapple [range=32] [speed=1.4]   slam [radius=4] [damage=8]   superjump",
		"perk <double_jump|glide|wall_run> [on|off]   shield [seconds=8] [hits=3]   parry [ticks=10]   iframes <ticks>",
		"summon [entity=wolf] [count=3] [seconds=30]   turret [seconds=20]   decoy [seconds=10]   totem <heal|slow|freeze|damage|pull|push|fire> [radius=5] [seconds=15]",
		"zone <kind> [radius=5] [seconds=8] [power]   aura <kind> [radius=4] [seconds=10] [power]   mine <blast|root|launch|freeze> [power=8]",
		"use <name> <cooldownTicks> [cost=0]  (result 1/0 for execute store)   energy <max> [regenPerSec=10]   blast [radius=4] [damage=8] [type]   clear");

	public static String exec(LivingEntity e, String... a) {
		String k = a.length == 0 ? "" : a[0].toLowerCase();
		Vec3 at = aim(e, 32);
		switch (k) {
			case "shoot" -> shoot(e, Shot.parse(String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length))));
			case "beam", "laser" -> beam(e, i(a, 1, 40), d(a, 2, 24), (float) d(a, 3, 10), s(a, 4, "laser"));
			case "chain" -> { if (!chain(e, i(a, 1, 4), d(a, 2, 8), (float) d(a, 3, 6))) return "no target"; }
			case "rain" -> rain(e, at, d(a, 1, 5), i(a, 2, 40), i(a, 3, 40));
			case "dash" -> dash(e, d(a, 1, 1.6), i(a, 2, 8));
			case "blink" -> { if (!blink(e, d(a, 1, 12))) return "no room"; }
			case "grapple" -> { if (!grapple(e, d(a, 1, 32), d(a, 2, 1.4))) return "nothing to hook"; }
			case "slam" -> slam(e, d(a, 1, 4), (float) d(a, 2, 8));
			case "superjump" -> superJump(e);
			case "perk" -> { if (!(e instanceof Player p)) return "players only"; perk(p, s(a, 1, "double_jump"), !s(a, 2, "on").equals("off")); }
			case "shield" -> shield(e, i(a, 1, 8) * 20, i(a, 2, 3));
			case "parry" -> parry(e, i(a, 1, 10));
			case "iframes" -> iframes(e, i(a, 1, 10));
			case "summon" -> summon(e, s(a, 1, "minecraft:wolf"), i(a, 2, 3), i(a, 3, 30));
			case "turret" -> turret(e, ground(e.level(), at), i(a, 1, 20), null);
			case "decoy", "clone" -> decoy(e, i(a, 1, 10));
			case "totem" -> totem(e, at, s(a, 1, "heal"), d(a, 2, 5), i(a, 3, 15));
			case "zone" -> zone(e, at, s(a, 1, "damage"), d(a, 2, 5), i(a, 3, 8), (float) d(a, 4, 0));
			case "aura" -> aura(e, s(a, 1, "damage"), d(a, 2, 4), i(a, 3, 10), (float) d(a, 4, 0));
			case "mine", "trap" -> mine(e, aim(e, 6), s(a, 1, "blast"), (float) d(a, 2, 8));
			case "use" -> { if (!use(e, s(a, 1, "ability"), i(a, 2, 20), (float) d(a, 3, 0))) throw new IllegalStateException("not ready"); }
			case "energy" -> energy(e, (float) d(a, 1, 100), (float) d(a, 2, 10));
			case "blast" -> { Vec3 p = ground(e.level(), at); fx("combat_burst", p, Vec3.ZERO, null); return "hit " + blast(e, p, d(a, 1, 4), (float) d(a, 2, 8), s(a, 3, "arcane"), 0.8); }
			case "clear" -> { stopAll(); return "cleared"; }
			default -> throw new IllegalArgumentException("unknown ability '" + k + "'\n" + HELP);
		}
		return k;
	}
	static String s(String[] a, int i, String def) { return i < a.length ? a[i] : def; }
	static double d(String[] a, int i, double def) { try { return i < a.length ? Double.parseDouble(a[i]) : def; } catch (NumberFormatException x) { return def; } }
	static int i(String[] a, int i, int def) { return (int) d(a, i, def); }

	// ---- lifecycle --------------------------------------------------------------------------------------------------

	public static void stopAll() { if (actives != null) { for (Active a : actives) try { a.end(); } catch (Throwable ignored) {} actives.clear(); } }

	static void tick(MinecraftServer s) {
		if (!enabled) return;
		long now = s.getTickCount();
		if (stats != null) for (Stats st : stats.values()) {
			if (st.energy < st.max && now - st.lastSpend > 20) st.energy = Math.min(st.max, st.energy + st.regen);
			if (!st.cooldowns.isEmpty()) st.cooldowns.values().removeIf(c -> c[0] <= now);
		}
		if (actives == null || actives.isEmpty()) return;
		for (Active a : actives) {
			boolean keep;
			try { keep = a.tick(now); }
			catch (Throwable t) { dev.moddeus.Moddeus.LOG.warn("combat ability stopped", t); keep = false; try { a.end(); } catch (Throwable ignored) {} }
			if (!keep) actives.remove(a);
		}
	}

	@OnReload
	public static void hook() {
		enabled = true;
		actives();
		if (System.getProperties().putIfAbsent("moddeus.combat", Boolean.TRUE) == null) {
			ServerTickEvents.END_SERVER_TICK.register(Combat::tick);
			ServerLivingEntityEvents.ALLOW_DAMAGE.register(Combat::allowDamage);
		}
		var s = LiveRegistry.server;
		if (s != null) {
			if (actives.isEmpty()) s.execute(() -> { for (ServerLevel l : s.getAllLevels()) cmd(l, Vec3.ZERO, "kill @e[tag=moddeus_combat]"); }); // leftovers of a crash
		}
	}

	@OnUnload
	public static void unload() { enabled = false; stopAll(); }
}
