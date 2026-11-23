QUALITY BAR (player's standing order for EVERY mod/item/ability, even if it takes much longer):
- Build a GOOD, crazy, full mod, never a simple one. Several abilities per item, each one spectacular, not just damage + a sound.
- Pick the COOLEST ability, never the easiest one to code. Brainstorm the most epic version first (e.g. Space Stone = a real black hole that pulls in mobs, blocks and items, swirling accretion disk, then collapses), then build that. Easy-to-code ideas (buffs, plain beams, "teleport a bit", potion effects) are NOT enough on their own.
- LOTS of new custom VFX per feature, designed first with .claude/skills/mc-content/VFX-DESIGN.md (one-line signature per ability: meaning · material · shape · motion · timing · colors; no two abilities share material+shape+motion; draw your own textures). Presets/guides are building blocks for YOUR idea, not templates: be creative, every mod should look like itself.
- But FEW particles: clean shaped effects (VFX layers) over particle spam. Keep counts low.
- VFX VARIETY: every ability gets its OWN UNIQUE effect (sphere, vortex/disk, dome, ground shockwave, cracks, pillar, swirl, aura, sky effects, textured models...). Never build all effects from one primitive - not lines, not swirly circles, not anything. Replacing one overused look with another overused look is the same bug.
- No leftover junk: effects end cleanly; nothing lingers afterwards (stray circles/rings after an impact) unless it clearly means something.
- Ultimate / combined abilities (e.g. a supernova using all stones) must be HUGE and special: a unique mechanic, not just a bigger explosion, with fitting colors (all stones = rainbow of every stone's color).
- Never break what already worked: after every change re-test ALL controls (right-click, sneak, hit, switching) in game, not only the new ability.
- Everything visible has a real look: meteors, projectiles, thrown objects get their own drawn texture/3D model, never an untextured or default block.
- Time stop (/tick freeze): the world freezes, but YOUR effects keep animating (VFX, particles, the player's own abilities run client-side / unfrozen). A frozen effect is a bug.
- Reverse time: rewinds ALL entities (at least every one the player can see), not just blocks or the player, and plays back SMOOTHLY (every tick, interpolated), not in jumps.
- When the player says an ability is bad or they don't like it: replace it with a much cooler new one, don't just tweak it.
- Do it for real, with the real mechanic: time stop = /tick freeze (+ unfreeze); reverse time = record the world (entities' positions, blocks changed) for ~5 s and play it backwards; meteors = real falling burning projectiles from the sky with trails, impact craters and debris (physics blocks). No fake stand-ins.
- Line effects up with the real thing (a beam VFX starts at the hand and follows the actual hit ray, not above it). Check with game shot.
- Test each ability in game before saying done; report the controls in one line.

MODDEUS TOOLBOX. First decide what the player asked for, then use that section. Never build from scratch when a tool fits.
Every creation is a MOD PROJECT (G screen > Mods: On/Off, Undo, Delete). It owns files by its id: Java classes <Id>*.java, datapack run/saves/<world>/datapacks/<Id>-DP, resource pack run/resourcepacks/<Id>-RP. Use those exact names.

== LEARN FROM EVERY MESSAGE (self-improving) ==
- When the player corrects, complains, asks "how do I...", or something didn't work first time: fix it, then ask "could another mod/chat make this same mistake?" If yes, make it permanent BEFORE replying:
  1. Generic bug (cutscene, HUD, keys, rendering, reload...) -> fix it in the shared core code/helper so every mod gets it, not only in your mod.
  2. Taste/quality/workflow rule -> node tools/mc.mjs lesson "<one generic imperative rule>" (saved in tools/lessons.md, which every chat sees on every prompt). Deeper how-to -> edit the matching guide/skill (.claude/skills/mc-content/*.md, VFX-DESIGN, GUI, CUTSCENES, TABS-KEYS, PIXEL-ART). Crash/API gotcha -> CLAUDE.md "Hotswap gotchas"/"Finding Minecraft APIs".
  3. Tell the player in one line: "Learned: <rule>".
- Write lessons generic (no mod names unless it's an example), short, and never duplicate one already listed: sharpen the existing line instead.

== WHICH KIND ==
- "datapack" / "vanilla" / "no mods" / "commands only" -> DATAPACK (+ resource pack for models/textures). Otherwise -> JAVA MOD (more power: custom VFX, physics, cutscenes, real mechanics).

== SHARED (both kinds) ==
- Check in game: node tools/mc.mjs game shot (Read the PNG), game log, game context. Tell the player the controls in one line.
- Instant actions/tests: node tools/mc.mjs game cmd "<command>" | game run '<java>' (Quick helpers: me(), world(), cmd(), every(), after(), launch(), physics(), looking()).
- Sounds/images from the web: --texture/--sound accept URLs (WebSearch first). Reference images the player attached: Read them.
- Claude orb (companion in the world): mcp__buddy__orb (follow, stay, goto, say...) when the player asks Claude to do something with them.

== EVERY MOD (Java or datapack) ==
- Creative tab: a real mod with several items gets its OWN tab named after the mod; a single small item goes into the fitting vanilla tab (emerald sword -> combat) unless the player asks. Java: --tab "<Mod Name>" | combat|tools|food|building|...; datapack: moddeus.json in <Id>-DP. Guide: mc-content TABS-KEYS.md.
- Key binds for abilities: Quick.key(mod, label, key, player -> ...) / datapack moddeus.json "keys" -> own rebindable section in Controls. K and G are taken; avoid vanilla keys.
- GUIs/HUDs: ModGui (GUI.md): each mod gets its own visual identity (theme, shape, motion, palette), never the default dark panel. Textures: PIXEL-ART.md.
- Tell the player the controls in one line (and put them in the item lore).

== JAVA MOD ==
- 3D items/blocks: node tools/mc.mjs item|block <id> --cubes "..." --tex "..." (draw the pixels). No flat sprites for held objects. Worn things (gloves, hats): --wear hand|head.
- Abilities: content/<Id>*.java with @OnReload hook (enabled = true first) + @OnUnload (started automatically, not from onInitialize); right-click / sneak / hit / while-held. Pattern: content/StormStaff.java.
- VFX: node tools/mc.mjs vfx <id> --preset smite|nova|slash|portal|beam|aura|explosion|heal|lightning --palette ...; Vfx.play(Vfx.id("<id>"), pos, dir, null); --vfx-use/--vfx-hit on items, --aura on mobs; custom particles: mc particle.
- Physics: mc physics <id> --preset ...; Quick.physics(state, pos[, vel]); throwables: item --throws <physics id>; debris explosions: explode + debris_radius; Quick.assemble(pos) = build -> rigid body.
- Mobs: mc mob <id> --template zombie|skeleton|spider|creeper|pig|chicken|iron_golem|bee|blaze ...
- Animations (moving parts, poses, glow pulses): skill mc-content "Animations".
- Cutscenes (boss intros, ultimates, story): dev.moddeus.client.Cutscene builder; guide .claude/skills/mc-content/CUTSCENES.md.
- Shaders (screen effects, custom glow surfaces): .claude/skills/mc-content/SHADERS.md. Custom RenderPipeline MUST have .withColorTargetState(...) or the game crashes.
- A finished feature = model + ability + VFX + sound, not just one of them.
- Reloads are instant (~10 ms, any change incl. new fields/methods) for mod classes named <Id>*.java as long as none extends a Minecraft class (Item/Entity/Screen...): use events + data-driven items/mobs (mc item / mc mob). Never call your hooks from Moddeus.onInitialize (mods start by themselves).
- Lag guard: if your mod lags the game it messages your chat with the numbers + how to fix; fix it right away. node tools/mc.mjs game lag shows costs. Datapacks: no unbounded recursion, no per-tick @e scans of the whole world.

== DATAPACK ==
- Skills: minecraft-datapack (files, formats), minecraft-commands-scripting (execute, scoreboards, NBT). Models/textures/sounds for it: a matching resource pack (skill minecraft-resource-pack).
- 26.3 formats: datapack pack.mcmeta "min_format"/"max_format" [121, 0]; resource pack [97, 1]. Folder data/<ns>/function/ (singular), tags data/minecraft/tags/function/load.json + tick.json.
- Must have data/<ns>/function/uninstall.mcfunction that removes EVERYTHING it added (scoreboards, attribute modifiers, tags, effects, summoned entities, bossbars) - the G screen Off runs it, then /datapack disable.
- After every edit: node tools/mc.mjs game cmd reload (instant for .mcfunction-only edits; edits to tags/advancements/recipes do a full ~300 ms reload, so batch them; resource pack files apply by themselves, never reloadResourcePacks), then game log (search "Failed to load" / "Couldn't parse") - a broken file silently disables the whole pack. That reload also saves an undo step.
- Datapack "VFX": display entities (block_display/item_display/text_display with transformation + interpolation), particles with shapes (few, placed), sounds, title/actionbar, custom item models via the resource pack (item_model component). Same quality bar: real mechanics, every ability its own look.
- Test with real input: game cmd to set up, game shot to look, Quick in game run to simulate keys if needed.

== LESSONS (built in; yours are in tools/lessons.md; apply to EVERY mod) ==
- Cutscenes: the player must be visible in third-person shots (core Cutscene draws them now). Check every shot with game shot before saying done.
- Characters, transformations, suits and bosses need a real 3D look: extra modeled geometry (tendrils, spikes, bulk, jaws, armor plates, capes) that animates, not only a re-skinned player model or a flat texture. Judge it from 3rd person (F5) screenshots; if it looks "lame", it is.
- Any state the player enters (transform, suit, flight, mode) needs an obvious exit: show its controls on screen when it starts (actionbar/HUD hint) and in the item lore, e.g. "X = unbond".
- Attach face/body parts (tongue, eyes, horns, jaws) in the frame of the rendered head/mouth they belong to, then check close-up front + side screenshots that they sit there.
