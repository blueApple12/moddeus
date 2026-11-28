# GEAR: armor, tools, blocks and other items

One command per kind (`mc` = `node tools/mc.mjs`), live, exported with the mod. Code: `tools/gear.mjs`, `live/Gear.java`, `client/GearClient.java`.
Vanilla 26.3 builds all of these from **data components** (`tool`, `equippable`, `glider`, `death_protection`, `repairable`, `enchantable`, `written_book_content`, `block_transformer`) plus **item tags**. Most "my custom X is broken" bugs are a missing tag or component, so the commands below add them for you.

Every kind: draw its textures (`mc pixels`, PIXEL-ART.md), put it in the mod's tab (`--tab "<Mod>"`), give it a recipe, test it in game, and tell the player the controls.

## Style: crazy vs minecrafty
- **Crazy mod** (bosses, gods, cosmic gear): worn armor gets a REAL 3D armor model (`mc worn`): horns, spikes, floating shards, shoulder plates, glowing cores (`glow` cubes). A recolored vanilla layer is not enough. Held tools get their own 3D cube model (`mc item <id> --cubes "..." --tex "..."`, MODELS.md; `--model <shape>` = sample only, read `tools/models.mjs`) and abilities written in the mod's Java (last section).
- **Minecrafty mod** (new ore tier, decor, survival): stay close to vanilla silhouettes and proportions, a 16x16 flat icon, vanilla-like stats. Restraint is the quality here.
- Decide which one the player wants before drawing. When unsure, a crazy mod gets crazy gear.

## Tool sets (sword, pickaxe, axe, shovel, hoe, paxel, hammer, excavator)
`mc tools ruby --of moddeus:ruby --tier diamond --durability 1800 --mining-speed 9 --enchant 18 [--extra paxel,hammer,excavator] [--kinds pickaxe,axe]`
- Writes `<set>_<kind>` items with a custom `ToolMaterial`: `tier` sets what it can mine (incorrect_for_X_tool tag), then durability / mining speed / damage bonus / enchantability. Repair tag `moddeus:repairs/<set>` (anvil), standard recipes with `--handle` (default stick).
- Tags: `minecraft:swords|pickaxes|axes|shovels|hoes`. The enchanting table, anvil books (Efficiency, Fortune, Sharpness, Unbreaking, Mending) and other "is a pickaxe" checks read these tags. Without them a custom pickaxe can't be enchanted. `mc item --tool` adds them too now.
- Axes strip logs, shovels make paths, hoes till: vanilla does that with the `block_transformer` component, which `props.axe/shovel/hoe` set. The paxel uses `#moddeus:mineable/paxel` (pickaxe + axe + shovel blocks) and strips like an axe.
- Hammer = pickaxe, excavator = shovel, both mine a 3x3 face (an `itemfx` `mine area:1` spec `<id>_fx`). Remove a hammer with `mc remove <id> <id>_fx`. A vein miner or tree feller: `mc itemfx <axe> --mine "vein:64"`.
- JSON: `"tool": {"type": "pickaxe", "tier": "diamond", "durability": 1800, "mining_speed": 9, "damage_bonus": 4, "enchant": 18, "repair": "moddeus:repairs/ruby", "damage": 1, "speed": -2.8}` (`damage`/`speed` = attack damage and attack speed).
- Make it amazing: [ ] diagonal 16x16 icon, handle bottom-left (`mc template item/diamond_pickaxe`) or a 3D model for a crazy mod [ ] mining speed above the previous tier, durability that matches the effort [ ] recipe uses the set's ingredient [ ] enchant it on a table to check the tags [ ] strip a log with the axe.
- Pitfalls: stats are fixed once registered: `mc remove <id>` before changing them. Hammers need `hasCorrectToolForDrops`, so they only take blocks their tier can drop.

## Armor sets
`mc armor ruby --material diamond --defense "3,8,6,3" --toughness 2.5 --knockback 0.05 --enchant 15 --durability 35 --of moddeus:ruby`
- `--defense` = helmet, chestplate, leggings, boots armor points (vanilla diamond 3,8,6,3; netherite 3,8,6,3 + toughness 3 + knockback 0.1). `--durability` multiplies the vanilla per-piece base (helmet 11, chest 16, legs 15, boots 13; diamond = 33). `--equip-sound` = a sound id.
- Tags added: `head_armor|chest_armor|leg_armor|foot_armor` (Protection, Thorns, Respiration, Feather Falling... apply) and `trimmable_armor` (smithing-table trims render on it). Repair tag + the four recipes from `--of`.
- Worn look: `mc pixels armor <set>` (64x32, outer layer) and `mc pixels armor_legs <set>` (leggings), shape guide `mc template entity/equipment/humanoid/diamond`. Set bonus: `mc setbonus <set> --effects .. --attr ..` (GAMEPLAY.md).
- JSON: `"armor": {"type": "helmet", "material": "diamond", "asset": "moddeus:ruby", "defense": 3, "toughness": 2.5, "knockback": 0.05, "enchant": 15, "durability": 35, "repair": "moddeus:repairs/ruby"}`.
- Make it amazing: [ ] both worn layers drawn (no missing texture on the legs) [ ] check front, back, crouch, swim (`mc game studio armor <set> --pose stand,crouch,swim`) [ ] icons match the worn colors [ ] a set bonus [ ] a 3D armor model for a crazy mod (next section).

## Worn 3D armor (custom armor MODEL)
**Armor = replace the armor model, never overlay geometry.** Hand-drawn world geometry on the player (VFX-style boxes that guess the pose) drifts when sneaking, swimming, crawling, riding or gliding. `mc worn` gives the armor piece its own model instead: client/GearArmor (Fabric `ArmorRenderer`) draws the normal textured armor layer, then a HumanoidModel whose head/body/arms/legs carry the extra cubes as CHILD parts. It poses itself from the render state exactly like vanilla armor, so every pose follows.

`mc worn abyssal_voidwalker_helmet --part "head: 3 11 7 5 15 9 horn rz=25; 11 11 7 13 15 9 horn rz=-25; 5 8 3.4 11 8.7 3.8 glow; 3 17.3 3 13 17.8 4 halo glow pivot=8,17.5,8 spin=60" --colors "horn=#1d1630,glow=#b080ff,halo=#e6d8ff"`
- Bones: `head body arms legs boots` (pairs = the right side as written + a mirrored left side) or `right_arm left_arm right_leg left_leg`. Several `--part` per piece (leggings: `legs` + a `body` belt).
- Grid (px): the bone's own box sits in the CENTER of 0..16: head 4..12 on every axis; body x4..12 y2..14 z6..10; arm/leg x6..10 y2..14 z6..10. Front = north (-z), up = +y. Armor plates sit 0.5-1 px outside the box; horns/spikes/halos go further out.
- Tokens per cube: a color key (`--colors key=#hex`), `rx= ry= rz=` (deg), `glow` (full-bright, emissive pass), `spin=<deg/s>` and `bob=<px>` (real-clock animation: floating shards, a turning halo; keeps moving during /tick freeze), `pivot=x,y,z` (rotation/spin centre in grid px: give a ring's pieces one shared pivot).
- The tool writes the box-UV sheet `textures/entity/equipment/parts/<item>.png` (per-face shading, dark edges) and the content JSON `"worn"` cubes. Repaint the sheet by hand for detail; re-running `mc worn` regenerates it.
- Works on every humanoid wearer (player, zombie, armor stand, mannequin). A NEW worn item needs a renderer rebuild: done by itself on the next client tick (one short hitch). The JSON of an existing item reloads live.
- Make it amazing: [ ] the silhouette changes (horns, pauldrons, crest, tail plates), readable from 20 blocks [ ] 1-2 glowing accents per piece, not everything glowing [ ] one or two animated parts (halo spin, shard bob) [ ] check F5 front + back while walking, sneaking, swimming, crawling and gliding [ ] nothing clips through the face or the held item.
- Pitfalls: big arm parts clip the held item (keep them on the shoulder/outer side). Not shown in first person (vanilla hides armor there too). Baby mobs use the adult model.

## Gliders (elytra-like)
`mc glider dragon_wings --durability 600 --of moddeus:dragon_scale`, then `mc pixels wings dragon_wings` (64x32, `mc template entity/equipment/wings/elytra`) + icons `<id>` and `<id>_broken`.
- Components: `glider`, `equippable` (chest, asset_id, elytra equip sound, `damage_on_hurt:false`), `repairable`, `enchantable`. Durability drains 1 per second of flight; at 1 left it stops gliding and the icon shows the broken model (`minecraft:broken` condition). Tags: enchantable/durability + enchantable/equippable (Unbreaking, Mending).
- Make it amazing: [ ] wings texture with membrane/feather detail and the use_player_texture cape fallback [ ] broken icon [ ] repair item that fits [ ] test: jump off a hill, glide, fire a rocket. For a crazy mod add the mod's own boost ability + trail VFX in Java (last section).

## Totems (death protection)
`mc totem phoenix_totem --effects "regeneration:45:1,absorption:5:1,fire_resistance:40" [--keep-effects] [--teleport 16] [--sound minecraft:entity.blaze.shoot]`
- Component `death_protection` with death_effects: clear_all_effects (unless `--keep-effects`), apply_effects, teleport_randomly, play_sound. Works from either hand; vanilla plays the totem pop animation with THIS item's model.
- Make it amazing: [ ] 16x16 icon readable in the big pop animation [ ] effects that match the theme (phoenix = fire res + regen) [ ] test: `mc game run 'player.hurt(...)'` or `/damage` while holding it in survival.

## Books, scrolls, journals (written content)
`mc book old_journal --title "Old Journal" --author "Lost Miner" --page "Day 1:\nThe caves sing." --page "Day 2: ..." [--glint]`
- A real `WrittenBookItem` (right-click opens the book screen) with `written_book_content` (title max 32 chars, pages as text). Stacks to 16.
- Make it amazing: [ ] a short hook on page 1 [ ] lore that points at a real place/boss in the mod [ ] scroll/tome texture, not a book clone, when it is a scroll.
- Rich text pages / guide books with pictures: a ModGui screen (GUI.md) opened on right-click is better than a vanilla book.

## Keys and locked containers
`mc key crypt_key` then lock a chest/barrel: `/data merge block X Y Z {lock:{items:"moddeus:crypt_key"}}`. Only a player holding that item opens it. A named key instead: `{lock:{components:{"minecraft:custom_name":"Gate Key"}}}`.
- Secret doors with a key: `mc secret <id> --key <item>` (SYSTEMS.md). Treasure maps: vanilla `exploration_map` loot function in a loot table.

## Fishing rods, shears and other vanilla-behaviour items (reskin)
`mc reskin coral_rod --base fishing_rod --of moddeus:coral --durability 128` (also `--base shears`, or any vanilla item).
- Vanilla hard-codes `Items.FISHING_ROD` (the bobber despawns for any other item) and `Items.SHEARS` (sheep, beehives, pumpkins, tripwire). So the best custom rod/shears IS the vanilla item with `item_model` + `item_name` (+ `max_damage`): every behaviour works, incl. the line, Lure/Luck of the Sea and Mending. The rod gets a cast model (`minecraft:fishing_rod/cast`). Draw `<id>` and `<id>_cast`.
- Obtain: the recipe (`--of`) or the printed `/give`. Not a separate registry item, so it isn't in a creative tab.

## Lanterns and torches
`mc light jade_lantern --kind lantern` | `mc light soul_torch2 --kind torch --particle soul_fire_flame --light 10`
- Lantern = `LanternBlock` (hangs under blocks: `hanging` state, waterloggable), texture layout like `mc template block/lantern`, light 15. Torch = `TorchBlock` + `<id>_wall` (`WallTorchBlock`) + one item that places either (`StandingAndWallBlockItem`), light 14, flame particle of your choice. Remove both: `mc remove <id> <id>_wall`.
- Make it amazing: [ ] glowing pixels only where the flame is [ ] a matching particle (soul, copper, custom) [ ] place it on a wall, a floor and under a ceiling, screenshot at night.

## Decorative blocks (every shape)
`mc block <id> --texture .. --shape slab|stairs|wall|fence|fence_gate|door|trapdoor|button|pressure_plate|pane|pillar|carpet|glass --mine pickaxe`; any model: `--cubes` (MODELS / mc-content).
- A family (Macaw's/Chipped style): one base block + its stairs, slab, wall from the same texture, plus 2-3 variants (bricks, polished, chiseled) and stonecutter recipes (`mc recipe <id> --stonecut <base>`).
- Glass keeps vanilla's `force_translucent` texture flag (fixed). Doors drop once (lower half), slabs drop 2 when double.
- Make it amazing: [ ] tiling texture with low contrast [ ] all shapes share one palette [ ] stonecutter + crafting recipes [ ] build a small wall/house with them and screenshot.

## Containers
`mc container ancient_barrel --sound wood` then draw `<id>_top`, `<id>_top_open`, `<id>_side`, `<id>_bottom` (`mc template block/barrel_side`).
- A `BarrelBlock` (faces the player, opens with the lid texture, 27 slots, comparator output, keeps its anvil name). Our block is added to the vanilla barrel block entity's valid blocks. GUI title = "Barrel" unless renamed in an anvil.
- Bigger inventories or custom GUIs: `mc machine` (SYSTEMS.md) or ModGui.

## Ores and storage blocks
`mc ore <id> --xp 3-7 --drop <gem>` (SYSTEMS.md), world spawning: `mc worldgen ore` (STRUCTURES.md). Storage: `mc storage ruby_block --of moddeus:ruby [--beacon]` = block + 9x / unpack recipes (+ beacon base tag), needs an iron pickaxe.

## Spawn eggs, music discs, banners
- Spawn eggs come with `mc mob` (draw `<id>_spawn_egg`). Discs: `mc disc` (SYSTEMS.md).
- Banner patterns are a world-load registry (`banner_pattern`): not live. Put `data/<ns>/banner_pattern/<id>.json` + textures `entity/banner/<id>.png` / `entity/shield/<id>.png` in the mod and restart.

## Abilities on gear (Java, written per mod)
The commands above give vanilla behaviour (mining, armor points, gliding, death protection). What the gear DOES beyond that is the mod's class (`content/<Id>.java`, skeleton GAME-RUN.md); `mc itemfx` / `mc setbonus` specs are samples (read `live/Effects.java`). Three different shapes:
```java
// Full-set bonus with a mechanic: Frostwalker armor turns a hard landing into a rime burst that slows everything around
static Map<UUID, Double> FALL = new HashMap<>();   // null-check / re-create in hook()
static void tick(MinecraftServer s) {
  for (ServerPlayer p : s.getPlayerList().getPlayers()) {
    boolean full = Stream.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)
        .allMatch(sl -> BuiltInRegistries.ITEM.getKey(p.getItemBySlot(sl).getItem()).getPath().startsWith("frostwalker_"));
    if (!full) continue;
    if (!p.onGround()) { FALL.put(p.getUUID(), p.fallDistance); continue; }   // vanilla resets fallDistance on landing
    double f = FALL.getOrDefault(p.getUUID(), 0.0); FALL.remove(p.getUUID());
    if (f < 4) continue;
    double r = 2 + f * 0.3;
    Bosses.fx(p.position(), new Vec3(0, 1, 0), null, Bosses.L("ring", "frost_mod_rime", "size", 0.5, "size_end", r, "duration", 18, "color", "#bfefff", "alpha_end", 0));
    for (LivingEntity e : Combat.enemies(p, p.position(), r)) { e.setTicksFrozen(200); e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 80, 2), p); }
  }
}
```
```java
// Tool active: sneak-use the Dowsing Pick pings the nearest ore within 12 blocks with a short glowing marker
if (p.isShiftKeyDown() && Combat.use(p, "Dowse", 100, 0)) {
  BlockPos best = BlockPos.betweenClosedStream(p.blockPosition().offset(-12, -12, -12), p.blockPosition().offset(12, 12, 12))
      .filter(b -> p.level().getBlockState(b).is(BlockTags.GOLD_ORES)).map(BlockPos::immutable).min(Comparator.comparingDouble(b -> b.distSqr(p.blockPosition()))).orElse(null);
  if (best != null) { var mark = Combat.display((ServerLevel) p.level(), Vec3.atCenterOf(best), "ore_mod:dowse_marker", 0.6f, true);
    Quick.after(60, () -> { if (mark != null) mark.discard(); }); }                    // no lingering marker
}
```
```java
// Glider boost on a key: a short burst along the look vector while gliding, charged by distance flown
static void boost(ServerPlayer p) {
  if (!p.isFallFlying() || !Combat.use(p, "Updraft Burst", 60, 0)) return;
  Combat.push(p, p.getLookAngle().scale(1.2).add(0, 0.3, 0));
}
```
**Make it unique to this mod:** [ ] the gear's own silhouette (worn cubes / held `--cubes`), palette and glow accents [ ] an ability that follows from what the gear is (frost armor freezes, a dowsing pick finds) [ ] its own timing and cooldown [ ] its own sound + VFX look, never shared between two pieces [ ] tell the player the controls in one line. Test on the stage (`mc game stage <ModId>` + `game run`, `s.hold(..).use()` / `s.sneakUse()`, TESTING.md), never on the player.
