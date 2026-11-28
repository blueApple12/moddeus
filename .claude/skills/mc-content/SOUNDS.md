# SOUNDS: sound design for every kind of content

Half of every effect is its sound, and the sound is part of what makes a mod ITS OWN: design it per mod, per ability. Plumbing (one command, live in ~1 s, no frame hitch via `client/SoundFast`): `mc sound` (import + clean), `mc sound-hold`, the sound flags of `mc block|mob|item|food|armor|disc|ambience`, `mc sound-test` / `mc sound-check`. Behaviour (when, where, pitch, layering, loops) is Java in the mod's class (`content/<Id>*.java`, section 10).
Every id lives in the mod's namespace (`--tab "<Mod>"` or `<ns>:<name>`, NAMESPACES.md). A brand-new namespace still needs one full reload.

## 1. sounds.json (what mc sound writes)
`assets/<ns>/sounds.json`: `{"<event>": {"sounds": [...], "subtitle": "subtitles.<ns>.<event>", "replace": false}}`. Each sound entry is a path
string (`"<ns>:<file>"` = `assets/<ns>/sounds/<file>.ogg`) or an object:
| key | default | use |
|---|---|---|
| `name` | - | file path (no .ogg), or an event id when `type:"event"` |
| `type` | `file` | `event` = pick from another event (reuse vanilla: `{"name":"minecraft:entity.zombie.ambient","type":"event"}`) |
| `volume` | 1 | 0-1 per entry (vanilla music 0.4, amethyst chime 0.2). Range comes from the PLAY volume: range = max(1, play volume x entry volume) x attenuation |
| `pitch` | 1 | fixed per entry. Variation: several entries of one file at different pitches (vanilla crossbow loading: 0.95/1/1.2) = `--pitch-set` |
| `weight` | 1 | relative pick chance (crimson forest: rare additions weight 3, particles 35) |
| `stream` | false | read from disk while playing: music, loops, anything > ~10 s. Non-stream files are fully decoded into RAM |
| `preload` | false | decode at resource load (first play has no delay). Vanilla uses it nowhere; only for sounds that must fire instantly |
| `attenuation_distance` | 16 | blocks until silent (linear). Vanilla: 3-12 for small blocks (beacon 7, portal 10), 32-48 for huge things |
Subtitle: always set it, describe the sound ("Portal hums"), never the file name (mc sound does it: `--subtitle`). `replace: true` only when
overriding a vanilla event from a resource pack.

**Categories** (`SoundSource`, chosen at PLAY time, not in sounds.json): `master music record weather block hostile neutral player ambient voice ui`.
Players set a slider per category, so pick the honest one: mob sounds = the mob's own (hostile/neutral), abilities = player, block
machines = block, biome beds = ambient, boss music = music or record, menus = ui. Commands: `playsound <id> <category> @a ~ ~ ~ <vol> <pitch>`.

## 2. Files: format, mono/stereo, loudness, variation
- **Ogg Vorbis** only (mc sound converts anything ffmpeg reads: mp3, wav, flac, m4a). 44.1/48 kHz.
- **MONO = positional** (pans and fades with distance). **STEREO is never positional**: it plays "in your head" at full volume at ANY distance
  (OpenAL does not attenuate multi-channel sources). Stereo only for streamed non-positional music/ambience (vanilla basalt deltas loop, game music). Mob, block, item, ability, jukebox
  sounds: mono (vanilla records are mono + stream). mc sound: mono unless `--stream` (then stereo; `--mono` forces mono, `--stereo` forces stereo).
- **Clean-up (default; `--raw` skips)**: downmix, trim leading/trailing silence (> -55 dB) so the sound starts ON the hit, 8 ms anti-click fades,
  linear gain to vanilla loudness. `--level` (measured on 26.3 assets): `loud` -14 LUFS (roar -15.6, explode -12.7), `normal` -20 (zombie say
  -22, bow -21), `quiet` -27 (cave -27, steps -33), `ambient` -30 (basalt bed -32), `music` -17 (game music -17.5..-18, cat -16.6); true peak
  capped at -1 dBTP. `--loop` = keep the file seamless (no trim/fades). `--max 2.5` = cut + 150 ms fade.
- **Never repetitive**: 2-4 recorded/synth variants (`--variants b,c`) x `--pitch-set 0.92,1,1.08` = up to 12 picks; code adds random pitch
  per play (`0.9 + rnd*0.2`, mobs: `(rnd-rnd)*0.2+1`, babies +0.5). Footsteps/hits that repeat often need the most variants.
- `mc sound-check [ns]` audits: missing files, stereo-but-positional, long non-streamed, one-shot with one variant, subtitle missing, orphan .ogg.

## 2. Files: format, mono/stereo, loudness, variation
- **Ogg Vorbis** only (mc sound converts anything ffmpeg reads: mp3, wav, flac, m4a). 44.1/48 kHz.
- **MONO = positional** (pans and fades with distance). **STEREO is never positional**: it plays "in your head" at full volume at ANY distance
  (OpenAL does not attenuate multi-channel sources). Stereo only for streamed non-positional music/ambience (vanilla basalt deltas loop, game music). Mob, block, item, ability, jukebox
  sounds: mono (vanilla records are mono + stream). mc sound: mono unless `--stream` (then stereo; `--mono` forces mono, `--stereo` forces stereo).
- **Clean-up (default; `--raw` skips)**: downmix, trim leading/trailing silence (> -55 dB) so the sound starts ON the hit, 8 ms anti-click fades,
  linear gain to vanilla loudness. `--level` (measured on 26.3 assets): `loud` -14 LUFS (roar -15.6, explode -12.7), `normal` -20 (zombie say
  -22, bow -21), `quiet` -27 (cave -27, steps -33), `ambient` -30 (basalt bed -32), `music` -17 (game music -17.5..-18, cat -16.6); true peak
  capped at -1 dBTP. `--loop` = keep the file seamless (no trim/fades). `--max 2.5` = cut + 150 ms fade.
- **Never repetitive**: 2-4 recorded/synth variants (`--variants b,c`) x `--pitch-set 0.92,1,1.08` = up to 12 picks; code adds random pitch
  per play (`0.9 + rnd*0.2`, mobs: `(rnd-rnd)*0.2+1`, babies +0.5). Footsteps/hits that repeat often need the most variants.
- `mc sound-check [ns]` audits: missing files, stereo-but-positional, long non-streamed, one-shot with one variant, subtitle missing, orphan .ogg.

## 3. Sourcing
1. **Web (real recordings)**: WebSearch `freesound <thing>` / Pixabay / OpenGameArt / Sonniss GDC bundles; pass the PAGE url to `mc sound`. It
   takes `og:audio` or the first .mp3/.ogg/.wav link (freesound: the HQ preview, no API key) and writes `<name>.license.txt` next to the
   .ogg (source, title, license). CC0 = free; CC-BY = credit the author (keep the license file, put it in the mod README); NC = not for a
   mod someone may sell; no license on the page = do not ship it.
2. **Your own ffmpeg chain** (designed UI/magic sounds, nothing fits): build the layers yourself, then `mc sound <name> out.wav`.
   Mix: `ffmpeg -i a.wav -i b.wav -filter_complex amix=inputs=2:normalize=0 out.wav`; pitch without speed change:
   `-af asetrate=44100*0.8,aresample=44100,atempo=1.25`; reverse swell: `areverse`; tail: `aecho=0.8:0.6:120:0.4`; tones/noise:
   `-f lavfi -i "aevalsrc=..."` / `anoisesrc`. Layer a real recording + a designed sub/tail so it is not a stock file.
3. `mc synth <name> --preset <p>` = sample only (prints so): read `PRESETS` in `tools/sounds.mjs` (one aevalsrc/anoise expression per layer) to learn the recipe, then write this mod's own chain. Never ship a bare preset as the ability's sound.
4. Reuse vanilla when it is right (`minecraft:entity.warden.roar`, `--event minecraft:x` to add a vanilla event as one more variant), but pitch/layer it so two mods never sound the same.

## 4. Mobs (`mc mob`, LIFE.md)
`mc mob <id> --sounds "ambient=<file|url|id>,hurt=..,death=..,step=..,attack=.." [--ambient-every 80]`: files/URLs are
imported as `<id>_<kind>` (death/roar at `--level loud`). Life.java silences the body's own sounds and plays these at the mob's category, louder
when scaled up, higher for babies, `(rnd-rnd)*0.2` pitch jitter; steps every stride from distance walked; missing keys keep the body's.
- Good: ambient = personality (3+ variants, `ambient_every` 80-160 ticks: never spammy), hurt short + sharp (2-3 variants), death
  longer + lower + loud, step matches size/material, attack = wind-up whoosh + bite/impact.
- Ability/boss sounds are Java, fired on the SAME tick as the VFX (section 10). A boss aura: `SoundLoops.follow(boss, "<ns>:aura", "aura", 2f, 1f, 20)` (32-block range, ends by itself on death).

## 5. Items
- Use/swing/ability: one sound per ability (vfx layer `{"type":"sound","sound":"<ns>:x","volume":1,"pitch":1}`, or Java `playSound`).
  Swing whoosh on attack, impact on hit, at the hit position (not the player) so it is heard where the VFX is.
- Food/drink: `mc food <id> --sound <id>` (consumable sound, plays every few ticks while eating; drinks `entity.generic.drink`).
- Armor: `mc armor ... --equip-sound <id>` (equippable.equip_sound). Gliders/totems: GEAR.md `--sound`.
- Bows/crossbows/guns/wands (WEAPONS.md): `--sound` (release/shot), `--charge-sound` (charge start), `--ready-sound` (fully charged).
- **Hold loops** (lightsaber hum, cursed whisper): `mc sound-hold <item> <SRC|id> [--volume 0.6]` = content `hold_sound`: loops on the holder
  while in either hand, fades in 6 ticks, fades out on put-away/death/world leave.
- Good: charge -> release -> impact -> tail are 3-4 separate sounds; distant explosions use a bigger play volume (range), not louder files.

## 6. Blocks
`mc block <id> --sound <base> [--place-sound|--break-sound|--step-sound|--hit-sound|--fall-sound <SRC|id>] [--sound-volume 1 --sound-pitch 1]`
= a full SoundType (break place step hit fall). `--sound` takes any vanilla SoundType by name (stone wood metal glass wool sand snow slime
amethyst grass gravel deepslate copper sculk mud moss chain lantern nether_bricks honey_block ...), files/URLs become `<id>_<kind>`.
(SoundType is fixed when a block id registers: changes apply to NEW ids, an existing id keeps its sounds until restart. `fallSound`, `soundVolume/soundPitch` and vanilla names beyond the 11 basics need one game restart after this update: that ContentLoader change is waiting.)
- **Block ambient** (vanilla `animateTick`: furnace crackle, portal hum, sculk): `--ambient-sound <SRC|id>[@0.02]` = content `ambient_sound`
  {sound, chance, volume}: the client samples 667 random blocks within 16 and 32 every tick like vanilla and plays it with chance per
  sampled block at the block, pitch 0.9-1.1. Give it a short attenuation (6-10) so a wall of them is not a roar: `--attenuation` applies to
  EVERY sound that command imports, so import it first (`mc sound <id>_ambient SRC --attenuation 8 --level quiet`), then `--ambient-sound <ns>:<id>_ambient`.
- Machines/engines that run: `SoundLoops.at(level, pos, "<ns>:engine", "engine_" + pos.asLong(), 0.8f, 1f, 10)` while on, `SoundLoops.stop("at:engine_" + pos.asLong())` when off (section 10).
- Make it amazing: step quiet (vanilla -33 LUFS), break = crunch + debris tail, place = solid thunk, magical blocks get an ambient + a
  hit sound that matches the material (crystal = `chime`/`shimmer` variants).

## 7. Ambient, biomes, dimensions, caves (WORLD.md, DIMENSIONS.md)
`mc ambience <id> --biomes .. --music SRC --loop SRC --mood SRC --additions "SRC@0.0111"` (environment attributes): `--loop` = streamed
stereo bed that crossfades on biome entry (vanilla nether), `--mood` = cave-style one-shots when the player is in dark enclosed space
(`tick_delay` 6000, `block_search_extent` 8, `offset` 2), `--additions` = random one-shots per tick chance (vanilla 0.0111), `--music` =
biome music with `--music-delay` and `replace_current_music`. The same attributes in a dimension's biome = dimension ambience.
- Make it amazing: bed at `--level ambient` (-30 LUFS; it never competes with gameplay), 4-8 mood variants (eerie, distant, mono), additions
  rare and varied, music that fits the place, no ambience louder than mob sounds.

## 8. Music and jukebox discs
- `mc disc <id> --sound SRC --title "Artist - Song"`: imports streamed + mono (positional at the jukebox, like vanilla records), length from
  ffprobe, jukebox song live + `data/<ns>/jukebox_song/<id>.json` with an INLINE sound event `{"sound_id": "<ns>:<id>"}` (by-id refs to a
  live-registered sound failed the world load = Safe Mode; preflight fixes old files). `length_in_seconds` must match the track.
- Biome music: `mc ambience --music`; boss music: the mod's own code (a streamed track started per nearby player, stopped on death; `music` in `live/Bosses.java` is a readable sample); any track: `--stream --level music` (-17 LUFS like vanilla).
- Tracks 1-5 min; loop points at a musical bar; fade the last 2-4 s (`--max` or the source) unless it loops.

## 9. Loops that follow things (`live/SoundLoops` + `client/SoundLoopsClient`), Java only
```java
String k = SoundLoops.follow(entity, "<ns>:hum", "hum", 1f, 1f, 10); // volume, pitch, fade ticks; returns the full key
SoundLoops.at(serverLevel, vec3, "<ns>:hum", "spot", 1f, 1f, 10);    // fixed position, full key "at:spot"
SoundLoops.stop(entity, "hum");  SoundLoops.stop("at:spot");  SoundLoops.stopEntity(entity);  SoundLoops.stopAll();
```
One tickable looping instance per key, positioned on the entity every tick (mid-height), fades in/out, `volume` > 1 widens the range (2 = 32 blocks).
Loops end by themselves when the entity dies/unloads, on `@OnUnload`, world leave or server stop: nothing lingers, but stop your own on
ability end. Fixed loops start within 128 blocks; a loop the engine dropped (no free channel, sound reload) restarts by itself. The file
must be seamless (`mc sound --loop`). Singleplayer/same-JVM (the Moddeus case).

## 10. Sound design in Java (write it per mod)
Inside the mod's class (`content/<Id>.java`, hook/unload pattern: GAME-RUN.md). Play helper (as in `content/AbyssalAscension.java`):
```java
static void sound(Level l, Vec3 p, String id, float vol, float pitch) {
  SoundEvent e = BuiltInRegistries.SOUND_EVENT.getValue(Identifier.parse(id));
  if (e != null) l.playSound(null, p.x, p.y, p.z, e, SoundSource.PLAYERS, vol, pitch);
}
```
Charge that climbs, then a two-part release (`Combat.charge` holds while the key is down; stepped one-shots give a rising pitch, a loop cannot):
```java
Combat.charge(p, "Tide Call", 60, () -> Quick.keyDown(MOD, "Tide Call (hold)"), f -> {
  sound(p.level(), p.position(), "tide_mod:surge_release", 1.2f, 0.7f + (float) f * 0.5f);
  Quick.after(5, () -> sound(p.level(), p.position(), "tide_mod:surge_tail", 0.8f, 0.9f));
});
Bosses.job(p, 60, (j, age) -> {
  if (!Quick.keyDown(MOD, "Tide Call (hold)")) { j.stop(); return; }
  if (age % 6 == 0) sound(p.level(), p.position(), "tide_mod:surge_tick", 0.5f, 0.6f + age / 60f);
});
```
Thunder that arrives late with distance (speed of sound, ~17 blocks per tick):
```java
for (ServerPlayer near : l.players()) {               // played at each listener, offset toward the strike
  int delay = (int) (near.position().distanceTo(strike) / 17);
  Vec3 at = near.position().add(strike.subtract(near.position()).normalize().scale(8));
  Quick.after(Math.max(1, delay), () -> sound(l, at, "storm_mod:crack", 1.5f, 0.8f + l.getRandom().nextFloat() * 0.2f));
}
```
Heartbeat that speeds up as health drops (in the mod's server tick):
```java
float hp = p.getHealth() / p.getMaxHealth();
int gap = 10 + (int) (hp * 30);                       // 40 ticks calm, 10 ticks near death
if (hp < 0.5f && p.tickCount % gap == 0) sound(p.level(), p.position(), "blood_mod:heartbeat", 1f - hp, 1f);
```
**Make it unique to this mod:** [ ] its own recorded/designed files (no bare preset, no unmodified vanilla event as the signature sound)
[ ] a sound palette per mod (e.g. glassy + high for ice, sub-heavy + low for void) [ ] every ability its own charge/release/impact set, never
shared [ ] timing locked to the mechanic (impact on the hit tick, tail after) [ ] pitch/volume that react to state (charge level, size, health)
[ ] loops stopped when the ability ends.

## 11. Verify (never at the player)
- `mc sound-test <ns:id>`: what the client resolved (entries, files, stream, attenuation, subtitle, MISSING FILE) + a real play 200 blocks
  away at volume 0.001 (`play=STARTED active=true picked=<file>` = it plays; inaudible even for stereo files). `--here` only when the player wants to hear.
- Abilities: `mc game stage <ModId>` + `game run` (`Stage.of("<ModId>")`, `s.hold(..).use()`, TESTING.md); nobody hears the stage.
- `run/logs/latest.log`: "Sound fast-reload" (applied), "Unable to play unknown soundEvent" (typo / missing sounds.json entry),
  "Missing subtitle". `mc sound-check` before shipping a mod. Remove test sounds: `mc remove-sound <ns:name>`.
