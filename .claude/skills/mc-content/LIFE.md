# LIFE.md: mobs, animals, mounts, villagers, NPCs

Code: `tools/life.mjs` (commands), `client/content/CubeMobs.java` (models + animation), `client/content/MobPoses.java` (mod-driven poses), `live/Life.java` (hitboxes, parts, food, taming, sounds, trades, professions), `content/Mobs.java` (entity types). All data lives in `moddeus/content/<id>.json`; edits are live.

How vanilla 26.3 does it (and so do we): an `EntityType` (brain/body class), a renderer (`MobRenderer` + render state), and an `EntityModel` baked from a `MeshDefinition` / `LayerDefinition` tree of `ModelPart` bones with box-UV cubes. Moddeus builds that tree at runtime from JSON, so no Java class per mob. Mowzie's Mobs and Alex's Mobs get their look from many small bones plus procedural motion (dynamic chains for tails and tentacles); we copy that: roles for procedural motion, clips for keyframes.

## Custom mob (real 3D model, never a reskinned vanilla mob)
Design the body for THIS creature: your own bone tree in a JSON file. Presets (`mc mobmodel`: biped quadruped bird flyer
serpent spider slime golem dragon) are SAMPLES (each use prints "preset = sample only"): read `PRESETS` in `tools/life.mjs`
for bone layout and roles, then write your own.
```bash
mc mob frost_mod:frost_wolf --template wolf --model frost_mod_wolf.json --colors "body=#5a6a8a,belly=#c0c8d8,eye=#9ff" --glow eyes --health 30 --tab "Frost Mod"
```
```json
{"bones": [
  {"name":"body","pivot":[0,10,0],"anim":"body breathe","cubes":[{"from":[-4,7,-8],"to":[4,13,8],"c":"body"},{"from":[-3,6,-6],"to":[3,7,6],"c":"belly"}]},
  {"name":"head","parent":"body","pivot":[0,12,-8],"anim":"head","cubes":[{"from":[-3,9,-14],"to":[3,15,-8],"c":"body"}]},
  {"name":"jaw","parent":"head","pivot":[0,10,-9],"anim":"jaw","cubes":[{"from":[-2,8,-15],"to":[2,10,-10],"c":"belly"}]},
  {"name":"spines","parent":"body","pivot":[0,13,0],"anim":"sway*0.5","cubes":[{"from":[-1,13,-6],"to":[1,17,-4],"c":"ice"},{"from":[-1,13,-1],"to":[1,18,1],"c":"ice"}]},
  {"name":"leg_fr","parent":"body","pivot":[-3,7,-6],"anim":"leg:0","cubes":[{"from":[-4,0,-7],"to":[-2,7,-5],"c":"body"}]}
], "clips": {"pose1": {"len": 1, "bones": {"body": {"rx": [[0,12]], "y": [[0,-2]]}, "head": {"rx": [[0,-15]]}}}}}
```
- `--template` = the brain and body class: ANY vanilla mob id (zombie, wolf, horse, camel, villager, ghast, parrot, axolotl...). It gives the AI, taming, riding and breeding code. It never decides the look. Pick one whose goals fit (wolf = pack hunter + tameable, horse = rideable, bee/parrot/ghast = flier, zombie/iron_golem = melee): its vanilla goals keep running under your Java.
- Bone: `{"name","parent","pivot":[x,y,z],"rot":[rx,ry,rz],"anim":"leg:0","cubes":[{"from":[..],"to":[..],"c":"body"}]}`. Pixels (16 = 1 block), y up from the feet, front = -z, absolute coordinates, whole-pixel cubes. `--add '<bones>'` appends bones.
- Skin: UVs packed automatically; painted from `--colors` (ramps, edge lines, eyes, `pattern=stripes|spots|scales`) only as a base: draw over `textures/entity/<id>.png` (each cube's box-UV region; `--repaint` regenerates). `--glow a,b` writes `<id>_glow.png`. Spawn egg painted from the same colors.
- Changing the model of an existing id is live. Stats/template of an existing id need a restart.

## Animations
- Roles (procedural, bone `anim`, space separated, `*amp`): `head` (looks at targets, big baby head), `look`, `leg:0|1` (diagonal pairs), `arm:0|1` (walk swing, raised when aggressive, swing on attack), `reach`, `wing:0|1` (fast flap in the air), `tail`, `jaw` (opens on attack/hurt), `segment:i` / `wave:i` (travelling body wave), `crawl:i*±1` (spider legs), `breathe`, `bob`, `spin`, `roll`, `sway`, `ear`, `body`, `lunge`.
- Clips (keyframes, Blockbench-style): `"clips": {"attack": {"len": 10, "bones": {"jaw": {"rx": [[0,0],[5,40],[10,0]]}}}}`. Names: idle (loops), walk (follows the walk cycle, weighted by speed), attack (vanilla swing), hurt, death (replaces the tip-over), sit, pose1..poseN. Channels rx ry rz (degrees), x y z (pixels), s (scale +). Keys are smoothstepped.
- Mod-driven poses (casting, charging, roaring): `MobPoses.set("moddeus:<id>", e -> new float[] {castWeight, chargeWeight})` in the mod's client hook blends `pose1`, `pose2`. Example: the Hollow King (`AbyssalAscensionClient.hook`).
- Test on your stage: `s.spawn("frost_mod:frost_wolf", 0, 0, 5, "")`, `setTarget(s.dummy(0, 0, -4))`, `game stage <ModId> wait 60`; look with `game studio entity <id> --anim walk|attack|pose1` (TESTING.md).

## Behaviour: write it per mod in Java
Roles and the template give a body and basic AI; what makes the creature itself is the mod's own code in `content/<Id>*.java`
(server tick + `Bosses.job` sequences) and `client/content/<Id>Client.java` (poses). Engine pieces: `Mobs.TYPES` (id → EntityType),
`m.getNavigation().moveTo(x, y, z, speed)`, `m.getTarget()`, `Combat.velocity/damage/enemies/blast/shoot`, `Bosses.job(owner, ticks, (j, age) -> ...)`
(owner-bound timed steps), `MobPoses.set(id, e -> float[])` (blends `pose1..N` clips), `SoundLoops.follow(e, sound, key, vol, pitch, fade)`.
`Bosses.behave(e, "flying"|"caster"|"leaper"|"burrower"|"pack"|"pet"|"mount", args)` are samples (`--pet`/`--mount` write them): read them in `live/Bosses.java`, then write your own.

**A. Circle, crouch, lunge** (server, in the mod's tick):
```java
public static Map<Integer, Long> CROUCH = new ConcurrentHashMap<>();   // entity id -> until ms (client reads it: same JVM)
static void tick(MinecraftServer s) {
    if (!enabled) return;
    var type = Mobs.TYPES.get(Identifier.parse("frost_mod:frost_wolf"));
    if (type == null) return;
    for (ServerLevel l : s.getAllLevels())
        for (Entity e : l.getEntities(type, Entity::isAlive)) {
            if (!(e instanceof Mob m) || !(m.getTarget() instanceof LivingEntity t)) continue;
            int phase = (e.tickCount + e.getId() * 13) % 80;            // own rhythm per wolf
            double a = e.tickCount * 0.05 + e.getId();
            if (phase < 55) m.getNavigation().moveTo(t.getX() + Math.cos(a) * 5, t.getY(), t.getZ() + Math.sin(a) * 5, 1.3);
            else if (phase == 55) { m.getNavigation().stop(); CROUCH.put(e.getId(), System.currentTimeMillis() + 500); }
            else if (phase == 65) lunge(m, t);
        }
}
static void lunge(Mob m, LivingEntity t) {
    Combat.velocity(m, t.position().subtract(m.position()).normalize().scale(1.4).add(0, 0.35, 0));
    Bosses.job(m, 12, (j, age) -> { if (m.distanceTo(t) < 1.8) { Combat.damage(t, m, 7, "moddeus:frost"); j.stop(); } });
}
```
**B. Pose from that state** (client hook; `pose1` = the crouch clip above):
```java
MobPoses.set("frost_mod:frost_wolf", e -> new float[] { FrostMod.CROUCH.getOrDefault(e.getId(), 0L) > System.currentTimeMillis() ? 1 : 0 });
```
**C. Something else entirely:** an ambusher that hides as a block (`setInvisible` + a `Combat.display` of a stone block at its feet) and bursts out when `Combat.enemies(m, m.position(), 4)` is not empty; a grazer that eats grass (`l.setBlock(below, Blocks.DIRT.defaultBlockState(), 3)`) and grows (`--scale` attribute) per meal. Each mob gets ONE signature mechanic nobody else has.

Unique checklist: own silhouette and bone tree, own skin ramps, own rhythm (timing differs from your other mobs), own signature move with a telegraph pose, own sounds (`--sounds`), its attacks drawn per VFX-DESIGN.md.

## Hitboxes (automatic)
- `mc mob --model` sizes the hitbox from the model bounds (times `scale`), written as `"hitbox": [w, h]`. `Life` applies it to the live entity type, so a model edit resizes mobs that already exist. Babies use half.
- Long bodies (dragons, serpents: length > 1.6x width) also get `"parts"`: invisible Interaction boxes along the body that follow it and pass hits to the mob. Check with F3+B.
- The `--scale` attribute scales model and hitbox together.

## Animals: food, breeding, babies
`mc mob ... --template cow --food "wheat,moddeus:clover"`: feeding heals, grows babies faster, and puts adults in love. Two adults in love breed with vanilla hearts and XP; Life swaps the vanilla template baby for your type. Baby = half size with a big head.

## Taming and pets
- `--tame bone [--tame-chance 0.33]`: TamableAnimal templates (wolf, cat, parrot) tame natively (sit/follow/defend owner). Other templates get the sample `Bosses.behave(e, "pet", ...)` (follow, fight for you, sneak+click = sit).
- `--pet "item=bone"` forces that sample. A pet with its own personality (tricks, fetch, a special skill) = your Java (Behaviour above).

## Mounts and vehicles
- Native: horse, donkey, camel, llama, pig/strider (saddle), happy_ghast templates keep vanilla riding controls.
- Any mob: `--mount "fly=1 speed=0.4 saddle=1"` (sample `Bosses.behave(e, "mount", ...)`: WASD, Space = jump/fly up, sprint, sneak = dismount). Give the mount its own feel in Java (a dash on sprint, a glide, a stomp on landing). Vehicles: a mob template + model + `--mount`, or a physics body (`Physics.spawn`, riders stand on solid bodies).

## Sounds
`--sounds "ambient=moddeus:growl,hurt=minecraft:entity.wolf.hurt,death=moddeus:howl,step=minecraft:block.stone.step,attack=moddeus:bite"`. The mob goes silent and Life plays these (pitch variance, louder when big, higher for babies). Missing keys keep the template's own sounds. Values can be files/URLs (imported as `<id>_<kind>`); `--ambient-every 120` = ticks between ambient sounds. Design + loops: SOUNDS.md.

## Drops and XP
`--drop "moddeus:frost_fang 1-2 0.5" --drop "bone 0-2"` writes `data/moddeus/loot_table/entities/<id>.json` (Looting adds one and raises the chance). Extra pools on vanilla mobs: `mc drop` (GAMEPLAY.md).

## Spawn eggs and natural spawns
Every `mc mob` gets `<id>_spawn_egg` (painted). Natural spawns: `mc spawnrule <id> --mob moddeus:<id> --biomes ... --time night` (BOSSES.md).

## Bosses
Bosses are big, multi-part, animated cube-model mobs: `mc mob <boss> --model <own file> --health 400 --scale 2` with attack/death/pose clips, THEN `mc boss <id> --mob <ns>:<boss>` for the bar and phases; its attacks are the mod's own Java (BOSSES.md, GAMEPLAY.md). Never a reskinned zombie, never geometry drawn on top of a vanilla mob.

## Villagers: trades and professions
```bash
mc trade frost_charm --profession cleric --level 2 --wants "12 emerald + 1 snowball" --gives "moddeus:frost_charm" --uses 6 --xp 10
mc block forge_table --cubes "..." --tex "..."          # the workstation
mc profession blacksmith --name "Blacksmith" --workstation moddeus:forge_table --colors "apron=#4a3020,trim=#c08030"
mc trade sword_deal --profession moddeus:blacksmith --wants "20 iron_ingot" --gives "moddeus:ruby_sword"
```
- Vanilla 26.3 trades are data (`trade_set/<profession>/level_N` -> `#villager_trade` tags), loaded with the world. Moddeus adds each `trade` live to every matching villager the next time a player opens it (once per villager), and to wandering traders (`--profession wandering_trader`).
- A profession registers a POI on the workstation block and a villager job live. Unemployed villagers claim newly PLACED workstations (run `game cmd reload` once so the acquirable-job tag picks it up). The apron overlay is `textures/entity/villager/profession/<id>.png`.
- Pitfall: villagers keep trades they already got; removing a trade spec does not take it back.

## NPC dialogue
`mc npc` (GAMEPLAY.md): named NPC (villager, mannequin with a skin, or any mob), dialogue lines, quest command after the last line, then a trade shop. MCA-style families and relationships are not built in.

## Paintings
`mc painting sunset --size 2x1 --file sunset.png --title "Sunset"`: painting_variant + placeable tag + texture. Paintings are a world-load registry: they appear after the next world load (no live path).

## Displays (item frames, armor stands, pedestals)
Use vanilla `item_display` / `block_display` / `text_display` entities (`summon item_display ~ ~1 ~ {item:{id:"moddeus:x"},transformation:...}`) or invisible armor stands; for interaction add an `interaction` entity. No custom code needed.

## Projectiles
`Combat.shoot(caster, Combat.Shot.of().speed(1.5).homing(0.2).item("frost_mod:shard").trail("frost_mod:frost_trail").hit("frost_mod:frost_pop"))` (fields: speed gravity homing knockback radius damage scale fire bounces pierce cluster chain life boomerang item trail hit type effect) or your own projectile loop; `mc item --throws` (physics). Give every projectile a model and its own VFX, never a bare default arrow (COMBAT.md).
