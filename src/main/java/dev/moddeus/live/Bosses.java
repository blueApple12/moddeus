package dev.moddeus.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.moddeus.Moddeus;
import dev.moddeus.content.Vfx;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.EvokerFangs;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Boss + mob engine (guide: .claude/skills/mc-content/BOSSES.md). Plain Java, no commands (call from mod code or mc game run):
 *   Bosses.attack(boss, "slam r=6 color=#ff4020")   built-in sample attacks: slam charge leap meteors summon laser teleport blades
 *                                        spikes shield enrage grab roar telegraph cycle random intro music lock death (read them, then write your own)
 *   Bosses.behave(mob, "flying", "height=6")        flying caster leaper burrower pack pet mount | clear   (persisted as entity tags mb:...)
 *   Bosses.variant(mob, "fiery,giant scale=1.5")    elite affixes: fiery frost vampiric swift armored giant tiny explosive thorns teleporter summoner regen wither
 *   Bosses.arena(level, pos, Bosses.args("r=12 h=4 style=end name=x")) | Bosses.arenaUndo("x")   Bosses.chest(level, pos, lootTable, "#ffd040")
 * Boss content JSON "attack" lines (phases / every / "death_attack") run Bosses.attack as the boss (live/Api.attacks).
 * Content JSON "mobai" (behaviours/variants for every mob of a type) and "spawnrule" (biome/time/light spawning) come via Gameplay.load.
 */
public final class Bosses {
	public static volatile boolean enabled = true;
	static List<Job> jobs;
	static Map<UUID, Entity> tracked; // entities with mb: tags
	static Map<UUID, St> state;
	static Map<UUID, Integer> cycle;
	static Map<String, JsonObject> AI, RULES;
	static Map<String, A> argCache;
	static Map<String, List<Object[]>> arenas; // name -> [{ServerLevel, BlockPos, BlockState}]
	static final Random R = new Random();
	static String session;
	/** Tag marking temp entities (spike/meteor displays, crystals) of this JVM run; others are leftovers and get removed on load. */
	static String sess() { if (session == null) session = "moddeus_s_" + Long.toHexString(System.nanoTime()); return session; }
	static final String[] PRESETS = {"slam", "charge", "leap", "meteors", "summon", "laser", "teleport", "blades", "spikes", "shield", "enrage", "grab", "roar", "telegraph", "cycle", "random", "intro", "music", "lock", "death"};
	static final String[] AFFIXES = {"fiery", "frost", "vampiric", "swift", "armored", "giant", "tiny", "explosive", "thorns", "teleporter", "summoner", "regen", "wither"};

	static void init() {
		if (jobs == null) jobs = new ArrayList<>();
		if (tracked == null) tracked = new ConcurrentHashMap<>();
		if (state == null) state = new HashMap<>();
		if (cycle == null) cycle = new HashMap<>();
		if (AI == null) AI = new ConcurrentHashMap<>();
		if (RULES == null) RULES = new ConcurrentHashMap<>();
		if (argCache == null) argCache = new ConcurrentHashMap<>();
		if (arenas == null) arenas = new LinkedHashMap<>();
	}

	// ---- content (called from Gameplay.begin/load) ---------------------------------------------------------------
	public static void begin() { init(); AI.clear(); RULES.clear(); }
	public static boolean load(String id, JsonObject o) {
		init();
		String t = o.has("type") ? o.get("type").getAsString() : "";
		if (t.equals("mobai")) AI.put(id, o); else if (t.equals("spawnrule")) RULES.put(id, o); else return false;
		return true;
	}
	public static boolean has(String id) { init(); return AI.containsKey(id) || RULES.containsKey(id); }

	// ---- hooks ---------------------------------------------------------------------------------------------------
	@OnReload
	public static void hook() {
		init();
		enabled = true;
		if (System.getProperties().putIfAbsent("moddeus.bosses", Boolean.TRUE) == null) {
			net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(Bosses::tick);
			net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register(Bosses::onLoad);
			net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_UNLOAD.register(Bosses::onUnloadEntity);
			net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register(Bosses::use);
			net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register(Bosses::attackEntity);
			net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DAMAGE.register(Bosses::afterDamage);
			net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register(Bosses::afterDeath);
		}
		MinecraftServer s = LiveRegistry.server;
		if (s != null) for (ServerLevel l : s.getAllLevels()) for (Entity e : l.getAllEntities()) onLoad(e, l);
	}

	@OnUnload
	public static void unhook() {
		enabled = false;
		init();
		for (Job j : new ArrayList<>(jobs)) j.stop();
		jobs.clear();
	}

	// ---- jobs: timed sequences (attacks, telegraphs, locks) ------------------------------------------------------
	public interface Step { void at(Job j, int age); }
	public static final class Job {
		public final Entity owner; public final int life; public int age; final Step step; Runnable end; boolean done;
		Job(Entity owner, int life, Step step) { this.owner = owner; this.life = life; this.step = step; }
		public void stop() { if (done) return; done = true; if (end != null) try { end.run(); } catch (Throwable t) { Moddeus.LOG.warn("boss job end", t); } }
		public Job onEnd(Runnable r) { end = r; return this; }
	}
	/** Runs step every tick for life ticks (owner-bound: ends when the owner dies or unloads; owner null = free). */
	public static Job job(Entity owner, int life, Step step) { init(); Job j = new Job(owner, life, step); jobs.add(j); return j; }

	static void tick(MinecraftServer s) {
		if (!enabled) return;
		init();
		for (Job j : new ArrayList<>(jobs)) {
			if (j.done || (j.owner != null && (j.owner.isRemoved() || !j.owner.isAlive())) || j.age >= j.life) { j.stop(); jobs.remove(j); continue; }
			try { j.step.at(j, j.age++); } catch (Throwable t) { Moddeus.LOG.warn("boss job", t); j.stop(); jobs.remove(j); }
		}
		try { behaviours(s); } catch (Throwable t) { Moddeus.LOG.warn("mob behaviours", t); }
		try { if (s.getTickCount() % 40 == 0) spawnRules(s); } catch (Throwable t) { Moddeus.LOG.warn("spawn rules", t); }
	}

	// ---- args: "r=6 dmg=8 title=\"The Lich\" slam charge" ---------------------------------------------------------
	public static final class A {
		final Map<String, String> kv = new HashMap<>(); final List<String> words = new ArrayList<>();
		public double d(String k, double def) { try { return kv.containsKey(k) ? Double.parseDouble(kv.get(k)) : def; } catch (NumberFormatException e) { return def; } }
		public int i(String k, int def) { return (int) Math.round(d(k, def)); }
		public String s(String k, String def) { return kv.getOrDefault(k, def); }
		public boolean b(String k) { String v = kv.get(k); return v != null && !v.equals("0") && !v.equalsIgnoreCase("false"); }
	}
	public static A args(String s) {
		init();
		if (s == null) s = "";
		A c = argCache.get(s);
		if (c != null) return c;
		A a = new A();
		List<String> toks = new ArrayList<>();
		StringBuilder cur = new StringBuilder(); boolean q = false;
		for (char ch : s.toCharArray()) {
			if (ch == '"') { q = !q; continue; }
			if ((ch == ' ' || (ch == ',' && !q && cur.indexOf("=") < 0)) && !q) { if (cur.length() > 0) toks.add(cur.toString()); cur.setLength(0); } else cur.append(ch);
		}
		if (cur.length() > 0) toks.add(cur.toString());
		for (String t : toks) { int e = t.indexOf('='); if (e > 0) a.kv.put(t.substring(0, e), t.substring(e + 1)); else a.words.add(t); }
		if (argCache.size() > 500) argCache.clear();
		argCache.put(s, a);
		return a;
	}

	// ---- VFX helpers (dynamic defs on top of content/Vfx) ----------------------------------------------------------
	public static JsonObject L(String type, String tex, Object... kv) {
		JsonObject o = new JsonObject();
		o.addProperty("type", type);
		if (tex != null) o.addProperty("texture", tex);
		for (int k = 0; k + 1 < kv.length; k += 2) {
			Object v = kv[k + 1];
			if (v instanceof Number n) o.addProperty((String) kv[k], n); else if (v instanceof Boolean b) o.addProperty((String) kv[k], b); else o.addProperty((String) kv[k], String.valueOf(v));
		}
		return o;
	}
	/** Plays these layers at pos (dir = facing/beam vector; follow = entity it sticks to, or null). */
	public static void fx(Vec3 pos, Vec3 dir, Entity follow, JsonObject... layers) {
		if (Vfx.client == null) return;
		JsonArray arr = new JsonArray();
		for (JsonObject l : layers) arr.add(l);
		JsonObject def = new JsonObject();
		def.add("layers", arr);
		Identifier id = LiveRegistry.id("bossfx_" + Integer.toHexString(arr.toString().hashCode()));
		Vfx.DEFS.put(id, def);
		try { Vfx.play(id, pos, dir == null ? Vec3.ZERO : dir, follow); } catch (Throwable t) { Moddeus.LOG.debug("bossfx {}", t.toString()); }
	}
	static final Vec3 UP = new Vec3(0, 1, 0);

	/** Warning circle on the ground: outline + a fill that grows to the edge when the hit lands (ticks). */
	public static void telegraph(Vec3 c, double r, int ticks, String color) {
		double s = r / 0.8;
		fx(c, UP, null,
			L("ring", "bossfx_ring", "size", s, "duration", ticks, "color", color, "alpha", 0.8, "alpha_end", 1, "blend", "alpha"),
			L("ring", "bossfx_fill", "size", 0.1, "size_end", s, "duration", ticks, "color", color, "alpha", 0.5, "alpha_end", 1, "blend", "alpha"),
			L("ring", "bossfx_glow", "size", s * 1.1, "duration", ticks, "color", color, "alpha", 0.25, "alpha_end", 0.55, "blend", "alpha"),
			L("ring", "bossfx_ring", "size", s, "duration", ticks, "color", color, "alpha", 0.3, "alpha_end", 0.8));
	}

	static void sound(Level l, Vec3 p, SoundEvent s, float vol, float pitch) { l.playSound(null, p.x, p.y, p.z, s, SoundSource.HOSTILE, vol, pitch); }
	static void sound(Level l, Vec3 p, Holder<SoundEvent> s, float vol, float pitch) { sound(l, p, s.value(), vol, pitch); }

	/** Top of the ground at (x, z) near y (feet position). */
	public static Vec3 ground(Level l, Vec3 p) {
		BlockPos b = BlockPos.containing(p.x, p.y + 3, p.z);
		for (int k = 0; k < 24; k++, b = b.below())
			if (!l.getBlockState(b.below()).getCollisionShape(l, b.below()).isEmpty() && l.getBlockState(b).getCollisionShape(l, b).isEmpty()) return new Vec3(p.x, b.getY(), p.z);
		return p;
	}

	public static void vel(Entity e, Vec3 v) {
		e.setDeltaMovement(v);
		e.needsSync = true;
		if (e instanceof ServerPlayer sp) sp.connection.send(new ClientboundSetEntityMotionPacket(sp));
	}

	static boolean ally(Entity boss, Entity e) {
		if (e == boss) return true;
		for (String t : e.entityTags()) if (t.startsWith("moddeus_boss_") || t.startsWith("moddeus_minion")) return true;
		return e instanceof Player p && (p.isCreative() || p.isSpectator());
	}
	/** Living non-allies within r of c (dy limits the height band). */
	static List<LivingEntity> victims(Entity boss, Vec3 c, double r, double dy) {
		return boss.level().getEntitiesOfClass(LivingEntity.class, new AABB(c, c).inflate(r, dy, r),
			e -> e.isAlive() && !ally(boss, e) && Math.hypot(e.getX() - c.x, e.getZ() - c.z) <= r && !(e instanceof net.minecraft.world.entity.decoration.ArmorStand));
	}
	static void hurt(Entity boss, LivingEntity v, double dmg) {
		if (!(boss.level() instanceof ServerLevel l)) return;
		DamageSource src = boss instanceof LivingEntity le ? l.damageSources().mobAttack(le) : l.damageSources().magic();
		v.hurtServer(l, src, (float) dmg);
	}
	static LivingEntity target(Entity boss, double r) {
		if (boss instanceof Mob m && m.getTarget() != null && m.getTarget().isAlive() && m.getTarget().distanceTo(boss) < r * 1.5) return m.getTarget();
		Player best = null; double bd = r * r;
		for (Player p : boss.level().players()) if (!p.isSpectator() && !p.isCreative() && p.isAlive() && p.distanceToSqr(boss) < bd) { bd = p.distanceToSqr(boss); best = p; }
		if (best == null) for (Player p : boss.level().players()) if (p.isAlive() && !p.isSpectator() && p.distanceToSqr(boss) < r * r) return p;
		return best;
	}
	static Vec3 flat(Vec3 v) { Vec3 f = new Vec3(v.x, 0, v.z); return f.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : f.normalize(); }
	static void face(Entity e, Vec3 d) {
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		e.setYRot(yaw); e.setYHeadRot(yaw);
		if (e instanceof LivingEntity le) le.setYBodyRot(yaw);
	}
	static void stopNav(Entity e) { if (e instanceof Mob m) m.getNavigation().stop(); }
	static void say(Entity boss, double r, String msg) {
		Component c = Component.literal(msg);
		for (Player p : boss.level().players()) if (p.distanceToSqr(boss) < r * r) p.sendSystemMessage(c);
	}
	static void dust(ServerLevel l, Vec3 c, int n, double spread) {
		BlockState st = l.getBlockState(BlockPos.containing(c.x, c.y - 0.5, c.z));
		if (st.isAir()) st = Blocks.DIRT.defaultBlockState();
		l.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, st), c.x, c.y + 0.2, c.z, n, spread, 0.2, spread, 0.15);
	}

	/** Ground-pound impact at c: shockwave + glowing cracks + dust, damage falling off with distance, knock-up. */
	public static void impact(Entity boss, Vec3 c, double r, double dmg, String color) {
		ServerLevel l = (ServerLevel) boss.level();
		fx(c, UP, null,
			L("ring", "bossfx_shock", "size", 0.4, "size_end", r * 1.25, "duration", 12, "color", "#ffffff", "color_end", color, "alpha_end", 0, "ease", "out"),
			L("ring", "bossfx_crack", "size", r * 0.75, "duration", 45, "color", "#2a1a10", "alpha", 1, "alpha_end", 0, "delay", 1, "blend", "alpha"),
			L("ring", "bossfx_crack", "size", r * 0.75, "duration", 30, "color", color, "alpha", 1, "alpha_end", 0, "delay", 1),
			L("billboard", "bossfx_smoke", "size", r * 0.4, "size_end", r * 0.9, "duration", 20, "color", "#b8a890", "alpha", 0.7, "alpha_end", 0, "frames", 4, "y", 0.6, "y_end", 1.6, "blend", "alpha"),
			L("billboard", "bossfx_flare", "size", r * 0.6, "size_end", 0.2, "duration", 6, "color", color, "y", 0.3),
			L("shake", null, "size", Math.min(3, r * 0.35), "duration", 10));
		dust(l, c, 18, r * 0.35);
		sound(l, c, SoundEvents.GENERIC_EXPLODE, 1.2f, 0.55f);
		sound(l, c, SoundEvents.ANVIL_LAND, 0.6f, 0.5f);
		for (LivingEntity v : victims(boss, c, r, 3)) {
			double d = Math.hypot(v.getX() - c.x, v.getZ() - c.z);
			hurt(boss, v, dmg * (1 - 0.5 * d / r));
			Vec3 out = flat(v.position().subtract(c)).scale(0.9 * (1.2 - d / r));
			vel(v, new Vec3(out.x, 0.55, out.z));
		}
	}

	// ---- attacks -----------------------------------------------------------------------------------------------------
	/** Runs a preset attack as boss. spec = "slam r=6 dmg=8". Returns a short status. */
	public static String attack(LivingEntity boss, String spec) {
		if (!(boss.level() instanceof ServerLevel)) return "client";
		A a = args(spec);
		if (a.words.isEmpty()) throw new IllegalArgumentException("presets: " + String.join(" ", PRESETS));
		String p = a.words.get(0).toLowerCase();
		String col = a.s("color", "#ff3a2a");
		switch (p) {
			case "slam" -> slam(boss, a, col);
			case "charge" -> charge(boss, a, col);
			case "leap" -> leap(boss, a, col);
			case "meteors", "meteor" -> meteors(boss, a);
			case "summon", "minions" -> summon(boss, a);
			case "laser" -> laser(boss, a, a.s("color", "#ff2040"));
			case "teleport", "blink" -> teleport(boss, a, a.s("color", "#b040ff"));
			case "blades" -> blades(boss, a, a.s("color", "#c8e4ff"));
			case "spikes" -> spikes(boss, a, a.s("color", "#ff8a3a"));
			case "shield" -> shield(boss, a, a.s("color", "#60c8ff"));
			case "enrage" -> enrage(boss, a, a.s("color", "#ff1a1a"));
			case "grab" -> grab(boss, a, a.s("color", "#a0ff60"));
			case "roar" -> roar(boss, a, a.s("color", "#ffd0a0"));
			case "telegraph" -> telegraph(ground(boss.level(), boss.position()), a.d("r", 4), a.i("warn", 20), col);
			case "cycle", "random" -> {
				List<String> names = a.words.subList(1, a.words.size());
				if (names.isEmpty()) throw new IllegalArgumentException(p + " needs preset names: " + p + " slam charge leap");
				int i = p.equals("cycle") ? cycle.merge(boss.getUUID(), 1, Integer::sum) - 1 : R.nextInt(names.size());
				String rest = spec.replaceFirst("^\\s*\\S+", "");
				for (String n : names) rest = rest.replaceFirst("(^|\\s)" + java.util.regex.Pattern.quote(n) + "(?=\\s|$)", " ");
				return attack(boss, names.get(Math.floorMod(i, names.size())) + " " + rest.trim());
			}
			case "intro" -> intro(boss, a);
			case "music" -> music(boss, a);
			case "lock" -> lock(boss, a, a.s("color", "#c040ff"));
			case "death" -> death(boss, a, a.s("color", "#ffd040"));
			default -> throw new IllegalArgumentException("unknown preset " + p + " (" + String.join(" ", PRESETS) + ")");
		}
		return p;
	}

	static void slam(LivingEntity b, A a, String col) {
		double r = a.d("r", 6), dmg = a.d("dmg", 10);
		int warn = a.i("warn", 22);
		Vec3 c = ground(b.level(), b.position());
		telegraph(c, r, warn, col);
		sound(b.level(), c, SoundEvents.RAVAGER_ROAR, 1, 0.7f);
		job(b, warn + 1, (j, age) -> {
			stopNav(b);
			if (age == warn - 9) vel(b, new Vec3(0, 0.75, 0));
			if (age == warn) impact(b, ground(b.level(), b.position()), r, dmg, col);
		});
	}

	static void charge(LivingEntity b, A a, String col) {
		LivingEntity t = target(b, a.d("range", 28));
		if (t == null) return;
		Vec3 d = flat(t.position().subtract(b.position()));
		double len = a.d("len", 20), speed = a.d("speed", 1.1), dmg = a.d("dmg", 9);
		int warn = a.i("warn", 18), run = (int) Math.ceil(len / speed);
		for (double k = 1.5; k < len; k += 2.2) telegraph(ground(b.level(), b.position().add(d.scale(k))), 1.1, warn + (int) (k / speed), col);
		sound(b.level(), b.position(), SoundEvents.RAVAGER_ROAR, 1.2f, 1.1f);
		Set<UUID> hit = new HashSet<>();
		ServerLevel l = (ServerLevel) b.level();
		job(b, warn + run, (j, age) -> {
			stopNav(b);
			face(b, d);
			if (age < warn) { vel(b, new Vec3(0, b.getDeltaMovement().y, 0)); if (age % 5 == 0) dust(l, b.position(), 4, 0.4); return; }
			vel(b, new Vec3(d.x * speed, b.getDeltaMovement().y, d.z * speed));
			if (age % 2 == 0) fx(b.position(), d, null, L("billboard", "bossfx_smoke", "size", 1, "size_end", 2.2, "duration", 14, "color", "#a89a88", "alpha", 0.6, "alpha_end", 0, "frames", 4, "y", 0.4, "y_end", 1.2, "blend", "alpha"));
			for (LivingEntity v : victims(b, b.position(), b.getBbWidth() + 1, 2)) if (hit.add(v.getUUID())) {
				hurt(b, v, dmg);
				vel(v, new Vec3(d.x * 1.6, 0.7, d.z * 1.6));
				sound(l, v.position(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, 1, 0.6f);
			}
			if (b.horizontalCollision && age > warn + 2) { // hit a wall: crash + stunned
				impact(b, b.position(), 3, dmg * 0.4, col);
				b.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 50, 6));
				j.stop();
			}
		});
	}

	static void leap(LivingEntity b, A a, String col) {
		LivingEntity t = target(b, a.d("range", 30));
		if (t == null) return;
		Vec3 from = b.position(), to = ground(b.level(), t.position());
		double r = a.d("r", 4.5), dmg = a.d("dmg", 12), h = Math.max(4, from.distanceTo(to) / 2.5);
		int T = a.i("warn", 26);
		telegraph(to, r, T, col);
		dust((ServerLevel) b.level(), from, 10, 0.6);
		sound(b.level(), from, SoundEvents.RAVAGER_STEP, 1.5f, 0.6f);
		b.setNoGravity(true);
		job(b, T + 1, (j, age) -> {
			stopNav(b);
			double f = Math.min(1, (age + 1) / (double) T);
			Vec3 p = from.lerp(to, f).add(0, 4 * h * f * (1 - f), 0);
			b.setPos(p.x, p.y, p.z);
			vel(b, Vec3.ZERO);
			face(b, to.subtract(from));
			if (age == T) { b.setNoGravity(false); impact(b, to, r, dmg, col); }
		}).onEnd(() -> b.setNoGravity(false));
	}

	static void meteors(LivingEntity b, A a) {
		ServerLevel l = (ServerLevel) b.level();
		int n = a.i("n", 7), warn = a.i("warn", 30);
		double spread = a.d("r", 7), dmg = a.d("dmg", 8), power = a.d("power", 1.5);
		boolean grief = a.b("grief");
		String block = a.s("block", "minecraft:magma_block");
		List<Player> ps = new ArrayList<>();
		for (Player p : l.players()) if (p.distanceTo(b) < 48 && !p.isSpectator()) ps.add(p);
		if (ps.isEmpty()) return;
		for (int i = 0; i < n; i++) {
			Player p = ps.get(i % ps.size());
			Vec3 land = ground(l, p.position().add(i < ps.size() ? Vec3.ZERO : new Vec3((R.nextDouble() - .5) * 2 * spread, 0, (R.nextDouble() - .5) * 2 * spread)));
			Vec3 start = land.add(10 - R.nextDouble() * 20, 32, 10 - R.nextDouble() * 20);
			int delay = i * a.i("gap", 6);
			String tag = "moddeus_meteor_" + System.nanoTime() + "_" + i;
			job(null, delay + warn + 1, (j, age) -> {
				if (age == delay) {
					telegraph(land, 2.6, warn, "#ff5a10");
					cmd(l, start, "summon block_display ~ ~ ~ {block_state:{Name:\"" + block + "\"},teleport_duration:2,brightness:{sky:15,block:15},"
						+ "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[-0.8f,-0.8f,-0.8f],scale:[1.6f,1.6f,1.6f]},Tags:[\"moddeus_bossfx\",\"" + sess() + "\",\"" + tag + "\"]}");
					Entity m = tagged(l, start, tag);
					if (m != null) fx(start, UP, m,
						L("billboard", "bossfx_glow", "size", 2.6, "duration", warn + 2, "color", "#ff6a18", "alpha", 0.9),
						L("billboard", "bossfx_flare", "size", 3.4, "duration", warn + 2, "color", "#ffc050", "spin", 20, "alpha", 0.6));
					sound(l, start, SoundEvents.BLAZE_SHOOT, 2, 0.5f);
				}
				if (age > delay && age <= delay + warn) {
					double f = (age - delay) / (double) warn;
					Vec3 pos = start.lerp(land, f);
					Entity m = tagged(l, pos, tag);
					if (m != null) { m.setPos(pos.x, pos.y, pos.z); m.setYRot(age * 25f); m.setXRot(age * 13f % 90); }
					if (age % 3 == 0) fx(pos, UP, null, L("billboard", "bossfx_smoke", "size", 1.4, "size_end", 3, "duration", 22, "color", "#504038", "alpha", 0.6, "alpha_end", 0, "frames", 4, "blend", "alpha"),
						L("billboard", "bossfx_glow", "size", 1.8, "size_end", 0.4, "duration", 8, "color", "#ff7020"));
				}
				if (age == delay + warn) {
					Entity m = tagged(l, land, tag);
					if (m != null) m.discard();
					l.explode(null, land.x, land.y + 0.5, land.z, (float) power, false, grief ? Level.ExplosionInteraction.MOB : Level.ExplosionInteraction.NONE);
					impact(b, land, 3, dmg, "#ff6a18");
					fx(land, UP, null, L("shards", "bossfx_spark", "size", 0.5, "size_end", 4, "count", 10, "piece", 0.5, "piece_end", 0.1, "duration", 16, "color", "#ffb040", "alpha_end", 0, "ease", "out"));
				}
			});
		}
	}

	static void summon(LivingEntity b, A a) {
		ServerLevel l = (ServerLevel) b.level();
		String mob = a.s("mob", "minecraft:zombie"), mtag = "moddeus_minion_" + b.getUUID().toString().substring(0, 8);
		int n = a.i("n", 3), max = a.i("max", 8), warn = a.i("warn", 26);
		double r = a.d("r", 4);
		int alive = l.getEntitiesOfClass(LivingEntity.class, b.getBoundingBox().inflate(64), e -> e.isAlive() && e.entityTags().contains(mtag)).size();
		n = Math.min(n, max - alive);
		if (n <= 0) return;
		sound(l, b.position(), SoundEvents.EVOKER_PREPARE_SUMMON, 1.5f, 0.8f);
		for (int i = 0; i < n; i++) {
			double ang = Math.PI * 2 * i / n + R.nextDouble() * 0.5;
			Vec3 at = ground(l, b.position().add(Math.cos(ang) * r, 0, Math.sin(ang) * r));
			fx(at, UP, null,
				L("ring", "bossfx_sigil", "size", 0.3, "size_end", 1.6, "duration", warn + 6, "color", "#b050ff", "spin", 6, "alpha_end", 0, "ease", "out"),
				L("pillar", "bossfx_swirl", "size", 0.7, "height", 0.2, "height_end", 2.6, "duration", warn, "color", "#8040ff", "spin", 10, "frames", 4, "alpha", 0.7, "alpha_end", 0, "planes", 3));
			job(null, warn + 1, (j, age) -> {
				if (age != warn) return;
				cmd(l, at, "summon " + mob + " ~ ~ ~ {Tags:[\"moddeus_minion\",\"" + mtag + "\"]}");
				fx(at, UP, null, L("billboard", "bossfx_star", "size", 2.6, "size_end", 0.2, "duration", 8, "color", "#e0b0ff", "y", 1));
				sound(l, at, SoundEvents.EVOKER_CAST_SPELL, 1, 1.3f);
				Entity m = tagged(l, at, mtag);
				LivingEntity t = target(b, 40);
				if (m instanceof Mob mm && t != null) mm.setTarget(t);
			});
		}
	}

	static void laser(LivingEntity b, A a, String col) {
		LivingEntity t = target(b, a.d("range", 26));
		if (t == null) return;
		ServerLevel l = (ServerLevel) b.level();
		int warn = a.i("warn", 24), time = a.i("time", 50);
		double arc = a.d("arc", 140), range = a.d("range", 26), dmg = a.d("dmg", 4);
		Vec3 to = t.getEyePosition().subtract(0, 0.4, 0).subtract(b.getEyePosition());
		float yaw0 = (float) Math.toDegrees(Math.atan2(-to.x, to.z)), pitch = (float) -Math.toDegrees(Math.atan2(to.y, Math.hypot(to.x, to.z)));
		float dirSign = R.nextBoolean() ? 1 : -1;
		fx(b.getEyePosition(), UP, null,
			L("billboard", "bossfx_glow", "size", 0.2, "size_end", 2.4, "duration", warn, "color", col),
			L("billboard", "bossfx_star", "size", 0.4, "size_end", 1.8, "duration", warn, "color", "#ffffff", "spin", 40, "alpha", 0.4));
		Vec3 d0 = Vec3.directionFromRotation(pitch, yaw0 - dirSign * (float) arc / 2);
		fx(b.getEyePosition().subtract(0, 1.4, 0), d0.scale(range), null, L("beam", "bossfx_beam", "size", 0.12, "duration", warn, "color", col, "alpha", 0.35, "fit", true, "grow", 0.01));
		sound(l, b.position(), SoundEvents.GUARDIAN_ATTACK, 2, 0.6f);
		job(b, warn + time, (j, age) -> {
			stopNav(b);
			vel(b, new Vec3(0, b.getDeltaMovement().y, 0));
			float yaw = yaw0 - dirSign * (float) arc / 2;
			if (age >= warn) yaw += dirSign * (float) arc * (age - warn) / time;
			Vec3 dir = Vec3.directionFromRotation(pitch, yaw);
			face(b, dir);
			if (age < warn) return;
			Vec3 eye = b.getEyePosition(), end = eye.add(dir.scale(range));
			var hitb = l.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, b));
			end = hitb.getLocation();
			final Vec3 e2 = end;
			for (LivingEntity v : l.getEntitiesOfClass(LivingEntity.class, new AABB(eye, e2).inflate(1), v -> !ally(b, v) && v.isAlive() && v.getBoundingBox().inflate(0.3).clip(eye, e2).isPresent())) {
				hurt(b, v, dmg);
				v.igniteForSeconds(3);
			}
			if (age % 2 == 0) {
				fx(eye.subtract(0, 1.4, 0), end.subtract(eye), null,
					L("beam", "bossfx_beam", "size", 0.9, "duration", 3, "color", col, "fit", true, "grow", 0.01, "frames", 4),
					L("beam", "bossfx_beam", "size", 0.3, "duration", 3, "color", "#ffffff", "fit", true, "grow", 0.01),
					L("beam", "bossfx_beam", "size", 0.55, "duration", 3, "color", col, "fit", true, "grow", 0.01, "blend", "alpha"));
				fx(end, UP, null, L("billboard", "bossfx_star", "size", 1.6, "size_end", 0.4, "duration", 4, "color", col, "spin", 50));
			}
			if (age % 6 == 0) {
				fx(ground(l, end), UP, null, L("ring", "bossfx_crack", "size", 0.9, "duration", 30, "color", "#ff6020", "alpha_end", 0));
				sound(l, end, SoundEvents.FIRECHARGE_USE, 0.6f, 1.4f);
			}
		});
	}

	static void teleport(LivingEntity b, A a, String col) {
		LivingEntity t = target(b, a.d("range", 32));
		if (t == null) return;
		ServerLevel l = (ServerLevel) b.level();
		int warn = a.i("warn", 14);
		double dmg = a.d("dmg", 9);
		Vec3 look = flat(t.getLookAngle());
		Vec3 behind = ground(l, t.position().subtract(look.scale(2.4)));
		JsonObject puff = L("billboard", "bossfx_smoke", "size", 1.5, "size_end", 3.2, "duration", 16, "color", "#3a1050", "alpha", 0.9, "alpha_end", 0, "frames", 4, "y", 1, "blend", "alpha");
		JsonObject flash = L("billboard", "bossfx_star", "size", 3, "size_end", 0.2, "duration", 7, "color", col, "y", 1, "spin", 60);
		fx(b.position(), UP, null, puff, flash);
		sound(l, b.position(), SoundEvents.ENDERMAN_TELEPORT, 1.2f, 0.6f);
		telegraph(behind, 1.8, warn, col);
		b.setInvisible(true);
		job(b, warn + 6, (j, age) -> {
			stopNav(b);
			if (age == warn) {
				b.setInvisible(false);
				b.teleportTo(behind.x, behind.y, behind.z);
				face(b, t.position().subtract(behind));
				fx(behind, UP, null, puff, flash);
				sound(l, behind, SoundEvents.ENDERMAN_TELEPORT, 1.2f, 0.9f);
			}
			if (age == warn + 4) {
				Vec3 d = flat(t.position().subtract(b.position()));
				fx(b.position().add(0, b.getBbHeight() * 0.55, 0), d, null, L("slash", "bossfx_slash", "size", 3, "width", 1.2, "arc", 200, "duration", 8, "color", col, "alpha_end", 0, "tilt", 20));
				sound(l, b.position(), SoundEvents.PLAYER_ATTACK_SWEEP, 1.4f, 0.6f);
				if (t.distanceTo(b) < 4) { hurt(b, t, dmg); vel(t, new Vec3(d.x * 1.2, 0.5, d.z * 1.2)); }
			}
		}).onEnd(() -> b.setInvisible(false));
	}

	static void blades(LivingEntity b, A a, String col) {
		int time = a.i("time", 100), n = a.i("n", 4);
		double r = a.d("r", 3.5), dmg = a.d("dmg", 4);
		fx(b.position(), UP, b,
			L("orbit", "bossfx_slash", "size", r, "count", n, "piece", 1.5, "spin", 24, "duration", time, "color", col, "y", 1, "bob", 0.15),
			L("orbit", "bossfx_spark", "size", r, "count", n, "piece", 0.6, "spin", 24, "duration", time, "color", "#ffffff", "y", 1, "bob", 0.15),
			L("ring", "bossfx_ring", "size", r / 0.8, "duration", time, "color", col, "alpha", 0.25, "spin", -10));
		sound(b.level(), b.position(), SoundEvents.TRIDENT_RIPTIDE_3, 1, 1.2f);
		job(b, time, (j, age) -> {
			if (age % 8 == 0) sound(b.level(), b.position(), SoundEvents.PLAYER_ATTACK_SWEEP, 0.8f, 1.5f);
			if (age % 5 != 0) return;
			for (LivingEntity v : victims(b, b.position(), r + 1.3, 2.5)) {
				double d = Math.hypot(v.getX() - b.getX(), v.getZ() - b.getZ());
				if (d < r - 1.3) continue;
				hurt(b, v, dmg);
				Vec3 tan = flat(v.position().subtract(b.position())).yRot((float) Math.PI / 2);
				vel(v, new Vec3(tan.x * 0.4, 0.25, tan.z * 0.4));
			}
		});
	}

	static void spikes(LivingEntity b, A a, String col) {
		ServerLevel l = (ServerLevel) b.level();
		String mode = a.words.size() > 1 ? a.words.get(1) : a.s("mode", "line");
		int warn = a.i("warn", 14);
		double dmg = a.d("dmg", 9), h = a.d("h", 3);
		LivingEntity t = target(b, 32);
		List<Vec3> at = new ArrayList<>(); List<Integer> delay = new ArrayList<>();
		if (mode.equals("ring")) {
			for (int ring = 1; ring <= a.i("rings", 3); ring++) {
				double rr = ring * 3;
				int cnt = (int) (Math.PI * 2 * rr / 1.8);
				for (int k = 0; k < cnt; k++) { double ang = Math.PI * 2 * k / cnt; at.add(b.position().add(Math.cos(ang) * rr, 0, Math.sin(ang) * rr)); delay.add((ring - 1) * 8); }
			}
		} else if (mode.equals("under")) {
			for (Player p : l.players()) if (p.distanceTo(b) < 32 && !p.isSpectator()) { at.add(p.position()); delay.add(0); }
			h = a.d("h", 4.5);
		} else {
			if (t == null) return;
			Vec3 d = flat(t.position().subtract(b.position()));
			for (int k = 1; k <= a.i("n", 12); k++) { at.add(b.position().add(d.scale(1.2 + k * 1.5))); delay.add(k * 2); }
		}
		sound(l, b.position(), SoundEvents.EVOKER_PREPARE_ATTACK, 1.5f, 0.7f);
		for (int k = 0; k < at.size(); k++) spike(b, ground(l, at.get(k)), delay.get(k), warn, dmg, h, col);
	}

	static void spike(LivingEntity b, Vec3 p, int delay, int warn, double dmg, double h, String col) {
		ServerLevel l = (ServerLevel) b.level();
		String tag = "moddeus_spike_" + System.nanoTime();
		float w = (float) (h > 4 ? 1.6 : 1.1);
		job(null, delay + warn + 40, (j, age) -> {
			if (age == delay) telegraph(p, w * 0.8, warn, col);
			if (age == delay + warn - 1) { // hidden in the ground, then shoots up (teleport interpolation)
				String tf = "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[" + (-w / 2) + "f,0f," + (-w / 2) + "f],scale:[" + w + "f," + (float) (h * 0.55) + "f," + w + "f]}";
				String tf2 = "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[" + (-w * 0.35f) + "f," + (float) (h * 0.55) + "f," + (-w * 0.35f) + "f],scale:[" + (w * 0.7f) + "f," + (float) (h * 0.45) + "f," + (w * 0.7f) + "f]}";
				Vec3 low = p.subtract(0, h + 0.2, 0);
				cmd(l, low, "summon block_display ~ ~ ~ {block_state:{Name:\"minecraft:pointed_dripstone\",Properties:{thickness:\"base\",vertical_direction:\"up\"}},teleport_duration:3," + tf + ",Tags:[\"moddeus_bossfx\",\"" + sess() + "\",\"" + tag + "\"]}");
				cmd(l, low, "summon block_display ~ ~ ~ {block_state:{Name:\"minecraft:pointed_dripstone\",Properties:{thickness:\"tip\",vertical_direction:\"up\"}},teleport_duration:3," + tf2 + ",Tags:[\"moddeus_bossfx\",\"" + sess() + "\",\"" + tag + "\"]}");
			}
			if (age == delay + warn + 1) {
				for (Entity e : l.getEntities((Entity) null, new AABB(p, p).inflate(2, h + 2, 2), e -> e.entityTags().contains(tag))) e.setPos(p.x, p.y - 0.2, p.z);
				dust(l, p, 8, 0.4);
				sound(l, p, SoundEvents.POINTED_DRIPSTONE_LAND, 1.4f, 0.6f);
				sound(l, p, SoundEvents.EVOKER_FANGS_ATTACK, 0.8f, 0.7f);
				for (LivingEntity v : victims(b, p, w * 0.9, h)) { hurt(b, v, dmg); vel(v, new Vec3(0, 0.95, 0)); }
			}
			if (age == delay + warn + 28) for (Entity e : l.getEntities((Entity) null, new AABB(p, p).inflate(2, h + 2, 2), e -> e.entityTags().contains(tag))) e.setPos(p.x, p.y - h - 0.3, p.z);
			if (age == delay + warn + 36) killTag(l, p, tag);
		}).onEnd(() -> killTag(l, p, tag));
	}

	static void shield(LivingEntity b, A a, String col) {
		if (b.entityTags().contains("moddeus_shielded")) return;
		ServerLevel l = (ServerLevel) b.level();
		int n = a.i("n", 4), time = a.i("time", 900);
		double r = a.d("r", 8);
		String ctag = "moddeus_crystal_" + b.getUUID().toString().substring(0, 8);
		List<EndCrystal> cs = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			double ang = Math.PI * 2 * i / n;
			Vec3 at = ground(l, b.position().add(Math.cos(ang) * r, 0, Math.sin(ang) * r)).add(0, 1.2, 0);
			EndCrystal c = new EndCrystal(l, at.x, at.y, at.z);
			c.setShowBottom(false);
			c.setPermanentlyInvulnerable(true);
			c.addTag("moddeus_crystal"); c.addTag(ctag); c.addTag(sess());
			l.addFreshEntity(c);
			cs.add(c);
			fx(at.subtract(0, 1.2, 0), UP, null, L("pillar", "bossfx_flare", "size", 0.6, "height", 0, "height_end", 6, "duration", 14, "color", col, "alpha_end", 0));
		}
		b.addTag("moddeus_shielded");
		b.setPermanentlyInvulnerable(true);
		say(b, 48, "§b" + b.getDisplayName().getString() + " raises a crystal shield! Break the " + n + " crystals.");
		sound(l, b.position(), SoundEvents.BEACON_ACTIVATE, 2, 0.7f);
		job(b, time, (j, age) -> {
			if (age % 38 == 0) fx(b.position(), UP, b,
				L("sphere", "bossfx_orb", "size", Math.max(1.6, b.getBbHeight() * 0.9), "duration", 40, "color", col, "alpha", 0.35, "spin", 3, "y", b.getBbHeight() / 2),
				L("ring", "bossfx_sigil", "size", r / 0.8, "duration", 40, "color", col, "alpha", 0.35, "spin", 4));
			cs.removeIf(c -> !c.isAlive());
			for (EndCrystal c : cs) {
				if (age % 10 == 0) c.setBeamTarget(b.blockPosition().above((int) (b.getBbHeight() / 2)));
				for (Projectile pr : l.getEntitiesOfClass(Projectile.class, c.getBoundingBox().inflate(0.8), pr -> pr.getOwner() instanceof Player)) { pr.discard(); breakCrystal(c); }
			}
			if (cs.isEmpty()) j.stop();
		}).onEnd(() -> {
			b.setPermanentlyInvulnerable(false);
			b.removeTag("moddeus_shielded");
			for (EndCrystal c : cs) if (c.isAlive()) c.discard();
			if (b.isAlive()) {
				fx(b.position(), UP, null,
					L("shards", "bossfx_spark", "size", 1, "size_end", 6, "count", 16, "piece", 0.6, "piece_end", 0.1, "duration", 18, "color", col, "alpha_end", 0, "ease", "out", "y", 1),
					L("sphere", "bossfx_orb", "size", 2, "size_end", 5, "duration", 10, "color", col, "alpha", 0.6, "alpha_end", 0, "y", 1));
				sound(l, b.position(), SoundEvents.GLASS_BREAK, 2, 0.5f);
				say(b, 48, "§bThe shield shatters!");
			}
		});
	}

	static void breakCrystal(EndCrystal c) {
		ServerLevel l = (ServerLevel) c.level();
		fx(c.position(), UP, null,
			L("shards", "bossfx_spark", "size", 0.3, "size_end", 3, "count", 10, "piece", 0.4, "duration", 14, "color", "#ff80ff", "alpha_end", 0, "ease", "out", "y", 1),
			L("billboard", "bossfx_star", "size", 4, "size_end", 0.2, "duration", 8, "color", "#ffd0ff", "y", 1));
		sound(l, c.position(), SoundEvents.GLASS_BREAK, 1.5f, 0.8f);
		sound(l, c.position(), SoundEvents.GENERIC_EXPLODE, 0.6f, 1.5f);
		c.discard();
	}

	static void enrage(LivingEntity b, A a, String col) {
		if (!b.addTag("moddeus_enraged")) return;
		ServerLevel l = (ServerLevel) b.level();
		int time = a.i("time", -1);
		b.addEffect(new MobEffectInstance(MobEffects.SPEED, time, a.i("speed", 1)));
		b.addEffect(new MobEffectInstance(MobEffects.STRENGTH, time, a.i("strength", 1)));
		b.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, time, 0));
		var bar = dev.moddeus.content.Gameplay.bars == null ? null : dev.moddeus.content.Gameplay.bars.get(b.getUUID());
		if (bar != null) bar.ev.setColor(net.minecraft.world.BossEvent.BossBarColor.RED);
		sound(l, b.position(), SoundEvents.ENDER_DRAGON_GROWL, 2, 0.8f);
		fx(b.position(), UP, null,
			L("ring", "bossfx_shock", "size", 0.5, "size_end", 10, "duration", 14, "color", col, "alpha_end", 0, "ease", "out"),
			L("flash", null, "color", "#ff0000", "alpha", 0.35, "duration", 8),
			L("shake", null, "size", 2, "duration", 12));
		say(b, 48, "§c" + b.getDisplayName().getString() + " is ENRAGED!");
		job(b, time < 0 ? Integer.MAX_VALUE : time, (j, age) -> {
			if (age % 38 == 0) fx(b.position(), UP, b,
				L("spiral", "bossfx_swirl", "size", Math.max(0.8, b.getBbWidth()), "height", b.getBbHeight() + 0.6, "turns", 1.5, "arms", 3, "width", 0.35, "duration", 40, "color", col, "spin", 12, "alpha", 0.7, "frames", 4),
				L("ring", "bossfx_glow", "size", Math.max(1.2, b.getBbWidth() * 1.5), "duration", 40, "color", col, "alpha", 0.35));
		}).onEnd(() -> b.removeTag("moddeus_enraged_fx"));
	}

	static void grab(LivingEntity b, A a, String col) {
		LivingEntity t = target(b, a.d("r", 7));
		if (t == null || t.distanceTo(b) > a.d("r", 7)) return;
		ServerLevel l = (ServerLevel) b.level();
		int pull = 8, hold = a.i("hold", 24);
		double thr = a.d("throw", 2.2), dmg = a.d("dmg", 5);
		sound(l, b.position(), SoundEvents.IRON_GOLEM_ATTACK, 1.5f, 0.6f);
		job(b, pull + hold + 1, (j, age) -> {
			stopNav(b);
			Vec3 dir = flat(t.position().subtract(b.position()));
			Vec3 holdAt = b.position().add(dir.scale(b.getBbWidth() * 0.6 + 0.6)).add(0, b.getBbHeight() + 0.3, 0);
			if (!t.isAlive()) { j.stop(); return; }
			if (age % 2 == 0 && age < pull + hold) {
				Vec3 hand = b.position().add(0, b.getBbHeight() * 0.7, 0);
				fx(hand.subtract(0, 1.4, 0), t.position().add(0, t.getBbHeight() / 2, 0).subtract(hand), null,
					L("beam", "bossfx_beam", "size", 0.3, "duration", 3, "color", col, "fit", true, "grow", 0.01, "frames", 4));
			}
			t.resetFallDistance();
			if (age < pull + hold) { vel(t, holdAt.subtract(t.position()).scale(age < pull ? 0.35 : 0.6)); return; }
			float yaw = b.getYRot() + (R.nextFloat() - 0.5f) * 80;
			Vec3 out = Vec3.directionFromRotation(0, yaw);
			vel(t, new Vec3(out.x * thr, 0.9, out.z * thr));
			hurt(b, t, dmg);
			sound(l, t.position(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, 1.5f, 0.5f);
			fx(t.position(), out, null, L("slash", "bossfx_slash", "size", 2, "width", 1, "arc", 120, "duration", 8, "color", col, "alpha_end", 0));
		});
	}

	static void roar(LivingEntity b, A a, String col) {
		ServerLevel l = (ServerLevel) b.level();
		double r = a.d("r", 10);
		fx(b.position(), UP, null,
			L("sphere", "bossfx_orb", "size", 0.5, "size_end", r, "duration", 14, "color", col, "alpha", 0.45, "alpha_end", 0, "ease", "out", "y", b.getBbHeight() * 0.7),
			L("ring", "bossfx_shock", "size", 0.5, "size_end", r * 1.2, "duration", 14, "color", col, "alpha_end", 0, "ease", "out"),
			L("shake", null, "size", 1.6, "duration", 14));
		sound(l, b.position(), SoundEvents.RAVAGER_ROAR, 2.5f, 0.6f);
		for (LivingEntity v : victims(b, b.position(), r, 4)) {
			Vec3 out = flat(v.position().subtract(b.position())).scale(1.3 * (1.1 - v.distanceTo(b) / r));
			vel(v, new Vec3(out.x, 0.45, out.z));
			v.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, a.i("slow", 60), 1));
			v.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 50, 0));
			if (a.d("dmg", 0) > 0) hurt(b, v, a.d("dmg", 0));
		}
	}

	/** Boss intro: the boss freezes, the camera orbits it with its title (client Cutscene), then the fight starts. */
	static void intro(LivingEntity b, A a) {
		double secs = a.d("time", 5);
		String title = a.s("title", b.getDisplayName().getString()), sub = a.s("sub", "");
		boolean wasNoAi = b instanceof Mob m && m.isNoAi();
		if (b instanceof Mob m) m.setNoAi(true);
		job(b, (int) (secs * 20), (j, age) -> {}).onEnd(() -> { if (b instanceof Mob m && !wasNoAi) m.setNoAi(false); });
		LivingEntity near = target(b, 48);
		if (near == null) return;
		try { Class.forName("dev.moddeus.client.BossesClient").getMethod("intro", int.class, String.class, String.class, double.class, String.class).invoke(null, b.getId(), title, sub, secs, a.s("sound", "minecraft:entity.ravager.roar")); }
		catch (Throwable t) { Moddeus.LOG.warn("boss intro: {}", t.toString()); }
	}

	/** Looping music for players near the boss while it lives (sound id; len = seconds per loop). */
	static void music(LivingEntity b, A a) {
		ServerLevel l = (ServerLevel) b.level();
		Identifier id = Identifier.parse(a.s("sound", "minecraft:music_disc.pigstep"));
		int len = a.i("len", 148) * 20;
		double r = a.d("r", 64);
		float vol = (float) a.d("vol", 1);
		Holder<SoundEvent> h = BuiltInRegistries.SOUND_EVENT.get(id).<Holder<SoundEvent>>map(x -> x).orElseGet(() -> Holder.direct(SoundEvent.createVariableRangeEvent(id)));
		Set<UUID> hearing = new HashSet<>();
		job(b, Integer.MAX_VALUE, (j, age) -> {
			if (age % 20 != 0) return;
			for (ServerPlayer p : l.players()) {
				boolean near = p.distanceTo(b) < r;
				if (near && (hearing.add(p.getUUID()) || age % len == 0)) {
					if (age % len == 0 && age > 0) p.connection.send(new ClientboundStopSoundPacket(id, SoundSource.RECORDS));
					p.connection.send(new ClientboundStopSoundPacket(null, SoundSource.MUSIC));
					p.connection.send(new ClientboundSoundEntityPacket(h, SoundSource.RECORDS, p, vol, 1, R.nextLong()));
				} else if (!near && hearing.remove(p.getUUID())) p.connection.send(new ClientboundStopSoundPacket(id, SoundSource.RECORDS));
			}
		}).onEnd(() -> { for (ServerPlayer p : l.players()) if (hearing.contains(p.getUUID())) p.connection.send(new ClientboundStopSoundPacket(id, SoundSource.RECORDS)); });
	}

	/** Arena lock: players inside r of where the boss stands can't leave until it dies. */
	static void lock(LivingEntity b, A a, String col) {
		if (!b.addTag("moddeus_locked")) return;
		ServerLevel l = (ServerLevel) b.level();
		double r = a.d("r", 20);
		Vec3 c = ground(l, b.position());
		Set<UUID> inside = new HashSet<>();
		say(b, r + 10, "§dThe arena seals shut!");
		sound(l, c, SoundEvents.IRON_DOOR_CLOSE, 2, 0.5f);
		job(b, Integer.MAX_VALUE, (j, age) -> {
			if (age % 40 == 0) {
				fx(c, UP, null, L("ring", "bossfx_ring", "size", r / 0.8, "duration", 42, "color", col, "alpha", 0.7),
					L("ring", "bossfx_ring", "size", r / 0.8, "duration", 42, "color", col, "alpha", 0.25, "y", 3, "spin", 6));
				for (int k = 0; k < 20; k++) { double ang = R.nextDouble() * Math.PI * 2; l.sendParticles(ParticleTypes.END_ROD, c.x + Math.cos(ang) * r, c.y + R.nextDouble() * 4, c.z + Math.sin(ang) * r, 1, 0, 0.2, 0, 0.01); }
			}
			for (ServerPlayer p : l.players()) {
				double d = Math.hypot(p.getX() - c.x, p.getZ() - c.z);
				if (d < r - 1) inside.add(p.getUUID());
				if (!inside.contains(p.getUUID()) || p.isCreative() || p.isSpectator()) continue;
				if (d > r + 6) { inside.remove(p.getUUID()); continue; } // escaped (pearl/teleport): let go
				if (d > r - 0.6) {
					Vec3 in = flat(c.subtract(p.position()));
					vel(p, new Vec3(in.x * 0.7, 0.25, in.z * 0.7));
					p.sendOverlayMessage(Component.literal("§dThe arena is sealed!"));
				}
			}
		}).onEnd(() -> { b.removeTag("moddeus_locked"); fx(c, UP, null, L("ring", "bossfx_shock", "size", r / 0.8, "size_end", r * 1.4, "duration", 16, "color", col, "alpha_end", 0)); sound(l, c, SoundEvents.IRON_DOOR_OPEN, 2, 0.6f); });
	}

	/** Death sequence at the boss: light beams, bursts, a final blast, then loot chest + xp. Use as the boss's --death. */
	static void death(LivingEntity b, A a, String col) {
		ServerLevel l = (ServerLevel) b.level();
		Vec3 c = b.position();
		int time = a.i("time", 70);
		double hgt = Math.max(2, b.getBbHeight());
		fx(c, UP, null,
			L("pillar", "bossfx_flare", "size", 0.8, "size_end", 2.4, "height", 0, "height_end", 40, "duration", time + 10, "color", col, "planes", 4, "spin", 30, "alpha_end", 0.2),
			L("orbit", "bossfx_star", "size", 1, "size_end", 3, "count", 6, "piece", 0.6, "spin", 30, "duration", time, "color", "#ffffff", "y", hgt / 2),
			L("ring", "bossfx_sigil", "size", 2, "size_end", 6, "duration", time, "color", col, "spin", 20, "alpha", 0.8),
			L("shake", null, "size", 0.6, "duration", time));
		sound(l, c, SoundEvents.WITHER_DEATH, 1.5f, 0.8f);
		job(null, time + 1, (j, age) -> {
			if (age % 9 == 0 && age < time) {
				Vec3 p = c.add((R.nextDouble() - .5) * 3, R.nextDouble() * hgt, (R.nextDouble() - .5) * 3);
				fx(p, UP, null, L("billboard", "bossfx_star", "size", 0.4, "size_end", 3, "duration", 8, "color", col, "alpha_end", 0, "spin", 80),
					L("sphere", "bossfx_glow", "size", 0.3, "size_end", 1.4, "duration", 6, "color", "#ffffff", "alpha_end", 0));
				sound(l, p, SoundEvents.GENERIC_EXPLODE, 0.5f, 1.5f + R.nextFloat() * 0.4f);
			}
			if (age != time) return;
			fx(c, UP, null,
				L("flash", null, "color", "#ffffff", "alpha", 0.8, "duration", 12),
				L("sphere", "bossfx_glow", "size", 1, "size_end", 9, "duration", 14, "color", col, "alpha_end", 0, "ease", "out", "y", hgt / 2),
				L("ring", "bossfx_shock", "size", 1, "size_end", 18, "duration", 18, "color", col, "alpha_end", 0, "ease", "out"),
				L("shards", "bossfx_spark", "size", 1, "size_end", 9, "count", 18, "piece", 0.8, "piece_end", 0.1, "duration", 22, "color", col, "alpha_end", 0, "ease", "out", "y", hgt / 2),
				L("shake", null, "size", 3, "duration", 14));
			sound(l, c, SoundEvents.GENERIC_EXPLODE, 2, 0.5f);
			sound(l, c, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1, 1);
			if (a.kv.containsKey("loot")) chest(l, ground(l, c), a.s("loot", ""), col);
			ExperienceOrb.award(l, c, a.i("xp", 100));
			if (a.kv.containsKey("say")) say(b, 64, a.s("say", ""));
		});
	}

	/** A loot chest (any loot table) with a light beam, e.g. minecraft:chests/end_city_treasure. */
	public static BlockPos chest(ServerLevel l, Vec3 at, String table, String col) {
		BlockPos p = BlockPos.containing(at);
		if (l.getBlockEntity(p) != null) p = p.above();
		l.setBlock(p, Blocks.CHEST.defaultBlockState(), 3);
		if (l.getBlockEntity(p) instanceof RandomizableContainerBlockEntity be && !table.isEmpty())
			be.setLootTable(ResourceKey.create(Registries.LOOT_TABLE, Identifier.parse(table.contains(":") ? table : "minecraft:chests/" + table)), R.nextLong());
		Vec3 c = Vec3.atBottomCenterOf(p);
		fx(c, UP, null,
			L("pillar", "bossfx_flare", "size", 0.7, "height", 0, "height_end", 14, "duration", 60, "color", col, "alpha_end", 0),
			L("ring", "bossfx_shock", "size", 0.3, "size_end", 3, "duration", 14, "color", col, "alpha_end", 0));
		sound(l, c, SoundEvents.PLAYER_LEVELUP, 1, 0.8f);
		return p;
	}

	// ---- arena builder ---------------------------------------------------------------------------------------------
	/** Round arena around c: floor + inlay rings, wall with battlements, lit pillars, air cleared inside. Undo restores every block. */
	public static String arena(ServerLevel l, BlockPos c, A a) {
		int r = Math.max(5, Math.min(24, a.i("r", 12))), h = Math.max(2, Math.min(10, a.i("h", 4)));
		String style = a.s("style", "stone");
		if (arenas.containsKey(a.s("name", "last"))) arenaUndo(a.s("name", "last")); // rebuild replaces, never stacks
		BlockState floor, inlay, wall, top, pillar, light;
		switch (style) {
			case "nether" -> { floor = Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState(); inlay = Blocks.GILDED_BLACKSTONE.defaultBlockState(); wall = Blocks.NETHER_BRICKS.defaultBlockState(); top = Blocks.RED_NETHER_BRICKS.defaultBlockState(); pillar = Blocks.CRYING_OBSIDIAN.defaultBlockState(); light = Blocks.SHROOMLIGHT.defaultBlockState(); }
			case "end" -> { floor = Blocks.END_STONE_BRICKS.defaultBlockState(); inlay = Blocks.PURPUR_BLOCK.defaultBlockState(); wall = Blocks.PURPUR_BLOCK.defaultBlockState(); top = Blocks.PURPUR_PILLAR.defaultBlockState(); pillar = Blocks.OBSIDIAN.defaultBlockState(); light = Blocks.END_ROD.defaultBlockState(); }
			case "ice" -> { floor = Blocks.PACKED_ICE.defaultBlockState(); inlay = Blocks.BLUE_ICE.defaultBlockState(); wall = Blocks.SNOW_BLOCK.defaultBlockState(); top = Blocks.PACKED_ICE.defaultBlockState(); pillar = Blocks.BLUE_ICE.defaultBlockState(); light = Blocks.SEA_LANTERN.defaultBlockState(); }
			case "jungle" -> { floor = Blocks.MOSSY_STONE_BRICKS.defaultBlockState(); inlay = Blocks.MOSSY_COBBLESTONE.defaultBlockState(); wall = Blocks.MOSSY_STONE_BRICKS.defaultBlockState(); top = Blocks.CHISELED_STONE_BRICKS.defaultBlockState(); pillar = Blocks.JUNGLE_LOG.defaultBlockState(); light = Blocks.OCHRE_FROGLIGHT.defaultBlockState(); }
			default -> { floor = Blocks.POLISHED_ANDESITE.defaultBlockState(); inlay = Blocks.CHISELED_STONE_BRICKS.defaultBlockState(); wall = Blocks.STONE_BRICKS.defaultBlockState(); top = Blocks.STONE_BRICK_WALL.defaultBlockState(); pillar = Blocks.POLISHED_DEEPSLATE.defaultBlockState(); light = Blocks.LANTERN.defaultBlockState(); }
		}
		Map<BlockPos, BlockState> plan = new LinkedHashMap<>();
		boolean clear = !a.kv.containsKey("clear") || a.b("clear");
		for (int dx = -r - 1; dx <= r + 1; dx++) for (int dz = -r - 1; dz <= r + 1; dz++) {
			double d = Math.sqrt(dx * dx + dz * dz);
			if (d > r + 1.5) continue;
			BlockPos f = c.offset(dx, -1, dz);
			boolean ring = Math.abs(d - r * 0.45) < 0.5 || Math.abs(d - r * 0.8) < 0.5 || d < 1.5;
			plan.put(f, ring ? inlay : floor);
			if (d > r + 0.5) {
				for (int y = 0; y < h; y++) plan.put(c.offset(dx, y, dz), wall);
				if (((dx + dz) & 1) == 0) plan.put(c.offset(dx, h, dz), top);
			} else if (clear) for (int y = 0; y < h + 4; y++) plan.put(c.offset(dx, y, dz), Blocks.AIR.defaultBlockState());
		}
		for (int k = 0; k < 4; k++) {
			double ang = Math.PI / 4 + Math.PI / 2 * k;
			BlockPos pc = c.offset((int) Math.round(Math.cos(ang) * r * 0.65), 0, (int) Math.round(Math.sin(ang) * r * 0.65));
			for (int y = 0; y < h + 1; y++) plan.put(pc.above(y), pillar);
			plan.put(pc.above(h + 1), light);
		}
		List<Object[]> undo = new ArrayList<>();
		int n = 0;
		for (var e : plan.entrySet()) {
			BlockPos p = e.getKey();
			if (l.getBlockEntity(p) != null) continue; // never wipe chests/spawners
			BlockState old = l.getBlockState(p);
			if (old.equals(e.getValue())) continue;
			undo.add(new Object[] {l, p.immutable(), old});
			l.setBlock(p, e.getValue(), 2);
			n++;
		}
		String name = a.s("name", "last");
		arenas.put(name, undo);
		fx(Vec3.atBottomCenterOf(c), UP, null, L("ring", "bossfx_shock", "size", 1, "size_end", r * 1.3, "duration", 20, "color", "#ffd070", "alpha_end", 0, "ease", "out"));
		sound(l, Vec3.atCenterOf(c), SoundEvents.ANVIL_PLACE, 1, 0.6f);
		return "arena '" + name + "' built: r=" + r + " h=" + h + " style=" + style + ", " + n + " blocks (undo: Bosses.arenaUndo(\"" + name + "\"))";
	}

	public static String arenaUndo(String name) {
		init();
		List<Object[]> u = arenas.remove(name);
		if (u == null) return "no arena '" + name + "' to undo (have: " + arenas.keySet() + ")";
		for (int k = u.size() - 1; k >= 0; k--) ((ServerLevel) u.get(k)[0]).setBlock((BlockPos) u.get(k)[1], (BlockState) u.get(k)[2], 2);
		return "arena '" + name + "' removed (" + u.size() + " blocks restored)";
	}

	// ---- mob behaviours (tags mb:<name>[:k=v,k=v]) -----------------------------------------------------------------
	public static final class St { int cool, timer, phase; Vec3 wander = Vec3.ZERO; double vy; }
	static St st(Entity e) { return state.computeIfAbsent(e.getUUID(), k -> new St()); }
	public static final String[] BEHAVIOURS = {"flying", "caster", "leaper", "burrower", "pack", "pet", "mount"};

	/** Adds (or replaces) a behaviour: behave(mob, "flying", "height=6 speed=0.3"). "clear" removes all. */
	public static void behave(Entity e, String name, String args) {
		init();
		name = name.toLowerCase();
		if (name.equals("clear")) {
			for (String t : new ArrayList<>(e.entityTags())) if (t.startsWith("mb:") || t.startsWith("mb_")) e.removeTag(t);
			if (e instanceof Mob m) { m.setNoAi(false); m.setNoGravity(false); m.setInvisible(false); m.setPermanentlyInvulnerable(false); m.noPhysics = false; }
			tracked.remove(e.getUUID());
			return;
		}
		if (!java.util.Arrays.asList(BEHAVIOURS).contains(name)) throw new IllegalArgumentException("behaviours: " + String.join(" ", BEHAVIOURS) + " clear");
		for (String t : new ArrayList<>(e.entityTags())) if (t.equals("mb:" + name) || t.startsWith("mb:" + name + ":")) e.removeTag(t);
		String a = args == null ? "" : args.trim().replace(' ', ',');
		e.addTag(a.isEmpty() ? "mb:" + name : "mb:" + name + ":" + a);
		if (e instanceof Mob m) m.setPersistenceRequired();
		tracked.put(e.getUUID(), e);
	}

	static A beh(Entity e, String name) {
		for (String t : e.entityTags()) {
			if (t.equals("mb:" + name)) return args("");
			if (t.startsWith("mb:" + name + ":")) return args(t.substring(name.length() + 4).replace(',', ' '));
		}
		return null;
	}
	static String tagVal(Entity e, String prefix) { for (String t : e.entityTags()) if (t.startsWith(prefix)) return t.substring(prefix.length()); return null; }

	static void onLoad(Entity e, ServerLevel l) {
		if (!enabled) return;
		init();
		if ((e.entityTags().contains("moddeus_bossfx") || e.entityTags().contains("moddeus_crystal")) && !e.entityTags().contains(sess())) { e.discard(); return; } // temp leftovers from an earlier session
		if (!AI.isEmpty() && !e.entityTags().contains("mb_init")) {
			String type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
			for (JsonObject o : AI.values()) if (o.has("mob") && type.equals(o.get("mob").getAsString())) {
				e.addTag("mb_init");
				if (o.has("behave")) for (var b : o.getAsJsonObject("behave").entrySet()) behave(e, b.getKey(), b.getValue().getAsString());
				if (o.has("variant") && (!o.has("variant_chance") || R.nextDouble() < o.get("variant_chance").getAsDouble())) variant(e, o.get("variant").getAsString());
			}
		}
		boolean any = false;
		for (String t : e.entityTags()) if (t.startsWith("mb:") || t.startsWith("elite:")) { any = true; break; }
		if (!any) return;
		tracked.put(e.getUUID(), e);
		if (e instanceof Mob m && e.entityTags().contains("mb_burrowed")) { surface(m); }
		if (e instanceof Mob m && e.entityTags().contains("mb_rideai")) { m.setNoAi(false); m.removeTag("mb_rideai"); }
		if (e instanceof Mob m && tagVal(e, "mb_owner:") != null) tameGoals(m);
	}
	static void onUnloadEntity(Entity e, ServerLevel l) { if (tracked != null) tracked.remove(e.getUUID()); if (state != null && e.isRemoved() && e.getRemovalReason() != null && e.getRemovalReason().shouldDestroy()) state.remove(e.getUUID()); }

	static void behaviours(MinecraftServer s) {
		if (tracked.isEmpty()) return;
		for (Entity e : new ArrayList<>(tracked.values())) {
			if (e.isRemoved() || !e.isAlive()) { tracked.remove(e.getUUID()); continue; }
			if (!(e instanceof Mob m) || !(e.level() instanceof ServerLevel l)) continue;
			A a;
			if ((a = beh(m, "mount")) != null && mount(m, l, a)) continue; // ridden: the rider drives
			if ((a = beh(m, "pet")) != null) pet(m, l, a);
			if ((a = beh(m, "pack")) != null && s.getTickCount() % 10 == 0) pack(m, a);
			if ((a = beh(m, "flying")) != null) flying(m, l, a);
			if ((a = beh(m, "caster")) != null) caster(m, l, a);
			if ((a = beh(m, "leaper")) != null) leaper(m, l, a);
			if ((a = beh(m, "burrower")) != null) burrower(m, l, a);
			elite(m, l);
		}
	}

	/** The mob's target, or (hostile mobs / hostile=1) the nearest survival player within range. Tamed pets keep their own. */
	static LivingEntity tgt(Mob m, A a, double range) {
		LivingEntity t = m.getTarget();
		if (t != null && t.isAlive() && t.distanceTo(m) < range * 1.5) return t;
		if (tagVal(m, "mb_owner:") != null) return null;
		if (!(m instanceof Enemy) && !a.b("hostile")) return null;
		Player p = m.level().getNearestPlayer(m, range);
		if (p == null || p.isCreative() || p.isSpectator()) return null;
		m.setTarget(p);
		return p;
	}
	static double attackDmg(Mob m, A a) { return a.d("dmg", m.getAttributes().hasAttribute(Attributes.ATTACK_DAMAGE) ? m.getAttributeValue(Attributes.ATTACK_DAMAGE) : 3); }
	static void melee(Mob m, LivingEntity t, A a) {
		St s = st(m);
		if (s.cool > 0 || t.distanceTo(m) > m.getBbWidth() / 2 + t.getBbWidth() / 2 + 1.4) return;
		m.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
		hurt(m, t, attackDmg(m, a));
		s.cool = 20;
	}

	static void flying(Mob m, ServerLevel l, A a) {
		St s = st(m);
		if (s.cool > 0) s.cool--;
		s.timer++;
		if (!m.isNoAi()) { m.setNoAi(true); m.addTag("mb_flyai"); }
		m.setNoGravity(true);
		m.resetFallDistance();
		double h = a.d("height", 5), sp = a.d("speed", 0.32);
		int swoop = a.i("swoop", 100);
		LivingEntity t = tgt(m, a, a.d("range", 24));
		Vec3 goal;
		if (t != null) {
			boolean dive = s.timer % swoop > swoop - 30;
			double ang = s.timer * 0.05 + m.getId();
			goal = dive ? t.position().add(0, t.getBbHeight() * 0.5, 0) : t.position().add(Math.cos(ang) * 5, h * 0.6 + 1.5, Math.sin(ang) * 5);
			if (dive) melee(m, t, a);
		} else {
			if (s.timer % 80 == 0 || s.wander == Vec3.ZERO) s.wander = new Vec3(R.nextDouble() - .5, 0, R.nextDouble() - .5).normalize().scale(sp * 0.4);
			Vec3 g = ground(l, m.position());
			goal = m.position().add(s.wander.scale(10)).add(0, (g.y + h) - m.getY(), 0);
		}
		Vec3 v = goal.subtract(m.position());
		if (v.length() > sp) v = v.normalize().scale(sp);
		Vec3 cur = s.wander.lengthSqr() > 0 && false ? Vec3.ZERO : m.getDeltaMovement();
		Vec3 nv = cur.scale(0.7).add(v.scale(0.3 * 1.8));
		m.move(MoverType.SELF, nv);
		m.setDeltaMovement(nv);
		if (nv.horizontalDistanceSqr() > 1e-4) face(m, t != null ? t.position().subtract(m.position()) : nv);
		if (s.timer % 30 == 0) sound(l, m.position(), SoundEvents.PHANTOM_FLAP, 0.6f, 1);
	}

	static void caster(Mob m, ServerLevel l, A a) {
		St s = st(m);
		LivingEntity t = tgt(m, a, a.d("range", 20));
		if (t == null || !m.hasLineOfSight(t)) return;
		int every = a.i("every", 50), wind = 12;
		s.phase++;
		double keep = a.d("keep", 0);
		if (keep > 0 && t.distanceTo(m) < keep && m instanceof PathfinderMob pm) { // kite: back off
			Vec3 away = m.position().add(flat(m.position().subtract(t.position())).scale(4));
			pm.getNavigation().moveTo(away.x, away.y, away.z, 1.2);
		}
		if (s.phase % every == every - wind) fx(m.position(), UP, m, L("ring", "bossfx_sigil", "size", 0.2, "size_end", 1.4, "duration", wind, "color", a.s("color", "#c060ff"), "spin", 20),
			L("billboard", "bossfx_glow", "size", 0.2, "size_end", 1, "duration", wind, "color", a.s("color", "#c060ff"), "y", m.getEyeHeight()));
		if (s.phase % every != 0) return;
		face(m, t.position().subtract(m.position()));
		Vec3 eye = m.getEyePosition(), to = t.getEyePosition().subtract(0, 0.3, 0).subtract(eye).normalize();
		String p = a.s("projectile", "small_fireball");
		Entity pr = null;
		switch (p) {
			case "fireball" -> pr = new LargeFireball(l, m, to.scale(0.1), a.i("power", 1));
			case "wither_skull" -> pr = new WitherSkull(l, m, to.scale(0.1));
			case "shulker" -> pr = new ShulkerBullet(l, m, t, net.minecraft.core.Direction.Axis.Y);
			case "snowball" -> { Snowball sb = new Snowball(l, m, new ItemStack(Items.SNOWBALL)); sb.shoot(to.x, to.y + 0.1, to.z, 1.6f, 1); pr = sb; }
			case "fangs" -> {
				Vec3 d = flat(to);
				for (int k = 1; k <= 10; k++) { Vec3 at = ground(l, m.position().add(d.scale(k * 1.25))); l.addFreshEntity(new EvokerFangs(l, at.x, at.y, at.z, m.getYRot() * (float) Math.PI / 180, k, m)); }
				sound(l, m.position(), SoundEvents.EVOKER_CAST_SPELL, 1, 1);
			}
			case "bolt" -> { // instant magic bolt
				fx(eye.subtract(0, 1.4, 0), t.getEyePosition().subtract(eye), null, L("beam", "bossfx_bolt", "size", 0.8, "duration", 6, "color", a.s("color", "#a0c0ff"), "fit", true, "grow", 0.01, "frames", 4, "alpha_end", 0));
				hurt(m, t, a.d("dmg", 5));
				sound(l, t.position(), SoundEvents.LIGHTNING_BOLT_IMPACT, 0.6f, 1.6f);
			}
			default -> pr = new SmallFireball(l, m, to.scale(0.1));
		}
		if (pr != null) {
			pr.setPos(eye.x + to.x, eye.y + to.y - 0.1, eye.z + to.z);
			l.addFreshEntity(pr);
			sound(l, eye, p.equals("snowball") ? SoundEvents.SNOWBALL_THROW : SoundEvents.BLAZE_SHOOT, 1, 1);
		}
	}

	static void leaper(Mob m, ServerLevel l, A a) {
		St s = st(m);
		if (s.cool > 0) s.cool--;
		LivingEntity t = tgt(m, a, a.d("range", 14));
		if (s.phase == 1) { // in the air
			if (t != null) melee(m, t, a);
			if (m.onGround() && s.timer++ > 3) { s.phase = 0; dust(l, m.position(), 8, 0.4); sound(l, m.position(), SoundEvents.RAVAGER_STEP, 0.8f, 1.2f); }
			return;
		}
		if (t == null || !m.onGround() || s.cool > 0) return;
		double d = t.distanceTo(m);
		if (d < 3.5 || d > a.d("range", 14) || R.nextDouble() > 0.08) return;
		Vec3 to = t.position().subtract(m.position());
		vel(m, new Vec3(to.x * 0.13, Math.min(1.1, 0.5 * a.d("power", 1) + Math.max(0, to.y) * 0.08), to.z * 0.13));
		face(m, to);
		s.phase = 1; s.timer = 0; s.cool = a.i("cooldown", 50);
		st(m).cool = Math.max(st(m).cool, 0);
		fx(m.position(), UP, null, L("ring", "bossfx_shock", "size", 0.3, "size_end", 2, "duration", 8, "color", "#e0d0b0", "alpha_end", 0));
		sound(l, m.position(), SoundEvents.GOAT_LONG_JUMP, 1, 0.8f);
	}

	static void burrower(Mob m, ServerLevel l, A a) {
		St s = st(m);
		LivingEntity t = tgt(m, a, a.d("range", 24));
		if (s.phase == 0) {
			if (t == null || !m.onGround() || ++s.timer < a.i("every", 120)) return;
			s.phase = 1; s.timer = 0;
			m.addTag("mb_burrowed");
			m.setNoAi(true); m.setNoGravity(true); m.setPermanentlyInvulnerable(true); m.setInvisible(true); m.noPhysics = true;
			dust(l, m.position(), 20, 0.5);
			sound(l, m.position(), SoundEvents.GRAVEL_BREAK, 1.5f, 0.6f);
			fx(m.position(), UP, null, L("ring", "bossfx_crack", "size", 1.6, "duration", 30, "color", "#a08060", "alpha_end", 0, "blend", "alpha"));
			return;
		}
		s.timer++;
		Vec3 g = ground(l, m.position());
		if (t != null) {
			Vec3 d = flat(t.position().subtract(m.position()));
			double sp = a.d("speed", 0.28);
			Vec3 next = ground(l, m.position().add(d.scale(sp)));
			m.setPos(next.x, next.y - 1.6, next.z);
			g = next;
		}
		if (s.timer % 3 == 0) dust(l, g, 5, 0.3);
		if (s.timer % 8 == 0) { sound(l, g, SoundEvents.ROOTED_DIRT_BREAK, 0.8f, 0.7f); fx(g, UP, null, L("ring", "bossfx_crack", "size", 0.9, "duration", 20, "color", "#8a7050", "alpha_end", 0, "blend", "alpha")); }
		boolean under = t != null && Math.hypot(t.getX() - m.getX(), t.getZ() - m.getZ()) < 1.3;
		if (!under && s.timer < a.i("time", 80) && t != null) return;
		m.setPos(g.x, g.y, g.z);
		surface(m);
		s.phase = 0; s.timer = 0;
		dust(l, g, 25, 0.8);
		sound(l, g, SoundEvents.GENERIC_EXPLODE, 0.8f, 1.2f);
		fx(g, UP, null, L("ring", "bossfx_shock", "size", 0.5, "size_end", 3.5, "duration", 10, "color", "#d0b080", "alpha_end", 0, "ease", "out"),
			L("shards", "bossfx_spark", "size", 0.5, "size_end", 2.5, "count", 8, "piece", 0.4, "duration", 12, "color", "#c0a070", "alpha_end", 0));
		for (LivingEntity v : victims(m, g, 2, 2.5)) if (v != m) { hurt(m, v, attackDmg(m, a) * 1.5); vel(v, new Vec3(0, 1, 0)); }
	}
	static void surface(Mob m) { m.removeTag("mb_burrowed"); m.setNoAi(false); m.setNoGravity(false); m.setPermanentlyInvulnerable(false); m.setInvisible(false); m.noPhysics = false; }

	static void pack(Mob m, A a) {
		String name = a.s("name", BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()).getPath());
		LivingEntity t = m.getTarget();
		if (t == null || !t.isAlive()) return;
		for (Mob o : m.level().getEntitiesOfClass(Mob.class, m.getBoundingBox().inflate(a.d("range", 16)), o -> o != m && o.getTarget() == null)) {
			A oa = beh(o, "pack");
			if (oa != null && oa.s("name", BuiltInRegistries.ENTITY_TYPE.getKey(o.getType()).getPath()).equals(name) && beh(t, "pack") == null) o.setTarget(t);
		}
	}

	// pets: tame with an item, follow, defend, attack what you attack; sneak+right-click = sit
	static void pet(Mob m, ServerLevel l, A a) {
		String ow = tagVal(m, "mb_owner:");
		if (ow == null) return;
		St s = st(m);
		if (s.cool > 0) s.cool--;
		Player o = l.getPlayerByUUID(UUID.fromString(ow));
		LivingEntity t = m.getTarget();
		if (t != null && (t == o || ow.equals(tagVal(t, "mb_owner:")))) { m.setTarget(null); t = null; }
		if (o == null) return;
		if (o.getLastHurtByMob() != null && o.getLastHurtByMob() != m && o.tickCount - o.getLastHurtByMobTimestamp() < 100 && o.getLastHurtByMob().isAlive()) t = o.getLastHurtByMob();
		else if (o.getLastHurtMob() != null && o.getLastHurtMob() != m && o.tickCount - o.getLastHurtMobTimestamp() < 100 && o.getLastHurtMob().isAlive() && !ow.equals(tagVal(o.getLastHurtMob(), "mb_owner:"))) t = o.getLastHurtMob();
		if (t != null && t != m.getTarget()) m.setTarget(t);
		boolean sit = m.entityTags().contains("mb_sit");
		if (sit) { m.getNavigation().stop(); return; }
		if (t != null && t.isAlive()) {
			if (m instanceof PathfinderMob && m.tickCount % 10 == 0) m.getNavigation().moveTo(t, 1.25);
			melee(m, t, a);
			return;
		}
		double d = m.distanceTo(o);
		if (d > 28 && m.level() == o.level()) { Vec3 g = ground(l, o.position().add(flat(o.getLookAngle()).scale(-2))); m.teleportTo(g.x, g.y, g.z); m.getNavigation().stop(); }
		else if (d > 5 && m.tickCount % 10 == 0) m.getNavigation().moveTo(o, a.d("speed", 1.2));
		else if (d < 3) m.getNavigation().stop();
	}
	/** Tames a pet-behaviour mob to the player (what right-clicking with its item does). */
	public static void tame(Mob m, Player p) { if (beh(m, "pet") == null) behave(m, "pet", ""); m.addTag("mb_owner:" + p.getUUID()); m.setTarget(null); tameGoals(m); }
	static void tameGoals(Mob m) { // hostile mobs stop hunting players once tamed
		try {
			var f = Mob.class.getDeclaredField("targetSelector");
			f.setAccessible(true);
			GoalSelector g = (GoalSelector) f.get(m);
			g.removeAllGoals(w -> w instanceof NearestAttackableTargetGoal<?>);
		} catch (ReflectiveOperationException ex) { Moddeus.LOG.warn("pet goals", ex); }
		m.setPersistenceRequired();
	}

	static InteractionResult use(Player p, Level level, InteractionHand hand, Entity e, EntityHitResult hit) {
		if (!enabled || level.isClientSide() || hand != InteractionHand.MAIN_HAND || !(e instanceof Mob m)) return InteractionResult.PASS;
		ServerLevel l = (ServerLevel) level;
		A pet = beh(m, "pet"), mount = beh(m, "mount");
		String ow = tagVal(m, "mb_owner:");
		ItemStack held = p.getItemInHand(hand);
		if (pet != null && ow == null) {
			Identifier want = Identifier.parse(pet.s("item", "minecraft:bone").contains(":") ? pet.s("item", "minecraft:bone") : "minecraft:" + pet.s("item", "bone"));
			if (!want.equals(BuiltInRegistries.ITEM.getKey(held.getItem()))) return InteractionResult.PASS;
			if (!p.isCreative()) held.shrink(1);
			if (R.nextDouble() < pet.d("chance", 0.34)) {
				m.addTag("mb_owner:" + p.getUUID());
				m.setTarget(null);
				tameGoals(m);
				l.sendParticles(ParticleTypes.HEART, m.getX(), m.getY() + m.getBbHeight(), m.getZ(), 7, 0.4, 0.3, 0.4, 0.05);
				sound(l, m.position(), SoundEvents.PLAYER_LEVELUP, 0.6f, 1.6f);
				p.sendOverlayMessage(Component.literal("§aTamed! It follows and fights for you. Sneak+right-click = sit/follow" + (mount != null ? ", right-click = ride" : "")));
			} else l.sendParticles(ParticleTypes.SMOKE, m.getX(), m.getY() + m.getBbHeight(), m.getZ(), 6, 0.3, 0.3, 0.3, 0.02);
			return InteractionResult.SUCCESS;
		}
		if (ow != null && ow.equals(p.getUUID().toString()) && p.isShiftKeyDown()) {
			boolean sit = !m.entityTags().contains("mb_sit");
			if (sit) m.addTag("mb_sit"); else m.removeTag("mb_sit");
			p.sendOverlayMessage(Component.literal(sit ? "§eSitting" : "§eFollowing"));
			return InteractionResult.SUCCESS;
		}
		if (mount != null && !p.isShiftKeyDown() && m.getPassengers().isEmpty() && (pet == null || p.getUUID().toString().equals(ow))) {
			if (mount.b("saddle") && !m.entityTags().contains("mb_saddled")) {
				if (!held.is(Items.SADDLE)) { p.sendOverlayMessage(Component.literal("§eNeeds a saddle")); return InteractionResult.SUCCESS; }
				if (!p.isCreative()) held.shrink(1);
				m.addTag("mb_saddled");
			}
			p.startRiding(m, true, true);
			p.sendOverlayMessage(Component.literal("§bWASD = move, Space = " + (mount.b("fly") ? "fly up (look down to dive)" : "jump") + ", Sprint = faster, Sneak = get off"));
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.PASS;
	}

	/** Rider steering. Returns true while ridden. */
	static boolean mount(Mob m, ServerLevel l, A a) {
		if (!(m.getFirstPassenger() instanceof ServerPlayer p)) {
			if (m.entityTags().contains("mb_rideai")) { m.removeTag("mb_rideai"); m.setNoAi(false); if (a.b("fly")) m.setNoGravity(false); }
			return false;
		}
		St s = st(m);
		if (!m.isNoAi()) { m.setNoAi(true); m.addTag("mb_rideai"); }
		m.setTarget(null);
		var in = p.getLastClientInput();
		double sp = a.d("speed", 0.32) * (in.sprint() ? 1.6 : 1);
		float yaw = p.getYRot();
		double f = in.forward() ? 1 : in.backward() ? -0.5 : 0, st = in.left() ? 1 : in.right() ? -1 : 0;
		double rad = Math.toRadians(yaw);
		Vec3 fw = new Vec3(-Math.sin(rad), 0, Math.cos(rad)), left = new Vec3(Math.cos(rad), 0, Math.sin(rad));
		Vec3 h = fw.scale(f).add(left.scale(st));
		if (h.lengthSqr() > 1) h = h.normalize();
		h = h.scale(sp);
		double vy;
		if (a.b("fly")) {
			m.setNoGravity(true);
			vy = in.jump() ? 0.35 : f > 0 ? -Math.sin(Math.toRadians(p.getXRot())) * sp : -0.02;
			m.resetFallDistance();
		} else {
			if (m.onGround()) s.vy = in.jump() ? a.d("jump", 0.7) : 0;
			else s.vy = (s.vy - 0.08) * 0.98;
			vy = s.vy;
		}
		Vec3 v = new Vec3(h.x, vy, h.z);
		m.move(MoverType.SELF, v);
		if (m.verticalCollision && vy > 0) s.vy = 0;
		m.setDeltaMovement(Vec3.ZERO);
		m.setYRot(yaw); m.setYHeadRot(yaw); m.setYBodyRot(yaw);
		m.setXRot(0);
		m.resetFallDistance();
		p.resetFallDistance();
		return true;
	}

	// ---- variants / elite affixes (tags elite:<affix>) ---------------------------------------------------------------
	/** spec: "fiery,giant" | "random" (1-2 affixes) [scale=1.5] [name=...]. */
	public static String variant(Entity e, String spec) {
		if (!(e instanceof LivingEntity le)) return "not a mob";
		A a = args(spec.replace(',', ' '));
		List<String> af = new ArrayList<>();
		for (String w : a.words) {
			if (w.equals("random")) { int n = 1 + R.nextInt(2); while (af.size() < n) { String x = AFFIXES[R.nextInt(AFFIXES.length)]; if (!af.contains(x)) af.add(x); } }
			else if (java.util.Arrays.asList(AFFIXES).contains(w)) af.add(w);
			else throw new IllegalArgumentException("affixes: random " + String.join(" ", AFFIXES));
		}
		double hp = 1.5, dmg = 1.2, scale = a.d("scale", 1);
		for (String x : af) {
			e.addTag("elite:" + x);
			switch (x) {
				case "giant" -> { scale *= 1.8; hp *= 2; dmg *= 1.5; }
				case "tiny" -> { scale *= 0.55; hp *= 0.7; }
				case "swift" -> attr(le, Attributes.MOVEMENT_SPEED, 0.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE, "elite_speed");
				case "armored" -> { attr(le, Attributes.ARMOR, 12, AttributeModifier.Operation.ADD_VALUE, "elite_armor"); attr(le, Attributes.KNOCKBACK_RESISTANCE, 0.6, AttributeModifier.Operation.ADD_VALUE, "elite_kb"); }
				case "fiery" -> le.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, -1, 0, true, false));
				case "regen" -> le.addEffect(new MobEffectInstance(MobEffects.REGENERATION, -1, 0, true, false));
				default -> {}
			}
		}
		if (scale != 1) attr(le, Attributes.SCALE, scale - 1, AttributeModifier.Operation.ADD_MULTIPLIED_BASE, "elite_scale");
		if (!af.isEmpty()) {
			attr(le, Attributes.MAX_HEALTH, hp - 1, AttributeModifier.Operation.ADD_MULTIPLIED_BASE, "elite_hp");
			if (le.getAttributes().hasAttribute(Attributes.ATTACK_DAMAGE)) attr(le, Attributes.ATTACK_DAMAGE, dmg - 1, AttributeModifier.Operation.ADD_MULTIPLIED_BASE, "elite_dmg");
			le.setHealth(le.getMaxHealth());
			StringBuilder nm = new StringBuilder();
			for (String x : af) nm.append(Character.toUpperCase(x.charAt(0))).append(x.substring(1)).append(' ');
			String base = le.getCustomName() != null ? le.getCustomName().getString() : le.getType().getDescription().getString();
			le.setCustomName(Component.literal(a.s("name", "§6✦ " + nm + base)));
			le.setCustomNameVisible(true);
			if (le instanceof Mob m) m.setPersistenceRequired();
			tracked.put(e.getUUID(), e);
		}
		return String.join(",", af);
	}
	static void attr(LivingEntity le, Holder<net.minecraft.world.entity.ai.attributes.Attribute> at, double v, AttributeModifier.Operation op, String id) {
		var inst = le.getAttribute(at);
		if (inst != null) inst.addOrReplacePermanentModifier(new AttributeModifier(LiveRegistry.id(id), v, op));
	}
	static boolean is(Entity e, String affix) { return e.entityTags().contains("elite:" + affix); }

	static void elite(Mob m, ServerLevel l) {
		boolean any = false;
		for (String t : m.entityTags()) if (t.startsWith("elite:")) { any = true; break; }
		if (!any) return;
		St s = st(m);
		s.timer++;
		if (m.tickCount % 40 == 0) { // one aura per elite, colored by its first affix
			String c = is(m, "fiery") ? "#ff7020" : is(m, "frost") ? "#80d0ff" : is(m, "vampiric") ? "#c01030" : is(m, "wither") ? "#404040" : is(m, "thorns") ? "#60a040" : "#ffd040";
			fx(m.position(), UP, m, L("ring", "bossfx_glow", "size", Math.max(0.9, m.getBbWidth() * 1.3), "duration", 40, "color", c, "alpha", 0.5),
				L("ring", "bossfx_sigil", "size", Math.max(0.8, m.getBbWidth() * 1.1), "duration", 40, "color", c, "alpha", 0.6, "spin", 30));
		}
		LivingEntity t = m.getTarget();
		if (is(m, "teleporter") && t != null && s.timer % 100 == 0 && t.distanceTo(m) > 6) {
			Vec3 to = ground(l, t.position().add(flat(t.getLookAngle()).scale(-2)));
			fx(m.position(), UP, null, L("billboard", "bossfx_smoke", "size", 1.2, "size_end", 2.4, "duration", 14, "color", "#401060", "alpha_end", 0, "frames", 4, "y", 1, "blend", "alpha"));
			m.teleportTo(to.x, to.y, to.z);
			sound(l, to, SoundEvents.ENDERMAN_TELEPORT, 1, 1);
		}
		if (is(m, "summoner") && t != null && s.timer % 240 == 0) {
			String type = BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()).toString();
			for (int k = 0; k < 2; k++) {
				Vec3 at = ground(l, m.position().add(R.nextDouble() * 4 - 2, 0, R.nextDouble() * 4 - 2));
				cmd(l, at, "summon " + type + " ~ ~ ~ {Tags:[\"moddeus_minion\",\"mb_init\"]}");
				fx(at, UP, null, L("ring", "bossfx_sigil", "size", 0.3, "size_end", 1.4, "duration", 16, "color", "#ffd040", "alpha_end", 0, "spin", 30));
			}
		}
	}

	static void afterDamage(LivingEntity victim, DamageSource src, float base, float taken, boolean blocked) {
		if (!enabled || taken <= 0 || !(victim.level() instanceof ServerLevel l)) return;
		Entity att = src.getEntity();
		if (att instanceof LivingEntity a && src.getDirectEntity() == att) {
			if (is(a, "fiery")) victim.igniteForSeconds(4);
			if (is(a, "frost")) victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 2));
			if (is(a, "wither")) victim.addEffect(new MobEffectInstance(MobEffects.WITHER, 60, 0));
			if (is(a, "vampiric")) { a.heal(taken * 0.5f); l.sendParticles(ParticleTypes.DAMAGE_INDICATOR, a.getX(), a.getY() + a.getBbHeight(), a.getZ(), 3, 0.3, 0.2, 0.3, 0.05); }
		}
		if (att instanceof LivingEntity a && is(victim, "thorns") && src.getDirectEntity() == att && a != victim) a.hurtServer(l, l.damageSources().thorns(victim), taken * 0.3f);
		if (att instanceof LivingEntity a && beh(victim, "pack") != null && victim instanceof Mob vm) {
			String name = beh(victim, "pack").s("name", BuiltInRegistries.ENTITY_TYPE.getKey(vm.getType()).getPath());
			for (Mob o : l.getEntitiesOfClass(Mob.class, victim.getBoundingBox().inflate(beh(victim, "pack").d("range", 16)), o -> o != victim && beh(o, "pack") != null && tagVal(o, "mb_owner:") == null))
				if (beh(o, "pack").s("name", BuiltInRegistries.ENTITY_TYPE.getKey(o.getType()).getPath()).equals(name) && beh(a, "pack") == null) o.setTarget(a);
		}
	}

	static void afterDeath(LivingEntity e, DamageSource src) {
		if (!enabled || !(e.level() instanceof ServerLevel l)) return;
		boolean elite = false;
		for (String t : e.entityTags()) if (t.startsWith("elite:")) { elite = true; break; }
		if (!elite) return;
		if (is(e, "explosive")) l.explode(e, e.getX(), e.getY() + 0.5, e.getZ(), 2.5f, false, Level.ExplosionInteraction.NONE);
		ExperienceOrb.award(l, e.position(), 15 * (int) e.entityTags().stream().filter(t -> t.startsWith("elite:")).count());
	}

	static InteractionResult attackEntity(Player p, Level level, InteractionHand hand, Entity e, EntityHitResult hit) {
		if (!enabled || level.isClientSide() || !(e instanceof EndCrystal c) || !e.entityTags().contains("moddeus_crystal")) return InteractionResult.PASS;
		breakCrystal(c);
		return InteractionResult.SUCCESS;
	}

	// ---- spawn rules (content JSON "spawnrule") ----------------------------------------------------------------------
	static void spawnRules(MinecraftServer s) {
		if (RULES.isEmpty()) return;
		for (var en : RULES.entrySet()) {
			JsonObject o = en.getValue();
			String tag = "msr_" + en.getKey();
			for (ServerPlayer p : s.getPlayerList().getPlayers()) {
				ServerLevel l = (ServerLevel) p.level();
				if (p.isSpectator()) continue;
				if (o.has("dimension") && !l.dimension().identifier().toString().equals(ns(o.get("dimension").getAsString()))) continue;
				int max = o.has("max") ? o.get("max").getAsInt() : 4;
				if (l.getEntitiesOfClass(Entity.class, p.getBoundingBox().inflate(64), e -> e.entityTags().contains(tag)).size() >= max) continue;
				if (R.nextDouble() > (o.has("chance") ? o.get("chance").getAsDouble() : 0.1)) continue;
				long day = l.getOverworldClockTime() % 24000;
				String time = o.has("time") ? o.get("time").getAsString() : "any";
				boolean night = day > 13000 && day < 23000;
				if (time.equals("night") && !night || time.equals("day") && night) continue;
				double dist = 24 + R.nextDouble() * 24, ang = R.nextDouble() * Math.PI * 2;
				int x = (int) (p.getX() + Math.cos(ang) * dist), z = (int) (p.getZ() + Math.sin(ang) * dist);
				BlockPos pos;
				if (o.has("cave") && o.get("cave").getAsBoolean()) {
					pos = null;
					int top = l.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z);
					for (int tries = 0; tries < 12 && pos == null; tries++) {
						BlockPos b = new BlockPos(x, l.getMinY() + 5 + R.nextInt(Math.max(1, top - l.getMinY() - 12)), z);
						if (l.getBlockState(b).isAir() && l.getBlockState(b.above()).isAir() && !l.getBlockState(b.below()).getCollisionShape(l, b.below()).isEmpty() && !l.canSeeSky(b)) pos = b;
					}
					if (pos == null) continue;
				} else pos = new BlockPos(x, l.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
				if (!l.isLoaded(pos) || !l.getFluidState(pos).isEmpty() && !(o.has("water") && o.get("water").getAsBoolean())) continue;
				if (o.has("y")) { String[] r = o.get("y").getAsString().split("\\.\\."); if (pos.getY() < Integer.parseInt(r[0]) || pos.getY() > Integer.parseInt(r[1])) continue; }
				if (o.has("light")) { String[] r = o.get("light").getAsString().split("\\.\\."); int lv = l.getMaxLocalRawBrightness(pos); if (lv < Integer.parseInt(r[0]) || lv > Integer.parseInt(r[1])) continue; }
				if (o.has("biomes") && !biomeOk(l, pos, o.getAsJsonArray("biomes"))) continue;
				String[] g = (o.has("group") ? o.get("group").getAsString() : "1").split("-");
				int n = Integer.parseInt(g[0]) + (g.length > 1 ? R.nextInt(Integer.parseInt(g[1]) - Integer.parseInt(g[0]) + 1) : 0);
				String mob = ns(o.has("mob") ? o.get("mob").getAsString() : "zombie");
				String extra = o.has("nbt") ? o.get("nbt").getAsString().trim() : "";
				for (int k = 0; k < n; k++) {
					Vec3 at = Vec3.atBottomCenterOf(pos).add(k == 0 ? 0 : R.nextDouble() * 3 - 1.5, 0, k == 0 ? 0 : R.nextDouble() * 3 - 1.5);
					String nbt = extra.isEmpty() ? "{Tags:[\"" + tag + "\"]}" : extra.replaceFirst("^\\{", "{Tags:[\"" + tag + "\"],");
					cmd(l, at, "summon " + mob + " ~ ~ ~ " + nbt);
					Entity e = tagged(l, at, tag);
					if (e == null) continue;
					if (o.has("behave")) for (var b : o.getAsJsonObject("behave").entrySet()) behave(e, b.getKey(), b.getValue().getAsString());
					if (o.has("variant") && R.nextDouble() < (o.has("variant_chance") ? o.get("variant_chance").getAsDouble() : 1)) variant(e, o.get("variant").getAsString());
					if (o.has("cmd")) dev.moddeus.content.Gameplay.run(e, o.get("cmd"));
				}
			}
		}
	}
	static String ns(String s) { return s.contains(":") ? s : "minecraft:" + s; }
	static boolean biomeOk(ServerLevel l, BlockPos p, JsonArray list) {
		var h = l.getBiome(p);
		for (JsonElement x : list) {
			String b = x.getAsString();
			if (b.startsWith("#")) { if (h.is(net.minecraft.tags.TagKey.create(Registries.BIOME, Identifier.parse(ns(b.substring(1)))))) return true; }
			else if (h.unwrapKey().map(k -> k.identifier().toString().equals(ns(b))).orElse(false)) return true;
		}
		return false;
	}

	// ---- small utils -----------------------------------------------------------------------------------------------
	public static void cmd(ServerLevel l, Vec3 at, String c) {
		var src = l.getServer().createCommandSourceStack().withLevel(l).withPosition(at).withSuppressedOutput();
		l.getServer().getCommands().performPrefixedCommand(src, c);
	}
	public static Entity tagged(ServerLevel l, Vec3 near, String tag) {
		for (Entity e : l.getEntities((Entity) null, new AABB(near, near).inflate(48), e -> e.entityTags().contains(tag))) return e;
		return null;
	}
	static void killTag(ServerLevel l, Vec3 near, String tag) { for (Entity e : l.getEntities((Entity) null, new AABB(near, near).inflate(12), e -> e.entityTags().contains(tag))) e.discard(); }
}
