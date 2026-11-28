# WORLD: ambience, sounds, music, particles, redstone, banners, rules, commands, toasts

The plumbing is one command each (`tools/world.mjs`, Java `live/WorldFeel.java` + `client/WorldFeelClient.java`), live, exported with the mod (`--tab "<Mod>"`). Remove: `mc remove <file id>` (printed by the command). What the world DOES (events, reactions, timing) is the mod's own Java: section "World behaviour in Java" at the end.

## Ambience (sky, fog, light, biome colors, music, ambient sound, particles)
26.3 biomes carry **environment attributes** (`data/minecraft/worldgen/biome/*.json` "attributes"). Moddeus writes the same JSON into the running biomes (server and client) and rebuilds each level's attribute system, so it blends at biome borders and darkens at night exactly like vanilla.
```bash
mc ambience abyss --biomes "deep_dark,#minecraft:is_ocean" --sky "#1a0830" --fog "#2a1050" --fog-end 40 \
   --music tracks/abyss.ogg --loop drone.ogg --additions "drip.ogg@0.02" --particles "moddeus:ember@0.01" \
   --water "#401070" --grass "#3a2a5a" --block-light "#c080ff" --monsters-burn false --tab "Abyss Mod"
```
- `--biomes`: ids, `#tags` or `all` (default). Sounds: a sound id, or a file/URL (imported as `moddeus:<id>_music|_loop|_mood|_add`; music and loops stereo + streamed).
- Look: `--sky --fog --fog-start --fog-end --water-fog --water-fog-end --clouds "#AARRGGBB" --cloud-height --sunset --stars --sky-light --sky-light-factor --block-light --ambient-light --night-vision`.
- Sound: `--music` (replaces the current track on entry; `--music-delay 20-600` ticks between tracks; `--music-volume`), `--loop` (fades in/out like nether biomes), `--mood` (cave-style, `--mood-delay`), `--additions src@chance`.
- Colors: `--water --grass --foliage --dry-foliage` (chunks re-mesh on the client).
- Anything else: `--attr "minecraft:gameplay/bees_stay_in_hive=true"` or `--attributes '{vanilla json}'` (bed_rule, natural_mob_spawns...).
- Pitfalls: overriding `audio/ambient_sounds` replaces the biome's set, so the tool keeps the cave mood unless you pass `--mood`. Time-of-day layers apply after the biome, so a custom sky still darkens at night. Datapack-only mods: put the same "attributes" in their own biome JSON (needs a world reload).

## Sounds
Full guide (mobs, items, blocks, loops, synth, sourcing, loudness, testing): `SOUNDS.md`.
`mc sound <name> SRC [--variants b.ogg,c.ogg] [--stream] [--volume 0.8] [--pitch 1.2] [--subtitle "Gears whir"]`
- Mono by default (positional, fades with distance). `--stream` = stereo + streamed: music, long loops. `--variants` = vanilla random pick per play (footsteps, hits).
- Every sound gets a subtitle; write what it is ("Portal hums"), not the file name.

## Music
Per biome: `mc ambience ... --music`. Discs: `mc disc`. Boss fights: the mod's own code (SOUNDS.md section 8). Vanilla `replace_current_music` cuts the old track when the player enters.

## Particles
1. Frames: draw them for this mod (`mc pixels particle <id>_0..N`, PIXEL-ART.md): its own silhouette and palette. `mc ptex <id> --shape ...` = sample only: read the shape code in `tools/vfxlib.mjs` to learn the glow falloff, then draw your own.
2. Motion: `mc particle <id> --frames 4 --lifetime 30 --size 1.5 --size-end 0.3 --spin 0.2 --swirl 0.04 --gravity -0.02 --color "#fff" --color-end "#c040ff" --glow true`.
3. Use: vanilla `/particle <ns>:<id>`, VFX layers, mob `--particle`, ambience `--particles <ns>:<id>@0.01`, Java `level.sendParticles((SimpleParticleType) BuiltInRegistries.PARTICLE_TYPE.getValue(Identifier.parse("<ns>:<id>")), x, y, z, n, dx, dy, dz, speed)`.
Keep counts low; prefer VFX layers for shaped effects (VFX-DESIGN.md).

## Redstone components (logic blocks)
`mc logic <id> --mode <mode> [options]` makes a block with a generated top label, on/off models and a facing like a repeater. Place it facing away from you: input at your side, output in front.
- Gates (output front, strong power): `and xor nand xnor` (left + right inputs), `or nor` (back/left/right), `not repeater` (back), `latch` (left = set, right = reset), `toggle` (T flip-flop on a back pulse), `pulse --ticks 10`, `delay --ticks 8`, `random` (0-15 on a back pulse).
- Sources (power every side): `clock --ticks 20 --on 2` (back input pauses it), sensors `player --radius 8 [--analog]`, `mob --entity zombie --per 3`, `item`, `light`, `daylight`, `night`, `rain` (8, thunder 15), `time` (0-15 over the day), `gamerule --rule my_rule`, `score --objective kills`; `--every 10` = check rate.
- Output: `--mode output --cmd "summon lightning_bolt ~ ~3 ~;; playsound ..." --cmd-off ".." --cmd-tick ".." --every 20 --sound <id> --vfx <ns:id>` runs at the block on a rising edge (`;;` separates vanilla commands; `--vfx` plays a VFX def via `Vfx.play`). Anything smarter: read the power in the mod's Java (`level.getBestNeighborSignal(pos)`).
- Logic reads its spec (`<id>_logic.json`) on every update: edit it and the placed blocks change behaviour.

## Banner patterns
`mc banner <id> --texture <image> [--name "Sun Sigil"] [--no-item]`: dark/opaque parts of the image become the pattern (banner + shield textures), plus a loom item `<id>_banner_pattern` (`provides_banner_patterns` + tag `#moddeus:pattern_item/<id>`). `--no-item` lists it in the loom for free. The pattern registers live AND is written to `data/moddeus/banner_pattern/<id>.json` (a live-only entry named by a tag blocks the world load after a restart); the loom tags bind on the `reload` the tool runs. Remove it only when no placed banner uses it: `mc remove <id> <id>_banner_pattern`.

## Game rules
`mc gamerule <id> [--int --default 5 --min 0] [--default false] [--category mobs]` = a real `/gamerule moddeus:<id>`, saved with the world. Read it: `WorldFeel.ruleOn(server, "<id>")` / `WorldFeel.rule(...)`, logic sensor `--mode gamerule --rule <id>`. Use rules for mod switches (griefing, difficulty, spawn rates).

## Custom commands (only when the player asks for a slash command)
Abilities belong on items, keys (`Quick.key`) and GUIs (`ModGui`), not commands. `mc command <name> --run 'line;; line $(arg)' --args "n:int,who:player,msg:text" [--op 2]`. Types: word string text int number bool player players entity (`player`/`entity` insert the UUID, usable as a selector). Lines run with permission 2 as the caller ("elevate": false in the spec = the caller's own permission); word/string values must match [A-Za-z0-9_.:-] (else the command fails), text values lose quotes, backslashes, line breaks, @, {}, [], §; players/targets run the line once per matched player (UUID). Players can't inject selectors/NBT/commands. Quote `$(arg)` with single quotes in the shell.

## Toasts and titles
Java only: `WorldFeel.toast(serverPlayer, "<ns>:gem", "Found!", "text", 0xFFE0B040)` = advancement-style popup; icon = item id, color = the mod's own accent (ARGB). Titles: vanilla `/title`. Quests with toasts: `mc quest`.

## GUIs, HUD, weather events
HUDs and screens: GUI.md (ModGui, own theme per mod). Info panels: `mc hud`. Night/thunder events with tint, buffs, spawns: `mc skyevent` (SYSTEMS.md; start/stop in Java `Systems.startSky(server, id)` / `Systems.stopSky(server)`). Weather: vanilla `/weather`.

## Fluids
No live custom fluid: a new Fluid needs fluid-state ids and a FluidRenderHandler at startup. Best path now: recolor water per biome (`--water --water-fog`), or a translucent block / VFX layer that looks like the liquid.

## World behaviour in Java (write it per mod)
In the mod's class (`content/<Id>.java`, skeleton GAME-RUN.md), called from its server tick or events. Three shapes, not templates:
```java
// Frost creep: a gamerule-gated spread that freezes water around players, slower in warm biomes
if (s.getTickCount() % 40 == 0 && WorldFeel.ruleOn(s, "frost_mod:frost_spread"))
  for (ServerPlayer p : s.getPlayerList().getPlayers()) {
    BlockPos b = p.blockPosition().offset(p.getRandom().nextInt(9) - 4, -1, p.getRandom().nextInt(9) - 4);
    if (p.level().getBlockState(b).is(Blocks.WATER) && p.level().getBiome(b).value().getBaseTemperature() < 0.8f)
      p.level().setBlockAndUpdate(b, Blocks.FROSTED_ICE.defaultBlockState());
  }
```
```java
// Eclipse: the mod's own sky event at noon on every 7th day, announced with the mod's accent color
long clock = s.overworld().getOverworldClockTime(), day = clock / 24000, tod = clock % 24000;
if (tod == 6000 && day % 7 == 6 && Systems.sky == null) {
  Systems.startSky(s, "eclipse");
  for (ServerPlayer p : s.getPlayerList().getPlayers()) WorldFeel.toast(p, "sun_mod:black_sun", "Eclipse", "The sun goes out", 0xFF40206A);
}
```
```java
// Footprints of light: the player leaves the mod's own particle where they step, only at night
if (p.tickCount % 6 == 0 && p.onGround() && p.getDeltaMovement().horizontalDistanceSqr() > 0.004 && p.level().isDarkOutside())
  ((ServerLevel) p.level()).sendParticles(glowStep, p.getX(), p.getY() + 0.02, p.getZ(), 1, 0.1, 0, 0.1, 0); // glowStep = this mod's particle type
```
**Make it unique to this mod:** [ ] its own colors (sky, fog, toast accent, particle palette) [ ] its own timing (when the event comes, how long, how it ends) [ ] a mechanic tied to the theme, not a generic buff [ ] its own sounds (SOUNDS.md) [ ] two events never share a look. Test on the stage (`mc game stage <ModId>` + `game run`, TESTING.md), never on the player.
