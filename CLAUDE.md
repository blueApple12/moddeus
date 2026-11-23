# Moddeus

Fabric mod for Minecraft **26.3** (Java 25, unobfuscated, Mojang names) that puts Claude Code inside a live singleplayer world. Plan: PLAN.md.

## Live workflow (game is usually running)
- Game runs from this project: `play.bat` → `gradlew runClient` on JetBrains Runtime 25 (`.tools/jbr`) with `-XX:+AllowEnhancedClassRedefinition` + our agent (`src/agent`).
- After editing code: `gradlew classes clientClasses` (JAVA_HOME=.tools/jbr). `HotReloader` sees changed .class files and redefines them **in the running world**. Adding/removing methods, fields and classes is fine.
- Code that must re-run after a swap (registrations, listeners) goes in a `static void` method annotated `@OnReload` (`dev.moddeus.live.OnReload`).
- Changes under `assets/` trigger a client resource reload (the F3+T loading screen). Changes under `data/` trigger a datapack reload. Never require leaving/rejoining the world.
- Results show up in `run/logs/latest.log` (search "Hotswap") and in game chat.
- **New content mid-game**: register through `LiveRegistry.register(BuiltInRegistries.X, id, key -> ...)` inside an `@OnReload` method, and also call it from `onInitialize` so it exists on the next launch. It thaws the frozen registry and binds tags, block-state ids and item components. Example: `content/ModBlocks.java` (Rizzium).
- New classes containing `@OnReload` methods are loaded and their hooks run automatically. Hooks run on the server thread while the client thread waits.

## Fast path (use first)
Blocks, items, food, textures and sounds (including from web URLs) take **one command, ~3-5s, live**: `node tools/mc.mjs ...`. See skill `mc-content`. Data lives in `src/main/resources/moddeus/content/*.json` and is loaded by `ContentLoader`.

## Self-improvement (you may change ANY of this, including the reloader itself)
- In-game chats learn from feedback (toolbox "LEARN FROM EVERY MESSAGE"): generic bugs get fixed in core, taste rules go in with `mc lesson "<rule>"` (toolbox.md LESSONS, injected on every prompt; `mc lessons` lists them), how-tos into the guides.
- Everything here is fair game, including `HotReloader`, `LiveRegistry`, `ContentLoader`, `tools/mc.mjs`, this file and the skills. The reloader hot-swaps itself.
- When a task is repetitive or slow, turn it into a `tools/mc.mjs` subcommand or a skill. When you hit a gotcha, add it below.
- When editing `HotReloader`: `apply()` may be the OLD code when it swaps itself. Anything after `redefineClasses` must go through `afterSwap(Object[])`, whose signature stays fixed. Never add lambdas after the swap point. Breaking this crashed the game once (NoSuchMethodError lambda$apply$N).
- This shell sets `NoDefaultCurrentDirectoryInExePath`, so call scripts/bat files by absolute path (`& "<proj>/gradlew.bat"`).
- In-game chats must start the game DETACHED (`cmd /c start "" "<proj>/play.bat"`), never as their own child: a game started from a chat lives in the bridge's PTY and dies when the bridge restarts (lost the world session once).
- If the game is not running: `play.bat` starts it and auto-joins the last world. Use the absolute path from this shell.

## Datapack mods
- Toolbox (`tools/toolbox.md`, injected every prompt) has WHICH KIND / SHARED / JAVA MOD / DATAPACK sections. Datapack rules live at the top of skill `minecraft-datapack` ("Moddeus live workflow"): `<ModId>-DP` folder, formats [121,0] / RP [97,1], required `uninstall`, `game cmd reload` + `game log` check.

## Mod projects + undo
- G screen sidebar "Mods": each mod = classes named `<Id>*.java` (content/, client/content/) + packs `run/saves/<world>/datapacks/<Id>-DP` / `run/resourcepacks/<Id>-RP` + its own chats + On/Off/Undo/Delete. Datapack Off = its `<ns>:uninstall` (or `:off`) then `/datapack disable`; On = enable (load re-runs). Undo snapshots include packs (snap after `game cmd reload`). List: `run/moddeus/projects.json` (bridge writes; seeded from Java features), `mc projects`. A project chat gets its mod in `--append-system-prompt` (`tools/projects.mjs`).
- Sidebar = Voice (Haiku helper + "Voice brain", its Opus chat) and Mods (every chat lives in a mod; no free chats, no Recent). Old free chats become their own mod at bridge start.
- Voice: `mc ask "<words>"` routes to the mod named in the words, else to Voice brain; `--project <name>`, `--new "<Mod Name>"` (new mod + chat), `--smart` (Voice brain).
- Undo: every hot-swap snapshots src/tools/CLAUDE.md/.claude into hidden ref `refs/moddeus/snap` (own index, user's git untouched). `mc undo [Prefix]`, `mc redo`, `mc snaps` (`tools/snap.mjs`).

## In-game Claude UI (press K)
- `tools/bridge/bridge.mjs` runs the REAL interactive `claude` in a hidden PTY (subscription usage, never -p). Claude Code HTTP hooks (`run/moddeus/hooks.settings.json`) stream messages, tool calls and permission prompts to the bridge. The game talks to the bridge over WebSocket (`run/moddeus/bridge.json` holds the port and token). Logs: `run/moddeus/bridge.log`.
- The game starts the bridge if needed (`client/ui/Bridge.java`). The bridge outlives the game and resumes the last session (`run/moddeus/session.json`).
- UI: `client/ui/ClaudeScreen.java` (immediate-mode, Moddeus palette: violet/teal/gold; mascot = `client/content/ClaudeOrb.mascot`, icon `assets/moddeus/icon.png`; old `claudecraft:` ids resolve via `LegacyIdMixin`), `ClaudeUi.java` (K key, HUD pill). Model, effort and ultracode go through `/model`, `/effort`; mode goes through Shift+Tab cycling read from the terminal screen.
- **Voice (hold K)**: tap K = open screen; hold K = Claude Code's built-in dictation (`/voice` tap mode, enabled only in the hidden "Voice" chat's `--settings`) in a Haiku helper chat (`VOICE_PROMPT` in bridge.mjs). Bridge taps Space on press/release, presses Enter for <3-word transcripts. Haiku does anything one `game run` can do and hands everything else (web, downloads, MCP, textures, multi-file, failures) to Opus via `node tools/mc.mjs ask "<request>"` (bridge cmd `forward`: last-focused main chat, or a new Opus chat if none). It never says "can't" or asks for sources. HUD: `ClaudeUi.voiceHud`. No whisper/local STT: transcription is Anthropic's and costs no tokens.
- Test without the game: POST `{"type":"send","text":"..."}` to `/cmd?t=<token>`; GET `/state`, `/screen`.

## Player requests (in-game chats)
- Requests are often speech-to-text: guess the intended word first ("verity" may be a misheard name).
- **Unknown name (a mob, item, character, song, meme): web search it yourself right away, pick the most likely meaning, and build or do it.** Don't ask "what did you mean?" or offer options; ask only if the search finds nothing usable. Say in one line what you found and what you made.
- Same for anything needing outside info or assets (textures, sounds, references): fetch it yourself. Textures are still drawn by you.
- **Quality bar (player's standing order, all mods, even if it takes much longer):** a GOOD crazy mod, not a simple one: several spectacular abilities (the COOLEST idea, never the easiest to code, e.g. Space Stone = a real black hole sucking in mobs/blocks), a TON of new custom VFX (but few particles: shaped VFX layers, not particle spam), real mechanics (time stop = `/tick freeze`; reverse time = record ~5 s of the world and replay it backwards; meteors = real falling burning projectiles with trails, craters, debris), effects aligned with the real ray/hit, every ability its own UNIQUE VFX (never one shared primitive: not lines, not swirly circles), no lingering leftover effects, ultimates HUGE with a unique mechanic and fitting colors (all stones = rainbow), re-test ALL controls after every change, textures on everything (meteors too), time-stop effects keep animating while the world is frozen, reverse time rewinds all visible entities smoothly, disliked abilities get replaced by cooler ones. Test every ability in game. Full text: `tools/toolbox.md` (injected on every prompt).
- **Build the real thing, never a texture-only placeholder.** An item/block from a game, movie or idea must DO what it does there: abilities on right-click / sneak+right-click / hit / while held (a Java feature in `content/<Name>.java` with `@OnReload` hook + `@OnUnload`). Held items and objects get a **3D cube model** (`mc item <id> --cubes ... --tex ...`), not a flat sprite. Tell the player the controls in one line.

## Instant actions (voice speed path)
- `mc game quick <action> [args] + <action> + cmd <command>`: prebuilt actions in `live/Quick.java` (launch, safe_landing, no_fall, kill_hostiles, stop_mobs, heal), no compile, ~0.1s. Add actions there (a case + a method, hot-swaps).
- Snippet helpers (static-imported from `live/Quick.java`): `me()`, `world()`, `cmd("...")`, `every("name"[, ticks], () -> ...)` / `after(ticks, ...)` / `stop(name)` / `stopAll()` / `running()` (repeating behaviour without new code), `physics(blockState, pos[, vel])` (built-in spec `block#<stateId>`), `assemble([pos])` (connected build → one rigid body, spec `body#x,y,z,stateId;...`), `launch`, `push`, `safeLanding`, `fallDamage`, `hostiles`. Voice Haiku uses only `game run`.
- `mc game run|eval "<java>"`: compiled IN MEMORY in the game (`client/game/FastRun.java`, javac in-process, child class loader), runs at once on the server thread: ~0.3s, no Gradle/hotswap/files. `--gradle` = old hotswap path. Vars: player, level, server, mc, print(), Quick.*.
- Voice chat runs Haiku with `--system-prompt` (short), `--tools Bash`, `--strict-mcp-config`, `--setting-sources project,local` for speed. Measured: action ~2.5s after the prompt, reply ~3.4s.

## Claude avatar (companion)
`content/Avatar.java` + `client/content/AvatarFeed.java`: a mannequin with Claude's skin and its OWN AI, ported from mineflayer (pathfinder/pvp/collectblock): `content/BuddyPath.java` = same move set + costs (dig through, bridge, 1x1 tower, parkour, drop 4, ladders, water) and goals (block/near/lookAt/follow/invert); `Avatar.java` = executor (real break time with best tool, cracks, drops), tasks (go/dig/collect/place/attack/give), 36-slot inventory with item pickup (saved run/moddeus/buddy-inventory.json). No helper mob; never teleports except 100+ blocks / other dimension. `stop` = stop on the spot. Follow mode walks only (no digging/placing) unless `rules`.
- **Body control = MCP `buddy`** (`tools/buddy-mcp.mjs`; the bridge writes its config to `run/moddeus/buddy-mcp.json` and passes it to EVERY chat): see (POV screenshot: camera hops into the avatar ~0.4 s), status, walk, mine, chop, place, attack, move, say. Java side: `Avatar.api(JsonObject)` via GameApi `buddy` endpoint. Scripts: `Quick.buddy()`. Mineflayer MCPs can't join 26.3.
- Voice helper prompt lives in `tools/bridge/voice-prompt.md` (read per session start, no bridge restart needed).
- TTS (`tools/bridge/tts.py`): Kokoro English (am_michael); text with Hebrew letters -> edge-tts he-IL-AvriNeural. Claude Code dictation has NO Hebrew (supported: en es fr ja de pt it ko hi id ru pl tr nl uk el cs da sv no), so Hebrew input = typed.
- Hide: `mc game run 'dev.moddeus.content.Avatar.enabled = false;'`.

## World tools (skill mc-world)
`node tools/mc.mjs game context|cmd|blocks|shot|say|log|eval`: see and act in the live world. `client/game/GameApi.java` is a localhost HTTP API (port and token in `run/moddeus/game.json`). `eval` compiles a throwaway `@OnReload` class (`src/main/java/dev/moddeus/eval`) and hotswaps it in.

## Self-test (no tokens, <=10s)
`node tools/mc.mjs test [game cmd blocks eval shot bridge permission mode]`: every check is capped at 10s and none uses the model. Run it after changes instead of spawning Claude sessions to test (the user pays for those tokens).
- If `eval` fails with "Unable to delete directory ~/.gradle/caches/.../transforms/...": the Gradle cache was partly deleted from outside, and the game's daemon holds the jars. Stop the game, run `gradlew --stop`, delete the listed dirs, rebuild, relaunch.
- Never restart the bridge while a chat is busy/waiting: it kills that Claude mid-request and its approval card is lost. Restart = kill the bridge pid in `run/moddeus/bridge.json`; the game respawns it within ~4s. Session keys change on restart.
- If Claude Code shows its own approval prompt in the terminal (the hook missed), the bridge turns it into a card (`terminalDialog`): Allow = Enter, Deny = Esc.
- Bridge sends prompts as raw keystrokes (LF = newline). Bracketed paste makes Claude Code wrap them in `<pasted_content>` and treat them as untrusted.

## Live removal
- One command for anything: `mc remove <id|"display name"|JavaFeature>` (`mc list` shows content + Java features). Java features = `content/<Name>*.java` (not the core systems): files deleted, one-line `Name.x();` calls/imports elsewhere removed, rebuilt.
- **Every Java feature that registers something (listeners, commands, ticks) must have a `@OnUnload` static method** that switches it off (guard flag false, remove command node), and its `@OnReload` hook must switch it back on (`enabled = true;` first). The G screen Mods On/Off runs exactly these two (`client/Projects.java`). Deleted classes stay loaded until restart; HotReloader runs `@OnUnload` when the .class disappears.
- Builds are serialized by `tools/lock.mjs` (`run/moddeus/build.lock`): parallel `game eval`s from several chats used to compile/delete each other's files.
`mc remove <id>` unregisters live (`ContentLoader.purgeRemoved` → `LiveRegistry.unregister`). Before unregistering, EVERY live reference must be gone, or the next save or packet crashes: inventories, ender chests, containers and blocks in loaded chunks, dropped item entities, **player stats** (crashed once: ServerStatsCounter.toJson), and StatType caches. If a new crash shows another holder of removed items/blocks, add a scrub for it there.

## Physics bodies
`content/PhysicsBlock` = rigid body of real blocks (Sable/Create Aeronautics style): grid in the SPEC string, mass/COM/inertia from block materials, impulse solver vs world + other bodies, per-block buoyancy, sleeping. Entity pos = centre of mass. Not punchable/pushable: `client/content/PhysicsMining` mines cells (real break time, crack overlay) → `Physics.mine` → `breakCell` (drops, splits disconnected parts). Right-click with a block = `placeCell`. Players collide per cell (`Collisions`) and ride bodies they stand on. Renderer has no shadow.

## Mixins
- `src/main/java/dev/moddeus/mixin` + `moddeus.mixins.json`. Mixins only apply at class load, so adding/changing one needs a restart; HotReloader skips that package. Keep mixins one-line delegations to a normal class (e.g. `LevelCollisionMixin` → `content/Collisions`) so the logic stays hot-swappable.
- **Stop the game BEFORE writing code that needs a restart** (new `defineId`, mixins): other chats run builds, and a half-applied swap crashed the game once (PhysicsBlock.OPTIONS null while saving).
- The dev run uses a fixed offline name (`--username Player224` in build.gradle): a random name each launch meant a fresh player.

## Tabs, keys, GUIs (every mod)
- Creative tabs (`content/ModTab.java`): "Moddeus" catalog + per-mod tabs (content JSON `"tab"`, `mc item ... --tab`) or a vanilla tab (`combat`, `tools`, ...; `MODIFY_OUTPUT_ALL`). Datapack mods: `<Id>-DP/moddeus.json` (`client/PackExtras.java`, ItemParser /give strings), reloaded instantly by `DataReload` and on every datapack reload.
- Key binds (`client/Keys.java`, `Quick.key`): live-added to `Options.keyMappings`, own Controls category per mod, labels in `assets/moddeus_keys/lang/en_us.json` (written by Keys, light-reloaded), saved rebinds read from options.txt. Startup uses Fabric `KeyMappingHelper`. `Keys.remove(mod)` drops them.
- GUIs (`client/ModGui.java`): immediate-mode lambdas for screens + one stable HUD element for all mods. Guides: mc-content `GUI.md`, `VFX-DESIGN.md`, `TABS-KEYS.md`, `PIXEL-ART.md`.

## Lag (never again)
- **Mod Java code = generations, no JVM pause** (`client/Gens.java`): classes of a mod project (`content/<Id>*`, `client/content/<Id>*`) are never redefined. Each change defines a new generation (`Foo__g3`, same package, ~3 ms off-thread) and switches over on the server thread (~5-10 ms, async): ANY change (fields, methods, records, new classes) is instant. `Event.register` inside mod code is rewritten to stable slot proxies (no doubled listeners), static fields carry over by name, the mod's guard System properties are cleared, then its `@OnReload` runs. Mods load at startup via `Gens.startAll()`: do NOT call mod hooks from `Moddeus.onInitialize` (core referencing a mod class makes it fall back to redefinition). Also falls back for a mod class that extends a Minecraft class (Item/Entity/Screen...: objects that live in the world): keep those in core or data-driven (`mc item/mob`). `game run` snippets are remapped to the live generation. Log: "Hotswap (generation)".
- **Core code** (everything else) still redefines: `HotReloader.redefineFast` (WhiteBox stub `src/whitebox`, launch flags in build.gradle) turns AllowEnhancedClassRedefinition off per swap: body-only = ~60 ms (HotSpot walks all 45k classes), schema change = enhanced ~200-800 ms.
- **Datapacks** (`live/DataReload.java`): `game cmd reload` swaps only changed .mcfunction files (~2 ms, file scan off-thread, then #load like vanilla); other data changes = vanilla /reload (~300 ms). **Resource packs** `run/resourcepacks/*-RP` are watched: edits light-reload (~20-100 ms) by themselves.
- **Lag guard** (`client/LagGuard.java`): times every server tick, runs/times each datapack tick function itself (takes over `ServerFunctionManager.ticking`), mixin `ServerFunctionManagerMixin` times other function calls, watchdog samples slow ticks/frames (Java frames per mod). Sustained lag (>60 ms/tick or <25 FPS for 5 s, ignoring 15 s after reloads/toggles/swaps) NEVER switches anything off: it sends the mod's chat (bridge `forward`, `quiet` = no focus jump) what lagged, the numbers and how to fix it, once per 3 min, + one chat line. `run/moddeus/lag.json`, live: `node tools/mc.mjs game lag`. A runaway datapack function (65536-command limit every tick) froze the game once.

## Hotswap gotchas
- `@OnReload` hooks of CORE classes run only on swaps, NOT at launch: call them from `Moddeus.onInitialize`. Mod project classes (`<Id>*`) are started by `Gens.startAll()` instead; never call them from core.
- Re-creating a deleted class (same name) REDEFINES the still-loaded old one: all its new statics start null (crashed Vfx.DEFS). Keep maps non-final and re-create them in the @OnReload hook.
- `System.getProperty(k)` returns null for non-String values: guards stored with `put(k, Boolean.TRUE)` must be read with `System.getProperties().get(k)` (an "add new events" guard silently never fired).
- Event callbacks can't be unregistered: register once per JVM (guard with a System property) and only with method refs (`Foo::bar`), never lambdas, so swaps reach the new code.
- Changing a brigadier command's argument names: remove the old literal node from the dispatcher root first (reflection on `children`/`literals`), else the old node wins.
- `gradlew processResources` skips files whose content didn't change (touch doesn't help); to re-run ContentLoader use `mc game eval "dev.moddeus.live.ContentLoader.loadAll();"`.
- **Texture swap** (blocks showing other blocks' textures after an asset change): adding/removing a texture re-stitches the atlas, and chunk meshes built before keep the old UVs. `LightReload.done` calls `mc.levelExtractor.allChanged()` (what F3+T does) to rebuild them; never drop that.
- Lag: each swap = stop-the-world redefine (~300ms/20 classes) + JIT deopt. HotReloader waits for 1.5s of quiet so one build = one swap; assets use LightReload (no shaders/fonts/sounds, no loading screen).
- A **static field added by a swap starts null/0**, because static initializers don't re-run. This includes `static final X = new ...`. It crashed the game once (Bridge.states). Use lazy init (`if (x == null) x = ...`) or set it in an `@OnReload` hook.
- **Instance fields added by a swap are null/0 on objects that already exist** (field initializers only run in constructors). Crashed the world once (PhysicsBlock.rotO). Null-guard them at the top of tick(), and wrap entity ticks in try/catch so a bug logs instead of crashing.
- New SynchedEntityData accessors (`defineId`) or a changed `createInterpolationHandler` only work after a game restart.
- **Never rename or remove a method that is registered as an event callback** (`UseItemCallback.EVENT.register(Foo::use)`): the registered ref still points at the old name and the next event crashes the game (NoSuchMethodError StormStaff.use, crashed once). Keep the old method as a one-line delegate to the new one. Same when deleting and re-creating a class.
- **Indexes saved before a swap can be out of range after it** (a list that shrank): clamp with `Math.floorMod(i, arr.length)` when reading stored selections. An unclamped one crashed the server tick (StormStaff.sel).
- Already-running loops keep their old frame, and only the methods they call pick up new code.
- If the in-game reloader itself breaks, use the out-of-band rescue: `gradlew rescueJar classes clientClasses`, then `.tools/jbr/bin/jcmd <pid> JVMTI.agent_load <abs>/build/agent/moddeus-rescue.jar <abs project dir>` (pid from `.tools/jbr/bin/jps -l`, the `devlaunchinjector` entry).

## Finding Minecraft APIs
- Common 26.3 moves: entity type constants are in `EntityTypes` (`EntityTypes.PIG`), not `EntityType`; mobs live in sub-packages (`animal.pig.Pig`, `monster.zombie.Zombie`, `monster.illager.Pillager`, `npc.villager.Villager`); Enderman is `monster.Enderman`.
- 26.3 names differ from older tutorials. Verify before use: `.tools/jbr/bin/javap -cp <jar> <class>`, with jars in `.gradle/loom-cache/minecraftMaven/net/minecraft/` (common + clientOnly).
- 26.3 entity type constants live in `net.minecraft.world.entity.EntityTypes` (not `EntityType`): `EntityTypes.PIG`, `.CHICKEN`, `.ENDERMAN`, `.IRON_GOLEM`, `.LIGHTNING_BOLT`. Classes moved into subpackages: `animal.pig.Pig`, `animal.chicken.Chicken`, `monster.Enderman`, `animal.golem.IronGolem`. Matching by id avoids class names: `BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath().equals("enderman")`. Entity tags: `entityTags()`.
- 26.3 renames: velocity sync flag = `Entity.needsSync` (no hurtMarked); `LivingEntity.swing(hand, SwingAnimation.DEFAULT, true)`; `entity.entityTags()`; client open screen = `mc.gui.screen()`; attack swing progress = `LivingEntity.getSwingAnimation(pt)` (no getAttackAnim); walk cycle = `walkAnimation.position(pt)/speed(pt)`.
- Cutscenes: `client/Cutscene.java` builder, guide `.claude/skills/mc-content/CUTSCENES.md`; from server code `Quick.cutscene("Name")`.
- Shaders: `.claude/skills/mc-content/SHADERS.md`. A custom `RenderPipeline` without `.withColorTargetState(...)` crashed the game on first draw ("color attachment count must match"). Shader/post_effect edits hot-reload (LightReload re-runs ShaderManager).
- VFX run on a real-time clock (`VfxClient.now`), so they keep animating during `/tick freeze`.
- VFX render order: `VfxClient.late` submits into the after-terrain phase (like particles), and `CloudOrderMixin` → `client/RenderOrder` runs that phase after clouds. Custom glow geometry submitted the normal way gets painted over by water and clouds behind it (they write no depth). `RenderOrder.afterClouds=false` restores vanilla order.
- Project skills in `.claude/skills/` (minecraft-modding, resource-pack, datapack, commands, worldgen, testing, imagegen).

## Release (GitHub blueApple12/moddeus)
- Public repo = branch `public` (fresh history, core only). Update: worktree of `public`, `node tools/release-export.cjs . <worktree>`, check for leftover IP/personal paths, `gradlew build` there, commit, `git push origin public:main`, `gh release create`. Never push `main` (old history has personal paths).

## Layout
- `src/main`: common/server code. `src/client`: client-only code. `src/agent`: java agent (Instrumentation).
