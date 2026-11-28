---
name: mc-content
description: FAST PATH for Moddeus. Use FIRST whenever the user wants a new or changed block, item, food, texture or sound in the running Minecraft world, or wants an image/sound from the web put into the game. One command, about 3 seconds, no Java, live in-world.
---

# mc-content: fast content in the live world

The game is running. `tools/mc.mjs` writes everything a block/item needs (content JSON, blockstate, models, item definition, lang, loot table, texture, sound), then runs `gradlew processResources`. The mod registers it **in-world** and reloads resources within about 1s. New entries go straight into the player's inventory.

**Plumbing vs. design.** These commands are plumbing (register, name, tab, texture, sound file, recipe, cube model): use them freely. What the player feels (abilities, VFX motion, boss attacks, mob AI, sound design, the look of a model) is written per mod in Java (`content/<Id>*.java`) with the engine APIs. Presets (`--preset`, `vfx-tex --shape`, `--model <shape>`, mob model presets, `synth --preset`, Bosses/Combat sample names) print `preset = sample only`: read their source to learn, then write this mod's own. Moddeus has no slash commands: test with `mc game run '<java>'` on your Stage.

Run from the project root (use the absolute path to node scripts, because this shell does not search the current dir):

```bash
node tools/mc.mjs block <id> --name "Name" --texture <file|url|page-url> [--place-sound <src>] [--break-sound <src>] \
     [--sound stone|wood|metal|glass|wool|sand|snow|slime|amethyst|grass|gravel] [--hardness 1.5] [--resistance 6] \
     [--light 0-15] [--friction 0.6] [--speed 1] [--jump 1] [--requires-tool] [--transparent] [--instabreak] [--no-drop] [--size 16|32|64]
node tools/mc.mjs item <id> --name "Name" --texture <src> [--stack 16] [--durability 250] [--food 6,0.8[,always]] (nutrition, saturation modifier; more: mc food, FOOD.md) [--rarity epic] [--fire-resistant] [--handheld]
node tools/mc.mjs texture <block|item> <id> <src> [--size 32]   # re-skin existing content
node tools/mc.mjs sound <name> <src> [--variants b,c --pitch-set 0.9,1,1.1 --level loud]   # registers <ns>:<name>; synth/loops/testing: SOUNDS.md
node tools/mc.mjs remove <id|"name"|JavaFeature> [...]   # delete anything, live: content is wiped from inventories/world; Java features (content/*.java) are deleted, unhooked and switched off via @OnUnload
node tools/mc.mjs remove-sound <name>
node tools/mc.mjs list
```

- `<src>` can be a web **page** URL: the tool takes its og:image, or its first mp3/ogg/wav link. To use real memes or sounds: WebSearch → pick a page → pass the URL. Always view the resulting PNG (Read) to confirm it's the right picture, because search results can be wrong.
- Only textures, sounds, names and new entries are live. Changing physics props (hardness, light…) of an **existing** id needs a restart (properties are fixed once registered), so create a new id instead, or tell the user.
- Need behaviour (right-click actions, ticking, custom shapes, entities)? Write Java in `src/main/java` using `LiveRegistry` + `@OnReload` (see CLAUDE.md), then `gradlew classes clientClasses`.
- Verify: grep `run/logs/latest.log` for `Live-registered` / `Hotswap applied` / `ERROR`.

## Mod namespaces (every mod)
Content of a mod goes in ITS namespace (snake_case of the mod name), never `moddeus:`: pass `--tab "<Mod Name>"` (or `<ns>:<id>`) to every
command, ids without the mod prefix (`abyssal_ascension:bow`). Old mods: `mc namespace "<Mod>"`. Guide: NAMESPACES.md.

## Self-improvement rule
If you do something by hand that this tool can't do yet (a new prop, a new content type such as tools/armor/entities, a new model shape), **extend `tools/mc.mjs` + `ContentLoader.java`** so that next time it's one command, then update this file.

## Tools, armor, shapes, recipes, textures (step 3)
```bash
node tools/mc.mjs item ruby_sword --tool sword --material diamond --damage 8     # then draw it: mc pixels item ruby_sword (guide: mc template item/diamond_sword)
node tools/mc.mjs item bonk_stick --texture my_stick.png --handheld --components '{"minecraft:enchantments":{"minecraft:knockback":255},"minecraft:enchantment_glint_override":true}'
node tools/mc.mjs armor ruby --material diamond      # then: mc pixels armor ruby / armor_legs ruby (64x32) + item icons
node tools/mc.mjs block ruby_bricks --texture ruby_bricks.png --mine pickaxe --requires-tool
node tools/mc.mjs block ruby_brick_stairs --shape stairs --texture ruby_bricks.png --mine pickaxe
node tools/mc.mjs recipe ruby_sword --shaped " R , R , S " --key "R=minecraft:redstone_block,S=minecraft:stick"
node tools/mc.mjs recipe ruby_from_ore --smelt moddeus:ruby_ore --result moddeus:ruby_gem --xp 1
node tools/mc.mjs pixels item ruby_gem --palette "a=#5a0a18,b=#d02040,c=#ff7090" --rows "....aa....,...abba...,..abcbba..,...abba...,....aa...."
```
- `--shape`: slab stairs wall fence fence_gate door trapdoor button pressure_plate pane pillar carpet glass (vanilla blockstates/models copied from the client jar, tags added).
- `--components`: any data component JSON, parsed with the vanilla codec. Registry-backed ones (enchantments) need a loaded world.
- **Textures are always drawn by you** with `pixels` (own palette, shading, highlights). No recoloring vanilla art (removed on purpose: it makes lazy, ugly content). `mc template <vanilla texture>` prints only a SHAPE mask ('#'/'.') to draw over; for big textures (64x64 skins) generate the rows with a small script (noise, gradients, masks) rather than typing them.
- Changing a tool's stats or a block's shape on an existing id needs `mc remove <id>` first: registered objects are not rebuilt (`LiveRegistry.register` returns the existing entry). Same for turning an existing item into a bow: `mc remove` it (or restart) first.
- Weapons: `mc item <id> --tool bow|crossbow|trident|mace|spear|shield|arrow|...` = the vanilla mechanic (plumbing); what makes it special is the mod's own Java ability: `WEAPONS.md` "Custom weapons". Bows show the nocked arrow in every pull stage; turning an existing id into a weapon needs `mc remove` first.
- Recipes referencing a new item: if the item failed to register, the recipe fails to parse. Fix the item, then `game cmd reload`.

## Mobs, custom particles, custom VFX
```bash
node tools/mc.mjs template entity/zombie/zombie            # skin layout mask (64x64)
node tools/mc.mjs pixels entity ember_wraith --palette ... --rows ...   # skin
node tools/mc.mjs pixels item ember_wraith_spawn_egg ...
node tools/mc.mjs mob ember_wraith --template zombie --health 40 --speed 0.3 --damage 6 --scale 1.3 --ai hostile --particle ember --aura solar_smite --aura-every 100
node tools/mc.mjs pixels particle starspark_0 ... (one per frame)
node tools/mc.mjs particle starspark --frames 4 --lifetime 30 --size 1.5 --size-end 0.3 --spin 0.25 --swirl 0.04 --gravity -0.02 --color "#ffffff" --color-end "#c040ff"
node tools/mc.mjs pixels vfx sun_ring ...                  # VFX textures: black = invisible (additive glow)
node tools/mc.mjs vfx solar_smite --layers '[{"type":"ring","texture":"sun_ring","size":0.5,"size_end":7,"duration":14,"alpha_end":0}, {"type":"pillar",...}, {"type":"flash",...}, {"type":"shake","size":1.5,"duration":10}]'
node tools/mc.mjs item bonk_stick ... --vfx-use solar_smite --vfx-hit sun_slash --vfx-cooldown 30
mc game run 'Vfx.play(Vfx.id("solar_smite"), me().position().add(me().getLookAngle().scale(5)), me().getLookAngle(), null);'   # Java only, no /vfx
```
- Mobs: `mc mob <id> --template <any vanilla mob> --model <your bones file>` (named model presets are samples to read) = a real 3D cube model (bones, animation roles, keyframe clips, hitbox from bounds). Full guide: `LIFE.md` (animals, taming, mounts, sounds, drops, villagers, professions, paintings). Never reskin a vanilla model for something new. Stat/template changes on an existing mob id need a restart.
- VFX layers: billboard ring pillar beam slash particles flash shake sound; keys in `tools/mc.mjs` header. Edits to a vfx JSON apply even to effects already playing.
- Example: `src/main/resources/moddeus/content/solar_smite.json`.

## Drawing textures well (read before every `mc pixels`)
- **Palette first**: 3–5 shades per material + 1 accent. Hue-shift: shadows go cooler/more saturated (toward blue/purple), highlights warmer (toward yellow). Never pure black or pure white except tiny specular dots.
- **Light from top-left**. Top/left edges get the highlight row, bottom/right the shadow row. No "pillow shading" (dark ring around every shape).
- **Items**: 16x16, 1px dark outline (darkest palette shade, not #000), silhouette readable at a glance, diagonal tools from bottom-left to top-right. Check the shape with `mc template item/<similar vanilla item>` (mask only).
- **Blocks**: must tile. Avoid features touching one edge only; break up repetition with 2–3 irregular clusters, not noise everywhere. Keep contrast lower than items.
- **Textures for 3D models (cubes)**: UVs are automatic, so each face shows the texture region under the cube's own pixel bounds (a cube from x=6..10 shows columns 6..10). Paint the 16x16 texture as if it were the unfolded object: detail where the cubes are, plain elsewhere. Use separate texture keys per part (`base`, `shade`) when parts differ.
- Animated/glow stuff belongs in VFX (textures you draw with `mc pixels vfx <name>`), not block textures.

## 3D held objects (every weapon/tool/held thing)
Design the silhouette for THIS mod: `--cubes` (below) or `mc item <id> --model custom --parts '[..]'`, then `mc game studio item <id>` (offscreen look, TESTING.md). The named `--model sword|axe|staff|gun|...` shapes are parametric samples (`tools/models.mjs`): read one to learn the part layout, never ship it as is. Rotated parts, glow, auto-fit GUI/hand displays, state variants, GUI icon: see `MODELS.md`.

## 3D models (blocks and items) from cubes
```bash
node tools/mc.mjs block lamp --cubes "2 0 2 14 3 14 base; 7 3 7 9 12 9 base; 4 12 4 12 16 12 shade ry=45" --tex "base=lamp_base,shade=lamp_shade" --light 15
node tools/mc.mjs pixels block lamp_base ...   # then draw each texture key
node tools/mc.mjs item hammer --cubes "7 0 7 9 12 9 handle; 4 11 5 12 16 11 head" --tex "handle=hammer_handle,head=hammer_head"
```
- Grid 0..16 = one block (-16..32 allowed). `rx|ry|rz=` ±22.5/±45 rotates one cube about its centre.
- Blocks get a matching hitbox/collision (ModelBlock, live) and no occlusion. Items get sensible GUI/hand/ground display transforms.
- Design tips: 3–8 cubes; leave 1–2px gaps from block edges so it reads as an object; thin parts ≥2px; plan textures per part.

**Worn vs held (`--wear`).** Cube items default by id: glove/gauntlet/fist/claw/bracer → `hand`; hat/helmet/mask/crown/goggles/hood/halo → `head`; else `held`. Override with `--wear held|hand|head`. Build to the matching convention or the presets put it in the wrong place:
- `hand`: an upright RIGHT hand: cuff/wrist at y=0, fingers up to y≈16, palm facing north (-z), back of the hand (gems, plates) facing south (+z), thumb on the west side. First person shows a fist punching forward with the back of the hand up; third person puts the cuff on the wrist with the fingers along the arm.
- `head`: built around a 0..16 head cube (head = 0..16 on every axis, a hat sits above y=16, a brim sticks out). Front = north (-z). Gets `equippable: head` (right-click to wear).
- A resource-pack model (not `mc item`) gets the same look by copying the display blocks from `WEAR` in tools/mc.mjs.

## Cutscenes
Camera paths, letterbox, titles, subtitles, fades, shake, timed actions: `CUTSCENES.md` in this folder (`dev.moddeus.client.Cutscene`).

## Shaders
Full-screen post effects, our own core shaders (RenderPipeline + RenderType), and vanilla shader overrides: see `SHADERS.md` in this folder (verified recipes; one crash gotcha: always `withColorTargetState`).

## Animations (read before making anything move)
Pick the cheapest layer that looks right, and combine them. Everything below hot-reloads.

1. **Animated textures** (glowing gems, flowing lava, flickering runes): draw the frames as a vertical strip (16 x 16·N), then add `<texture>.png.mcmeta` next to it: `{"animation": {"frametime": 2, "interpolate": true}}` (frametime = ticks per frame; interpolate blends frames). Works for item, block and armor textures. VFX textures: `mc vfx-tex <name> --frames N` plus the layer keys `frames` / `loops`.
2. **Pose keyframes for a 3D item** (snap fingers, open/close a claw, a charging gun): make one cube model per pose (`mc item <id>_pose1 --cubes ...`, same textures), then point `items/<id>.json` at a dispatcher:
   ```json
   {"model": {"type": "minecraft:range_dispatch", "property": "minecraft:custom_model_data", "index": 0,
     "fallback": {"type": "minecraft:model", "model": "moddeus:item/<id>"},
     "entries": [{"threshold": 1, "model": {"type": "minecraft:model", "model": "moddeus:item/<id>_pose1"}},
                 {"threshold": 2, "model": {"type": "minecraft:model", "model": "moddeus:item/<id>_pose2"}}]}}
   ```
   Play it from Java (server side, one frame per tick or slower), and ALWAYS end on 0 so no pose lingers:
   `stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of((float) frame), List.of(), List.of(), List.of()));`
   `int[] f = {1}; every("snap", 2, () -> { setFrame(f[0]++); if (f[0] > 4) { setFrame(0); stop("snap"); } });`
3. **Free animations from item properties** (no Java): `range_dispatch` on `minecraft:use_duration` (charge-up while right-click is held), `minecraft:use_cycle` (loops while using), `minecraft:cooldown` (recharging look), `minecraft:damage`; `condition` on `minecraft:using_item` or `minecraft:keybind_down` (26.3 id; `is_keybind_down` fails to parse) (`"keybind": "key.use"`); `select` on `minecraft:display_context` (a different model in first person, GUI or on the ground, e.g. an open glove in the hand, a closed fist icon) or `minecraft:local_time` (real clock).
4. **Use animations** (how the arm holds it while right-click is held): the `consumable` component's `animation` = none, eat, drink, block, bow, trident, crossbow, spyglass, toot_horn, brush, bundle, spear. Plus `player.swing(hand, SwingAnimation.DEFAULT, true)` for a swing on demand.
5. **Smooth motion in the world** (spinning statues, opening doors, orbiting shields, a hovering gauntlet): a Java renderer (or a VFX layer: `spin`, `size`→`size_end`, `alpha`→`alpha_end`, `delay`, `duration`) driven by the REAL clock `VfxClient.now`, never `tickCount`/game time: those stop during /tick freeze and the effect would freeze with the world. Interpolate with the frame's partialTick, ease (`t*t*(3-2*t)`), and pose with `PoseStack.rotate(Quaternionf)` / `translate` / `scale`. Examples: `client/content/ClaudeOrb.java` (pulse, spin, wobble), `PhysicsRenderer` (slerped rotation).
6. **Mobs**: walk/attack cycles come from the vanilla model the mob copies (`mc mob ... --model zombie` etc.); extra motion = layer 5 on top.

Rules: every ability gets its own animation (a pose, a glow pulse or a VFX layer), it starts on the real input (right-click, hit, key), stays in sync with the real effect, and returns to rest when it ends (no lingering frame). Test it in game: hold the item, use it, screenshot.

## Physics blocks (entity with real physics, fully spec-driven)
```bash
node tools/mc.mjs physics ember_core --texture ember_core --mass 6 --bounce 0.15 --impact-vfx ember_burst --impact-sound <ns>:ember_hit
node tools/mc.mjs item ball_launcher --throws ember_core --throw-speed 1.5 --throw-cooldown 10
mc game run 'Physics.spawn(world(), "ember_core", me().position().add(0, 2, 3), Vec3.ZERO);'   # Java spawn (no /physics)
```
- Samples (`--preset`, tools/library/physics/*.json): crate bouncy_ball boulder ice_cube bomb balloon platform falling_sand floaty glass_orb. Read one for sensible numbers, then tune the keys for your object.
- Every key is optional and read **live each tick** (edit JSON → existing blocks change). Full list in `Physics.java` header. Main ones:
  look `texture texture_top texture_bottom size|width height glow translucent`; motion `gravity drag friction bounce buoyancy`;
  pushing `mass push pushable hit_force hit_lift punchable solid pickup`; spin `rotates round roll_grip spin_on_hit spin_on_impact angular_drag ground_spin_drag settle spin_y max_spin`;
  boom `fuse explode debris_radius debris_max debris_global_max debris_force explode_vfx` (blocks in the radius become flying debris that land back as blocks; built-in `debris` spec);
  impacts `impact_sound impact_vfx impact_damage impact_knockback explode break_speed max_bounces health drop`; life `lifetime lands_as`.
- **Per-block options** (any key above, on ONE block, synced + saved): in code `physics(state, pos).set("bounce", 1.2)` or `looking().set("float", true)` (the block the player looks at). There is no /physics command (player removed it).
- `float: true` = floats on water (`float_depth` 0.5 = half under, 0.2 = rides high).
- `slide: 0.08` = standing on a tilted face slides you downhill (off by default; sneak to hold on).
- Weights: `push_weight` = how hard to shove by walking into it, `punch_weight` = how little it flies when hit (both default to `mass`).
- Hitbox: the box grows to enclose the rotated block, and players collide with the true diagonal shape (1/8-block voxels via `Collisions` + `LevelCollisionMixin`). `diagonal_hitbox: false` = plain box.
- `solid: true` = players/mobs can stand on it and ride it smoothly while it moves (client carries its own player, server carries mobs). Collision box is axis-aligned even when the visual rotates; use `rotates:false` for platforms.
- Punching applies force at the exact hit point → torque/spin. Boxes tumble and stay at whatever rotation they stop in (`settle: 0.25` makes them tip flat); `round:true` rolls.

## VFX samples (read them, then write your own)
Write each ability's signature first (VFX-DESIGN.md), draw its textures (`mc pixels vfx <name>`) and build its own layers or client renderer. The presets are samples to read (`tools/vfxlib.mjs`: how a nova or a beam is layered and timed); using one prints `preset = sample only`.
```bash
node tools/mc.mjs vfx frost_nova --preset nova --palette ice      # SAMPLE: look at the JSON it writes, then replace it with your own layers
node tools/mc.mjs vfx-tex my_ring --shape ring --palette "#001,#08f,#fff"   # SAMPLE procedural texture
```
- Sample names: smite nova slash portal beam aura explosion heal lightning mega mushroom. Layers can move: `x_end/y_end/z_end` + `ease: out|in|inout`.
- Smoke that should look solid: shape `puff` with `{lit: true, premul: false}` + layer `blend: "alpha"` (see mushroom preset). Palettes: fire ice arcane holy poison shadow blood lightning nature void sun, or custom "#hex,#hex,...".
- Shapes (vfx-tex): glow orb ring shockwave flare star spark beam slash sigil swirl smoke lightning crack (`--frames N` animates).
- Attach to items with `--vfx-use/--vfx-hit`, to mobs with `--aura`, to physics with `impact_vfx`.

## Guides (read the one that fits before building)
- `VFX-DESIGN.md`: decide what each effect IS (signature: meaning, material, shape, motion, timing, color) and build it yourself; presets are samples only.
- `WEAPONS.md`: every weapon kind (bow, arrows, crossbow, trident, spear, mace, shield, throwables, guns/wands/staffs, specials) + custom-ability weapons with a worked example.
- `TESTING.md`: test every kind on your private Stage (fake-player actor) and look with the offscreen Studio; never with the player's camera, body or inventory.
- `GUI.md`: screens, HUDs, ability wheels with `ModGui` (instant reload, your own look per mod).
- `SYSTEMS.md`: `mc recipe` (all types), `quest`, `disc`, `pad` (launch/elevator/waystone/conveyor/trap), `crop`, `ore`, `machine`, `economy`/`shop`, `skill`, `hud`, `skyevent`, `secret`.
- `GEAR.md`: tool sets (paxel, hammer), armor stats/tags/recipes, custom 3D armor models (`mc worn`), gliders, totems, books, keys, reskinned rods/shears, lanterns/torches, containers, storage blocks: one command per kind + a "make it amazing" checklist each.
- `GAMEPLAY.md`: `mc boss` (boss bar, phases, attacks), `mc setbonus`, `mc drop` (loot), `mc npc` (dialogue + trades). `ROADMAP.md`: what exists / what's missing.
- `BOSSES.md`: writing a boss's OWN attacks in Java; engine API `Bosses.attack/behave/variant/arena/chest` (sample attacks to read), `mc mobai`, `mc spawnrule`.
- `TABS-KEYS.md`: creative tab (own tab for real mods, a vanilla tab for single small items) + key binds (own section in Controls).
- `FOOD.md`: foods, drinks, stews, golden foods, feasts/pies (`mc feast`), crops, cooking (`mc cook`), tooltips, tasty textures.
- `PIXEL-ART.md`: textures that read well at 16x16 (hue-shifted ramps, selout, no pillow shading).
- `SHADERS.md`, `CUTSCENES.md`.
- `DIMENSIONS.md`: new dimensions (void/flat/islands/caves/noise, sky/fog), datapack or Java, loaded live: `mc dimension <id>`.
- `SOUNDS.md`: sounds for everything: sounds.json keys, mono/stereo, loudness, variants, mob/item/block/ambient/music/disc sounds, loops that follow entities (`SoundLoops.follow`), designing a mod's own sounds (`mc synth` presets = samples), sourcing + licenses, `mc sound-test`/`sound-check`.
- `WORLD.md`: biome ambience (sky/fog/light/colors, music, ambient loops, particles: `mc ambience`), sounds (`--variants --stream`), particles (`mc ptex`), redstone components (`mc logic`), banners, game rules, custom commands (only when asked), toasts (`WorldFeel.toast`), fluids.
- `STRUCTURES.md`: `mc struct` (castle, dungeon, tower... with undo), selections/templates, `mc terrain` brushes, `mc worldgen` (ores, trees, plants, lakes, geodes, structures in new chunks; datapack or Java).
