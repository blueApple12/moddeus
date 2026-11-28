package dev.moddeus.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.moddeus.Moddeus;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Effects, potions, enchantments and item on-hit/held/worn behaviours as data (content JSON written by
 * tools/effects.mjs, loaded by ContentLoader). Custom MobEffects are live-registered ({@link Custom});
 * potions too (brewing = data recipes, tipped arrows/splash/lingering come free); enchantments are
 * data-driven vanilla JSON (data/moddeus/enchantment) injected live into the server + client registries.
 * Behaviours: action strings ("lifesteal:0.2", "25%apply:wither:3:1", "vein:24", "cmd:say hi"), see run().
 * Java: Effects.on("moddeus:frostbite", "tick", (self, other, level, amount) -> ...).
 * Guide: .claude/skills/mc-content/EFFECTS.md
 */
public final class Effects {
	@FunctionalInterface
	public interface Fx { void run(LivingEntity self, LivingEntity other, int level, float amount); }

	static Map<String, JsonObject> EFFECTS, POTIONS, ENCHANTS, ITEMFX, ITEMS; // content id -> spec
	static Map<String, Fx> JAVA;                                              // "ns:id|event" -> code
	static Set<UUID> flying;                                                  // players given mayfly by an effect
	static boolean mining;                                                    // vein/area guard
	static int clock;
	public static volatile boolean enabled = true;

	static Map<String, JsonObject> m(Map<String, JsonObject> x) { return x != null ? x : new ConcurrentHashMap<>(); }
	static void init() {
		EFFECTS = m(EFFECTS); POTIONS = m(POTIONS); ENCHANTS = m(ENCHANTS); ITEMFX = m(ITEMFX); ITEMS = m(ITEMS);
		if (JAVA == null) JAVA = new ConcurrentHashMap<>();
		if (flying == null) flying = ConcurrentHashMap.newKeySet();
	}

	// ---- Java API ------------------------------------------------------------------------------------------------
	/** Java behaviour for an effect / enchantment / item id. event: tick | attack | hurt | start | mine | held | worn. Re-registering replaces. */
	public static void on(String id, String event, Fx fx) { init(); JAVA.put(LiveRegistry.id(id) + "|" + event, fx); }
	/** Gives an effect (any id: "moddeus:frostbite", "speed"). */
	public static boolean give(LivingEntity e, String effect, int seconds, int amplifier) {
		Holder<MobEffect> h = effect(effect);
		return h != null && e.addEffect(new MobEffectInstance(h, seconds * 20, amplifier));
	}
	public static Holder<MobEffect> effect(String id) {
		return BuiltInRegistries.MOB_EFFECT.get(Identifier.parse(id.contains(":") ? id : "minecraft:" + id)).map(r -> (Holder<MobEffect>) r).orElse(null);
	}
	/** Level of a (moddeus or vanilla) enchantment on a stack. */
	public static int level(ItemStack s, String enchant) {
		var reg = LiveRegistry.server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
		return reg.get(ResourceKey.create(Registries.ENCHANTMENT, LiveRegistry.id(enchant))).map(h -> EnchantmentHelper.getItemEnchantmentLevel(h, s)).orElse(0);
	}
	/** Adds (or replaces, same id) a permanent attribute modifier on an entity: attr(player, "scale", 0.5, "add_multiplied_base"). */
	public static void attr(LivingEntity e, String attribute, double amount, String op) {
		var h = BuiltInRegistries.ATTRIBUTE.get(Identifier.parse(attribute.contains(":") ? attribute : "minecraft:" + attribute)).orElseThrow();
		var inst = e.getAttribute(h);
		if (inst == null) return;
		Identifier mid = LiveRegistry.id("fx_" + h.key().identifier().getPath());
		inst.removeModifier(mid);
		if (amount != 0) inst.addPermanentModifier(new AttributeModifier(mid, amount, AttributeModifier.Operation.valueOf(op.toUpperCase())));
	}

	// ---- loading (ContentLoader) -----------------------------------------------------------------------------------
	public static void begin() { init(); EFFECTS.clear(); POTIONS.clear(); ENCHANTS.clear(); ITEMFX.clear(); ITEMS.clear(); }

	/** true if the type is one of ours. */
	public static boolean load(String id, JsonObject o) {
		init();
		switch (o.has("type") ? o.get("type").getAsString() : "") {
			case "effect" -> EFFECTS.put(id, o);
			case "potion" -> POTIONS.put(id, o);
			case "enchant" -> ENCHANTS.put(id, o);
			case "itemfx" -> ITEMFX.put(o.has("item") ? o.get("item").getAsString() : LiveRegistry.id(id).toString(), o);
			default -> { return false; }
		}
		return true;
	}

	/** Every content item: remembered so component edits (mc food / mc attr) re-bind live. */
	public static void item(String id, JsonObject o) { init(); if (o.has("components") && !"block".equals(str(o, "type", "block"))) ITEMS.put(id, o); }

	public static void end() {
		init();
		for (var e : EFFECTS.entrySet()) try { registerEffect(e.getKey(), e.getValue()); } catch (Exception x) { Moddeus.LOG.error("effect {}", e.getKey(), x); }
		boolean newPotion = false;
		for (var e : POTIONS.entrySet()) try { newPotion |= registerPotions(e.getKey(), e.getValue()); } catch (Exception x) { Moddeus.LOG.error("potion {}", e.getKey(), x); }
		MinecraftServer s = LiveRegistry.server;
		if (s == null) return;
		for (var e : ITEMS.entrySet()) try { rebind(s, e.getKey(), e.getValue()); } catch (Exception x) { Moddeus.LOG.error("components {}", e.getKey(), x); }
		boolean newEnchant = false;
		for (var e : ENCHANTS.entrySet()) try { newEnchant |= liveEnchant(s, e.getKey(), e.getValue()); } catch (Exception x) { Moddeus.LOG.error("enchant {}", e.getKey(), x); }
		// brewing recipes reference the potions: a data reload once they exist (recipes sync to the client too)
		if (newPotion) s.execute(() -> s.reloadResources(s.getPackRepository().getSelectedIds()));
		if (newEnchant || newPotion) try { Class.forName("dev.moddeus.content.ModTab").getMethod("refresh").invoke(null); } catch (Throwable ignored) {}
	}

	// ---- effects -------------------------------------------------------------------------------------------------------
	/** A data-driven status effect: everything it does is looked up by id at call time, so edits apply live. */
	public static final class Custom extends MobEffect {
		public final Identifier id;
		Custom(Identifier id, MobEffectCategory cat, int color) { super(cat, color); this.id = id; }
		JsonObject spec() { init(); return EFFECTS.get(id.getPath()); }
		@Override public boolean shouldApplyEffectTickThisTick(int remaining, int amp) {
			JsonObject s = spec();
			if (s == null || !enabled) return false;
			int every = Math.max(1, num(s, "every", 20));
			return remaining % every == 0 || remaining == 1;
		}
		@Override public boolean applyEffectTick(ServerLevel level, LivingEntity e, int amp) {
			JsonObject s = spec();
			if (s == null) return true;
			fire(s, id, "tick", e, null, amp + 1, 0, null);
			MobEffectInstance inst = e.getEffect(BuiltInRegistries.MOB_EFFECT.wrapAsHolder(this));
			if (inst != null && inst.getDuration() == 1) fire(s, id, "end", e, null, amp + 1, 0, null);
			return true;
		}
		@Override public void onEffectStarted(LivingEntity e, int amp) { JsonObject s = spec(); if (s != null && enabled) fire(s, id, "start", e, null, amp + 1, 0, null); }
		@Override public void onMobHurt(ServerLevel level, LivingEntity e, int amp, DamageSource src, float dmg) {
			JsonObject s = spec();
			if (s != null && enabled) fire(s, id, "hurt", e, src.getEntity() instanceof LivingEntity a ? a : null, amp + 1, dmg, null);
		}
	}

	static void registerEffect(String id, JsonObject o) throws Exception {
		MobEffectCategory cat = MobEffectCategory.valueOf(str(o, "category", "beneficial").toUpperCase());
		int color = color(str(o, "color", "#a070ff"));
		MobEffect fx = LiveRegistry.register(BuiltInRegistries.MOB_EFFECT, LiveRegistry.id(id), k -> new Custom(k.identifier(), cat, color));
		set(fx, "color", color); // live edits of an already registered effect
		set(fx, "category", cat);
		Map<?, ?> mods = (Map<?, ?>) get(fx, "attributeModifiers");
		mods.clear();
		if (o.has("attributes")) for (JsonElement el : o.getAsJsonArray("attributes")) {
			JsonObject a = el.getAsJsonObject();
			Holder<Attribute> attr = BuiltInRegistries.ATTRIBUTE.get(Identifier.parse(ns(a.get("attribute").getAsString()))).orElseThrow(() -> new IllegalArgumentException("no attribute " + a.get("attribute")));
			fx.addAttributeModifier(attr, LiveRegistry.id("effect." + id + "." + attr.unwrapKey().get().identifier().getPath()), a.get("amount").getAsDouble(),
				AttributeModifier.Operation.valueOf(str(a, "operation", "add_value").toUpperCase()));
		}
	}

	// ---- potions -------------------------------------------------------------------------------------------------------
	/** "effects": [{"id","duration","amplifier"}], optional "long"/"strong" variants: {"duration"/"amplifier"} -> potions <id>, long_<id>, strong_<id>. */
	static boolean registerPotions(String id, JsonObject o) {
		boolean fresh = false;
		fresh |= potion(id, id, o.getAsJsonArray("effects"), 1, 0);
		if (o.has("long")) fresh |= potion("long_" + id, id, o.getAsJsonArray("effects"), o.getAsJsonObject("long").has("factor") ? o.getAsJsonObject("long").get("factor").getAsDouble() : 8.0 / 3, 0);
		if (o.has("strong")) fresh |= potion("strong_" + id, id, o.getAsJsonArray("effects"), 0.5, 1);
		return fresh;
	}

	static boolean potion(String regId, String name, JsonArray effects, double durMul, int ampAdd) {
		boolean fresh = !BuiltInRegistries.POTION.containsKey(LiveRegistry.id(regId));
		List<MobEffectInstance> list = new ArrayList<>();
		for (JsonElement el : effects) {
			JsonObject e = el.getAsJsonObject();
			Holder<MobEffect> h = effect(e.get("id").getAsString());
			if (h == null) throw new IllegalArgumentException("no effect " + e.get("id"));
			list.add(new MobEffectInstance(h, h.value().isInstantaneous() ? 1 : (int) (num(e, "duration", 3600) * durMul), num(e, "amplifier", 0) + ampAdd));
		}
		Potion p = LiveRegistry.register(BuiltInRegistries.POTION, LiveRegistry.id(regId), k -> new Potion(name, list.toArray(MobEffectInstance[]::new)));
		try { set(p, "effects", List.copyOf(list)); } catch (Exception ignored) {} // live edit of durations
		return fresh;
	}

	// ---- enchantments (dynamic registry: live-injected on server + client copies) ---------------------------------------
	/** Datapack enchantment (any namespace) put live into the running registries; the pack file covers later launches. */
	public static String liveEnchant(String fullId, String json, String tagsCsv) throws Exception {
		MinecraftServer s = LiveRegistry.server;
		Identifier rid = Identifier.parse(fullId);
		JsonObject o = new JsonObject();
		o.add("enchantment", com.google.gson.JsonParser.parseString(json));
		JsonArray tg = new JsonArray(); for (String x : tagsCsv.split(",")) if (!x.isBlank()) tg.add(x.trim()); o.add("enchant_tags", tg);
		return liveEnchant(s, rid, o) ? "registered " + rid : "updated " + rid;
	}
	static boolean liveEnchant(MinecraftServer s, String id, JsonObject o) throws Exception { return liveEnchant(s, LiveRegistry.id(id), o); }
	static boolean liveEnchant(MinecraftServer s, Identifier id, JsonObject o) throws Exception {
		JsonObject json = o.getAsJsonObject("enchantment");
		List<String> tags = tags(o);
		boolean fresh = putEnchant(s.registryAccess(), id, json, tags);
		try {
			Object mc = Class.forName("net.minecraft.client.Minecraft").getMethod("getInstance").invoke(null);
			Object conn = mc.getClass().getMethod("getConnection").invoke(mc);
			if (conn != null) putEnchant((RegistryAccess) conn.getClass().getMethod("registryAccess").invoke(conn), id, json, tags);
		} catch (ClassNotFoundException ignored) { // dedicated server: the data file loads at the next start
		}
		return fresh;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	static boolean putEnchant(RegistryAccess access, String id, JsonObject json, List<String> tags) throws Exception { return putEnchant(access, LiveRegistry.id(id), json, tags); }
	static boolean putEnchant(RegistryAccess access, Identifier rid, JsonObject json, List<String> tags) throws Exception {
		Registry<Enchantment> reg = access.lookupOrThrow(Registries.ENCHANTMENT);
		String id = rid.toString();
		Enchantment value = Enchantment.DIRECT_CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, access), json).getOrThrow(e -> new IllegalArgumentException("enchantment " + id + ": " + e));
		boolean fresh = !reg.containsKey(rid);
		if (fresh) LiveRegistry.register(reg, rid, k -> value);
		else { // edit: swap the value in place (same holder, same network id)
			Holder.Reference<Enchantment> h = reg.get(ResourceKey.create(Registries.ENCHANTMENT, rid)).orElseThrow();
			Enchantment old = h.value();
			if (old != value) {
				Map byValue = (Map) get(reg, "byValue"); byValue.remove(old); byValue.put(value, h);
				var toId = (it.unimi.dsi.fastutil.objects.Reference2IntMap) get(reg, "toId"); int n = toId.removeInt(old); toId.put(value, n);
				call(h, "bindValue", new Class<?>[] {Object.class}, value);
			}
		}
		Holder.Reference<Enchantment> h = reg.get(ResourceKey.create(Registries.ENCHANTMENT, rid)).orElseThrow();
		Set<TagKey<Enchantment>> mine = new HashSet<>();
		for (String t : tags) {
			TagKey<Enchantment> key = TagKey.create(Registries.ENCHANTMENT, Identifier.parse(ns(t)));
			var named = reg.get(key);
			if (named.isEmpty()) continue;
			mine.add(key);
			List<Holder<Enchantment>> list = new ArrayList<>(named.get().stream().toList());
			if (!list.contains(h)) { list.add(h); call(named.get(), "bind", new Class<?>[] {List.class}, list); }
		}
		Set<TagKey<Enchantment>> all = new HashSet<>();
		h.tags().forEach(all::add);
		if (all.addAll(mine)) call(h, "bindTags", new Class<?>[] {java.util.Collection.class}, all);
		return fresh;
	}

	// ---- events -------------------------------------------------------------------------------------------------------
	@OnReload
	public static void hook() {
		init();
		enabled = true;
		if (System.getProperties().putIfAbsent("moddeus.effects", Boolean.TRUE) != null) return;
		net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DAMAGE.register(Effects::afterDamage);
		net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE.register(Effects::allowDamage);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(Effects::tick);
		net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER.register(Effects::mined);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register(Effects::starting);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(Effects::started);
	}

	/** Enchantments come from content JSON (no data file): injected before the levels load, then synced to clients like any entry. */
	public static void starting(MinecraftServer s) {
		init();
		for (var e : ENCHANTS.entrySet()) try { putEnchant(s.registryAccess(), e.getKey(), e.getValue().getAsJsonObject("enchantment"), tags(e.getValue())); } catch (Exception x) { Moddeus.LOG.error("enchant {}", e.getKey(), x); }
	}
	/** Component edits (mc food / mc attr) on items whose defaults (tool/armor stats) were baked at boot. */
	public static void started(MinecraftServer s) {
		init();
		for (var e : ITEMS.entrySet()) try { rebind(s, e.getKey(), e.getValue()); } catch (Exception x) { Moddeus.LOG.error("components {}", e.getKey(), x); }
	}
	static List<String> tags(JsonObject o) { List<String> t = new ArrayList<>(); if (o.has("enchant_tags")) for (JsonElement x : o.getAsJsonArray("enchant_tags")) t.add(x.getAsString()); return t; }

	@OnUnload
	public static void unhook() {
		enabled = false;
		init();
		MinecraftServer s = LiveRegistry.server;
		if (s != null) for (UUID u : flying) { ServerPlayer p = s.getPlayerList().getPlayer(u); if (p != null) setFly(p, false); }
		flying.clear();
	}

	/** Attacker side: its effects' "attack", enchantments on its weapon, its held item's itemfx. Victim side ("hurt"): armor enchants + worn/held itemfx. */
	public static void afterDamage(LivingEntity victim, DamageSource src, float base, float taken, boolean blocked) {
		if (!enabled || blocked || taken <= 0) return;
		try {
			if (src.getEntity() instanceof LivingEntity a && a != victim) {
				for (MobEffectInstance i : List.copyOf(a.getActiveEffects())) if (i.getEffect().value() instanceof Custom c && c.spec() != null) fire(c.spec(), c.id, "attack", a, victim, i.getAmplifier() + 1, taken, null);
				ItemStack w = a.getMainHandItem();
				enchants(w, "attack", a, victim, taken, null);
				itemfx(w, "attack", a, victim, taken, null);
			}
			LivingEntity attacker = src.getEntity() instanceof LivingEntity l ? l : null;
			for (EquipmentSlot slot : EquipmentSlot.values()) {
				ItemStack st = victim.getItemBySlot(slot);
				if (st.isEmpty()) continue;
				enchants(st, "hurt", victim, attacker, taken, null);
				if (slot.isArmor() || slot == EquipmentSlot.MAINHAND || slot == EquipmentSlot.OFFHAND) itemfx(st, "hurt", victim, attacker, taken, null);
			}
		} catch (Throwable t) { Moddeus.LOG.warn("effects afterDamage: {}", t.toString()); }
	}

	/** "dodge": chance (per level) to cancel incoming damage, from effects / armor enchants / itemfx. */
	public static boolean allowDamage(LivingEntity victim, DamageSource src, float amount) {
		if (!enabled) return true;
		try {
			double chance = 0;
			for (MobEffectInstance i : victim.getActiveEffects()) if (i.getEffect().value() instanceof Custom c && c.spec() != null) chance += dnum(c.spec(), "dodge", 0) * (i.getAmplifier() + 1);
			for (EquipmentSlot slot : EquipmentSlot.values()) {
				ItemStack st = victim.getItemBySlot(slot);
				if (st.isEmpty()) continue;
				JsonObject fx = ITEMFX.get(BuiltInRegistries.ITEM.getKey(st.getItem()).toString());
				if (fx != null) chance += dnum(fx, "dodge", 0);
				for (var e : st.getEnchantments().entrySet()) { JsonObject sp = ours(e.getKey()); if (sp != null) chance += dnum(sp, "dodge", 0) * e.getIntValue(); }
			}
			if (chance > 0 && victim.getRandom().nextDouble() < chance) {
				victim.level().playSound(null, victim.blockPosition(), net.minecraft.sounds.SoundEvents.BREEZE_DEFLECT, net.minecraft.sounds.SoundSource.PLAYERS, 0.8f, 1.4f);
				return false;
			}
		} catch (Throwable t) { Moddeus.LOG.warn("effects allowDamage: {}", t.toString()); }
		return true;
	}

	/** Every 10 ticks: "held" (main hand) / "worn" (armor) itemfx, enchantment "tick" on equipped items, flight cleanup. */
	public static void tick(MinecraftServer s) {
		if (!enabled || ++clock % 10 != 0) return;
		try {
			for (ServerPlayer p : s.getPlayerList().getPlayers()) {
				boolean fly = false;
				for (MobEffectInstance i : p.getActiveEffects()) if (i.getEffect().value() instanceof Custom c && c.spec() != null && has(c.spec(), "fly")) fly = true;
				for (EquipmentSlot slot : EquipmentSlot.values()) {
					ItemStack st = p.getItemBySlot(slot);
					if (st.isEmpty()) continue;
					String ev = slot.isArmor() ? "worn" : slot == EquipmentSlot.MAINHAND ? "held" : null;
					if (ev != null) { JsonObject fx = ITEMFX.get(BuiltInRegistries.ITEM.getKey(st.getItem()).toString()); if (fx != null) { fire(fx, null, ev, p, null, 1, 0, null); fly |= hasIn(fx, ev, "fly"); } }
					for (var e : st.getEnchantments().entrySet()) {
						JsonObject sp = ours(e.getKey());
						if (sp == null || !slotOk(e.getKey().value(), slot)) continue;
						fire(sp, e.getKey().unwrapKey().get().identifier(), "tick", p, null, e.getIntValue(), 0, null);
						fly |= hasIn(sp, "tick", "fly");
					}
				}
				if (!fly && flying.remove(p.getUUID())) setFly(p, false);
			}
		} catch (Throwable t) { Moddeus.LOG.warn("effects tick: {}", t.toString()); }
	}

	public static void mined(Level level, Player p, BlockPos pos, BlockState state, net.minecraft.world.level.block.entity.BlockEntity be) {
		if (!enabled || mining || level.isClientSide() || !(p instanceof ServerPlayer sp)) return;
		try {
			ItemStack w = p.getMainHandItem();
			MINED = state;
			enchants(w, "mine", sp, null, 0, pos);
			itemfx(w, "mine", sp, null, 0, pos);
		} catch (Throwable t) { Moddeus.LOG.warn("effects mined: {}", t.toString()); }
	}
	static BlockState MINED;

	static void enchants(ItemStack st, String ev, LivingEntity self, LivingEntity other, float amt, BlockPos pos) {
		for (var e : st.getEnchantments().entrySet()) {
			JsonObject sp = ours(e.getKey());
			if (sp != null) fire(sp, e.getKey().unwrapKey().get().identifier(), ev, self, other, e.getIntValue(), amt, pos);
		}
	}
	static void itemfx(ItemStack st, String ev, LivingEntity self, LivingEntity other, float amt, BlockPos pos) {
		Identifier iid = BuiltInRegistries.ITEM.getKey(st.getItem());
		JsonObject fx = ITEMFX.get(iid.toString());
		if (fx != null) fire(fx, iid, ev, self, other, 1, amt, pos);
	}
	static JsonObject ours(Holder<Enchantment> h) {
		var k = h.unwrapKey();
		return k.isPresent() && k.get().identifier().equals(LiveRegistry.id(k.get().identifier().getPath())) ? ENCHANTS.get(k.get().identifier().getPath()) : null;
	}
	static boolean slotOk(Enchantment e, EquipmentSlot slot) { return e.matchingSlot(slot); }

	/** Runs the spec's action list for one event (+ the Java behaviour registered with on()). */
	static void fire(JsonObject spec, Identifier id, String ev, LivingEntity self, LivingEntity other, int level, float amount, BlockPos pos) {
		if (spec.has(ev)) {
			JsonElement el = spec.get(ev);
			if (el.isJsonArray()) for (JsonElement a : el.getAsJsonArray()) run(a.getAsString(), self, other, level, amount, pos);
			else run(el.getAsString(), self, other, level, amount, pos);
		}
		if (id != null) { Fx fx = JAVA.get(id + "|" + ev); if (fx != null) try { fx.run(self, other, level, amount); } catch (Throwable t) { Moddeus.LOG.warn("fx {} {}: {}", id, ev, t.toString()); } }
	}

	// ---- the action library ---------------------------------------------------------------------------------------------
	/**
	 * One action. "N%action" = chance. Harmful actions hit the other entity (victim / attacker) when there is one,
	 * else self. Numbers marked *L scale with the effect/enchant level.
	 *   damage:N*L  heal:N*L  lifesteal:F*L (heal F of the damage dealt)  thorns:F*L (reflect F of damage taken)
	 *   ignite:S*L  freeze:T*L (powder-snow freeze ticks)  apply:effect:secs[:amp]  self_apply:effect:secs[:amp]
	 *   cleanse (remove harmful)  immune:poison,wither  levitate:V*L  launch:V*L  knockback:V*L  pull:R  repel:R
	 *   magnet:R (items+xp to self)  aura:N*L:R (damage hostiles around)  chain:N:R (arc F=0.5 of the hit to N nearby)
	 *   lightning  explode:P  fly (mayfly while active)  no_fall  feed:N  xp:N  teleport:R (random, chorus-like)
	 *   vein:N (mine connected same blocks)  area:R (mine a (2R+1)^2 face)  particle:id[:count]  vfx:id  sound:id[:pitch]
	 *   cmd:<command> (as self at self; {level} {other} {amount} placeholders)
	 */
	public static void run(String action, LivingEntity self, LivingEntity other, int level, float amount, BlockPos pos) {
		if (action == null || action.isBlank() || self == null || !(self.level() instanceof ServerLevel lv)) return;
		var pm = java.util.regex.Pattern.compile("^(\\d+(?:\\.\\d+)?)%(.*)$").matcher(action.trim());
		if (pm.matches()) { if (self.getRandom().nextDouble() * 100 >= Double.parseDouble(pm.group(1))) return; action = pm.group(2); }
		String[] a = action.trim().split(":", 2);
		String name = a[0], arg = a.length > 1 ? a[1] : "";
		String[] p = arg.isEmpty() ? new String[0] : arg.split(":");
		LivingEntity t = other != null ? other : self;
		double n = p.length > 0 ? d(p[0], 1) : 1;
		try {
			switch (name) {
				case "damage" -> t.hurtServer(lv, lv.damageSources().magic(), (float) (n * level));
				case "heal" -> self.heal((float) (n * level));
				case "lifesteal" -> self.heal((float) (amount * n * level));
				case "thorns" -> { if (other != null) other.hurtServer(lv, lv.damageSources().thorns(self), (float) Math.max(0.5, amount * n * level)); }
				case "ignite" -> t.igniteForSeconds((float) (n * level));
				case "freeze" -> t.setTicksFrozen(Math.min(t.getTicksRequiredToFreeze() + 60, Math.max(t.getTicksFrozen() + 40, (int) (n * level)))); // +40 outpaces the thaw between ticks
				case "apply", "self_apply" -> { LivingEntity to = name.equals("apply") ? t : self; Holder<MobEffect> h = effect(p[0]);
					if (h != null) to.addEffect(new MobEffectInstance(h, (int) (d(p.length > 1 ? p[1] : "5", 5) * 20), p.length > 2 ? (int) d(p[2], 0) : 0), self); }
				case "cleanse" -> self.getActiveEffects().stream().filter(i -> i.getEffect().value().getCategory() == MobEffectCategory.HARMFUL).map(MobEffectInstance::getEffect).toList().forEach(self::removeEffect);
				case "immune" -> { for (String e : arg.split(",")) { Holder<MobEffect> h = effect(e.trim()); if (h != null && self.hasEffect(h)) self.removeEffect(h); } }
				case "levitate" -> push(self, new Vec3(0, n * level, 0), true);
				case "launch" -> push(t, new Vec3(0, n * level, 0), false);
				case "knockback" -> { if (t != self) push(t, t.position().subtract(self.position()).multiply(1, 0, 1).normalize().scale(n * level).add(0, 0.25, 0), false); }
				case "pull", "repel" -> { double r = n; for (LivingEntity e : lv.getEntitiesOfClass(LivingEntity.class, self.getBoundingBox().inflate(r), e -> e != self && (e instanceof Enemy || name.equals("pull") && !(e instanceof Player)))) {
					Vec3 v = self.position().subtract(e.position()).normalize().scale(name.equals("pull") ? 0.35 : -0.6); push(e, v.add(0, 0.05, 0), false); } }
				case "magnet" -> { double r = n; for (Entity e : lv.getEntities(self, self.getBoundingBox().inflate(r), e -> e instanceof ItemEntity || e instanceof ExperienceOrb)) {
					e.setDeltaMovement(self.position().add(0, 0.5, 0).subtract(e.position()).normalize().scale(0.45)); e.needsSync = true; } }
				case "aura" -> { double r = p.length > 1 ? d(p[1], 4) : 4; for (LivingEntity e : lv.getEntitiesOfClass(LivingEntity.class, self.getBoundingBox().inflate(r), e -> e != self && e instanceof Enemy)) e.hurtServer(lv, lv.damageSources().indirectMagic(self, self), (float) (n * level)); }
				case "chain" -> { double r = p.length > 1 ? d(p[1], 5) : 5; int left = (int) n; LivingEntity from = t;
					for (LivingEntity e : lv.getEntitiesOfClass(LivingEntity.class, t.getBoundingBox().inflate(r), e -> e != self && e != t && !(e instanceof Player))) {
						if (left-- <= 0) break;
						e.hurtServer(lv, lv.damageSources().indirectMagic(self, self), Math.max(1, amount * 0.5f));
						arc(lv, from.position().add(0, from.getBbHeight() / 2, 0), e.position().add(0, e.getBbHeight() / 2, 0)); from = e; } }
				case "lightning" -> { var b = EntityTypes.LIGHTNING_BOLT.create(lv, net.minecraft.world.entity.EntitySpawnReason.TRIGGERED); if (b != null) { b.snapTo(t.position()); lv.addFreshEntity(b); } }
				case "explode" -> lv.explode(self, t.getX(), t.getY(), t.getZ(), (float) n, Level.ExplosionInteraction.NONE);
				case "fly" -> { if (self instanceof ServerPlayer sp && !sp.getAbilities().mayfly) { setFly(sp, true); flying.add(sp.getUUID()); } }
				case "no_fall" -> self.resetFallDistance();
				case "feed" -> { if (self instanceof Player pl) pl.getFoodData().eat((int) n, 0.3f); }
				case "xp" -> { if (self instanceof Player pl) pl.giveExperiencePoints((int) n); }
				case "teleport" -> { double r = n; for (int i = 0; i < 16; i++) { double x = self.getX() + (self.getRandom().nextDouble() - 0.5) * 2 * r, z = self.getZ() + (self.getRandom().nextDouble() - 0.5) * 2 * r;
					if (self.randomTeleport(x, self.getY() + self.getRandom().nextInt(8) - 4, z, true, (java.util.function.Predicate<BlockState>) st -> true)) break; } }
				case "vein", "area" -> { if (pos != null && self instanceof ServerPlayer sp) mine(sp, pos, name.equals("vein"), (int) n); }
				case "particle" -> { var type = BuiltInRegistries.PARTICLE_TYPE.getValue(Identifier.parse(ns(p[0])));
					if (type instanceof net.minecraft.core.particles.SimpleParticleType sp) lv.sendParticles(sp, t.getX(), t.getY() + t.getBbHeight() / 2, t.getZ(), p.length > 1 ? (int) d(p[1], 6) : 6, t.getBbWidth() / 2, t.getBbHeight() / 3, t.getBbWidth() / 2, 0.02); }
				case "vfx" -> dev.moddeus.content.Vfx.play(dev.moddeus.content.Vfx.id(arg), t.position().add(0, t.getBbHeight() / 2, 0), self.getLookAngle(), t);
				case "sound" -> { var se = BuiltInRegistries.SOUND_EVENT.get(Identifier.parse(ns(p[0]))); if (se.isPresent()) lv.playSound(null, t.blockPosition(), se.get().value(), net.minecraft.sounds.SoundSource.PLAYERS, 1f, p.length > 1 ? (float) d(p[1], 1) : 1f); }
				case "cmd" -> { String c = arg.replace("{level}", "" + level).replace("{amount}", "" + amount).replace("{other}", other != null ? other.getStringUUID() : self.getStringUUID());
					var src = lv.getServer().createCommandSourceStack().withEntity(self).withPosition(self.position()).withLevel(lv).withSuppressedOutput();
					lv.getServer().getCommands().performPrefixedCommand(src, c.startsWith("/") ? c.substring(1) : c); }
				default -> Moddeus.LOG.warn("unknown fx action {}", action);
			}
		} catch (Throwable x) { Moddeus.LOG.warn("fx action {}: {}", action, x.toString()); }
	}

	static void push(Entity e, Vec3 v, boolean set) {
		e.setDeltaMovement(set ? new Vec3(e.getDeltaMovement().x, v.y, e.getDeltaMovement().z) : e.getDeltaMovement().add(v));
		e.needsSync = true;
		if (e instanceof ServerPlayer sp) sp.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(sp));
	}

	static void arc(ServerLevel lv, Vec3 a, Vec3 b) { // a few sparks along the chain, not a particle wall
		for (int i = 1; i < 6; i++) { Vec3 q = a.lerp(b, i / 6.0); lv.sendParticles(net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK, q.x, q.y, q.z, 1, 0.05, 0.05, 0.05, 0); }
	}

	static void setFly(ServerPlayer p, boolean on) {
		if (p.isCreative() || p.isSpectator()) return;
		p.getAbilities().mayfly = on;
		if (!on) p.getAbilities().flying = false;
		p.onUpdateAbilities();
	}

	/** vein = flood-fill the same block (max n), area = the (2n+1)^2 face around pos, facing the player's look. */
	static void mine(ServerPlayer p, BlockPos start, boolean vein, int n) {
		BlockState like = MINED;
		if (like == null) return;
		ServerLevel lv = (ServerLevel) p.level();
		List<BlockPos> todo = new ArrayList<>();
		if (vein) {
			Set<BlockPos> seen = new HashSet<>(); ArrayDeque<BlockPos> q = new ArrayDeque<>(); q.add(start); seen.add(start);
			while (!q.isEmpty() && todo.size() < Math.min(n, 256)) {
				BlockPos c = q.poll();
				for (BlockPos o : BlockPos.betweenClosed(c.offset(-1, -1, -1), c.offset(1, 1, 1))) {
					if (seen.add(o.immutable()) && lv.getBlockState(o).is(like.getBlock())) { todo.add(o.immutable()); q.add(o.immutable()); }
				}
			}
		} else {
			var axis = net.minecraft.core.Direction.orderedByNearest(p)[0].getAxis();
			int r = Math.min(n, 3);
			for (int i = -r; i <= r; i++) for (int j = -r; j <= r; j++) {
				BlockPos o = switch (axis) { case X -> start.offset(0, i, j); case Y -> start.offset(i, 0, j); default -> start.offset(i, j, 0); };
				if (!o.equals(start) && !lv.getBlockState(o).isAir() && lv.getBlockState(o).getDestroySpeed(lv, o) >= 0 && p.hasCorrectToolForDrops(lv.getBlockState(o))) todo.add(o);
			}
		}
		mining = true;
		try { for (BlockPos o : todo) { if (p.getMainHandItem().isEmpty()) break; p.gameMode.destroyBlock(o); } }
		finally { mining = false; }
	}

	/** Content item components (mc food / mc attr / --components) applied to an already registered item. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	static void rebind(MinecraftServer s, String id, JsonObject o) {
		var ref = BuiltInRegistries.ITEM.get(LiveRegistry.id(id)).orElse(null);
		if (ref == null || !ref.areComponentsBound()) return;
		var ops = RegistryOps.create(JsonOps.INSTANCE, s.registryAccess());
		DataComponentMap cur = ref.components();
		DataComponentMap.Builder b = DataComponentMap.builder().addAll(cur);
		boolean changed = false;
		for (var e : o.getAsJsonObject("components").entrySet()) {
			DataComponentType type = BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(Identifier.parse(e.getKey()));
			if (type == null) continue;
			Object v = type.codecOrThrow().parse(ops, e.getValue()).result().orElse(null);
			if (v instanceof net.minecraft.world.item.component.ItemAttributeModifiers add && cur.get(type) instanceof net.minecraft.world.item.component.ItemAttributeModifiers base) {
				var ids = add.modifiers().stream().map(x -> x.modifier().id()).collect(java.util.stream.Collectors.toSet());
				var all = new ArrayList<>(base.modifiers().stream().filter(x -> !ids.contains(x.modifier().id())).toList());
				all.addAll(add.modifiers());
				v = new net.minecraft.world.item.component.ItemAttributeModifiers(all); // keep tool/armor stats, add ours
			}
			if (v != null && !v.equals(cur.get(type))) { b.set(type, v); changed = true; }
		}
		if (changed) { ref.bindComponents(b.build()); Moddeus.LOG.info("Re-bound components of moddeus:{}", id); }
	}

	// ---- plumbing ---------------------------------------------------------------------------------------------------------
	static boolean has(JsonObject s, String action) { for (String ev : new String[] {"tick", "start", "held", "worn"}) if (hasIn(s, ev, action)) return true; return false; }
	static boolean hasIn(JsonObject s, String ev, String action) {
		if (!s.has(ev)) return false;
		JsonElement el = s.get(ev);
		if (!el.isJsonArray()) return el.getAsString().startsWith(action);
		for (JsonElement a : el.getAsJsonArray()) if (a.getAsString().startsWith(action)) return true;
		return false;
	}
	static String ns(String s) { return s.contains(":") ? s : "minecraft:" + s; }
	static String str(JsonObject o, String k, String def) { return o.has(k) ? o.get(k).getAsString() : def; }
	static int num(JsonObject o, String k, int def) { return o.has(k) ? o.get(k).getAsInt() : def; }
	static double dnum(JsonObject o, String k, double def) { return o.has(k) ? o.get(k).getAsDouble() : def; }
	static double d(String s, double def) { try { return Double.parseDouble(s); } catch (Exception e) { return def; } }
	static int color(String hex) { return (int) Long.parseLong(hex.replace("#", ""), 16) & 0xFFFFFF; }
	static Field field(Class<?> c, String name) throws NoSuchFieldException {
		for (Class<?> k = c; k != null; k = k.getSuperclass()) try { Field f = k.getDeclaredField(name); f.setAccessible(true); return f; } catch (NoSuchFieldException ignored) {}
		throw new NoSuchFieldException(name);
	}
	static Object get(Object o, String name) throws Exception { return field(o.getClass(), name).get(o); }
	static void set(Object o, String name, Object v) throws Exception { field(o.getClass(), name).set(o, v); }
	static Object call(Object o, String name, Class<?>[] types, Object... args) throws Exception {
		for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass()) try { Method m = k.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(o, args); } catch (NoSuchMethodException ignored) {}
		throw new NoSuchMethodException(name);
	}
}
