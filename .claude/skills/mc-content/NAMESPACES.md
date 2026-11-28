# Per-mod namespaces

Every mod uses its OWN namespace = snake_case of the mod name ("Abyssal Ascension" -> `abyssal_ascension`). Never put a
mod's content in `moddeus:` (that is the core's).

## Making content
- Pass `--tab "<Mod Name>"` (or `--project "<Mod Name>"`, or write the id as `<ns>:<id>`) to every mc content command
  (block item armor mob particle vfx physics pixels texture sound effect potion brew enchant attr itemfx). mc then writes
  `assets/<ns>/...`, `data/<ns>/...`, lang `item.<ns>.<id>`, and the content JSON gets `"namespace": "<ns>"` + the mod's tab.
- No mod prefix in ids: `abyssal_ascension:bow`, not `abyssal_bow`. A content file name is one id across ALL mods; mc refuses
  an id another namespace owns ("pick another id").
- `mc pixels item bow_grip` finds the namespace by itself when the name extends existing content (bow -> bow_grip).
- Existing content keeps its namespace when re-run (`mc item bow --tab ...` updates abyssal_ascension:bow).
- Java: `Identifier.fromNamespaceAndPath("<ns>", id)`; `LiveRegistry.id("bow")` resolves a content path to its namespace.
  Engine calls take full ids: `Vfx.play(Vfx.id("<ns>:rift"), ...)`, `SoundLoops.follow(e, "<ns>:hum", ...)`, `Physics.spawn(level, "<ns>:boulder", ...)`,
  `BuiltInRegistries.SOUND_EVENT.getValue(Identifier.parse("<ns>:x"))`. A bare VFX layer texture name resolves to `assets/moddeus/textures/vfx/<name>.png`, shared by all mods:
  name them `<ns>_<what>` (or give a full id `<ns>:textures/vfx/<what>.png`) so two mods never share or overwrite a look.
- Tags, recipes, loot tables: refer to `<ns>:<id>`.
- Every module command takes the namespace the same way (food feast cook, recipe quest disc pad crop ore machine shop skill
  hud skyevent secret, tools worn glider totem book key reskin light container storage, ambience logic banner gamerule command,
  boss mobai spawnrule setbonus drop npc, trade profession painting): mc passes `ctx.NS` + `ctx.stamp` (= nsContent) to
  tools/*.mjs. Bare ids of existing content resolve to THEIR namespace (`--mob hollow_king` -> `abyssal_ascension:hollow_king`).
- `mc mob --model file.json`: the file's `colors` and `glow` are used; changed colors/glow repaint the skin (model `paint` key).
- Check: `mc test refs` (tools/preflight.mjs) fails on any model/texture/item/equipment/mob-skin ref of our namespaces that
  doesn't exist; at launch it copies a file written under the wrong namespace into place.

## Moving an existing mod: `mc namespace "<Mod Name>" [--strip abyssal_] [--dry [--verbose]]`
- Owns: content whose `"tab"` is the mod, files named after it (or containing the stripped prefix), the mod's Java
  (`<Id>*.java`), its -DP/-RP packs. Moves assets/data to `<ns>/`, renames ids (prefix stripped when every id has it),
  moves lang + sounds.json keys, rewrites `moddeus:` refs, Java namespace checks and bare ids. Run `--dry --verbose` first.
- Content JSON keeps `"aliases": ["moddeus:<old id>"]`: registry lookups of the old id return the new entry (LegacyIds), so
  worlds, chests and saved stacks keep working across restarts. Live: ContentLoader swaps every loaded stack, container,
  dropped item and placed block to the new id (same count/components/block state), then unregisters the old one.
- Not moved: `textures/vfx/` (looked up by bare name), entity textures named in Java literals, dimensions (player may be
  inside). Jukebox songs and new dimension blocks pick up their new ids on the next restart.
- A mod whose block fills a whole dimension (default_block) makes the live swap slow (27M blocks = 16 s pause).
- Tell the mod's chat what changed: `mc ask "<what changed>" --project "<Mod Name>"`.

## Creative tabs
No "Moddeus" catalog tab. A mod tab lists ALL its content: items, blocks, spawn eggs, every potion form (potion, splash,
lingering, tipped arrow; long_/strong_), enchanted books (`content/ModTabExtras`). Off hides it by tab, prefix and namespace.

## Live resources
A new namespace dir is invisible to Fabric's mod pack until rescanned: `live/PackNamespaces.refresh()` (run before every
content/asset/data reload) adds it and does ONE full resource reload (F3+T) for a new client namespace.
