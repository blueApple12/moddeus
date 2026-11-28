# Dimensions (both kinds, loaded LIVE)

Vanilla reads `data/<ns>/dimension/*.json` only when a world loads. Moddeus also loads them live: every datapack reload
(`game cmd reload`, a data/ save, /datapack enable) runs `live/Dimensions.syncData`, which creates the missing
ServerLevels (and registers new dimension_types on server + client) and unloads ones whose file is gone (pack Off).
Players inside a removed dimension are sent back where they came from. Next world load, vanilla loads them itself.

## Command (fast path)
```
node tools/mc.mjs dimension <id> --kind datapack|java [--mod <Id>] --preset void|flat|islands|caves|noise
   [--block minecraft:end_stone] [--fluid minecraft:lava] [--biome minecraft:cherry_grove]
   [--layers "minecraft:bedrock,minecraft:dirt*3,minecraft:grass_block"]     (flat)
   [--sky "#ff70c0"] [--fog "#402040"] [--clouds "#ffffffff"|none] [--ambient 0.3] [--skybox overworld|end|none]
   [--min-y -64] [--height 384] [--portal minecraft:crying_obsidian] [--no-live]
node tools/mc.mjs dimension list | go <ns:id> | back | remove <id> [--kind ..] [--mod ..]
```
- Presets: void = the_void biome + vanilla stone start platform; flat = layers; islands = floating_islands noise; caves = caves
  noise + ceiling; noise = overworld terrain (all biomes unless --biome). --block/--fluid copy the vanilla noise settings
  inline (tools/library/dimension/) with your blocks.
- datapack -> `run/saves/<world>/datapacks/<Id>-DP/data/<ns>/{dimension,dimension_type}/<id>.json`, functions
  `<ns>:<id>/go` (stores where you were, spreadplayers near 0,0) and `<ns>:<id>/back` (exact position + look), --portal =
  sneak on that block to travel / come back (tick tag). Lines are added to uninstall.mcfunction (marked, removed by remove).
- java -> `src/main/resources/data/moddeus/{dimension,dimension_type}/<snake_id>_<id>.json` (owned by the mod for Export;
  the exported jar loads it at world load like any mod data).
- Sky/fog/clouds are 26.3 environment attributes in the dimension_type (`minecraft:visual/sky_color` ...): edit the JSON for more
  (`minecraft:visual/sky_light_color`, `audio/background_music`, `gameplay/bed_rule`). Re-run with new flags = new look after
  `dimension remove` (a loaded level keeps its type until removed).

## Java (live/Dimensions, Quick)
```java
ServerLevel l = Dimensions.create("moddeus:my_realm", json);                      // same JSON as a dimension file; inline "type" ok
ServerLevel l = Dimensions.create(Identifier.parse("moddeus:x"), Dimensions.type("minecraft:overworld"), myChunkGenerator);
Dimensions.go(player, "moddeus:my_realm");  Dimensions.back(player);             // Quick: visit("moddeus:my_realm"), back()
Dimensions.remove("moddeus:my_realm", deleteFiles);                               // players moved out, saved, closed
Dimensions.get(id) / list()
```
- Server thread only (game run, events, ticks are). Before the server starts, create() queues and runs at SERVER_STARTED,
  so a mod's @OnReload may call it at launch. Idempotent: an existing level is returned.
- In a mod's @OnUnload: `Dimensions.remove(id, false)` (keeps the chunks) if the dimension belongs to that mod.

## 26.3 worldgen JSON gotchas (inline noise settings)
- Block states are plain strings (`"result_state": "ns:block"`, `default_block`), not `{"Name": ...}`.
- `y_clamped_gradient` is gone: `{"type":"minecraft:gradient","axis":"y","from_coordinate","to_coordinate","from_value","to_value"}` (clamped).
- `interpolated` needs `cell_size_xz`/`cell_size_y`; `abs`/`clamp` take `input` (not `argument`); `add`/`mul` take `left`/`right`.
- The `islands` preset (vanilla floating_islands density) is nearly solid: one huge grassy continent. Real archipelago (Abyssal Ascension):
  `interpolated(noise(gravel, xz 4, y 6)*2 - 1.1 + y fades) - 4*clamp(1 - 8*|noise(ice, xz 5, y 0)|, 0, 1)` (the second term carves void cracks
  between islands) + custom `material_rule` (stone_depth floor/ceiling + noise_threshold). Tune headless: create a preview dimension, generate
  chunks with `Quick.every` (1/tick), measure coverage/blob count via getBlockState; no player visit needed.
- A loaded level keeps its generator: `Dimensions.remove(id, true)` (deletes chunks) then `create` to regenerate.

## Limits
- New dimension_types sync to the singleplayer client only (same JVM). LAN/dedicated players joined before the type
  existed need a rejoin; vanilla types (overworld, the_nether, the_end, overworld_caves) always work.
- Custom biomes / noise settings / density functions in a datapack are NOT loaded live (worldgen registries): use vanilla
  biomes or inline settings (--block/--fluid); new biome files work after a world reload.
- go/back store one return point per player (Java) / one per dimension (datapack storage, singleplayer).
