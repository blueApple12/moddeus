package dev.moddeus.live;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.moddeus.Moddeus;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Position;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.Weapon;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ranged + melee weapon kinds for content JSON "tool": {"type": ...}. Guide: .claude/skills/mc-content/WEAPONS.md, CLI: tools/weapons.mjs.
 * Vanilla classes/components: bow crossbow trident mace spear shield. Moddeus kinds: arrow (ammo for every bow/crossbow/dispenser),
 * throwable, gun|wand|staff. Projectiles get their own entity type moddeus:<item id> (renderers: client/content/WeaponsClient).
 * Tool JSON is read LIVE on every shot/tick, so tuning a number in the content JSON applies to the next shot.
 * "special": {"on": "use|sneak_use|hit", "do": "sweep 4 6" | any Combat.exec ability ("dash 1.6"), "cooldown": 40} works on every item.
 */
public final class Weapons {
	public static volatile boolean enabled = true;
	static Map<Identifier, JsonObject> specs, specials;
	static Map<Identifier, EntityType<?>> types;
	static Map<Identifier, String> kinds;
	/** Client half (WeaponsClient.sync) registers renderers for new projectile types. */
	public static Runnable clientListener;

	static Map<Identifier, JsonObject> specs() { if (specs == null) specs = new ConcurrentHashMap<>(); return specs; }
	static Map<Identifier, JsonObject> specials() { if (specials == null) specials = new ConcurrentHashMap<>(); return specials; }
	public static Map<Identifier, EntityType<?>> types() { if (types == null) types = new ConcurrentHashMap<>(); return types; }
	/** projectile type id -> arrow (arrow model + textures/entity/projectiles/<id>.png) | item (the item's own model, flies point-first) | thrown (item sprite, like a snowball) */
	public static Map<Identifier, String> kinds() { if (kinds == null) kinds = new ConcurrentHashMap<>(); return kinds; }

	@OnReload
	public static void hook() {
		enabled = true;
		if (System.getProperties().get("moddeus.weapons.hooked") == null) {
			System.getProperties().put("moddeus.weapons.hooked", Boolean.TRUE);
			UseItemCallback.EVENT.register(Weapons::onUse);
			AttackEntityCallback.EVENT.register(Weapons::onHit);
		}
	}

	@OnUnload
	public static void unload() { enabled = false; }

	// ---- content JSON -> item (ContentLoader.item delegates here first; null = not a weapon kind) ---------------------

	public static Item item(String id, JsonObject o, Item.Properties props) {
		Identifier rl = LiveRegistry.id(id);
		if (o.has("special") && o.get("special").isJsonObject()) specials().put(rl, o.getAsJsonObject("special")); else specials().remove(rl);
		if (!o.has("tool") || !o.get("tool").isJsonObject()) return null;
		JsonObject t = o.getAsJsonObject("tool");
		specs().put(rl, t);
		switch (str(t, "type", "")) {
			case "bow" -> { // vanilla BowItem: pull stages, Power/Punch/Flame/Infinity, shoots any arrow incl. ours
				if (!o.has("durability")) props.durability(384);
				props.enchantable(1);
				return LiveRegistry.register(BuiltInRegistries.ITEM, rl, k -> new BowItem(props.setId(k)));
			}
			case "crossbow" -> { // vanilla CrossbowItem: charge, loaded arrow/firework, Multishot/Piercing/Quick Charge
				if (!o.has("durability")) props.durability(465);
				props.enchantable(1).component(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.EMPTY);
				return LiveRegistry.register(BuiltInRegistries.ITEM, rl, k -> new CrossbowItem(props.setId(k)));
			}
			case "trident" -> { // TridentItem: Loyalty/Riptide/Channeling/Impaling; flies as moddeus:<id> showing its own model
				if (!o.has("durability")) props.durability(250);
				props.enchantable(1).attributes(attrs(t, 8, -2.9)).component(DataComponents.TOOL, TridentItem.createToolProperties()).component(DataComponents.WEAPON, new Weapon(1));
				Item it = LiveRegistry.register(BuiltInRegistries.ITEM, rl, k -> new Trident(props.setId(k)));
				entity(rl, "item", ModTrident::new, 0.5f, 20);
				return it;
			}
			case "mace" -> { // MaceItem: smash attack from a fall, Density/Breach/Wind Burst
				if (!o.has("durability")) props.durability(500);
				props.enchantable(15).attributes(attrs(t, 5, -3.4)).component(DataComponents.TOOL, MaceItem.createToolProperties()).component(DataComponents.WEAPON, new Weapon(1));
				return LiveRegistry.register(BuiltInRegistries.ITEM, rl, k -> new MaceItem(props.setId(k)));
			}
			case "spear" -> { // vanilla 26.x spear: kinetic charge (hold right-click), piercing jab, Lunge; numbers = vanilla iron spear
				ToolMaterial m = material(str(t, "material", "iron"));
				return LiveRegistry.register(BuiltInRegistries.ITEM, rl, k -> LiveRegistry.thawed(BuiltInRegistries.BLOCK, () -> {
					props.spear(m, f(t, "swing", 0.95), f(t, "charge_damage", 0.95), f(t, "delay", 0.6), f(t, "dismount_time", 2.5), f(t, "dismount_speed", 11),
						f(t, "knockback_time", 6.75), f(t, "knockback_speed", 5.1), f(t, "damage_time", 11.25), f(t, "damage_speed", 4.6));
					if (t.has("damage") || t.has("speed")) props.attributes(attrs(t, m.attackDamageBonus() + 1, -2.9)); // --damage/--attack-speed override the material numbers
					return new Item(props.setId(k)); }));
			}
			case "shield" -> { // blocks_attacks component (vanilla shield numbers), offhand
				if (!o.has("durability")) props.durability(336);
				props.equippableUnswappable(EquipmentSlot.OFFHAND);
				if (!(o.has("components") && o.getAsJsonObject("components").has("minecraft:blocks_attacks"))) props.delayedComponent(DataComponents.BLOCKS_ATTACKS, new ShieldInit(t)); // delayed: #bypasses_shield needs the damage-type registry
				return LiveRegistry.register(BuiltInRegistries.ITEM, rl, k -> new Item(props.setId(k)));
			}
			case "arrow" -> { // ammo (needs #minecraft:arrows, tools/weapons.mjs adds it): every bow, crossbow and dispenser fires moddeus:<id>
				Item it = LiveRegistry.register(BuiltInRegistries.ITEM, rl, k -> new ArrowKind(props.setId(k)));
				net.minecraft.world.level.block.DispenserBlock.registerProjectileBehavior(it); // dispensers fire it (vanilla only registers its own arrows)
				Weapons.<ModArrow>entity(rl, "arrow", ModArrow::new, 0.5f, 20);
				return it;
			}
			case "throwable" -> { // snowball / splash-potion style: the item itself flies (synced item stack)
				if (!o.has("stack")) props.stacksTo(16);
				Item it = LiveRegistry.register(BuiltInRegistries.ITEM, rl, k -> new ThrowItem(props.setId(k)));
				Weapons.<ModThrown>entity(rl, "thrown", ModThrown::new, 0.25f, 10);
				return it;
			}
			case "gun", "wand", "staff" -> {
				if (!o.has("stack") && !o.has("durability")) props.stacksTo(1);
				return LiveRegistry.register(BuiltInRegistries.ITEM, rl, k -> new Caster(props.setId(k)));
			}
			default -> { return null; } // sword/axe/pickaxe/...: ContentLoader (a "special" still works)
		}
	}

	/** Vanilla shield numbers + #minecraft:bypasses_shield (starvation, void, /kill... are not blocked). */
	record ShieldInit(JsonObject t) implements net.minecraft.core.component.DataComponentInitializers.SingleComponentInitializer<BlocksAttacks> {
		public BlocksAttacks create(net.minecraft.core.HolderLookup.Provider p) {
			return new BlocksAttacks(f(t, "block_delay", 0.25), f(t, "disable_scale", 1),
				List.of(new BlocksAttacks.DamageReduction(f(t, "angle", 90), Optional.empty(), 0, f(t, "reduction", 1))), new BlocksAttacks.ItemDamageFunction(3, 1, 1),
				Optional.of(p.lookupOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE).getOrThrow(net.minecraft.tags.DamageTypeTags.BYPASSES_SHIELD)),
				Optional.of(sound(str(t, "block_sound", "minecraft:item.shield.block"))), Optional.of(sound(str(t, "break_sound", "minecraft:item.shield.break"))));
		}
	}

	static <T extends Entity> void entity(Identifier rl, String kind, EntityType.EntityFactory<T> f, float size, int interval) {
		kinds().put(rl, kind);
		EntityType<?> type = LiveRegistry.register(BuiltInRegistries.ENTITY_TYPE, rl, k -> EntityType.Builder.of(f, MobCategory.MISC).sized(size, size).clientTrackingRange(4).updateInterval(interval).build(k));
		if (types().put(rl, type) == null && clientListener != null) try { clientListener.run(); } catch (Throwable e) { Moddeus.LOG.warn("Weapons renderers: {}", e.toString()); }
	}

	static ItemAttributeModifiers attrs(JsonObject t, double dmg, double speed) {
		return ItemAttributeModifiers.builder()
			.add(Attributes.ATTACK_DAMAGE, new AttributeModifier(Item.BASE_ATTACK_DAMAGE_ID, num(t, "damage", dmg), AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
			.add(Attributes.ATTACK_SPEED, new AttributeModifier(Item.BASE_ATTACK_SPEED_ID, num(t, "speed", speed), AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND).build();
	}

	static ToolMaterial material(String n) {
		return switch (n) {
			case "wood", "wooden" -> ToolMaterial.WOOD; case "stone" -> ToolMaterial.STONE; case "copper" -> ToolMaterial.COPPER;
			case "gold", "golden" -> ToolMaterial.GOLD; case "diamond" -> ToolMaterial.DIAMOND; case "netherite" -> ToolMaterial.NETHERITE; default -> ToolMaterial.IRON;
		};
	}

	// ---- shared projectile behaviour (arrows, tridents, throwables) ---------------------------------------------------
	// keys: damage gravity homing homing_range trail trail_every fire effect ("poison:100:0,slowness:60:1") freeze lightning
	//       explode (radius) explode_blocks splash (radius: effects on all) linger (zone kind: heal|slow|freeze|damage|pull|push|fire)
	//       knockback vfx_hit damage_type

	public static JsonObject spec(EntityType<?> t) { JsonObject s = specs().get(EntityType.getKey(t)); return s == null ? new JsonObject() : s; }
	public static JsonObject spec(Item i) { JsonObject s = specs().get(BuiltInRegistries.ITEM.getKey(i)); return s == null ? new JsonObject() : s; }
	static Item itemOf(EntityType<?> t) { return BuiltInRegistries.ITEM.getValue(EntityType.getKey(t)); }
	@SuppressWarnings("unchecked") static <T extends Entity> EntityType<T> typeOf(Item i) { return (EntityType<T>) types().get(BuiltInRegistries.ITEM.getKey(i)); }

	static void fly(Projectile p, ServerLevel l, JsonObject s) {
		if (!enabled) return;
		double homing = num(s, "homing", 0);
		Vec3 v = p.getDeltaMovement();
		if (homing > 0 && p.getOwner() instanceof LivingEntity owner && v.lengthSqr() > 0.01) {
			LivingEntity t = Combat.nearestEnemy(owner, p.position(), num(s, "homing_range", 16), v, 0.3);
			if (t != null) {
				Vec3 to = t.getBoundingBox().getCenter().subtract(p.position()).normalize();
				p.setDeltaMovement(v.normalize().lerp(to, Math.min(1, homing)).normalize().scale(v.length()));
				p.needsSync = true;
			}
		}
		String trail = str(s, "trail", null);
		if (trail != null && p.tickCount % Math.max(1, (int) num(s, "trail_every", 3)) == 0) Combat.fx(trail, p.position(), v, null);
	}

	static Entity owner(Projectile p) { return p.getOwner() != null ? p.getOwner() : p; }

	static void hitEntity(Projectile p, LivingEntity t, JsonObject s) {
		if (!enabled) return;
		if (num(s, "fire", 0) > 0) t.igniteForSeconds((float) num(s, "fire", 0));
		if (num(s, "freeze", 0) > 0) t.setTicksFrozen(t.getTicksRequiredToFreeze() + (int) num(s, "freeze", 0));
		String eff = str(s, "effect", null);
		if (eff != null) for (String e : eff.split(",")) if (!e.isBlank()) Combat.effect(t, e.trim(), owner(p));
		if (num(s, "knockback", 0) > 0) Combat.push(t, p.getDeltaMovement().normalize().scale(num(s, "knockback", 0)).add(0, 0.2, 0));
	}

	/** Landing (block or entity): explosion, splash effects, lingering zone, hit VFX. Returns true when the projectile should vanish. */
	static boolean impact(Projectile p, Vec3 at, JsonObject s) { return impact(p, at, s, null); }
	static boolean impact(Projectile p, Vec3 at, JsonObject s, Entity direct) {
		if (!enabled || !(p.level() instanceof ServerLevel l)) return false;
		boolean gone = false;
		String vfx = str(s, "vfx_hit", null);
		if (vfx != null) Combat.fx(vfx, at, p.getDeltaMovement(), null);
		double r = num(s, "explode", 0);
		if (r > 0) {
			if (bool(s, "explode_blocks")) l.explode(p, at.x, at.y, at.z, (float) r, Level.ExplosionInteraction.TNT);
			else { if (vfx == null) Combat.fx("combat_burst", at, Vec3.ZERO, null); Combat.blast(owner(p), at, r, (float) num(s, "explode_damage", r * 2.5), str(s, "damage_type", "arcane"), 0.8); }
			gone = true;
		}
		double splash = num(s, "splash", 0);
		if (splash > 0) {
			for (LivingEntity e : l.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(splash), e -> e != direct && e.position().distanceTo(at) <= splash + 1)) hitEntity(p, e, s);
			l.sendParticles(ParticleTypes.SPLASH, at.x, at.y + 0.2, at.z, 24, splash * 0.4, 0.2, splash * 0.4, 0.1);
			gone = true;
		}
		String linger = str(s, "linger", null);
		if (linger != null && owner(p) instanceof LivingEntity le) { Combat.zone(le, at, linger, num(s, "linger_radius", 3), (int) num(s, "linger_seconds", 6), (float) num(s, "linger_power", 0)); gone = true; }
		if (bool(s, "lightning")) Combat.cmd(l, at, "summon lightning_bolt");
		return gone;
	}

	// ---- arrows -------------------------------------------------------------------------------------------------------

	/** Custom arrow item: the bow/crossbow/dispenser asks the ammo for its projectile, so ours is fired by all of them. */
	public static final class ArrowKind extends ArrowItem {
		public ArrowKind(Properties p) { super(p); }
		@Override public AbstractArrow createArrow(Level l, ItemStack ammo, LivingEntity shooter, ItemStack weapon) {
			EntityType<ModArrow> t = typeOf(this);
			return t == null ? super.createArrow(l, ammo, shooter, weapon) : new ModArrow(t, shooter, l, ammo.copyWithCount(1), weapon);
		}
		@Override public Projectile asProjectile(Level l, Position p, ItemStack s, Direction d) {
			EntityType<ModArrow> t = typeOf(this);
			if (t == null) return super.asProjectile(l, p, s, d);
			ModArrow a = new ModArrow(t, p.x(), p.y(), p.z(), l, s.copyWithCount(1), null);
			a.pickup = AbstractArrow.Pickup.ALLOWED;
			return a;
		}
	}

	public static final class ModArrow extends AbstractArrow {
		public ModArrow(EntityType<? extends AbstractArrow> t, Level l) { super(t, l); }
		ModArrow(EntityType<? extends AbstractArrow> t, LivingEntity owner, Level l, ItemStack pickup, ItemStack weapon) { super(t, owner, l, pickup, weapon); init(); }
		ModArrow(EntityType<? extends AbstractArrow> t, double x, double y, double z, Level l, ItemStack pickup, ItemStack weapon) { super(t, x, y, z, l, pickup, weapon); init(); }
		void init() { JsonObject s = spec(getType()); setBaseDamage(num(s, "damage", 2)); if (num(s, "fire", 0) > 0) igniteForSeconds(100); }
		@Override protected ItemStack getDefaultPickupItem() { return new ItemStack(itemOf(getType())); }
		@Override protected double getDefaultGravity() { return num(spec(getType()), "gravity", 0.05); }
		@Override public void tick() {
			super.tick();
			if (!isRemoved() && level() instanceof ServerLevel l && !isInGround()) try { fly(this, l, spec(getType())); } catch (Throwable e) { Moddeus.LOG.warn("arrow tick: {}", e.toString()); }
		}
		@Override protected void doPostHurtEffects(LivingEntity t) { super.doPostHurtEffects(t); hitEntity(this, t, spec(getType())); impact(this, t.position().add(0, t.getBbHeight() / 2, 0), spec(getType()), t); }
		@Override protected void onHitBlock(BlockHitResult r) { super.onHitBlock(r); if (impact(this, r.getLocation(), spec(getType()))) discard(); }
	}

	// ---- trident ------------------------------------------------------------------------------------------------------

	/** TridentItem whose throw spawns moddeus:<id> (same loyalty/channeling/impaling code) so the flying trident shows this item's model. */
	public static final class Trident extends TridentItem {
		public Trident(Properties p) { super(p); }
		@Override public boolean releaseUsing(ItemStack st, Level l, LivingEntity e, int left) {
			EntityType<ModTrident> type = typeOf(this);
			if (type == null || !(e instanceof Player p) || getUseDuration(st, e) - left < 10 || EnchantmentHelper.getTridentSpinAttackStrength(st, p) > 0 || st.nextDamageWillBreak())
				return super.releaseUsing(st, l, e, left); // riptide, too short, about to break: vanilla
			Holder<SoundEvent> snd = EnchantmentHelper.pickHighestLevel(st, EnchantmentEffectComponents.TRIDENT_SOUND).orElse(net.minecraft.sounds.SoundEvents.TRIDENT_THROW);
			p.awardStat(Stats.ITEM_USED.get(this));
			if (l instanceof ServerLevel sl) {
				st.hurtWithoutBreaking(1, p);
				ItemStack thrown = st.consumeAndReturn(1, p);
				ModTrident t = Projectile.spawnProjectileFromRotation((lv, o, s) -> ModTrident.create(type, lv, o, s), sl, thrown, p, 0, (float) num(spec(this), "throw_speed", 2.5), 1);
				if (p.hasInfiniteMaterials()) t.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
				l.playSound(null, t, snd.value(), SoundSource.PLAYERS, 1, 1);
			}
			return true;
		}
	}

	public static final class ModTrident extends ThrownTrident {
		public ModTrident(EntityType<? extends ThrownTrident> t, Level l) { super(t, l); }
		static ModTrident create(EntityType<ModTrident> type, ServerLevel l, LivingEntity owner, ItemStack st) {
			ModTrident t = new ModTrident(type, l);
			t.setPos(owner.getX(), owner.getEyeY() - 0.1, owner.getZ());
			t.setOwner(owner);
			if (owner instanceof Player) t.pickup = Pickup.ALLOWED;
			t.setPickupItemStack(st.copy());
			data(t, ThrownTrident.class, "ID_LOYALTY", (byte) EnchantmentHelper.getTridentReturnToOwnerAcceleration(l, st, t));
			data(t, ThrownTrident.class, "ID_FOIL", st.hasFoil());
			return t;
		}
		@Override protected ItemStack getDefaultPickupItem() { return new ItemStack(itemOf(getType())); }
		@Override public void tick() {
			super.tick();
			if (!isRemoved() && level() instanceof ServerLevel l && !isInGround()) try { fly(this, l, spec(getType())); } catch (Throwable e) { Moddeus.LOG.warn("trident tick: {}", e.toString()); }
		}
		@Override protected void onHitEntity(EntityHitResult r) {
			super.onHitEntity(r);
			if (r.getEntity() instanceof LivingEntity t) { hitEntity(this, t, spec(getType())); impact(this, r.getLocation(), spec(getType()), t); }
		}
	}

	@SuppressWarnings("unchecked")
	static <T> void data(Entity e, Class<?> owner, String field, T value) {
		try { var f = owner.getDeclaredField(field); f.setAccessible(true); e.getEntityData().set((EntityDataAccessor<T>) f.get(null), value); }
		catch (ReflectiveOperationException x) { Moddeus.LOG.warn("Weapons: no {}.{}", owner.getSimpleName(), field); }
	}

	// ---- throwables ---------------------------------------------------------------------------------------------------

	public static final class ThrowItem extends Item {
		public ThrowItem(Properties p) { super(p); }
		@Override public InteractionResult use(Level l, Player p, InteractionHand h) {
			ItemStack st = p.getItemInHand(h);
			JsonObject s = spec(this);
			EntityType<ModThrown> type = typeOf(this);
			if (type == null) return InteractionResult.PASS;
			l.playSound(null, p.getX(), p.getY(), p.getZ(), sound(str(s, "sound", "minecraft:entity.snowball.throw")).value(), SoundSource.NEUTRAL, 0.5f, 0.4f / (l.getRandom().nextFloat() * 0.4f + 0.8f));
			if (l instanceof ServerLevel sl)
				Projectile.spawnProjectileFromRotation((lv, o, s2) -> new ModThrown(type, o, lv, s2), sl, st, p, (float) num(s, "arc", 0), (float) num(s, "speed", 1.5), (float) num(s, "inaccuracy", 1));
			p.awardStat(Stats.ITEM_USED.get(this));
			if (num(s, "cooldown", 0) > 0) p.getCooldowns().addCooldown(st, (int) num(s, "cooldown", 0));
			st.consume(1, p);
			return InteractionResult.SUCCESS;
		}
	}

	public static final class ModThrown extends ThrowableItemProjectile {
		int bounces = -1;
		public ModThrown(EntityType<? extends ThrowableItemProjectile> t, Level l) { super(t, l); }
		ModThrown(EntityType<? extends ThrowableItemProjectile> t, LivingEntity owner, Level l, ItemStack s) { super(t, owner, l, s); }
		@Override protected Item getDefaultItem() { return itemOf(getType()); }
		@Override protected double getDefaultGravity() { return num(spec(getType()), "gravity", 0.03); }
		@Override public void tick() {
			super.tick();
			if (!isRemoved() && level() instanceof ServerLevel l) try { fly(this, l, spec(getType())); } catch (Throwable e) { Moddeus.LOG.warn("throwable tick: {}", e.toString()); }
		}
		@Override protected void onHitEntity(EntityHitResult r) {
			super.onHitEntity(r);
			JsonObject s = spec(getType());
			if (r.getEntity() instanceof LivingEntity t && level() instanceof ServerLevel) {
				Combat.damage(t, owner(this), (float) num(s, "damage", 3), str(s, "damage_type", "minecraft:thrown"));
				hitEntity(this, t, s);
			}
		}
		@Override protected void onHit(HitResult r) {
			super.onHit(r);
			if (!(level() instanceof ServerLevel l)) return;
			JsonObject s = spec(getType());
			if (bounces < 0) bounces = (int) num(s, "bounces", 0);
			if (r instanceof BlockHitResult bh && bounces-- > 0) { // ricochet off the face
				Vec3 n = Vec3.atLowerCornerOf(bh.getDirection().getUnitVec3i()), v = getDeltaMovement();
				setDeltaMovement(v.subtract(n.scale(2 * v.dot(n))).scale(0.7)); needsSync = true;
				return;
			}
			impact(this, r.getLocation(), s, r instanceof EntityHitResult eh ? eh.getEntity() : null);
			l.broadcastEntityEvent(this, (byte) 3);
			discard();
		}
	}

	// ---- guns, wands, staffs ------------------------------------------------------------------------------------------
	// keys: shot (Combat.Shot spec "speed=3 damage=6 ...") | hitscan + range + tracer, count + spread (shotgun), ammo (item id),
	//       cooldown (ticks, vanilla cooldown sweep), charge (ticks to full; release fires, damage x charge), min_charge (0..1),
	//       auto (fires every rate ticks while held), recoil (push back), sound, charge_sound, ready_sound, muzzle (vfx), animation

	public static final class Caster extends Item {
		public Caster(Properties p) { super(p); }
		boolean holds(JsonObject s) { return num(s, "charge", 0) > 0 || bool(s, "auto"); }
		@Override public InteractionResult use(Level l, Player p, InteractionHand h) {
			ItemStack st = p.getItemInHand(h);
			JsonObject s = spec(this);
			if (p.getCooldowns().isOnCooldown(st)) return InteractionResult.FAIL;
			if (holds(s)) {
				p.startUsingItem(h);
				if (!l.isClientSide()) play(p, str(s, "charge_sound", num(s, "charge", 0) > 0 ? "minecraft:item.crossbow.loading_start" : null), 0.8f, 1);
				return InteractionResult.CONSUME;
			}
			return fire(l, p, st, 1) ? InteractionResult.SUCCESS : InteractionResult.FAIL;
		}
		@Override public int getUseDuration(ItemStack st, LivingEntity e) { return holds(spec(this)) ? 72000 : 0; }
		@Override public ItemUseAnimation getUseAnimation(ItemStack st) {
			JsonObject s = spec(this);
			String def = "staff".equals(str(s, "type", "")) ? "trident" : "bow";
			try { return ItemUseAnimation.valueOf(str(s, "animation", def).toUpperCase()); } catch (IllegalArgumentException e) { return ItemUseAnimation.BOW; }
		}
		@Override public void onUseTick(Level l, LivingEntity e, ItemStack st, int left) {
			if (l.isClientSide()) return;
			JsonObject s = spec(this);
			int used = getUseDuration(st, e) - left;
			if (bool(s, "auto")) { if (used % Math.max(1, (int) num(s, "rate", 4)) == 0 && !fire(l, e, st, 1)) e.stopUsingItem(); return; }
			if (used == (int) num(s, "charge", 0)) { play(e, str(s, "ready_sound", "minecraft:item.crossbow.loading_end"), 1, 1.2f); Combat.fx("combat_charge_full", Combat.hand(e), e.getLookAngle(), e); }
		}
		@Override public boolean releaseUsing(ItemStack st, Level l, LivingEntity e, int left) {
			JsonObject s = spec(this);
			if (bool(s, "auto") || num(s, "charge", 0) <= 0) return false;
			float f = (float) Math.min(1, (getUseDuration(st, e) - left) / num(s, "charge", 20));
			if (f < num(s, "min_charge", 0.25)) return false;
			return fire(l, e, st, f);
		}
	}

	/** One shot of a gun/wand/staff at power 0..1 (charge). Server side does the work; false = no ammo / cooling down. */
	public static boolean fire(Level l, LivingEntity e, ItemStack st, float power) {
		JsonObject s = spec(st.getItem());
		String ammo = str(s, "ammo", null);
		if (ammo != null && e instanceof Player p && !p.hasInfiniteMaterials()) {
			Item a = BuiltInRegistries.ITEM.getValue(Identifier.parse(ammo));
			int slot = -1;
			for (int i = 0; i < p.getInventory().getContainerSize() && slot < 0; i++) if (p.getInventory().getItem(i).is(a)) slot = i;
			if (slot < 0) { if (!l.isClientSide()) { play(p, "minecraft:block.dispenser.fail", 0.6f, 1.6f); p.sendOverlayMessage(Component.literal("No " + new ItemStack(a).getHoverName().getString())); } return false; }
			if (!l.isClientSide()) p.getInventory().getItem(slot).shrink(1);
		}
		if (!(l instanceof ServerLevel sl) || !enabled) return true;
		String kind = str(s, "type", "gun");
		Vec3 from = Combat.hand(e), look = e.getLookAngle();
		int n = Math.max(1, (int) num(s, "count", 1));
		double spread = num(s, "spread", n > 1 ? 0.12 : 0);
		for (int i = 0; i < n; i++) {
			Vec3 dir = spread <= 0 ? look : look.add(e.getRandom().triangle(0, spread), e.getRandom().triangle(0, spread), e.getRandom().triangle(0, spread)).normalize();
			if (bool(s, "hitscan")) hitscan(sl, e, from, dir, s, power);
			else {
				Combat.Shot shot = Combat.Shot.parse(str(s, "shot", switch (kind) {
					case "wand" -> "speed=1.4 homing=0.2 damage=5 type=arcane scale=0.35 item=minecraft:amethyst_shard";
					case "staff" -> "speed=1.1 radius=3 damage=8 type=arcane scale=0.5";
					default -> "speed=3.2 damage=6 type=shrapnel scale=0.2 item=minecraft:iron_nugget hit=combat_spark";
				}));
				shot.damage *= power; shot.speed *= 0.6 + 0.4 * power;
				Combat.shoot(e, from, dir, shot);
			}
		}
		play(e, str(s, "sound", switch (kind) { case "wand" -> "minecraft:entity.evoker.cast_spell"; case "staff" -> "minecraft:entity.blaze.shoot"; default -> "minecraft:entity.firework_rocket.blast"; }), 1, 0.9f + e.getRandom().nextFloat() * 0.2f);
		String muzzle = str(s, "muzzle", "combat_muzzle");
		if (!muzzle.isEmpty()) Combat.fx(muzzle, from, look, null);
		double recoil = num(s, "recoil", "gun".equals(kind) ? 0.15 : 0);
		if (recoil > 0) Combat.push(e, look.scale(-recoil));
		if (e instanceof Player p) {
			int cd = (int) num(s, "cooldown", "gun".equals(kind) && !bool(s, "auto") ? 8 : bool(s, "auto") ? 0 : 20);
			if (cd > 0) p.getCooldowns().addCooldown(st, cd);
			p.awardStat(Stats.ITEM_USED.get(st.getItem()));
		}
		if (st.isDamageableItem()) st.hurtAndBreak(1, e, e.getUsedItemHand() == InteractionHand.OFF_HAND ? EquipmentSlot.OFFHAND : EquipmentSlot.MAINHAND);
		return true;
	}

	static void hitscan(ServerLevel l, LivingEntity e, Vec3 from, Vec3 dir, JsonObject s, float power) {
		double range = num(s, "range", 48);
		Vec3 eye = e.getEyePosition(), end = eye.add(dir.scale(range));
		BlockHitResult bh = l.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, e));
		if (bh.getType() != HitResult.Type.MISS) end = bh.getLocation();
		int pierce = (int) num(s, "pierce", 0);
		Vec3 stop = end;
		java.util.Set<Entity> done = new java.util.HashSet<>();
		for (int i = 0; i <= pierce; i++) {
			EntityHitResult eh = ProjectileUtil.getEntityHitResult(l, e, eye, stop, new AABB(eye, stop).inflate(1), x -> x instanceof LivingEntity le && Combat.enemy(e, le) && !done.contains(x), 0.3f);
			if (eh == null) break;
			LivingEntity t = (LivingEntity) eh.getEntity();
			done.add(t);
			Combat.damage(t, e, (float) num(s, "damage", 6) * power, str(s, "damage_type", "shrapnel"));
			hitEntityDirect(e, t, s);
			Combat.fx(str(s, "vfx_hit", "combat_spark"), eh.getLocation(), dir, null);
			if (i == pierce) end = eh.getLocation();
		}
		Combat.line(str(s, "tracer", "combat_bolt_dot"), from, end, 0.6, 0);
		if (bh.getType() != HitResult.Type.MISS) Combat.fx(str(s, "vfx_hit", "combat_spark"), bh.getLocation(), dir, null);
	}

	static void hitEntityDirect(LivingEntity src, LivingEntity t, JsonObject s) {
		if (num(s, "fire", 0) > 0) t.igniteForSeconds((float) num(s, "fire", 0));
		String eff = str(s, "effect", null);
		if (eff != null) for (String e : eff.split(",")) if (!e.isBlank()) Combat.effect(t, e.trim(), src);
		if (num(s, "knockback", 0) > 0) Combat.push(t, t.position().subtract(src.position()).normalize().scale(num(s, "knockback", 0)).add(0, 0.2, 0));
	}

	// ---- specials: right-click / sneak+right-click / hit attack on any item -------------------------------------------

	static InteractionResult onUse(Player p, Level l, InteractionHand h) {
		if (!enabled || l.isClientSide()) return InteractionResult.PASS;
		JsonObject s = specials == null ? null : specials.get(BuiltInRegistries.ITEM.getKey(p.getItemInHand(h).getItem()));
		if (s == null) return InteractionResult.PASS;
		String on = str(s, "on", "use");
		if (!(on.equals("use") && !p.isShiftKeyDown() || on.equals("sneak_use") && p.isShiftKeyDown() || on.equals("any_use"))) return InteractionResult.PASS;
		return special(p, h, s) ? InteractionResult.SUCCESS : InteractionResult.PASS;
	}

	static InteractionResult onHit(Player p, Level l, InteractionHand h, Entity target, EntityHitResult hit) {
		if (!enabled || l.isClientSide()) return InteractionResult.PASS;
		JsonObject s = specials == null ? null : specials.get(BuiltInRegistries.ITEM.getKey(p.getItemInHand(h).getItem()));
		if (s != null && str(s, "on", "use").equals("hit") && (!bool(s, "crit_only") || p.fallDistance > 0 && !p.onGround())) special(p, h, s);
		return InteractionResult.PASS; // the normal hit still happens
	}

	static boolean special(Player p, InteractionHand h, JsonObject s) {
		String what = str(s, "do", "sweep");
		if (!Combat.use(p, str(s, "name", what.split(" ")[0]), (int) num(s, "cooldown", 40), (float) num(s, "cost", 0))) return false;
		String[] a = what.trim().split("\\s+");
		try {
			if (a[0].equals("sweep")) sweep(p, a.length > 1 ? Double.parseDouble(a[1]) : 3.5, a.length > 2 ? Float.parseFloat(a[2]) : 6, a.length > 3 ? Double.parseDouble(a[3]) : 120);
			else Combat.exec(p, a);
		} catch (RuntimeException e) { if (p instanceof ServerPlayer sp) sp.sendOverlayMessage(Component.literal(e.getMessage() == null ? e.toString() : e.getMessage())); return false; }
		p.swing(h, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
		String vfx = str(s, "vfx", null);
		if (vfx != null) Combat.fx(vfx, p.position().add(0, 1, 0), p.getLookAngle(), p);
		return true;
	}

	/** Wide arc slash: hits every enemy within r blocks inside the arc (degrees) in front, vanilla sweep particles + sound. */
	public static int sweep(LivingEntity e, double r, float dmg, double arc) {
		if (!(e.level() instanceof ServerLevel l)) return 0;
		Vec3 look = e.getLookAngle().multiply(1, 0, 1).normalize();
		double cos = Math.cos(Math.toRadians(arc / 2));
		int n = 0;
		for (LivingEntity t : Combat.enemies(e, e.position(), r)) {
			Vec3 to = t.position().subtract(e.position()).multiply(1, 0, 1);
			if (to.lengthSqr() > 0.01 && to.normalize().dot(look) < cos) continue;
			Combat.damage(t, e, dmg, "blade");
			Combat.push(t, to.normalize().scale(0.5).add(0, 0.25, 0));
			n++;
		}
		for (int i = -2; i <= 2; i++) {
			Vec3 d = look.yRot((float) Math.toRadians(i * arc / 5)).scale(r * 0.6);
			l.sendParticles(ParticleTypes.SWEEP_ATTACK, e.getX() + d.x, e.getY() + e.getBbHeight() * 0.55, e.getZ() + d.z, 1, 0, 0, 0, 0);
		}
		play(e, "minecraft:entity.player.attack.sweep", 1, 0.8f);
		return n;
	}

	// ---- small helpers ------------------------------------------------------------------------------------------------

	static Holder<SoundEvent> sound(String id) {
		var h = BuiltInRegistries.SOUND_EVENT.get(Identifier.parse(id));
		return h.isPresent() ? h.get() : Holder.direct(SoundEvent.createVariableRangeEvent(Identifier.parse(id)));
	}
	static void play(Entity e, String id, float vol, float pitch) {
		if (id == null || id.isEmpty() || e.level().isClientSide()) return;
		e.level().playSound(null, e.getX(), e.getY(), e.getZ(), sound(id).value(), SoundSource.PLAYERS, vol, pitch);
	}
	static String str(JsonObject o, String k, String d) { JsonElement e = o.get(k); return e == null || e.isJsonNull() ? d : e.getAsString(); }
	static double num(JsonObject o, String k, double d) { JsonElement e = o.get(k); return e == null || !e.isJsonPrimitive() ? d : e.getAsDouble(); }
	static float f(JsonObject o, String k, double d) { return (float) num(o, k, d); }
	static boolean bool(JsonObject o, String k) { JsonElement e = o.get(k); return e != null && e.isJsonPrimitive() && e.getAsBoolean(); }
}
