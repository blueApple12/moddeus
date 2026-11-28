# Structures, terrain, worldgen (Moddeus)

One command each, live, with undo. CLI: `node tools/mc.mjs struct|terrain|worldgen ...` (tools/structures.mjs). Core: `live/Structures.java` (in the export runtime).
From Java/snippets: `Structures.run("tower size=9 palette=nether")` (server thread), same syntax as the CLI.

## Builds (`struct <kind> [k=v ...]`)
Kinds: tower, castle, dungeon, ruins, temple (pyramid), house, bridge, arena, statue, portal, maze, chest, spawner.
- Common: `size= height= palette= decay=0..1 seed= at=front|look|here|x,y,z (~ ok) facing=north|south|east|west dist=`. Default: in front of the player on the ground, entrance facing them.
- Palettes (`struct palettes`): stone deepslate sandstone nether end quartz prismarine oak spruce blackstone mossy dungeon hedge ice gold, or `palette=<block id>`. Override one role: `wall= roof= floor= pillar= trim= window= light= fence= door= path= base= stairs= slab=`.
- tower: `size` (diameter) `height` `roof=cone|flat`; floors every 5, ladder, windows, lights.
- castle: `size=27 height=7`: walls + walkway + crenels, 4 corner towers, gatehouse, keep with loot chest.
- dungeon: `rooms=3 size=9 height=5 depth=6 mob=zombie,skeleton,spider loot=<table> bossloot=<table>`: underground rooms + corridors, spawners, loot chests, boss room, staircase up to the surface.
- ruins (`decay` default 0.4), temple (`size=21`, hidden chest under the centre, roof shrine), house (`width depth height`, gable stairs roof, door, furnace/crafting/chest, path, chimney), bridge (`length width`, pillars to the ground, railings, lanterns), arena (`size`=radius, tiered seats, 4 gates), statue (`size` = height, `pose=stand|raise|wide`, `block= eyes=`), portal (`style=nether lit=true width height block=obsidian` | `style=ring size fill=<block>`), maze (`size` cells, `height`, centre chest), chest (`loot=`), spawner (`mob=`).

## Terrain (`terrain <op> [k=v]`), WorldEdit/Axiom-style brushes
sphere/cylinder (`r= block= hollow height=`), crater (`r depth`: scorched bowl + rim), mountain/hill (`r height snowline=0.7`), river (`length width depth`, meanders along the look direction), flatten (`r y= square block=`), noise (`r amp scale`), smooth (`r`), overlay (`r block`), replace (`r from to`), drain (`r`), island (`r depth`, floating, use `at=~,~20,~`).

## Selection, schematics, templates
`struct pos1` / `pos2` (block you look at; `at=x,y,z`), `struct sel set|walls|outline|hollow <block>`, `sel replace <a> <b>`, `sel copy` + `struct paste [rotate=90|180|270 mirror=lr|fb at=]`.
`struct sel save <name> [--datapack <Id>]` = vanilla structure NBT (world/generated/<ns>/structure; structure blocks and `/place template` read it; --datapack copies it into <Id>-DP). `struct place <ns:name>` pastes any template (vanilla ones too: `minecraft:igloo/top`). `struct list [filter]`.
`struct feature <ns:feature>` / `placed:<ns:id>` places a vanilla/datapack feature (trees, geodes...) with undo. `struct vanilla <radius> <command>` runs ANY command (/place structure, /fill, /clone) with undo.

## Undo
Every edit records replaced blocks + block-entity NBT: `struct undo [n]`, `struct redo`, `struct ops` (history, 64 ops / 4M blocks, in memory until the world closes).

## Worldgen (generates in NEW chunks, live, no world reload)
`worldgen ore|tree|plant|lake|geode|rock|spring|structure|feature <id> ...` writes a rule and injects it into the live biomes (Structures.sync). Ids: prefix with your mod's snake id.
- Java mod (default): rule = `src/main/resources/moddeus/worldgen/<id>.json` (ships in exports).
- Datapack mod: `--datapack <Id>` also writes vanilla files into <Id>-DP: feature + placed_feature + biome overrides (or for `--template`: jigsaw structure + template_pool + structure_set + the NBT). Vanilla uses them after the world is reopened / without Moddeus; Moddeus applies the rule live now and skips it once vanilla loaded it.
- `--biomes "#minecraft:is_overworld,minecraft:plains"` (default overworld; nether/end ores default to their dimension), `--step` = vanilla decoration step.
- `worldgen structure <id> --build tower --args "size=7 palette=mossy decay=0.3" --chance 48` (1 per N chunks; builds stay inside the 3x3 chunk area, keep size <= 40; skips water unless --water). `--template ns:name` uses a saved template.
- `worldgen test <id>` = one instance next to the player (undo with struct undo). `worldgen list|sync|remove <id> [--datapack Id]`.
- Existing chunks never change (vanilla too): explore new land to see it.
- Custom biomes: copy a vanilla biome JSON into the pack (data/<ns>/worldgen/biome/) and edit it; usable by custom dimensions (mc dimension --biome).

## Gotchas
- Test builds/terrain 150+ blocks from the player (`at=x,y,z`, forceload) and undo right after. `worldgen test` places one instance without generating chunks; never test a rule by generating chunks with a junk block (decorated neighbour chunks keep it).
- Quote args with `~` in Git Bash (`at="~,~20,~"`), else the shell turns `~` into the home path.
- Builders never place on the player: default spot = `dist` (footprint + 4) ahead. Test, `game shot`, then `struct undo`.
- Undo is per op and LIFO; it restores chests/spawners with their NBT. It does not remove entities a template spawned.
