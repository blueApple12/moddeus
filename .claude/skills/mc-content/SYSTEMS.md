# Game systems (tools/systems.mjs + live/Systems.java)

One command each, live in ~1 s, exportable (content JSON in `moddeus/content/`, runtime class in the jar). Add `--tab "<Mod Name>"` so Export owns it. Researched against Waystones, Elevator Mod, Create/Mekanism machines, Farmer's Delight crops, Lightman's Currency, mcMMO/Pufferfish's Skills, FTB Quests, Enhanced Celestials (blood moon), Supplementaries/Quark (secret doors).
Recipes, quests, discs, crops, ores, machines, economy/skill scoreboards and HUD lines are plumbing: use them. Pads, sky events and secret walls are behaviour SAMPLES (read `live/Systems.java`): fine for a minecrafty mod, but a crazy mod writes its own mechanic in Java (last section). No slash commands: players use blocks, items, keys and GUIs; code uses the Java calls below.

## Recipes (`mc recipe`)
- Types: `--shaped/--key`, `--shapeless`, `--smelt|--blast|--smoke|--campfire <in> [--xp --time]`, `--stonecut`, `--smithing "template,base,addition"`, `--brew "<base potion> + <reagent>"` with `--potion <id>` (any vanilla/mod potion) or `--result <any item>`; `--bottle splash_potion|lingering_potion`.
- Tags work everywhere: `#minecraft:planks`. `--pack <ModId>` writes into the world datapack `<ModId>-DP` (namespace = snake id) and reloads.
- Remove: `mc remove-recipe <id>`.

## Quests (`mc quest`)
- Each quest = one advancement in tab folder `quests` (`--tab` = other folder). First one: `--root [--background stone|<texture>]`; children: `--parent <id>`.
- Triggers (repeat = all needed): `obtain:<item|#tag>[*n]`, `kill:<entity>`, `enter:<dimension>`, `biome:<biome>`, `place:<block>`, `eat:<item>`, `craft:<recipe id>`, `tame[:entity]`, `breed[:entity]`, `manual` (grant it yourself: `advancement grant @s only moddeus:quests/<id>`).
- `--reward "xp:50,item:diamond*3,coins:10,skill:mining*20,cmd:say hi"` -> reward function `quest/<id>` + toast sound. `--frame goal|challenge`, `--hidden`, `--announce`.
- Remove: `mc quest <id> --remove`.

## Music discs (`mc disc`)
- `mc disc <id> --sound <file|URL> --title "Artist - Song" [--length s] [--comparator 1-15] [--color #hex]`: sound + disc item (drawn) + jukebox song (live into the dynamic registry, server + client; data file for later launches).
- Remove: `mc remove <id>` then `mc remove-sound <id>`.

## Functional blocks (`mc pad`), event-driven, no Block subclass
- `launch` `--power 1.6 --forward 1` (players fly in look direction, no fall damage unless `--fall-damage`; mobs/items too).
- `elevator` `--range 32`: place two+ in a column with 2 free blocks above each; jump = up, sneak = down.
- `teleport` (waystone): stepping on one discovers it (named after the biome); sneak on it = menu of discovered ones (ModGui), `--cycle` = go to the next without a menu. Any dimension.
- `conveyor` `--speed 0.15`: the block is placed facing away from the player (shape `facing`), moves players, mobs, items that way.
- `trap` `--damage 3 --fire 2 --effects "poison:1:60"`; `cmd` `--cmd "effect give @s speed 5 1; playsound ..."` (runs as whoever steps on it).
- Any pad also takes `--effects`, `--cmd`, `--sound`, `--cooldown`. `--block minecraft:slime_block` (or any block/#tag) turns an existing block into a pad; otherwise a block `<id>` with a drawn texture is made (`--color`, retexture with `mc pixels block <id>`/`<id>_side`).

## Crops, ores
- `mc crop tomato --stages 4 --color "#e33" --food 4,0.3 --count 1-3` -> `tomato_seeds` (vanilla CropBlock: farmland, light, bone meal, random ticks) + food `tomato` (or `--produce <existing item>`). Stage textures `tomato_seeds_stage0..N` (redraw with pixels block). `--food` = nutrition,saturation modifier; seeds go into villager/chicken/`c:seeds` tags, the produce composts. Cooking + feasts: FOOD.md.
- `mc ore ruby_ore --xp 3-7 --drop ruby --count 1-2 --color "#e0115f"` -> DropExperienceBlock, pickaxe, fortune + silk touch; makes the gem item if missing. World spawning: the structures/worldgen tool.

## Machines (`mc machine`)
- `--recipe "[n] <in> -> [n] <out> [ticks] [xp]"` (repeat), `--fuel` (coal/charcoal/blaze rod/lava/logs/any fuel item; `--fuels "moddeus:uranium=8000"`). Right-click = furnace-style GUI with progress arrow + flame (vanilla FurnaceMenu, own container per block). Runs while the chunk is loaded, saved in `<world>/moddeus/systems.json`, spills its items when broken. Shift-click only moves smeltable items into the input: drag the rest.

## Economy, shops
- `mc economy coins --name Coins --symbol "coins" --start 10 --earn "killed:zombie=2,mined:#minecraft:diamond_ores=10,fish=3"`: scoreboard `coins` (datapacks read/add it with vanilla `scoreboard players`). Java: `Systems.coins(p)`, `Systems.addCoins(p, n)` (negative n spends; false if too poor), `Systems.symbol()`. A pay/transfer is the mod's own code: `if (Systems.addCoins(from, -n)) Systems.addCoins(to, n);`.
- `mc shop <id> --item "diamond buy 50 sell 20" --item "3 bread buy 5"`: right-click its block (or `--block` an existing one, `--no-block`), or open it from the mod's code (an NPC, an item, a key): `Systems.openShop(p, id)`. GUI with icons/tooltips/balance; scripted trade: `Systems.trade(p, id, row, sell)` (returns a status line).

## Skills (mcMMO style)
- `mc skill mining --earn "mined:#minecraft:mineable/pickaxe=1" --base 50 --growth 1.15 --max 50 --attr "mining_efficiency:0.2,block_break_speed:0.02" --perk "10: give @s diamond 2"`.
- XP sources = vanilla stats, so anything the game counts works: `mined:<block|#tag>`, `killed:<entity|#tag>`, `crafted:<item>`, `used:<item>` (eat/use), `picked:<item>`, `custom:<stat>`, shortcuts `fish`, `breed`, `trade`, `walk` (per cm), `jump`, `enchant`.
- Scoreboards `sk_<id>` (xp) + `sk_<id>_lv`; level-up actionbar + sound + totem sparkle; XP bar above the hotbar on every gain; `--attr` amounts are per level. Java: `Systems.addSkillXp(p, id, n)`, `Systems.skillLevel(p, id)`. Perks that DO something (an ability unlocked at level 10) are the mod's Java (last section), not `--perk` commands.

## HUD panel (`mc hud`)
- `--line` with placeholders `{coins} {symbol} {score:obj} {skill:id} {skillxp:id} {day} {time} {x} {y} {z} {biome} {dim} {health} {food} {player} {sky}`; `--pos top_left|top_right|middle_left`, `--color`. Singleplayer (reads the integrated server).

## Sky events (`mc skyevent`)
- `--when night|day|thunder|manual --chance 0.15`: rolled at nightfall/dawn/storm start, ends at the next edge. `--tint "#a00010" --alpha 0.22` (screen tint + vignette), `--mob-effects "strength:1,speed:0"` (hostiles near players), `--player-effects`, `--spawn zombie --spawn-count 2 --spawn-every 200`, `--start/--end "cmds"`, `--weather thunder`, `--subtitle`, `--sound`. Java: `Systems.startSky(server, id)`, `Systems.stopSky(server)`, active id `Systems.sky` (null = none). Test: `mc game run 'Systems.startSky(server, "<id>");'` then stop it.

## Secret walls / hidden doors (`mc secret`)
- Right-click a block of it: the connected group (`--max 48`, within 12 blocks) vanishes for `--ticks 100` and comes back (waits if someone stands inside). `--key <item>` = locked (must hold it). `--block minecraft:bookshelf` = secret bookshelf door. Saved to disk while open.

## Removing test content
`mc remove <id>` (pads/machines/shops/secrets also delete their spec file), spec-only ones by file id: `mc remove <id>_skill|<id>_hud|<id>_sky|coins_economy`; scoreboards stay (`scoreboard objectives remove ...`).

## Mechanics in Java (write the mod's own)
In the mod's class (`content/<Id>.java`, skeleton GAME-RUN.md). Three different shapes:
```java
// Bounty streaks: kills within 5 s stack a multiplier (register ServerLivingEntityEvents.AFTER_DEATH.register(GoldMod::afterDeath))
static void afterDeath(LivingEntity dead, DamageSource src) {
  if (!enabled || !(src.getEntity() instanceof ServerPlayer p) || !(dead instanceof Enemy)) return;
  int streak = Combat.combo(p, "bounty", 100);
  Systems.addCoins(p, 2 * streak);
  if (streak % 5 == 0) WorldFeel.toast(p, "gold_mod:bounty_badge", streak + " streak!", "+" + 2 * streak + " " + Systems.symbol(), 0xFFE0B040);
}
```
```java
// Skill-gated ability on a key: radius, damage and cooldown scale with the skill level
static void quake(ServerPlayer p) {
  int lv = Systems.skillLevel(p, "geomancy");
  if (lv < 5 || !Combat.use(p, "Quake", 200 - lv * 4, 0)) return;
  double r = 2 + lv * 0.2; Vec3 c = p.position();
  Bosses.fx(c, new Vec3(0, 1, 0), null, Bosses.L("ring", "geo_mod_fault", "size", r, "duration", 15, "color", "#8a5a2a"));
  Quick.after(15, () -> Combat.blast(p, c, r, 4 + lv, "quake", 0.8));
  Systems.addSkillXp(p, "geomancy", 3);
}
```
```java
// Updraft block (instead of a launch pad): lifts players up to 6 blocks above it, weaker with height, no fall damage (in the mod's server tick)
for (ServerPlayer p : s.getPlayerList().getPlayers())
  for (int dy = 1; dy <= 6; dy++)
    if (Systems.is(p.level().getBlockState(p.blockPosition().below(dy)), "sky_mod:updraft")) { Combat.push(p, new Vec3(0, 0.09 - dy * 0.01, 0)); p.fallDistance = 0; break; }
```
**Make it unique to this mod:** [ ] its own currency name/symbol, shop block texture and GUI colors [ ] rewards and costs tuned to this mod's loop [ ] a mechanic that fits the theme (not a renamed pad) [ ] its own sounds + VFX textures [ ] two systems never share a look. Test on the stage: `mc game stage <ModId>` + `game run` (TESTING.md), never on the player.
