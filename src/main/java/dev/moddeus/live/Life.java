package dev.moddeus.live;

import com.google.common.collect.ImmutableSet;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.moddeus.Moddeus;
import dev.moddeus.content.Mobs;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Entities + life for custom mobs (spec keys written by tools/life.mjs), villager trades and custom professions:
 *  - "hitbox" [w,h]: live EntityType dimensions from the cube model bounds; "parts" [{z,w,h}]: extra Interaction hitboxes
 *    along the body (long dragons/serpents) that pass hits on to the mob.
 *  - "food" [items]: feed = heal / grow babies / breed (babies are the custom type, not the vanilla template).
 *  - "tame" item (+ "tame_chance"): native taming for TamableAnimal templates, else Bosses.behave pet. "behave" {pet, mount}: Bosses.behave behaviours.
 *  - "sounds" {ambient, hurt, death, step, attack}: the mob goes silent and plays these (missing keys = its vanilla sounds).
 *  - content type "trade" {profession, level, wants, wants2, gives, uses, xp}: added to matching villagers / wandering traders.
 *  - content type "profession" {name, workstation, sound}: a new villager job + POI on that block (live).
 */
public final class Life {
	public static volatile boolean enabled = true;
	static Map<String, JsonObject> TRADES, PROFS;
	static Map<UUID, Mob> tracked;
	static Map<UUID, List<Interaction>> parts;
	static Set<UUID> liveParts;
	static Map<UUID, Double> stride;
	static List<Object[]> swaps;
	static Set<String> registered;
	static final Random R = new Random();

	static void init() {
		if (TRADES == null) TRADES = new ConcurrentHashMap<>();
		if (PROFS == null) PROFS = new ConcurrentHashMap<>();
		if (tracked == null) tracked = new ConcurrentHashMap<>();
		if (parts == null) parts = new ConcurrentHashMap<>();
		if (liveParts == null) liveParts = ConcurrentHashMap.newKeySet();
		if (stride == null) stride = new ConcurrentHashMap<>();
		if (swaps == null) swaps = new ArrayList<>();
		if (registered == null) registered = ConcurrentHashMap.newKeySet();
	}

	@OnReload
	public static void hook() {
		enabled = true;
		init();
		if (System.getProperties().get("moddeus.life.hooked") != null) return;
		System.getProperties().put("moddeus.life.hooked", Boolean.TRUE);
		ServerEntityEvents.ENTITY_LOAD.register(Life::onLoad);
		ServerTickEvents.END_SERVER_TICK.register(Life::tick);
		ServerLivingEntityEvents.AFTER_DAMAGE.register(Life::afterDamage);
		ServerLivingEntityEvents.AFTER_DEATH.register(Life::afterDeath);
		UseEntityCallback.EVENT.register(Life::use);
		AttackEntityCallback.EVENT.register(Life::attack);
	}

	@OnUnload
	public static void unhook() {
		enabled = false;
		if (parts != null) { for (var l : parts.values()) for (Interaction i : l) i.discard(); parts.clear(); }
	}

	/** ContentLoader: starts a pass (called from Gameplay.begin). */
	public static void begin() { init(); TRADES.clear(); PROFS.clear(); }

	/** ContentLoader: our content types. */
	public static boolean load(String id, JsonObject o) {
		init();
		String t = o.has("type") ? o.get("type").getAsString() : "";
		if (t.equals("trade")) { TRADES.put(id, o); return true; }
		if (t.equals("profession")) { PROFS.put(id, o); return true; }
		return false;
	}

	static JsonObject spec(Entity e) { return Mobs.SPECS.get(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType())); }
	static String str(JsonObject o, String k, String d) { return o != null && o.has(k) ? o.get(k).getAsString() : d; }
	static double num(JsonObject o, String k, double d) { return o != null && o.has(k) ? o.get(k).getAsDouble() : d; }

	// ---- load / tick ------------------------------------------------------------------------------------------
	public static void onLoad(Entity e, ServerLevel level) {
		if (!enabled) return;
		init();
		if (e instanceof Interaction && e.entityTags().contains("life_part") && !liveParts.contains(e.getUUID())) { e.discard(); return; } // stale part from an earlier session
		JsonObject o = spec(e);
		if (o == null) {
			if (e instanceof AgeableMob b && b.isBaby()) checkBaby(b, level);
			return;
		}
		if (!(e instanceof Mob m)) return;
		if (o.has("sounds")) m.setSilent(true);
		if (!m.entityTags().contains("life_init")) {
			m.addTag("life_init");
			if (o.has("behave")) for (var b : o.getAsJsonObject("behave").entrySet()) Bosses.behave(m, b.getKey(), b.getValue().getAsString());
			if (o.has("tame") && !(m instanceof TamableAnimal)) Bosses.behave(m, "pet", "item=" + o.get("tame").getAsString() + ",chance=" + num(o, "tame_chance", 0.34));
		}
		tracked.put(m.getUUID(), m);
	}

	/** Vanilla breeding makes a baby of the TEMPLATE type: swap it for the custom type next tick. */
	static void checkBaby(AgeableMob baby, ServerLevel level) {
		String vt = BuiltInRegistries.ENTITY_TYPE.getKey(baby.getType()).toString();
		for (Entity p : level.getEntities(baby, baby.getBoundingBox().inflate(4))) {
			if (!(p instanceof Animal a) || a.getAge() < 5990) continue;
			JsonObject o = spec(p);
			if (o == null) continue;
			String tpl = str(o, "template", "zombie");
			if (!vt.equals(tpl.contains(":") ? tpl : "minecraft:" + tpl)) continue;
			synchronized (swaps) { swaps.add(new Object[] {baby, p.getType()}); }
			return;
		}
	}

	public static void tick(MinecraftServer server) {
		if (!enabled) return;
		init();
		if (!swaps.isEmpty()) {
			List<Object[]> now;
			synchronized (swaps) { now = new ArrayList<>(swaps); swaps.clear(); }
			for (Object[] s : now) {
				AgeableMob b = (AgeableMob) s[0];
				if (!b.isAlive() || !(b.level() instanceof ServerLevel l)) continue;
				Entity n = ((EntityType<?>) s[1]).create(l, EntitySpawnReason.BREEDING);
				if (n instanceof AgeableMob am) {
					am.setAge(-24000);
					am.snapTo(b.getX(), b.getY(), b.getZ(), b.getYRot(), 0);
					l.addFreshEntity(am);
					b.discard();
				} else if (n != null) n.discard();
			}
		}
		if (!PROFS.isEmpty()) for (var p : PROFS.entrySet()) if (!registered.contains(p.getKey())) profession(p.getKey(), p.getValue());
		if (server.getTickCount() % 40 == 0) hitboxes(server);
		for (Mob m : new ArrayList<>(tracked.values())) {
			if (m.isRemoved() || !m.isAlive()) { tracked.remove(m.getUUID()); removeParts(m.getUUID()); stride.remove(m.getUUID()); continue; }
			JsonObject o = spec(m);
			if (o == null) continue;
			try {
				if (o.has("sounds")) sounds(m, o.getAsJsonObject("sounds"));
				if (o.has("parts")) parts(m, o);
				if (o.has("hitbox") && m.isBaby()) { // templates keep their own baby size: use half the model hitbox instead
					float bw = o.getAsJsonArray("hitbox").get(0).getAsFloat() * 0.5f * m.getScale(), bh = o.getAsJsonArray("hitbox").get(1).getAsFloat() * 0.5f * m.getScale();
					if (Math.abs(m.getBbWidth() - bw) > 0.01f || Math.abs(m.getBbHeight() - bh) > 0.01f) {
						var df = Entity.class.getDeclaredField("dimensions"); df.setAccessible(true); df.set(m, EntityDimensions.scalable(bw, bh).withEyeHeight(bh * 0.85f));
						var ef = Entity.class.getDeclaredField("eyeHeight"); ef.setAccessible(true); ef.setFloat(m, bh * 0.85f);
						m.setPos(m.position());
					}
				}
			} catch (RuntimeException | ReflectiveOperationException ex) { Moddeus.LOG.warn("life tick {}: {}", m, ex.toString()); }
		}
	}

	// ---- hitboxes ---------------------------------------------------------------------------------------------
	/** Applies "hitbox" to the live EntityType (model edits resize mobs that already exist). */
	static void hitboxes(MinecraftServer server) {
		for (var e : Mobs.SPECS.entrySet()) {
			JsonObject o = e.getValue();
			EntityType<?> t = Mobs.TYPES.get(e.getKey());
			if (t == null || !o.has("hitbox")) continue;
			float w = o.getAsJsonArray("hitbox").get(0).getAsFloat(), h = o.getAsJsonArray("hitbox").get(1).getAsFloat();
			EntityDimensions d = t.getDimensions();
			if (Math.abs(d.width() - w) < 0.01 && Math.abs(d.height() - h) < 0.01) continue;
			try {
				var f = EntityType.class.getDeclaredField("dimensions");
				f.setAccessible(true);
				f.set(t, EntityDimensions.scalable(w, h).withEyeHeight(h * 0.85f));
			} catch (ReflectiveOperationException ex) { Moddeus.LOG.warn("hitbox {}: {}", e.getKey(), ex.toString()); continue; }
			for (ServerLevel l : server.getAllLevels()) for (Entity x : l.getEntities(t, Entity::isAlive)) x.refreshDimensions();
			Moddeus.LOG.info("Life: hitbox of {} is now {}x{}", e.getKey(), w, h);
		}
	}

	/** Interaction boxes along the body (forward = model front), following the mob every tick. */
	static void parts(Mob m, JsonObject o) {
		var spec = o.getAsJsonArray("parts");
		List<Interaction> list = parts.get(m.getUUID());
		float sc = m.getScale();
		if (list == null || list.size() != spec.size() || list.stream().anyMatch(Entity::isRemoved)) {
			removeParts(m.getUUID());
			list = new ArrayList<>();
			for (JsonElement x : spec) {
				Interaction i = EntityTypes.INTERACTION.create(m.level(), EntitySpawnReason.TRIGGERED);
				if (i == null) return;
				JsonObject p = x.getAsJsonObject();
				i.setWidth((float) num(p, "w", 1) * sc);
				i.setHeight((float) num(p, "h", 1) * sc);
				i.addTag("life_part");
				i.addTag("life_of:" + m.getUUID());
				i.setPos(m.position());
				liveParts.add(i.getUUID());
				m.level().addFreshEntity(i);
				list.add(i);
			}
			parts.put(m.getUUID(), list);
		}
		double yaw = Math.toRadians(m.yBodyRot);
		Vec3 fwd = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
		for (int k = 0; k < list.size(); k++) {
			JsonObject p = spec.get(k).getAsJsonObject();
			Vec3 at = m.position().add(fwd.scale(num(p, "z", 0) * sc)).add(0, num(p, "y", 0) * sc, 0);
			Interaction i = list.get(k);
			i.setPos(at.x, at.y, at.z);
		}
	}

	static void removeParts(UUID owner) {
		List<Interaction> l = parts.remove(owner);
		if (l != null) for (Interaction i : l) { liveParts.remove(i.getUUID()); i.discard(); }
	}

	static Mob ownerOf(Entity part) {
		if (!(part instanceof Interaction) || !part.entityTags().contains("life_part")) return null;
		for (String t : part.entityTags()) if (t.startsWith("life_of:")) {
			Mob m = tracked.get(UUID.fromString(t.substring(8)));
			if (m != null) return m;
		}
		return null;
	}

	// ---- sounds -------------------------------------------------------------------------------------------------
	static SoundEvent sound(String id) {
		Identifier k = Identifier.parse(id);
		return BuiltInRegistries.SOUND_EVENT.getOptional(k).orElseGet(() -> SoundEvent.createVariableRangeEvent(k));
	}

	static void play(Mob m, SoundEvent s, float vol) {
		if (s == null) return;
		float pitch = (R.nextFloat() - R.nextFloat()) * 0.2f + (m.isBaby() ? 1.5f : 1f);
		m.level().playSound(null, m.getX(), m.getY(), m.getZ(), s, m.getSoundSource(), vol * Math.max(1f, m.getScale()), pitch);
	}

	static SoundEvent vanilla(Mob m, String method, DamageSource src) {
		try {
			java.lang.reflect.Method f = src == null ? LivingEntity.class.getDeclaredMethod(method) : LivingEntity.class.getDeclaredMethod(method, DamageSource.class);
			if (method.equals("getAmbientSound")) f = Mob.class.getDeclaredMethod(method);
			f.setAccessible(true);
			return (SoundEvent) (src == null ? f.invoke(m) : f.invoke(m, src));
		} catch (ReflectiveOperationException | RuntimeException e) { return null; }
	}

	static void sounds(Mob m, JsonObject s) {
		if (!m.isSilent()) m.setSilent(true);
		if (R.nextInt(Math.max(1, (int) num(s, "ambient_every", 100))) == 0 && m.hurtTime == 0)
			play(m, s.has("ambient") ? sound(s.get("ambient").getAsString()) : vanilla(m, "getAmbientSound", null), 1f);
		if (m.onGround()) {
			double dx = m.getX() - m.xo, dz = m.getZ() - m.zo, d = stride.getOrDefault(m.getUUID(), 0.0) + Math.sqrt(dx * dx + dz * dz);
			double len = 0.6 + m.getBbWidth() * 0.6;
			if (d > len) {
				d = 0;
				BlockState below = m.level().getBlockState(m.blockPosition().below());
				play(m, s.has("step") ? sound(s.get("step").getAsString()) : below.getSoundType().getStepSound(), s.has("step") ? 0.5f : 0.15f);
			}
			stride.put(m.getUUID(), d);
		}
	}

	public static void afterDamage(LivingEntity e, DamageSource src, float base, float taken, boolean blocked) {
		if (!enabled || blocked) return;
		if (src.getEntity() instanceof Mob a) { JsonObject ao = spec(a); if (ao != null && ao.has("sounds") && ao.getAsJsonObject("sounds").has("attack")) play(a, sound(ao.getAsJsonObject("sounds").get("attack").getAsString()), 1f); }
		JsonObject o = spec(e);
		if (o == null || !o.has("sounds") || !(e instanceof Mob m) || !e.isAlive()) return;
		JsonObject s = o.getAsJsonObject("sounds");
		play(m, s.has("hurt") ? sound(s.get("hurt").getAsString()) : vanilla(m, "getHurtSound", src), 1f);
	}

	public static void afterDeath(LivingEntity e, DamageSource src) {
		if (!enabled) return;
		init();
		removeParts(e.getUUID());
		JsonObject o = spec(e);
		if (o == null || !o.has("sounds") || !(e instanceof Mob m)) return;
		JsonObject s = o.getAsJsonObject("sounds");
		play(m, s.has("death") ? sound(s.get("death").getAsString()) : vanilla(m, "getDeathSound", null), 1.2f);
	}

	// ---- interaction: parts, food, taming, trades ---------------------------------------------------------------
	public static InteractionResult attack(Player p, Level level, InteractionHand hand, Entity e, EntityHitResult hit) {
		if (!enabled || level.isClientSide()) return InteractionResult.PASS;
		Mob owner = ownerOf(e);
		if (owner == null) return InteractionResult.PASS;
		p.attack(owner);
		return InteractionResult.SUCCESS;
	}

	static boolean matches(ItemStack st, JsonElement list) {
		String id = BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
		if (list.isJsonArray()) { for (JsonElement x : list.getAsJsonArray()) if (id.equals(x.getAsString())) return true; return false; }
		return id.equals(list.getAsString());
	}

	public static InteractionResult use(Player p, Level level, InteractionHand hand, Entity e, EntityHitResult hit) {
		if (!enabled || level.isClientSide() || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
		init();
		if (e instanceof AbstractVillager v) { trades(v); return InteractionResult.PASS; }
		Mob owner = ownerOf(e);
		if (owner != null) e = owner;
		JsonObject o = spec(e);
		ItemStack st = p.getMainHandItem();
		if (o == null || !(e instanceof Mob m) || st.isEmpty()) return InteractionResult.PASS;
		if (o.has("tame") && m instanceof TamableAnimal t && !t.isTame() && matches(st, o.get("tame"))) {
			if (!p.getAbilities().instabuild) st.shrink(1);
			if (R.nextDouble() < num(o, "tame_chance", 0.34)) {
				t.tame(p); t.getNavigation().stop(); t.setTarget(null); t.setOrderedToSit(true);
				level.broadcastEntityEvent(t, (byte) 7);
				p.sendOverlayMessage(Component.literal("§aTamed! Right-click = sit/follow"));
			} else level.broadcastEntityEvent(t, (byte) 6);
			return InteractionResult.SUCCESS;
		}
		if (o.has("food") && matches(st, o.get("food"))) {
			boolean used = false;
			if (m instanceof Animal a && a.isBaby()) { a.ageUp((int) (-a.getAge() / 20 * 0.1f), true); used = true; }
			else if (m.getHealth() < m.getMaxHealth()) { m.heal(Math.max(2, (float) num(o, "food_heal", 4))); used = true; }
			else if (m instanceof Animal a && a.getAge() == 0 && a.canFallInLove()) { a.setInLove(p); used = true; }
			if (!used) return InteractionResult.PASS;
			if (!p.getAbilities().instabuild) st.shrink(1);
			level.playSound(null, m.getX(), m.getY(), m.getZ(), SoundEvents.GENERIC_EAT.value(), m.getSoundSource(), 1f, 1f);
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.PASS;
	}

	static ItemCost cost(JsonObject s) { return new ItemCost(BuiltInRegistries.ITEM.getValue(Identifier.parse(s.get("item").getAsString())), (int) num(s, "count", 1)); }

	/** Adds every matching "trade" spec once to this villager / wandering trader (tag lt_<id>). */
	static void trades(AbstractVillager v) {
		if (TRADES.isEmpty()) return;
		String prof; int lvl;
		if (v instanceof Villager vi) { prof = BuiltInRegistries.VILLAGER_PROFESSION.getKey(vi.getVillagerData().profession().value()).toString(); lvl = vi.getVillagerData().level(); }
		else if (v instanceof WanderingTrader) { prof = "minecraft:wandering_trader"; lvl = 5; }
		else return;
		for (var t : TRADES.entrySet()) {
			JsonObject o = t.getValue();
			if (!prof.equals(str(o, "profession", "")) || num(o, "level", 1) > lvl || v.entityTags().contains("lt_" + t.getKey())) continue;
			try {
				var r = o.getAsJsonObject("gives");
				v.getOffers().add(new MerchantOffer(cost(o.getAsJsonObject("wants")),
					o.has("wants2") ? java.util.Optional.of(cost(o.getAsJsonObject("wants2"))) : java.util.Optional.empty(),
					new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(r.get("item").getAsString())), (int) num(r, "count", 1)),
					(int) num(o, "uses", 12), (int) num(o, "xp", 2), (float) num(o, "price_mult", 0.05)));
				v.addTag("lt_" + t.getKey());
			} catch (RuntimeException ex) { Moddeus.LOG.warn("trade {}: {}", t.getKey(), ex.toString()); }
		}
	}

	// ---- professions --------------------------------------------------------------------------------------------
	/** Job-site predicate as a named class (no lambdas kept in registries across hot swaps). */
	public static final class IsPoi implements Predicate<Holder<PoiType>> {
		final ResourceKey<PoiType> key;
		IsPoi(ResourceKey<PoiType> key) { this.key = key; }
		public boolean test(Holder<PoiType> h) { return h.is(key); }
	}

	@SuppressWarnings("unchecked")
	static void profession(String id, JsonObject o) {
		registered.add(id);
		Block b = BuiltInRegistries.BLOCK.getValue(Identifier.parse(str(o, "workstation", "minecraft:air")));
		if (b == null || b.defaultBlockState().isAir()) { Moddeus.LOG.warn("profession {}: unknown workstation {}", id, str(o, "workstation", "")); return; }
		try {
			Identifier pid = LiveRegistry.id(id);
			Set<BlockState> states = ImmutableSet.copyOf(b.getStateDefinition().getPossibleStates());
			PoiType poi = LiveRegistry.register(BuiltInRegistries.POINT_OF_INTEREST_TYPE, pid, k -> new PoiType(states, 1, 1));
			ResourceKey<PoiType> pk = ResourceKey.create(Registries.POINT_OF_INTEREST_TYPE, pid);
			Holder<PoiType> ph = BuiltInRegistries.POINT_OF_INTEREST_TYPE.get(pk).orElseThrow();
			var f = net.minecraft.world.entity.ai.village.poi.PoiTypes.class.getDeclaredField("TYPE_BY_STATE");
			f.setAccessible(true);
			Map<BlockState, Holder<PoiType>> byState = (Map<BlockState, Holder<PoiType>>) f.get(null);
			for (BlockState s : states) byState.putIfAbsent(s, ph);
			SoundEvent ws = o.has("sound") ? sound(str(o, "sound", "")) : SoundEvents.VILLAGER_WORK_TOOLSMITH;
			LiveRegistry.register(BuiltInRegistries.VILLAGER_PROFESSION, pid, k -> new VillagerProfession(Component.literal(str(o, "name", id)),
				new IsPoi(pk), new IsPoi(pk), ImmutableSet.of(), ImmutableSet.of(), ws, new Int2ObjectOpenHashMap<>()));
			Moddeus.LOG.info("Life: profession moddeus:{} on {} (poi {})", id, str(o, "workstation", ""), poi);
		} catch (ReflectiveOperationException | RuntimeException ex) { Moddeus.LOG.warn("profession {}: {}", id, ex.toString()); }
	}
}
