package dev.moddeus.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.moddeus.Moddeus;
import dev.moddeus.live.LiveRegistry;
import dev.moddeus.live.OnReload;
import dev.moddeus.live.OnUnload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Data-driven gameplay systems (content JSON written by tools/extra.mjs, loaded by ContentLoader):
 * "boss" = boss bar + health phases + timed attacks + death commands on a mob type or tagged entity,
 * "setbonus" = full-armor-set effects/attributes/commands, "drop" = extra loot pool on any loot table (mob, block, chest),
 * "npc" = talking NPC with dialogue lines and a trade shop. Commands run as the boss / player, so anything vanilla can do works.
 */
public final class Gameplay {
	static Map<String, JsonObject> BOSS, SETS, DROPS, NPCS;
	public static Map<UUID, Bar> bars; // live boss bars by entity
	static Set<String> worn; // "playerUuid|setId" currently getting a bonus
	static Map<String, Integer> talk; // "playerUuid|entityUuid" -> next line
	static String dropsHash = "";
	public static volatile boolean enabled = true;

	static Map<String, JsonObject> m(Map<String, JsonObject> x) { return x != null ? x : new ConcurrentHashMap<>(); }
	static void init() {
		BOSS = m(BOSS); SETS = m(SETS); DROPS = m(DROPS); NPCS = m(NPCS);
		if (bars == null) bars = new HashMap<>();
		if (worn == null) worn = new HashSet<>();
		if (talk == null) talk = new HashMap<>();
	}

	// ---- loading (ContentLoader) -------------------------------------------------------------------------------
	public static void begin() { init(); BOSS.clear(); SETS.clear(); DROPS.clear(); NPCS.clear(); dev.moddeus.live.Bosses.begin(); dev.moddeus.live.Life.begin(); }

	/** true if the type is one of ours. */
	public static boolean load(String id, JsonObject o) {
		init();
		String t = o.has("type") ? o.get("type").getAsString() : "";
		switch (t) {
			case "boss" -> BOSS.put(id, o);
			case "setbonus" -> SETS.put(id, o);
			case "drop" -> DROPS.put(id, o);
			case "npc" -> NPCS.put(id, o);
			default -> { return dev.moddeus.live.Bosses.load(id, o); } // mobai / spawnrule
		}
		return true;
	}

	/** Loot tables only pick up drop changes on a data reload: trigger one when they changed. */
	public static void end() {
		init();
		String h = new java.util.TreeMap<>(DROPS).toString();
		if (h.equals(dropsHash)) return;
		dropsHash = h;
		MinecraftServer s = LiveRegistry.server;
		if (s != null) s.execute(() -> s.reloadResources(s.getPackRepository().getSelectedIds()));
	}

	public static boolean has(String id) { init(); return BOSS.containsKey(id) || SETS.containsKey(id) || DROPS.containsKey(id) || NPCS.containsKey(id); }

	// ---- hooks -------------------------------------------------------------------------------------------------
	@OnReload
	public static void hook() {
		init();
		enabled = true;
		if (System.getProperties().putIfAbsent("moddeus.gameplay", Boolean.TRUE) != null) return;
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(Gameplay::tick);
		net.fabricmc.fabric.api.loot.v3.LootTableEvents.MODIFY.register(Gameplay::loot);
		net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register(Gameplay::use);
	}

	@OnUnload
	public static void unhook() {
		enabled = false;
		init();
		for (Bar b : bars.values()) b.ev.removeAllPlayers();
		bars.clear();
		MinecraftServer s = LiveRegistry.server;
		if (s != null) for (ServerPlayer p : s.getPlayerList().getPlayers()) for (String k : new ArrayList<>(worn)) if (k.startsWith(p.getUUID() + "|")) unwear(p, k.substring(k.indexOf('|') + 1));
		worn.clear();
	}

	static void tick(MinecraftServer s) {
		if (!enabled) return;
		init();
		try { bosses(s); } catch (Throwable t) { Moddeus.LOG.warn("boss tick", t); }
		try { sets(s); } catch (Throwable t) { Moddeus.LOG.warn("set bonus tick", t); }
	}

	// ---- commands ----------------------------------------------------------------------------------------------
	/** Runs commands as + at the entity (op level, no chat spam). One string or an array of strings. */
	public static void run(Entity e, JsonElement cmds) {
		if (cmds == null || !(e.level() instanceof ServerLevel lvl)) return;
		MinecraftServer s = lvl.getServer();
		var src = s.createCommandSourceStack().withEntity(e).withPosition(e.position()).withLevel(lvl).withSuppressedOutput();
		List<String> l = new ArrayList<>();
		if (cmds.isJsonArray()) cmds.getAsJsonArray().forEach(c -> l.add(c.getAsString())); else l.add(cmds.getAsString());
		for (String c : l) try { s.getCommands().performPrefixedCommand(src, c); } catch (Throwable t) { Moddeus.LOG.warn("command {}: {}", c, t.toString()); }
	}

	static String str(JsonObject o, String k, String d) { return o.has(k) ? o.get(k).getAsString() : d; }
	static double num(JsonObject o, String k, double d) { return o.has(k) ? o.get(k).getAsDouble() : d; }

	// ---- bosses ------------------------------------------------------------------------------------------------
	public static final class Bar { public ServerBossEvent ev; public Entity e; public String id; }

	static String bossOf(Entity e) {
		String type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
		for (var b : BOSS.entrySet()) {
			if (e.entityTags().contains("moddeus_boss_" + b.getKey())) return b.getKey();
			if (b.getValue().has("mob") && !b.getValue().has("tagged") && type.equals(b.getValue().get("mob").getAsString())) return b.getKey();
		}
		return null;
	}

	static void bosses(MinecraftServer s) {
		if (!BOSS.isEmpty() && s.getTickCount() % 10 == 0)
			for (ServerLevel lvl : s.getAllLevels())
				for (Entity e : lvl.getAllEntities()) {
					if (!(e instanceof LivingEntity le) || !le.isAlive() || bars.containsKey(e.getUUID())) continue;
					String id = bossOf(e);
					if (id != null) start(id, le);
				}
		for (var it = bars.entrySet().iterator(); it.hasNext(); ) {
			Bar b = it.next().getValue();
			JsonObject o = BOSS.get(b.id);
			LivingEntity le = (LivingEntity) b.e;
			if (o == null || le.isRemoved() || !le.isAlive()) {
				if (o != null && le.isDeadOrDying()) { run(le, o.get("death")); dev.moddeus.live.Api.attacks(le, o.get("death_attack")); say(b, o, "death_say"); }
				b.ev.removeAllPlayers();
				it.remove();
				continue;
			}
			float frac = le.getHealth() / Math.max(1, le.getMaxHealth());
			b.ev.setProgress(Math.max(0, Math.min(1, frac)));
			double r = num(o, "radius", 48);
			for (ServerPlayer p : s.getPlayerList().getPlayers()) {
				if (p.level() == le.level() && p.distanceToSqr(le) < r * r) b.ev.addPlayer(p); else b.ev.removePlayer(p);
			}
			JsonArray ph = o.has("phases") ? o.getAsJsonArray("phases") : new JsonArray();
			for (int i = 0; i < ph.size(); i++) {
				JsonObject p = ph.get(i).getAsJsonObject();
				if (frac > num(p, "at", 0.5) || !le.addTag("moddeus_phase_" + i)) continue; // addTag: once per boss, survives reloads
				if (p.has("color")) b.ev.setColor(color(p.get("color").getAsString()));
				if (p.has("name")) b.ev.setName(Component.literal(p.get("name").getAsString()));
				say(b, p, "say");
				run(le, p.get("cmd"));
				dev.moddeus.live.Api.attacks(le, p.get("attack"));
			}
			if (b.ev.getPlayers().isEmpty() || !o.has("every")) continue; // attacks only while someone is near
			for (JsonElement x : o.getAsJsonArray("every")) {
				JsonObject a = x.getAsJsonObject();
				if (a.has("phase") && !le.entityTags().contains("moddeus_phase_" + a.get("phase").getAsInt())) continue;
				if (le.tickCount % Math.max(1, (int) num(a, "ticks", 100)) == 0) { run(le, a.get("cmd")); dev.moddeus.live.Api.attacks(le, a.get("attack")); }
			}
		}
	}

	static void start(String id, LivingEntity le) {
		JsonObject o = BOSS.get(id);
		if (o.has("health") && le.addTag("moddeus_boss_init")) {
			var a = le.getAttribute(Attributes.MAX_HEALTH);
			if (a != null) { a.setBaseValue(o.get("health").getAsDouble()); le.setHealth(le.getMaxHealth()); }
		}
		if (o.has("name") && le.getCustomName() == null) le.setCustomName(Component.literal(o.get("name").getAsString()));
		if (le instanceof Mob mob) mob.setPersistenceRequired();
		Bar b = new Bar();
		b.e = le; b.id = id;
		b.ev = new ServerBossEvent(UUID.randomUUID(), o.has("name") ? Component.literal(o.get("name").getAsString()) : le.getDisplayName(),
			color(str(o, "color", "purple")), overlay(str(o, "overlay", "progress")));
		b.ev.setDarkenScreen(o.has("darken") && o.get("darken").getAsBoolean());
		b.ev.setPlayBossMusic(o.has("music") && o.get("music").getAsBoolean());
		b.ev.setCreateWorldFog(o.has("fog") && o.get("fog").getAsBoolean());
		// phases already passed (tags) keep their color/name
		JsonArray ph = o.has("phases") ? o.getAsJsonArray("phases") : new JsonArray();
		for (int i = 0; i < ph.size(); i++) if (le.entityTags().contains("moddeus_phase_" + i)) {
			JsonObject p = ph.get(i).getAsJsonObject();
			if (p.has("color")) b.ev.setColor(color(p.get("color").getAsString()));
			if (p.has("name")) b.ev.setName(Component.literal(p.get("name").getAsString()));
		}
		bars.put(le.getUUID(), b);
	}

	static void say(Bar b, JsonObject o, String key) {
		if (!o.has(key)) return;
		Component c = Component.literal("§5<" + b.ev.getName().getString() + ">§r " + o.get(key).getAsString());
		for (ServerPlayer p : b.ev.getPlayers()) p.sendSystemMessage(c);
	}

	static BossEvent.BossBarColor color(String c) {
		try { return BossEvent.BossBarColor.valueOf(c.toUpperCase()); } catch (IllegalArgumentException e) { return BossEvent.BossBarColor.PURPLE; }
	}

	static BossEvent.BossBarOverlay overlay(String c) {
		return switch (c.replace("notched_", "")) { case "6" -> BossEvent.BossBarOverlay.NOTCHED_6; case "10" -> BossEvent.BossBarOverlay.NOTCHED_10;
			case "12" -> BossEvent.BossBarOverlay.NOTCHED_12; case "20" -> BossEvent.BossBarOverlay.NOTCHED_20; default -> BossEvent.BossBarOverlay.PROGRESS; };
	}

	// ---- armor set bonuses -------------------------------------------------------------------------------------
	static boolean wearing(Player p, JsonArray pieces) {
		for (JsonElement x : pieces) {
			Identifier want = Identifier.parse(x.getAsString());
			boolean found = false;
			for (EquipmentSlot s : EquipmentSlot.values()) {
				if (s == EquipmentSlot.MAINHAND || s == EquipmentSlot.OFFHAND) continue;
				if (want.equals(BuiltInRegistries.ITEM.getKey(p.getItemBySlot(s).getItem()))) { found = true; break; }
			}
			if (!found) return false;
		}
		return pieces.size() > 0;
	}

	static void sets(MinecraftServer s) {
		if (SETS.isEmpty() && worn.isEmpty()) return;
		int t = s.getTickCount();
		for (ServerPlayer p : s.getPlayerList().getPlayers()) {
			for (String k : new ArrayList<>(worn)) if (k.startsWith(p.getUUID() + "|") && !SETS.containsKey(k.substring(k.indexOf('|') + 1))) { unwear(p, k.substring(k.indexOf('|') + 1)); worn.remove(k); }
			for (var e : SETS.entrySet()) {
				JsonObject o = e.getValue();
				String key = p.getUUID() + "|" + e.getKey();
				boolean full = o.has("pieces") && wearing(p, o.getAsJsonArray("pieces")), was = worn.contains(key);
				if (!full) { if (was) { unwear(p, e.getKey()); worn.remove(key); } continue; }
				if (!was) {
					worn.add(key);
					p.sendOverlayMessage(Component.literal("§6" + str(o, "name", "Set bonus active")));
					if (o.has("on")) run(p, o.get("on"));
				}
				if (t % 10 == 0 && o.has("effects")) for (var ef : o.getAsJsonObject("effects").entrySet())
					BuiltInRegistries.MOB_EFFECT.get(Identifier.parse(ef.getKey())).ifPresent(h -> p.addEffect(new MobEffectInstance(h, 260, ef.getValue().getAsInt(), true, false, true)));
				if (t % 10 == 0 && o.has("attributes")) for (var at : o.getAsJsonObject("attributes").entrySet())
					BuiltInRegistries.ATTRIBUTE.get(Identifier.parse(at.getKey())).ifPresent(h -> {
						var inst = p.getAttribute(h);
						if (inst != null) inst.addOrUpdateTransientModifier(new AttributeModifier(LiveRegistry.id("set_" + e.getKey()), at.getValue().getAsDouble(), AttributeModifier.Operation.ADD_VALUE));
					});
				if (o.has("every") && t % Math.max(1, (int) num(o, "every_ticks", 20)) == 0) run(p, o.get("every"));
			}
		}
	}

	static void unwear(ServerPlayer p, String id) {
		JsonObject o = SETS.get(id);
		if (o != null && o.has("effects")) for (String ef : o.getAsJsonObject("effects").keySet())
			BuiltInRegistries.MOB_EFFECT.get(Identifier.parse(ef)).ifPresent(h -> { var in = p.getEffect(h); if (in != null && in.isAmbient()) p.removeEffect(h); });
		for (var h : BuiltInRegistries.ATTRIBUTE.asHolderIdMap()) { var inst = p.getAttribute(h); if (inst != null) inst.removeModifier(LiveRegistry.id("set_" + id)); }
		if (o != null && o.has("off")) run(p, o.get("off"));
	}

	// ---- loot --------------------------------------------------------------------------------------------------
	static void loot(ResourceKey<LootTable> key, LootTable.Builder b, net.fabricmc.fabric.api.loot.v3.LootTableSource src, HolderLookup.Provider reg) {
		if (!enabled || DROPS == null) return;
		String k = key.identifier().toString();
		for (var d : DROPS.entrySet()) {
			JsonObject o = d.getValue();
			if (!k.equals(str(o, "table", "")) || !o.has("pool")) continue;
			LootPool.CODEC.parse(reg.createSerializationContext(JsonOps.INSTANCE), o.get("pool"))
				.resultOrPartial(err -> Moddeus.LOG.warn("drop {}: {}", d.getKey(), err))
				.ifPresent(pool -> ((net.fabricmc.fabric.api.loot.v3.FabricLootTableBuilder) b).pool(pool));
		}
	}

	// ---- NPCs: dialogue + trades -------------------------------------------------------------------------------
	static String npcOf(Entity e) {
		for (String t : e.entityTags()) if (t.startsWith("moddeus_npc_") && NPCS.containsKey(t.substring(12))) return t.substring(12);
		return null;
	}

	static InteractionResult use(Player player, Level level, InteractionHand hand, Entity e, EntityHitResult hit) {
		if (!enabled || level.isClientSide() || hand != InteractionHand.MAIN_HAND || NPCS == null) return InteractionResult.PASS;
		String id = npcOf(e);
		if (id == null) return InteractionResult.PASS;
		JsonObject o = NPCS.get(id);
		String name = str(o, "name", e.getDisplayName().getString());
		JsonArray lines = o.has("lines") ? o.getAsJsonArray("lines") : new JsonArray();
		String k = player.getUUID() + "|" + e.getUUID();
		int i = talk.getOrDefault(k, 0);
		boolean shop = o.has("trades") && o.getAsJsonArray("trades").size() > 0;
		if (lines.size() > 0 && (i < lines.size() || !shop)) { // story first, then the shop on every later click
			player.sendSystemMessage(Component.literal("§e" + name + "§r: " + lines.get(i % lines.size()).getAsString()));
			level.playSound(null, e.blockPosition(), SoundEvents.VILLAGER_AMBIENT, e.getSoundSource(), 0.6f, 1.1f);
			talk.put(k, i + 1);
			if (i == lines.size() - 1) run(player, o.get("cmd"));
			return InteractionResult.SUCCESS;
		}
		if (shop) new Shop(e, o).openTradingScreen(player, Component.literal(name), 1);
		return InteractionResult.SUCCESS;
	}

	static ItemStack stack(JsonObject o) {
		return new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(o.get("item").getAsString())), (int) num(o, "count", 1));
	}

	static ItemCost cost(JsonObject o) {
		return new ItemCost(BuiltInRegistries.ITEM.getValue(Identifier.parse(o.get("item").getAsString())), (int) num(o, "count", 1));
	}

	/** A trade window on any entity (villager, mannequin, mob): offers from the npc spec, unlimited stock by default. */
	public static final class Shop implements Merchant {
		final Entity owner;
		final MerchantOffers offers = new MerchantOffers();
		Player trading;

		Shop(Entity owner, JsonObject o) {
			this.owner = owner;
			for (JsonElement x : o.getAsJsonArray("trades")) {
				JsonObject t = x.getAsJsonObject();
				offers.add(new MerchantOffer(cost(t.getAsJsonObject("cost")),
					t.has("cost2") ? java.util.Optional.of(cost(t.getAsJsonObject("cost2"))) : java.util.Optional.empty(),
					stack(t.getAsJsonObject("result")), (int) num(t, "uses", 9999), (int) num(t, "xp", 0), 0f));
			}
		}

		public void setTradingPlayer(Player p) { trading = p; }
		public Player getTradingPlayer() { return trading; }
		public MerchantOffers getOffers() { return offers; }
		public void overrideOffers(MerchantOffers o) {}
		public void notifyTrade(MerchantOffer o) { o.increaseUses(); }
		public void notifyTradeUpdated(ItemStack s) {}
		public int getVillagerXp() { return 0; }
		public void overrideXp(int x) {}
		public boolean showProgressBar() { return false; }
		public SoundEvent getNotifyTradeSound() { return SoundEvents.VILLAGER_YES; }
		public boolean isClientSide() { return false; }
		public boolean stillValid(Player p) { return owner.isAlive() && p.distanceToSqr(owner) < 100; }
	}

	// ---- spawning (Quick.spawn) --------------------------------------------------------------------------------
	/** Spawns an npc (its "summon") or a boss (its "mob", tagged) at pos. Returns the entity or null. */
	public static Entity spawn(String id, Vec3 pos) {
		init();
		if (!has(id)) dev.moddeus.live.ContentLoader.loadAll(); // just written by mc: load before the watcher does
		ServerLevel lvl = dev.moddeus.live.Quick.world();
		String what;
		if (NPCS.containsKey(id)) what = str(NPCS.get(id), "summon", "minecraft:villager {NoAI:1b,Invulnerable:1b,PersistenceRequired:1b,Tags:[\"moddeus_npc_" + id + "\"]}");
		else if (BOSS.containsKey(id)) what = str(BOSS.get(id), "mob", "minecraft:zombie") + " {Tags:[\"moddeus_boss_" + id + "\"]}";
		else throw new IllegalArgumentException("no npc or boss named " + id);
		String tag = "moddeus_new_" + System.nanoTime();
		int sp = what.indexOf(' ');
		String type = sp < 0 ? what : what.substring(0, sp), nbt = sp < 0 ? "{}" : what.substring(sp + 1).trim();
		nbt = nbt.replaceFirst("Tags:\\[", "Tags:[\"" + tag + "\",");
		if (!nbt.contains("Tags:")) nbt = nbt.replaceFirst("^\\{", "{Tags:[\"" + tag + "\"]" + (nbt.equals("{}") ? "" : ","));
		var src = lvl.getServer().createCommandSourceStack().withLevel(lvl).withPosition(pos).withSuppressedOutput();
		lvl.getServer().getCommands().performPrefixedCommand(src, "summon " + type + " " + pos.x + " " + pos.y + " " + pos.z + " " + nbt);
		for (Entity e : lvl.getEntities((Entity) null, new net.minecraft.world.phys.AABB(pos, pos).inflate(3), x -> x.entityTags().contains(tag))) { e.removeTag(tag); return e; }
		return null;
	}
}
