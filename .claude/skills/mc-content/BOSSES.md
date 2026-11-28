# Bosses & mobs (core `live/Bosses.java`, client `client/BossesClient.java`, tools `tools/extra.mjs` (boss), `tools/bosses.mjs`)

A boss = three parts: a real BODY (3D cube-model mob with its own bone tree: `mc mob <id> --model <own file>.json --health 400 --scale 2`, own skin and clips, LIFE.md), the PLUMBING (`mc boss`: bar, phases, timers, GAMEPLAY.md) and its own ATTACKS, written in Java for this boss (`content/<Id>*.java`, skeleton in WEAPONS.md). No `/bossattack`, `/mobai`, `/mobvariant`, `/bossarena`, `/bosschest` commands: everything is `dev.moddeus.live.Bosses.*`. Exported jars include it (runtime).

## Toolkit (server thread)
- `Bosses.telegraph(pos, r, ticks, "#hex")` ground warning (outline + fill that reaches the edge on impact). Every hit gets one.
- `Bosses.impact(boss, pos, r, dmg, "#hex")` landing hit: shock ring, cracks, dust, shake, sound, damage to the player side.
- `Bosses.fx(pos, dir, follow, Bosses.L(type, texture, "size", .., "duration", .., "color", .., ...))` inline VFX layers (types: ring billboard beam pillar slash sphere spiral orbit shards flash shake; keys: size(_end) color(_end) alpha(_end) duration delay y(_end) spin frames blend ease fit grow arc tilt). Textures `bossfx_*` (ring fill glow shock crack beam spark slash smoke swirl flare star sigil bolt orb) are white: tint them, or draw your own (`mc pixels vfx <name>`). On bright ground use `"blend","alpha"`.
- `Bosses.job(owner, ticks, (j, age) -> ...)` timed attack (ends when the owner dies; `j.stop()`, `.onEnd(...)`). `Bosses.ground(level, pos)`, `Bosses.vel(e, v)`, `Bosses.cmd(level, pos, "vanilla cmd")`, `Bosses.tagged(level, near, tag)`.
- Combat helpers for targets and damage: `Combat.enemies(boss, pos, r)` (mob casters only hit the player side), `Combat.damage`, `Combat.blast` (COMBAT.md). Moving blocks: `Quick.physics(state, pos, vel)`, `Physics.spawn(level, "ns:id", pos, vel)`.
- Arena + loot: `Bosses.arena(level, blockPos, Bosses.args("r=12 h=4 style=stone|nether|end|ice|jungle name=x"))` (floor rings, walls, pillars, air cleared; block entities untouched), `Bosses.arenaUndo("x")` (memory only: undo before restarting), `Bosses.chest(level, vec3, "minecraft:chests/end_city_treasure", "#ffd040")` (chest + light beam).
- Bar state: `Gameplay.bars` (entity UUID -> `Bar{ev, e, id}`), phases = entity tags `moddeus_phase_<i>` (set by `mc boss --phase`, survive saves), boss tag `moddeus_boss_<id>`.

## Samples (read the source, then write your own)
`Bosses.attack(boss, "slam r=6 color=#ff4020")` runs a sample attack: slam charge leap meteors summon laser teleport blades spikes shield enrage grab roar telegraph cycle/random intro music lock death (`mc bossattacks` lists keys). Each is a short method in `live/Bosses.java` (`slam`, `charge`, `laser`, ...): read one to see telegraph -> job -> impact in practice. Shipping them raw makes every boss fight the same; the utility ones (`intro`, `music`, `lock`, `death`) are fine as plumbing. In boss content JSON they go in `"attack": [specs]` (phases, every) and `"death_attack"` (`mc boss` lines written `attack <spec>`).

## The boss's own attacks: driver + two attacks
```java
static void tick(MinecraftServer s) {                     // registered in hook(): ServerTickEvents.END_SERVER_TICK.register(TideMod::tick)
	if (!enabled || Gameplay.bars == null) return;
	for (Gameplay.Bar bar : List.copyOf(Gameplay.bars.values())) {
		if (!bar.id.endsWith("tide_warden") || !(bar.e instanceof Mob boss) || !boss.isAlive() || boss.getTarget() == null) continue;
		boolean angry = boss.entityTags().contains("moddeus_phase_1");          // 2nd --phase fired: faster, harder pattern
		int every = angry ? 70 : 120;
		if (boss.tickCount % every != 0) continue;
		if ((boss.tickCount / every) % 2 == 0) tidalRing(boss, angry); else hailOfPillars(boss, boss.getTarget(), angry ? 5 : 3);
	}
}
/** A wave ring expands from the boss along the ground: jump over it or get hit. Angry = a second ring right behind. */
static void tidalRing(Mob boss, boolean twin) {
	Vec3 c = Bosses.ground(boss.level(), boss.position()); double speed = 0.45, max = 16;
	Bosses.telegraph(c, 2.5, 20, "#40a8ff");
	for (int k = 0; k < (twin ? 2 : 1); k++) {
		int start = 20 + k * 14;
		Bosses.job(boss, start + (int) (max / speed), (j, age) -> {
			if (age == start) Bosses.fx(c, new Vec3(0, 1, 0), null, Bosses.L("ring", "tide_crest", "size", 0.5, "size_end", max / 0.8, "duration", (int) (max / speed), "color", "#7fd0ff", "alpha_end", 0.2));
			if (age < start) return;
			double r = (age - start) * speed;
			for (LivingEntity e : Combat.enemies(boss, c, r + 1)) {
				double d = Math.hypot(e.getX() - c.x, e.getZ() - c.z);
				if (Math.abs(d - r) < 0.7 && e.onGround()) { Combat.damage(e, boss, 7, "slam"); Bosses.vel(e, e.position().subtract(c).normalize().scale(0.8).add(0, 0.5, 0)); }
			}
		});
	}
}
/** Stone pillars fall from the sky on a checker pattern around the target: real physics blocks, removed after. */
static void hailOfPillars(Mob boss, LivingEntity t, int n) {
	ServerLevel l = (ServerLevel) boss.level(); List<PhysicsBlock> bodies = new ArrayList<>();
	List<Vec3> spots = new ArrayList<>();
	for (int x = -n / 2; x <= n / 2; x++) for (int z = -n / 2; z <= n / 2; z++) if ((x + z) % 2 == 0) spots.add(Bosses.ground(l, t.position().add(x * 3, 0, z * 3)));
	for (Vec3 p : spots) Bosses.telegraph(p, 1.3, 30, "#c8b890");
	Bosses.job(boss, 120, (j, age) -> {
		if (age == 16) for (Vec3 p : spots) bodies.add(Quick.physics(Blocks.POLISHED_DEEPSLATE.defaultBlockState(), p.add(0, 14, 0), new Vec3(0, -1.2, 0)));
		if (age == 30) for (Vec3 p : spots) Bosses.impact(boss, p, 1.6, 9, "#c8b890");
	}).onEnd(() -> { for (PhysicsBlock b : bodies) if (b != null && !b.isRemoved()) b.discard(); });
}
```
Make it `mc boss tide_warden --mob <ns>:tide_warden --name "Tide Warden" --phase "0.5 blue Tide Warden, Risen: playsound <ns>:warden_rise hostile @a ~ ~ ~ 2" --death "attack death loot=minecraft:chests/end_city_treasure xp=300" --tab "<Mod>"` (vanilla commands in lines, attacks in Java).

## Mob behaviours, elites, spawning (plumbing + samples)
- `Bosses.behave(entity, "flying", "height=6")` sample AIs: flying caster leaper burrower pack pet mount (stored as tags `mb:...`, survive saves). `pet`/`mount` are fine plumbing (`Bosses.tame(mob, player)`); for a signature creature, read `flying`/`caster`/... in `live/Bosses.java` and write its own movement and attacks in your tick.
- `Bosses.variant(entity, "fiery,giant scale=1.5")` elite affixes (fiery frost vampiric swift armored giant tiny explosive thorns teleporter summoner regen wither | random): +50% hp, gold name, aura, bonus xp. Samples too: a mod's elites get their own affix logic.
- Type-wide: `mc mobai <id> --mob <ns>:wyvern --flying "height=7" [--variant random --variant-chance 0.1]` (every mob of that type on spawn/load).
- Natural spawning (plumbing): `mc spawnrule <id> --mob <ns>:wyvern --biomes "desert,#minecraft:is_mountain" --time night --light 0..7 --max 3 --group 1-2 --chance 0.15 [--cave] [--y -64..40] [--dimension overworld] [--behave "pack name=w"] [--cmd "..."]`; checked every 2 s per player, 24-48 blocks away. Remove: `mc remove <id>`.
- Datapacks can't call any of this: datapack mods use vanilla commands only.

## Make it unique to this boss
- Own body (model, skin, clips) and own colors; attacks that fit its fiction (a tide boss floods, a clockwork boss ticks).
- Own mechanic per attack (dodge by jumping, by hiding, by breaking something); every hit telegraphed, timed to its sound.
- Own textures for its layers, own sounds, phases that change the PATTERN, not just the numbers. Nothing left after a fight.

## Test (never on the player)
`mc game stage <ModId>`, then `game run`: `Stage s = Stage.of("<ModId>"); var b = (Mob) s.spawnDef("tide_warden", 0, 0, 8); b.setTarget(s.dummy(0, 0, -4)); s.run(() -> TideMod.tidalRing(b, true));`, `game stage <ModId> wait 60` (report), `game stage <ModId> clear`. Look: `game studio entity <ns>:tide_warden --anim attack`.

## Limits
Visuals are singleplayer (VFX run in-process). Arena undo is in memory. Mount steering reads the rider's input (server-side Input), no mixin.
