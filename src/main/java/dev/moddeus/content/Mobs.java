package dev.moddeus.content;

import com.google.gson.JsonObject;
import dev.moddeus.Moddeus;
import dev.moddeus.live.LiveRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom mobs built on a vanilla BODY template (model + AI), with their own drawn texture,
 * stats (health/speed/damage/armor/scale), optional hostile AI and a spawn egg.
 */
public final class Mobs {
	public static final String[] TEMPLATES = {"zombie", "skeleton", "spider", "creeper", "pig", "chicken", "iron_golem", "bee", "blaze"};
	/** id -> spec JSON (re-read on every content load, so ai/aura edits are live). */
	public static final Map<Identifier, JsonObject> SPECS = new ConcurrentHashMap<>();
	public static final Map<Identifier, EntityType<?>> TYPES = new ConcurrentHashMap<>();
	/** Set by the client: registers renderers for new mob types. */
	public static volatile Runnable clientListener;

	/** Event hooks, registered once per JVM (a System property survives hot swaps; static fields may not). */
	@dev.moddeus.live.OnReload
	public static void hook() {
		if (System.getProperty("moddeus.mobs.hooked") != null) return;
		System.setProperty("moddeus.mobs.hooked", "1");
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register(Mobs::onLoad);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(Mobs::tick);
	}

	/** "particle": "<particle id>" mobs leave a trail of that particle. */
	public static void tick(net.minecraft.server.MinecraftServer server) {
		if (server.getTickCount() % 3 != 0) return;
		for (var e : SPECS.entrySet()) {
			if (!e.getValue().has("particle")) continue;
			var type = TYPES.get(e.getKey());
			var p = BuiltInRegistries.PARTICLE_TYPE.getValue(Identifier.parse(e.getValue().get("particle").getAsString()));
			if (type == null || !(p instanceof net.minecraft.core.particles.SimpleParticleType sp)) continue;
			int n = e.getValue().has("particle_count") ? e.getValue().get("particle_count").getAsInt() : 2;
			for (ServerLevel level : server.getAllLevels())
				for (Entity m : level.getEntities(type, Entity::isAlive))
					level.sendParticles(sp, m.getX(), m.getY() + m.getBbHeight() * 0.5, m.getZ(), n, m.getBbWidth() * 0.4, m.getBbHeight() * 0.3, m.getBbWidth() * 0.4, 0.01);
		}
	}

	public static Item register(String id, JsonObject o) {
		Identifier rl = LiveRegistry.id(id);
		String tpl = o.has("template") ? o.get("template").getAsString() : "zombie";
		SPECS.put(rl, o);
		EntityType<?> type = LiveRegistry.register(BuiltInRegistries.ENTITY_TYPE, rl, k -> {
			EntityType<? extends LivingEntity> t = build(tpl, k, o);
			FabricDefaultAttributeRegistry.register(t, attributes(tpl, o));
			return t;
		});
		TYPES.put(rl, type);
		Item egg = LiveRegistry.register(BuiltInRegistries.ITEM, LiveRegistry.id(id + "_spawn_egg"),
			k -> new SpawnEggItem(new Item.Properties().spawnEgg(type).setId(k)));
		Runnable l = clientListener;
		if (l != null) l.run();
		return egg;
	}

	public static String template(Identifier id) {
		JsonObject o = SPECS.get(id);
		return o != null && o.has("template") ? o.get("template").getAsString() : "zombie";
	}

	private static EntityType<? extends LivingEntity> build(String tpl, ResourceKey<EntityType<?>> k, JsonObject o) {
		EntityType.Builder<? extends LivingEntity> b = switch (tpl) {
			case "zombie" -> EntityType.Builder.<Zombie>of(Zombie::new, MobCategory.MONSTER);
			case "skeleton" -> EntityType.Builder.<Skeleton>of(Skeleton::new, MobCategory.MONSTER);
			case "spider" -> EntityType.Builder.<Spider>of(Spider::new, MobCategory.MONSTER);
			case "creeper" -> EntityType.Builder.<Creeper>of(Creeper::new, MobCategory.MONSTER);
			case "blaze" -> EntityType.Builder.<Blaze>of(Blaze::new, MobCategory.MONSTER);
			case "pig" -> EntityType.Builder.<Pig>of(Pig::new, MobCategory.CREATURE);
			case "chicken" -> EntityType.Builder.<Chicken>of(Chicken::new, MobCategory.CREATURE);
			case "iron_golem" -> EntityType.Builder.<IronGolem>of(IronGolem::new, MobCategory.MISC);
			case "bee" -> EntityType.Builder.<Bee>of(Bee::new, MobCategory.CREATURE);
			default -> throw new IllegalArgumentException("unknown template " + tpl + " (" + String.join(", ", TEMPLATES) + ")");
		};
		var dims = vanilla(tpl).getDimensions();
		b.sized(dims.width(), dims.height()).eyeHeight(dims.eyeHeight());
		if (o.has("fire_immune") && o.get("fire_immune").getAsBoolean()) b.fireImmune();
		return b.build(k);
	}

	private static EntityType<?> vanilla(String tpl) {
		return switch (tpl) {
			case "skeleton" -> net.minecraft.world.entity.EntityTypes.SKELETON; case "spider" -> net.minecraft.world.entity.EntityTypes.SPIDER; case "creeper" -> net.minecraft.world.entity.EntityTypes.CREEPER;
			case "blaze" -> net.minecraft.world.entity.EntityTypes.BLAZE; case "pig" -> net.minecraft.world.entity.EntityTypes.PIG; case "chicken" -> net.minecraft.world.entity.EntityTypes.CHICKEN;
			case "iron_golem" -> net.minecraft.world.entity.EntityTypes.IRON_GOLEM; case "bee" -> net.minecraft.world.entity.EntityTypes.BEE; default -> net.minecraft.world.entity.EntityTypes.ZOMBIE;
		};
	}

	private static AttributeSupplier.Builder attributes(String tpl, JsonObject o) {
		AttributeSupplier.Builder b = switch (tpl) {
			case "skeleton" -> AbstractSkeleton.createAttributes(); case "spider" -> Spider.createAttributes();
			case "creeper" -> Creeper.createAttributes(); case "blaze" -> Blaze.createAttributes(); case "pig" -> Pig.createAttributes();
			case "chicken" -> Chicken.createAttributes(); case "iron_golem" -> IronGolem.createAttributes(); case "bee" -> Bee.createAttributes();
			default -> Zombie.createAttributes();
		};
		@SuppressWarnings("unchecked")
		var base = DefaultAttributes.getSupplier((EntityType<? extends LivingEntity>) vanilla(tpl));
		if (o.has("health")) b.add(Attributes.MAX_HEALTH, o.get("health").getAsDouble());
		if (o.has("speed")) b.add(Attributes.MOVEMENT_SPEED, o.get("speed").getAsDouble());
		if (o.has("damage")) b.add(Attributes.ATTACK_DAMAGE, o.get("damage").getAsDouble());
		else if (!base.hasAttribute(Attributes.ATTACK_DAMAGE)) b.add(Attributes.ATTACK_DAMAGE, 2); // hostile AI needs it
		if (o.has("armor")) b.add(Attributes.ARMOR, o.get("armor").getAsDouble());
		if (o.has("scale")) b.add(Attributes.SCALE, o.get("scale").getAsDouble());
		if (o.has("knockback_resistance")) b.add(Attributes.KNOCKBACK_RESISTANCE, o.get("knockback_resistance").getAsDouble());
		b.add(Attributes.FOLLOW_RANGE, o.has("follow_range") ? o.get("follow_range").getAsDouble() : base.hasAttribute(Attributes.FOLLOW_RANGE) ? base.getBaseValue(Attributes.FOLLOW_RANGE) : 16);
		return b;
	}

	/** ServerEntityEvents.ENTITY_LOAD: gives "ai": "hostile" mobs melee + player targeting. */
	public static void onLoad(Entity e, ServerLevel level) {
		JsonObject o = SPECS.get(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()));
		if (o == null || !(e instanceof PathfinderMob mob) || !o.has("ai") || !"hostile".equals(o.get("ai").getAsString())) return;
		try {
			var goals = Mob.class.getDeclaredField("goalSelector");
			var targets = Mob.class.getDeclaredField("targetSelector");
			goals.setAccessible(true);
			targets.setAccessible(true);
			GoalSelector g = (GoalSelector) goals.get(mob);
			if (g.getAvailableGoals().stream().anyMatch(w -> w.getGoal() instanceof MeleeAttackGoal)) return; // already hostile
			g.addGoal(2, new MeleeAttackGoal(mob, 1.2, false));
			((GoalSelector) targets.get(mob)).addGoal(1, new NearestAttackableTargetGoal<>(mob, Player.class, true));
		} catch (ReflectiveOperationException ex) {
			Moddeus.LOG.warn("hostile ai failed", ex);
		}
	}
}
