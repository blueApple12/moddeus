# Weapons: vanilla bases (one command) + the weapon's own abilities (Java)

Plumbing is one command: `mc item <id> --tool <kind>` gives the vanilla base (bow pull, crossbow load, trident throw, spear charge, mace smash, shield block, enchant tags). The LOOK is your own: `--cubes ... --tex ...` + `mc pixels` (MODELS.md, PIXEL-ART.md). WHAT it does beyond vanilla is Java you write for this mod (`content/<Id>*.java`). Files: `live/Weapons.java` (kinds, projectiles, specials), `client/content/WeaponsClient.java` (projectile renderers), `tools/weapons.mjs`. The tool JSON is read on every shot: edit a number in `moddeus/content/<id>.json` and the next shot uses it.

## Vanilla bases (`--tool <kind>`)
- **bow**: `"tool":{"type":"bow"}`, `durability` (384). Writes `<id>_pulling_0..2` (use_duration x 0.05: <0.65 / 0.65 / 0.9 s); draw each stage with the nocked arrow visible. Shoots any arrow; Power/Punch/Flame/Infinity work. Draw speed and power are vanilla: extra behaviour goes in the ARROW or your Java.
- **arrow**: ammo for bows, crossbows, dispensers. Keys: `damage` (2 x bow power), `gravity` (0.05), `homing`, `fire`, `effect` "id:ticks:amp", `freeze`, `knockback`, `explode` r, `splash` r, `linger`, `trail`/`vfx_hit` (your vfx ids), `damage_type`. Adds `#minecraft:arrows`, paints `textures/entity/projectiles/<id>.png` + icon: redraw both with `mc pixels`. Each arrow = entity type `<ns>:<id>` (registers live; `mc remove` leaves the type until restart).
- **crossbow**: model params `draw` 0..1, `load` 1 bolt / 2 rocket; item definition selects on `charge_type`. Multishot/Piercing/Quick Charge work. Needs the `charged_projectiles` component (Weapons sets it).
- **trident**: keys `damage` (8), `speed` (-2.9), `throw_speed` (2.5) + projectile keys. Thrown damage/Riptide are vanilla. Flies tail-first: `WeaponsClient.tipFix = 180`.
- **spear** (26.x kinetic): `material`, `damage`/`speed`, kinetic numbers `swing charge_damage delay dismount_time dismount_speed knockback_time knockback_speed damage_time damage_speed`. Tagged `#minecraft:spears`. The charge only hurts while you MOVE.
- **mace**: `damage` (5), `speed` (-3.4); smash after a 1.5+ block fall, Density/Breach/Wind Burst.
- **shield**: `block_delay` (0.25 s), `angle` (90), `reduction`, `disable_scale`, `block_sound`, `break_sound`; a `minecraft:blocks_attacks` in `--components` replaces the default (incl. `#minecraft:bypasses_shield`).
- **throwable**: `speed` (1.5), `arc`, `inaccuracy`, `cooldown`, `sound`, `gravity`, `bounces`, `damage` + projectile keys; stacks to 16.
- **gun / staff**, `--model <shape>`, `--special "sweep 4 7 140"` (`"special":{"do": sweep | any Combat.exec ability, "on": use|sneak_use|hit, "cooldown"}`): **samples** (the CLI prints "preset = sample only"). Read `live/Weapons.java` / `tools/models.mjs` to learn how they work, then write this weapon's own fire logic and model.
- Pitfall: turning an existing item into another kind needs `mc remove` or a restart (registered items are not rebuilt).
- Hold-to-charge on a kind with no use animation: `--components '{"minecraft:consumable":{"consume_seconds":3600,"animation":"spear","has_consume_particles":false,"sound":"minecraft:intentionally_empty"}}'`; pose/glow states via the item definition (`using_item`, `use_duration`, `custom_model_data`, MODELS.md).

## Writing the weapon's abilities
Mod class skeleton (same as `content/AbyssalAscension.java`):
```java
package dev.moddeus.content;
public final class PrismMod {                  // content/<Id>*.java: Gens swaps it as generations and runs hook() at launch
	public static boolean enabled = true;
	static final String MOD = "Prism Mod", NS = "prism_mod";
	@OnReload public static void hook() {
		enabled = true;
		UseItemCallback.EVENT.register(PrismMod::use);          // method refs only; Gens makes register() a stable slot (no doubles).
		AttackEntityCallback.EVENT.register(PrismMod::hit);     // A CORE class must guard: if (System.getProperties().putIfAbsent("x", Boolean.TRUE) == null)
		ServerTickEvents.END_SERVER_TICK.register(PrismMod::tick);
		Quick.key(MOD, "Shard Hammer: Rupture (hold)", "R", PrismMod::rupture);
	}
	@OnUnload public static void off() { enabled = false; SoundLoops.stopAll(); }   // every callback checks enabled
	static boolean holds(LivingEntity e, String id) { var k = BuiltInRegistries.ITEM.getKey(e.getMainHandItem().getItem()); return k.getNamespace().equals(NS) && k.getPath().equals(id); }
	static void sound(Level l, Vec3 p, String id, float vol, float pitch) { SoundEvent e = BuiltInRegistries.SOUND_EVENT.getValue(Identifier.parse(id)); if (e != null) l.playSound(null, p.x, p.y, p.z, e, SoundSource.PLAYERS, vol, pitch); }
}
```
Inputs: right-click / sneak = `UseItemCallback`; hit = `AttackEntityCallback` (return PASS so the hit lands); held = `END_SERVER_TICK`; keys = `Quick.key` / `Quick.keyDown(MOD, label)`; hold to charge = `Combat.charge(p, name, maxTicks, holding, f -> ...)`; cooldown = `Combat.use(p, name, ticks, cost)` (no HUD drawn; your own `ModGui.hud` if needed). Mechanics, timed jobs and VFX: COMBAT.md, BOSSES.md (`Bosses.job`, `Bosses.fx`), VFX-DESIGN.md. Three different weapons (methods of the class above):

**1. Ricochet rail** (right-click): hitscan that reflects off blocks, more damage per bounce, a thinner beam each leg.
```java
static final String[] HUE = {"#e8f6ff", "#9ad8ff", "#c08aff", "#ff7ad0"};
static InteractionResult use(Player pl, Level lv, InteractionHand h) {
	if (!enabled || !(pl instanceof ServerPlayer p) || !holds(p, "prism_rail")) return InteractionResult.PASS;
	if (!Combat.use(p, "Prism Rail", 25, 0)) return InteractionResult.FAIL;
	ServerLevel l = p.level(); Vec3 a = p.getEyePosition().add(0, -0.2, 0), dir = p.getLookAngle(); Set<LivingEntity> hit = new HashSet<>();
	for (int k = 0; k < 4; k++) {
		BlockHitResult bh = l.clip(new ClipContext(a, a.add(dir.scale(40)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
		Vec3 b = bh.getLocation();
		for (double t = 0; t < a.distanceTo(b); t += 0.5) for (LivingEntity e : Combat.enemies(p, a.add(dir.scale(t)), 0.9)) if (hit.add(e)) Combat.damage(e, p, 6 + k * 4, "laser");
		Bosses.fx(a, b.subtract(a), null, Bosses.L("beam", "prism_core", "size", 0.2 - k * 0.04, "duration", 7, "color", HUE[k], "fit", true, "alpha_end", 0));
		sound(l, b, NS + ":rail_ping", 1f, 1f + k * 0.25f);
		if (bh.getType() == HitResult.Type.MISS) break;
		Direction d = bh.getDirection(); Vec3 n = new Vec3(d.getStepX(), d.getStepY(), d.getStepZ());
		dir = dir.subtract(n.scale(2 * dir.dot(n))); a = b.add(n.scale(0.05));
	}
	return InteractionResult.SUCCESS;
}
```
**2. Rupture hammer** (hold R, release): the ground in front tears up as real physics blocks, thrown out by charge.
```java
static void rupture(ServerPlayer p) {
	if (!enabled || !holds(p, "shard_hammer") || Combat.cooldown(p, "Rupture") > 0) return;
	Combat.charge(p, "Rupture", 40, () -> Quick.keyDown(MOD, "Shard Hammer: Rupture (hold)"), f -> {
		if (f < 0.2 || !Combat.use(p, "Rupture", 120, 0)) return;
		ServerLevel l = p.level(); Vec3 fwd = new Vec3(p.getLookAngle().x, 0, p.getLookAngle().z).normalize();
		for (int i = 2; i < 4 + f * 8; i++) {                               // a lane, not a circle
			BlockPos bp = BlockPos.containing(p.position().add(fwd.scale(i)).add(0, -1, 0));
			BlockState bs = l.getBlockState(bp); if (bs.isAir() || bs.hasBlockEntity()) continue;
			int delay = i * 2;                                              // the wave travels outward
			Quick.after(delay, () -> {
				l.removeBlock(bp, false);
				PhysicsBlock body = Quick.physics(bs, Vec3.atCenterOf(bp).add(0, 1, 0), fwd.scale(0.3).add(0, 0.6 + f, 0));
				for (LivingEntity e : Combat.enemies(p, Vec3.atCenterOf(bp), 1.8)) { Combat.damage(e, p, 5 + 8 * (float) f, "slam"); Combat.push(e, new Vec3(0, 0.9, 0)); }
				Quick.after(80, () -> { if (!body.isRemoved()) body.discard(); }); // nothing lingers
			});
		}
		sound(l, p.position(), NS + ":rupture", 1.4f, 0.8f);
	});
}
```
**3. Hum blade** (while held + on hit): own looping hum, a slash layer aligned with the swing, every 3rd hit a cross-cut.
```java
static Set<UUID> humming = new HashSet<>();                                   // re-create in hook() if null (CLAUDE.md swap gotcha)
static void tick(MinecraftServer s) {
	if (!enabled) return;
	for (ServerPlayer p : s.getPlayerList().getPlayers()) {
		boolean h = holds(p, "hum_blade");
		if (h && humming.add(p.getUUID())) SoundLoops.follow(p, NS + ":blade_hum", "blade", 0.6f, 1f, 8);
		if (!h && humming.remove(p.getUUID())) SoundLoops.stop(p, "blade");
	}
}
static InteractionResult hit(Player p, Level l, InteractionHand h, Entity target, EntityHitResult r) {
	if (!enabled || l.isClientSide() || !holds(p, "hum_blade") || !(target instanceof LivingEntity t)) return InteractionResult.PASS;
	int n = Combat.combo(p, "Hum Blade", 20);
	Vec3 c = t.position().add(0, t.getBbHeight() * 0.6, 0);
	Bosses.fx(c, p.getLookAngle(), null, Bosses.L("slash", "hum_arc", "size", 2.4, "duration", 5, "color", "#ff3048", "arc", 150, "tilt", n % 2 == 0 ? 35 : -35));
	if (n % 3 == 0) { Combat.damage(t, p, 6, "blade"); Bosses.fx(c, p.getLookAngle(), null, Bosses.L("slash", "hum_arc", "size", 3, "duration", 7, "color", "#fff0f0", "arc", 200, "tilt", 90)); }
	return InteractionResult.PASS;
}
```
Imports: dev.moddeus.live.{Bosses, Combat, OnReload, OnUnload, Quick, SoundLoops}; net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents; net.fabricmc.fabric.api.event.player.{AttackEntityCallback, UseItemCallback}; java.util.*; plus the server/level/phys/block classes used. Draw `prism_core`, `hum_arc` with `mc pixels vfx <name>`; import the sounds (SOUNDS.md).

## Make it unique to this mod
- Own textures for the item, its projectile and every VFX layer (`mc pixels`), own palette (not the sample colors).
- Own mechanic per ability (reflect, tear, combo, recall, freeze...): if it reads as "projectile + burst" in a new color, redesign.
- Own timing: wind-up, travel speed, delay between beats, cooldown that fits the fantasy.
- Own sound per ability (import or `mc synth`, SOUNDS.md); no two abilities share a look (VFX-DESIGN.md "signature" line).

## Test checklist (every weapon, every change)
1. Save: auto-build is live (`mc build` shows compile errors); latest.log has no "Content ... failed".
2. Look: `mc game studio item <id> --view gui,fp,hand,ground`; each use state via `--nbt '{"minecraft:item_model":"<ns>:<test def>"}'`.
3. Fire it on your stage: `mc game stage <ModId>`, then `game run` with `Stage s = Stage.of("<ModId>"); var z = s.spawn("minecraft:husk", 0, 0, 6, "{}"); s.hold("<ns>:<id>").lookAt(z); print(s.use()); print(s.hit(z));`, `game stage <ModId> wait 40` (report: hp, effects, VFX played). Held/tick code: `s.run(() -> PrismMod.tick(server))`. Keys: call the key method with `s.actor()`.
4. Controls: re-test EVERY input (right-click, sneak, hold, release early, hit, key) after every change; charge bar (and your own cooldown HUD, if any) appear and clear.
5. Enchant: enchanting table / anvil with a book shows the kind's enchants.
6. Finish: `game stage <ModId> clear`, `mc remove` test items.
