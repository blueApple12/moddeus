# Moddeus content roadmap (what chats can / can't make in one step)

Cost: S = <100 lines, M = a core class, L = needs restart-proof design or new rendering.
Plumbing (registering, textures, tags, recipes, packs) = one `mc` command. Behaviour (abilities, VFX, attacks, AI, sound design, model look) = Java written per mod on the engine APIs (GAME-RUN.md). Presets are samples, not features: grow engine APIs and guides, never more presets. No Moddeus slash commands.

## Exists
| Feature | Tool |
|---|---|
| Blocks, shapes, 3D cube models (`--cubes`), items, food, tools, weapons | `mc block/item` |
| Armor sets (+ worn texture) | `mc armor`, `pixels armor` |
| Armor set bonuses | `mc setbonus` (new) |
| Custom mobs on vanilla bodies | `mc mob`; AI/behaviour = Java (`Bosses.behave` samples, LIFE.md) |
| Bosses: boss bar, health phases, timed attacks, death | `mc boss` (JSON `"attack"`/`"death_attack"` specs) + the mod's own attacks in Java (`Bosses.job/fx`, BOSSES.md) |
| Loot: extra drops on mobs/blocks/chests/fishing | `mc drop` (new) |
| NPCs: dialogue, quest reward command, trade shop | `mc npc` (new) |
| Recipes: shaped, shapeless, smelting, stonecutting | `mc recipe` |
| VFX, particles, physics bodies, throwables (projectiles) | `mc particle`, `item --throws`; looks + motion in Java (`Vfx.play`, `Bosses.fx`, `Physics.spawn`, VFX-DESIGN.md) |
| Sounds from files/URLs | `mc sound` |
| Cutscenes, GUIs/HUDs, key binds, shaders, creative tabs | Java guides (CUTSCENES, GUI, TABS-KEYS, SHADERS) |
| Datapack mods (functions, advancements, predicates) | skill minecraft-datapack |
| Dimensions, 3D item models | other tracks (see their guides) |

## Missing (best next)
Owners now: effects/enchantments/potions, structures/worldgen, blocks + game systems (recipes, quests, discs, machines), dimensions, 3D models; boss/combat/AI work = engine APIs + guides, not new presets. Strike a row when its tool ships and add it to toolbox COMMAND INDEX.

| Gap | Why it matters | How | Cost |
|---|---|---|---|
| Custom status effects ("Frozen", "Bleeding") with icon | Every magic/combat mod has them | core `ModEffect extends MobEffect` + `LiveRegistry.register(MOB_EFFECT)`, icon `textures/mob_effect/`, `mc effect <id> --color --attr --every` | M |
| Smithing recipes / blasting / smoking / campfire | upgrade paths (netherite-style) | extend `recipe()` in mc.mjs with `--smithing template,base,addition`, `--blast` | S |
| Advancements / quest lines | progression + toasts | JSON (`data/moddeus/advancement`), `mc advancement <id> --icon --title --trigger has:<item>|kill:<mob>`, full reload | S |
| Custom enchantments | very common request | data-driven enchantment JSON is a dynamic registry: only loads at world start, so live needs a Java path (event-based "enchant" component + lore) | M/L |
| Music discs | common fun request | jukebox_song is a dynamic registry (world start only); live path = item that plays a sound on a jukebox via UseBlock event | M |
| Structures placed live + natural generation | dungeons, towers | `/place template` works live with `data/<ns>/structure/*.nbt`; add `mc structure save <name> <from> <to>` (StructureTemplate) + `place`. Natural gen = worldgen (needs world restart) | M |
| Natural spawn rules for custom mobs | mobs appear without eggs | Fabric BiomeModifications only at startup: live = own spawner tick (`every`) near players by biome | M |
| Custom projectiles with their own model | arrows/spells | today physics throwables; a shared core projectile entity (data-driven speed/gravity/hit VFX) | M |
| Villager professions / wandering trader trades | trading mods | `mc npc` covers custom traders; real professions need POI registry (startup) | L |
| Custom fluids, crops/saplings, workstations with block entities | farming/tech mods | block entities need new BlockEntityType (registry, restart-safe design) | L |
