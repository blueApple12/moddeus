# Testing on a Stage + Studio (never on the player)

Several chats test at once in the SAME live world. The player must never see a test: no cutscene or shake on their camera,
no mob next to them, no teleport, no test item in their inventory, no camera hop. So every test runs on YOUR stage
(far away, private) and every look is a Studio render (offscreen). `game shot` / buddy `see` show the player's world
and the avatar's camera: use them only when the player asked you to look at something they see.

## Stage: server-side tests
`node tools/mc.mjs game stage <ModId>` opens your stage and waits until it is loaded (first time a few s):
overworld x = 3,000,000 + slot*4096, z = 3,000,000, floor y=200 (33x33, 4-block grid, barrier rim). Same id = same slot.
Then Java via `game run --file snippet.java` (`Stage` is imported):

```java
Stage s = Stage.of("FrostMod");                         // coordinates below are relative to the stage centre
var z = s.spawn("minecraft:zombie", 0, 0, 5, "{equipment:{head:{id:\"stone_button\"}}}"); // tagged FrostMod_test, persistent
s.hold("moddeus:frost_blade").lookAt(z);                 // the actor: a fake player, not the real one
print(s.use());          // right-click (UseItemCallback + Item.use)   s.sneakUse(), s.useOn(dx,dy,dz), s.release(ticks)
print(s.hit(z));         // left-click at full strength (AttackEntityCallback first)
s.after(40, () -> s.log("hp after 2 s: " + ((LivingEntity) z).getHealth()));
print(s.report());       // entities (hp, effects, burning, pos), blocks, actor, log (also: VFX/cutscenes that were skipped)
```
- Call the mod's own code straight from the snippet (no slash commands exist for testing; abilities are plain Java): make the ability method `public static` and run `s.run(() -> dev.moddeus.content.FrostMod.iceLine(s.actor()))`, or `s.hold(...)` + `s.use()` for the real input path. Snippets always reach the live generation of a mod class.
- Wait without blocking: `game stage <ModId> wait 40` (sleeps 40 ticks, prints the report). `game stage <ModId> report`.
- More helpers: `place(dx,dy,dz,"minecraft:target")`, `block(dx,dy,dz)`, `cmd("...")` (at the stage; @a/@p/@r refused),
  `spawnDef("<npc|boss id>", dx,dy,dz)`, `dummy(dx,dy,dz)` (mannequin target: `mob.setTarget(s.dummy(0,0,-4))`),
  `moveTo`, `lookAt(pos|entity)`, `run(() -> ...)` (any code as the actor), `pos(dx,dy,dz)`, `at(dx,dy,dz)`, `box()`.
- While the actor acts (and in Quick.after/every tasks it starts), `Quick.me()` returns the actor, `Quick.cutscene` /
  `Cutscene.play` skip, and `Vfx.play` at a stage position skips (logged in report()). Your own player-facing code
  (titles, screen effects, client calls) must skip too: `if (Stage.quiet() || Stage.isStage(pos)) return;`.
- Limits: the actor doesn't tick (no "while held" / per-tick player code; test those with `s.run(() -> MyMod.tickHeld(s.actor()))`),
  mobs don't target it (use `dummy`), undead burn in daylight (give them a helmet), no natural spawns.
- ALWAYS finish with `game stage <ModId> clear` (entities, blocks, forceload). `game stage list` shows all stages.

## Studio: how it looks (offscreen, no camera)
`game studio <kind> <id> ...` renders client-side dummies into its own texture and prints a contact-sheet PNG (Read it;
single views sit next to it). ~1 s, no hitch, works with modded renderers, 3D models, worn armor, cube-mob clips.
- Mob/entity: `studio entity moddeus:frost_golem --angles 4 --anim walk|attack|hurt|death|idle|pose1|sit --t 0.5`
  (`--t` = clip progress 0..1; `--nbt '{...}'` entity NBT, e.g. a boss's equipment or size).
- Item: `studio item moddeus:frost_blade` = GUI + first person + in a mannequin's hand from 4 angles; `--view gui,fp,hand,ground`;
  `--nbt '{"minecraft:item_model":"moddeus:frost_bow_pull2"}'` = item components (pull stages, charged, custom_model_data).
- Armor: `studio armor frost --pose stand,crouch,swim` (set name or comma item ids).
- Block: `studio block moddeus:ruby_ore` (3/4 views + GUI icon).
- `--size 384` (64-1024), `--pitch 15`, `--angles 1-8`. Judge proportions, colors, missing textures, part attachment.

## What to check per kind
| Kind | Stage (does it work) | Studio (does it look right) |
|---|---|---|
| Item ability / weapon | `hold` + `use`/`sneakUse`/`hit`; report: target hp, effects, spawned projectiles, blocks, log "vfx ..." | `studio item` (+ `--nbt` per state) |
| Armor / set bonus | `s.actor().setItemSlot(EquipmentSlot.HEAD, ...)`, then call your tick with the actor; check attributes | `studio armor <set> --pose stand,crouch,swim` |
| Mob / boss | `spawn` / `spawnDef`, `setTarget(s.dummy(...))`, `wait`, report hp/positions; hitbox = `e.getBoundingBox()` | `studio entity <id>` with each `--anim` |
| Block | `place`, `useOn`, `block(...)`; drops = item entities in report | `studio block <id>` |
| VFX | fires from the right spot: report log "vfx <id>" (own geometry: call `Stage.note(pos, "fx <name>")` when on a stage); ids/timing from the def or your code | look: `VfxShot.queue` (below) |
- Real terrain in a shot: VfxShot draws only custom geometry. NaturalDisasters adds `NaturalDisastersClient.shotTerrain = 60` (radius): `NaturalDisastersTerrainShot.capture` snapshots the server blocks on the server thread (getChunkNow returns null off-thread), the shot draws surface + exposed walls + fluids. Copy the pattern for other mods that need to show landscape changes.
| Sounds | `game log --grep <sound id>` / registry check; play them on the stage (nobody hears it) | - |
| Datapack | `s.cmd("function <ns>:test")` / `execute positioned` at the stage; read scoreboards with `s.cmd("scoreboard ...")` | - |

## VfxShot: frames of your own VFX geometry (offscreen, virtual camera)
`client/game/VfxShot`: `public static String queue(String name, Vec3 cam, Vec3 target, float fov, int size, int frames, int every, double groundY, Scene scene)` renders `scene` (your submit code: `(nodes, poseStack, cam, camRot) -> ...`) from `cam` looking at `target`, `frames` frames `every` ticks apart, on a stone plane at `groundY` (no terrain, no entities). Result `run/moddeus/shots/studio/<name>.json` = `{sheet, files, views}` or `{error}`; Read the sheet. Real clock, so it works during `/tick freeze`. Example wrapper: `NaturalDisastersClient.shot(disaster, dist, yawDeg, pitchDeg, lookUp, frames, every)`.
