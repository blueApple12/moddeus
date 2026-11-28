# Gameplay systems: boss, setbonus, drop, npc

One command each (`node tools/mc.mjs <cmd> <id> ...`, `tools/extra.mjs`), data-driven (`src/main/resources/moddeus/content/<id>.json`), live in ~1 s, run by core `content/Gameplay.java`, exported with the mod (put `--tab "<Mod Name>"` on each so Export owns it). Remove: `mc remove <id>`. These are PLUMBING: lines in `--phase/--every/--death/--on/--cmd` are vanilla commands separated by `;` (no leading /), run with op rights, output suppressed. Anything that should look or play like THIS mod (attacks, set powers, quest logic) is the mod's own Java (`content/<Id>*.java`), which reads the state these systems keep.

## boss: boss bar + phases + timers
The body comes first: a cube-model mob with its own model file (`mc mob <id> --model ...`, LIFE.md "Bosses"). Never a reskinned zombie.
```bash
mc boss lich_king --mob lich_mod:lich --name "The Lich King" --color purple --overlay 10 --health 400 --darken --music \
  --phase "0.5 red Lich King, Unbound: effect give @s strength 9999 1; playsound lich_mod:unbound hostile @a ~ ~ ~ 2" \
  --phase "0.2 white Last Stand: attribute @s minecraft:scale base set 2; weather thunder" \
  --death "attack death loot=minecraft:chests/end_city_treasure xp=300; summon item ~ ~ ~ {Item:{id:\"lich_mod:lich_crown\",count:1}}" --tab "Lich Mod"
```
- `--spawn` (boss, npc) = `Quick.spawn("<id>")` next to the player: only when the player asked for one; tests use the stage.
- Applies to EVERY `--mob` entity (a custom `mc mob` type is the normal case). `--tagged` = only bosses from `--spawn` / `Quick.spawn("<id>")` (use with vanilla types). Any entity tagged `moddeus_boss_<id>` is that boss.
- `--phase "<at> [color] [Bar Name]: lines"`: fires once when health <= at (0-1, or 50 = 50%). Phase index = order given (0, 1, ...), stored as entity tag `moddeus_phase_<i>` (survives reloads/saves).
- `--every "<ticks>[@phase]: lines"`: repeats while a player is within `--radius` (48); `@1` = only after phase 1 fired.
- Lines run as + at the boss (`@s` boss, `@p` nearest player, `^ ^ ^6` ahead). A line `attack <spec>` becomes a JSON `"attack"` entry (`"death_attack"` for --death) = `Bosses.attack(boss, spec)`: engine SAMPLES (BOSSES.md); use them for intro/music/lock/death, not as the fight.
- The fight: the mod's tick loops `Gameplay.bars` (UUID -> `Bar{ev, e, id}`), checks `moddeus_phase_<i>` tags and runs its own attacks (worked driver in BOSSES.md).

## setbonus: full armor set bonus
```bash
mc armor frost --material diamond --tab "Frost Mod"
mc setbonus frost --name "Frost Walker set: frozen steps + slow aura" --effects "speed:1,fire_resistance" \
  --attr "max_health:8,armor_toughness:2" --every "10: execute at @s run fill ~-2 ~-1 ~-2 ~2 ~-1 ~2 frosted_ice replace water" \
  --on "playsound block.amethyst_block.chime player @s" --tab "Frost Mod"
```
- `--pieces` defaults to `<ns>:<id>_helmet/_chestplate/_leggings/_boots` that exist; any items work (`--pieces diamond_helmet,frost_mod:frost_boots`).
- Effects (`id:amplifier`) refresh every 10 ticks, hidden particles, removed when a piece comes off. Attributes = ADD_VALUE modifiers `moddeus:set_<id>`. `--every` runs as/at the player.
- A set POWER (an ability, an aura with its own look) = Java: in your tick, check the four slots (`p.getItemBySlot(EquipmentSlot.HEAD)`...) and run it; key-triggered: `Quick.key` (example: Voidwalker set in `content/AbyssalAscension.java`).

## drop: extra loot (mobs, blocks, chests)
```bash
mc drop zombie_fang --from zombie --item frost_mod:fang --count 1-2 --chance 0.3 --looting --player
mc drop ruby_ore_bonus --from block:frost_mod:ruby_ore --item frost_mod:ruby_shard --count 0-2
mc drop dungeon_relic --from chest:simple_dungeon --item frost_mod:relic --chance 0.15
```
- Adds a pool; vanilla drops stay. `--from`: bare name = mob, `block:`, `chest:`, or a full table id (`minecraft:gameplay/fishing`). `--pool '<json>'` = any vanilla 26.3 pool (keys `modifier`/`condition`, not functions/conditions).
- A data reload runs by itself (~0.3 s). Chests roll loot when first opened: test with a fresh chest on your stage (`Stage.of("<ModId>").place(0, 0, 3, "chest{LootTable:\"minecraft:chests/simple_dungeon\"}")`).

## npc: dialogue + trades
```bash
mc npc merlin --name "Merlin" --profession librarian --line "Ah, a traveller." --line "The Lich stirs in the north." \
  --trade "5 emerald + book -> lich_mod:spell_tome" --trade "lich_mod:fang*8 -> 2 diamond" --cmd "give @s lich_mod:map_scrap"
mc npc ghost --entity mannequin --skin lich_mod:entity/ghost_skin --name "Lost Knight" --line "..."
```
- Right-click: next dialogue line in chat; after the last line `--cmd` runs as the player (quest reward/start), later clicks open the shop (if any trades). Trades: `N item [+ N item] -> N item`, unlimited stock (`--json '{"trades":[{...,"uses":3}]}'` to limit).
- `--entity villager` (default; NoAI, invulnerable, silent) | `mannequin` (`--skin` texture drawn with pixels) | any entity id. Spawn more: `game run 'dev.moddeus.live.Quick.spawn("merlin");'`. NPCs are tagged `moddeus_npc_<id>`; delete one with `kill @e[tag=moddeus_npc_<id>]`.
- Real quests (steps, conditions, a reward with a moment) = Java reading the NPC tag + the player's state; dialogue lines are the plumbing.

## Test (never on the player)
`mc game stage <ModId>`, then `game run`: `Stage s = Stage.of("<ModId>"); var b = s.spawnDef("lich_king", 0, 0, 8);` (bosses and NPCs by content id), `game stage <ModId> wait 60`, `game stage <ModId> clear`.
