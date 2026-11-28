# Combat & abilities (core: live/Combat.java + client/CombatClient.java)

Plain Java on the server thread: `dev.moddeus.live.Combat.*`, called from your mod's own code (`content/<Id>*.java`, class skeleton in WEAPONS.md). No `/combat` command. The engine gives you the PLUMBING (gates, HUD, targeting, damage, timed abilities); the ability itself (shape, motion, timing, look, sound) you write per mod. Works in exported jars (runtime).

## Plumbing (use freely; automatic HUD = charge bar + combo xN only. No energy/cooldown widget: if the mod needs one, draw it yourself with `ModGui.hud`, GUI.md, in the mod's own style; read `Combat.peek(uuid).cooldowns/energy`)
- Gate: `if (!Combat.use(p, "Fireball", 60, 25)) return;` cooldown ticks + energy cost; false shows an actionbar hint. Mobs pay no energy. `cooldown(e, name)` 0..1, `resetCooldown`.
- Energy: `Combat.energy(p, 150, 15)` max + regen/s, `addEnergy(e, n)` (negative = drain).
- Combo: `int n = Combat.combo(p, "slash", 20)` 1,2,3.. within the window.
- Charge: `Combat.charge(p, "Meteor", 40, () -> Quick.keyDown(MOD, "Cast"), f -> ...)` hold, release(f 0..1).
- Timed ability: `Combat.run(now -> { ...; return stillRunning; })` (`Combat.Active`: `boolean tick(long now)`, `default void end()` for cleanup), or `Bosses.job(owner, ticks, (j, age) -> ...)` (ends when the owner dies; `.onEnd(...)`).
- Damage + teams: `damage(target, attacker, amt, type)`, `blast(owner, pos, r, dmg, type, knock)` (falloff, returns hits), `enemies(caster, pos, r)`, `enemy(caster, t)`, `friendly(a, b)`. Player side never hits its summons; mob casters only hit the player side. Damage types: moddeus:arcane laser shock shrapnel slam frost void blade (new types register only at world load).
- Geometry: `hand(e)`, `aim(e, range)` (look point), `ground(level, pos)`, `velocity(e, v)` / `push(e, v)` (synced), `iframes(e, ticks)`, `display(level, pos, item, scale, billboard)` (ItemDisplay to move yourself; discard it in `end()`).
- VFX: `Bosses.fx(pos, dir, follow, Bosses.L(type, texture, k, v, ...))` inline layers, or `Combat.fx("<ns>:<vfx id>", pos, dir, follow)` / `Vfx.play(Vfx.id(..), pos, dir, follow)` for a named content vfx; `Combat.line(id, a, b, step, jitter)` beads along a path. Design first: VFX-DESIGN.md.

## Engine samples (read, don't ship as the mod's ability)
`shoot(caster, Shot)` (homing/bounce/pierce/cluster/chain/boomerang projectiles), `beam`, `chain`, `rain`, `dash`, `blink`, `grapple`, `slam`, `superJump`, `perk(p, "double_jump"|"glide"|"wall_run", on)`, `shield`, `parry`, `summon`, `turret`, `decoy`, `totem`, `zone`/`aura` (heal damage slow freeze pull push fire), `mine`. One-string form: `Combat.exec(caster, "dash", "1.6")` (names = the `exec` switch). Each plays a shared `combat_*` look, so a mod that uses them raw looks like every other mod. Read the method in `live/Combat.java` (e.g. `Proj.tick` for projectile flight, `beam` for raycasts), then write this mod's version: own motion, own VFX ids (`Shot.trail/hit` take yours), own sound. Fine to use for small utility (an i-frame dodge under your own VFX).

## Worked examples (different mechanics; methods of a mod class)
**1. Three-hit combo with a different finisher** (on hit): alternating cuts, 3rd hit launches and slams back down.
```java
static InteractionResult hit(Player p, Level l, InteractionHand h, Entity target, EntityHitResult r) {
	if (!enabled || l.isClientSide() || !holds(p, "ink_katana") || !(target instanceof LivingEntity t)) return InteractionResult.PASS;
	int n = Combat.combo(p, "Ink Cut", 18);
	Vec3 c = t.getBoundingBox().getCenter();
	if (n % 3 != 0) { Bosses.fx(c, p.getLookAngle(), null, Bosses.L("slash", "ink_stroke", "size", 2, "duration", 6, "color", "#101018", "blend", "alpha", "tilt", n % 3 == 1 ? 30 : -30)); return InteractionResult.PASS; }
	Combat.velocity(t, new Vec3(0, 1.1, 0));                                     // up...
	Bosses.job(t, 14, (j, age) -> { if (age == 10) { Combat.velocity(t, new Vec3(0, -2, 0)); Combat.damage(t, p, 9, "blade");   // ...and down
		Bosses.fx(Combat.ground(l, t.position()), new Vec3(0, 1, 0), null, Bosses.L("ring", "ink_splat", "size", 0.5, "size_end", 3.5, "duration", 16, "color", "#101018", "blend", "alpha", "alpha_end", 0)); } });
	return InteractionResult.PASS;
}
```
**2. Orbiting glaives** (key): three item displays circle the caster for 8 s, each cuts what it passes; cleaned up in `end()`.
```java
static void glaives(ServerPlayer p) {
	if (!enabled || !Combat.use(p, "Glaive Ring", 200, 40)) return;
	ServerLevel l = p.level(); List<Display.ItemDisplay> g = new ArrayList<>();
	for (int i = 0; i < 3; i++) g.add(Combat.display(l, p.position(), NS + ":glaive", 0.8f, false));
	long t0 = l.getServer().getTickCount();
	Combat.run(new Combat.Active() {
		public boolean tick(long now) {
			long age = now - t0; if (age > 160 || !p.isAlive()) return false;
			for (int i = 0; i < g.size(); i++) {
				double a = age * 0.25 + i * Math.PI * 2 / 3, r = 2.2 + 0.4 * Math.sin(age * 0.1);   // breathing radius
				Vec3 at = p.position().add(Math.cos(a) * r, 1 + 0.3 * Math.sin(a * 2), Math.sin(a) * r);
				if (g.get(i) != null) g.get(i).teleportTo(at.x, at.y, at.z);
				if (age % 5 == 0) for (LivingEntity e : Combat.enemies(p, at, 0.9)) Combat.damage(e, p, 3, "blade");
			}
			return true;
		}
		public void end() { for (var d : g) if (d != null) d.discard(); }
	});
}
```
**3. Channelled frost field** (hold key, drains energy): enemies inside slow more the longer you hold; release = shatter.
```java
static void field(ServerPlayer p) {
	if (!enabled || Combat.cooldown(p, "Frost Field") > 0) return;
	Combat.energy(p, 100, 8);
	Combat.charge(p, "Frost Field", 100, () -> Quick.keyDown(MOD, "Frost Field (hold)") && Combat.stats(p).energy > 2, f -> {
		for (LivingEntity e : Combat.enemies(p, p.position(), 6)) { Combat.damage(e, p, (float) (4 + 10 * f), "frost"); e.setTicksFrozen(0); }
		Bosses.fx(p.position(), new Vec3(0, 1, 0), null, Bosses.L("shards", "frost_shard", "size", 0.4, "count", 24, "duration", 18, "color", "#bff4ff"));
		Combat.use(p, "Frost Field", 120, 0);
	});
	Bosses.job(p, 100, (j, age) -> {
		if (!Quick.keyDown(MOD, "Frost Field (hold)")) { j.stop(); return; }
		Combat.addEnergy(p, -1);
		for (LivingEntity e : Combat.enemies(p, p.position(), 6)) e.setTicksFrozen(Math.min(140, e.getTicksFrozen() + 3));
		if (age % 10 == 0) Bosses.fx(p.position(), new Vec3(0, 1, 0), p, Bosses.L("ring", "frost_rim", "size", 6, "duration", 12, "color", "#7fd8ff", "alpha_end", 0));
	});
}
```
Register keys in `hook()`: `Quick.key(MOD, "Frost Field (hold)", "V", MyMod::field)`. Draw every texture used (`mc pixels vfx <name>`).

## Make it unique to this mod
- Own mechanic first (what does it DO that no other ability does?), then the look; never "projectile + burst" recolored.
- Own textures and palette per ability, own timing (wind-up, beats, cooldown), own sound (SOUNDS.md).
- No two abilities in the mod share material + shape + motion (VFX-DESIGN.md); cleanup leaves nothing behind.

## Test (never on the player)
`mc game stage <ModId>`, then `game run`: `Stage s = Stage.of("<ModId>"); var z = s.spawn("minecraft:husk", 0, 0, 5, "{}"); s.lookAt(z); s.run(() -> MyMod.glaives(s.actor())); s.after(60, () -> s.log(((LivingEntity) z).getHealth()));`, then `game stage <ModId> wait 80` and `game stage <ModId> clear`. Sample abilities: `node tools/mc.mjs combat <ability> [args]` (runs `Combat.exec` on the stage actor when a stage is up; no args = list). Leftovers: `game run 'dev.moddeus.live.Combat.stopAll();'`.
