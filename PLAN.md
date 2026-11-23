# Moddeus — plan v2

Claude Code works inside a running Minecraft 26.3 singleplayer world, with its full toolset and your normal subscription usage. It can edit the mod's real source, and the changes go live without reopening the world.

## Core decisions

| Topic | Choice |
|---|---|
| Loader / version | **Fabric 26.3** (stable), Java 25, unobfuscated |
| Mode | Singleplayer (integrated server, so client and server share one JVM) |
| Brain | **Interactive Claude Code session** (`claude`, not `claude -p`). Uses your regular Claude Code limits, not the Agent SDK credit |
| Game ↔ Claude link | **Claude Code Channel** (official MCP extension, research preview). Pushes in-game chat into the live session, and relays permission prompts into the game |
| Live code | **Real Java hotswap**: JetBrains Runtime 25 + `AllowEnhancedClassRedefinition` + HotswapAgent, plus Mixin hotswap agent |
| Registries | Mixin unfreezes `MappedRegistry` so new blocks/items/entities/sounds can be registered **in-world** |

No "add block" commands. Claude edits the mod project like any codebase (Java, mixins, assets, data) and then hot-applies the result.

## Architecture

```
 You (in game)                                     Claude Code (interactive, regular usage)
 ─────────────                                     ───────────────────────────────────────
 chat "@claude ..." / C-key chat screen             cwd = this project (the mod's own source)
        │                                           all your skills, MCPs, plugins + project skills
        ▼                                                       ▲   │
 ┌──────────── Fabric mod "moddeus" ─────────┐              │   │ tools
 │ Bridge (127.0.0.1 WebSocket, token) ◀─────────┼── channel MCP server (Node, stdio)
 │  • forwards chat + world context  ────────────┼──▶ notifications/claude/channel
 │  • shows replies, avatar speech               │◀── reply / world / command / eval /
 │  • permission popup [Allow]/[Deny] ───────────┼──▶ screenshot / logs / hot_reload tools
 │ Live runtime                                  │
 │  • @OnReload hooks rerun after each hotswap   │
 │  • registry thaw → register → refreeze        │
 │  • resource reload (assets) + /reload (data)  │
 │ Claude avatar entity, chat screen             │
 └───────────────────────────────────────────────┘
```

### "Fully works like Claude Code"
- It **is** Claude Code, the interactive TUI, started by the mod in a terminal window (later rendered in-game, see phase 6). Edit, Bash, Grep, subagents, web, your skills and MCPs all work.
- Minecraft source is available for grepping (Loom `genSources`, unobfuscated). Claude reads the real game code instead of guessing.
- Game tools exposed through the channel:
  - `reply`: chat or avatar speech
  - `world_context`: position, dimension, biome, target block, inventory, nearby entities
  - `run_command`
  - `eval_java`: compile and run a snippet in the game JVM with full access
  - `screenshot`: Claude can **see** the game
  - `game_log`
  - `hot_reload`: compile, then redefine classes, then run reload hooks, then reload resources

### Live update ladder (from fastest to slowest)
1. **Hotswap**: method bodies, new methods/fields/classes, mixin changes. Instant and in-world.
2. **Reload hooks + registry thaw**: new blocks/items/entities/commands/event handlers appear in-world. The client resource reload brings their textures and models in.
3. **Data reload**: recipes, loot, tags, structures and functions via `/reload`.
4. **Fast rejoin**: auto save → leave → rejoin in a few seconds. For per-world data registries (biomes, dimensions, data-driven enchantments).
5. **Fast restart**: rare (for example, a new mixin on a class that's already loaded and can't be retransformed). The game relaunches and auto-joins the same world at the same spot.

## Usage / billing
- Interactive `claude` = your normal Claude Code subscription limits. The mod never uses `-p` or the SDK, so it doesn't draw on the extra Agent SDK credit, and it needs no API key.
- Custom channels need `--dangerously-load-development-channels server:moddeus` during the preview. Claude Code asks you to confirm it once each launch, in the terminal.
- Personal use only. Don't publish the mod with this auth setup.

## How you play
- Launch with `play.bat`. It runs the game from the dev build (`gradlew runClient` on JBR 25) and the Claude Code terminal next to it.
- Worlds live in `run/saves`. You can copy existing worlds in.
- Your real Microsoft account works via the DevAuth mod. Otherwise the game uses an offline name.

## Phases
0. **Scaffold**: Fabric 26.3 project, JBR 25 + HotswapAgent auto-download, `play.bat`, genSources.
1. **Channel + bridge**: in-game chat ↔ live Claude session, replies in chat, permission popup.
2. **Game tools**: context, commands, eval_java, screenshot, logs.
3. **Live reload**: hot_reload pipeline, @OnReload hooks, registry thaw, resource/data reload, fast rejoin/restart with auto-join.
4. **Project brain**: CLAUDE.md + project skills (26.3 modding, live-reload rules, textures/models, datapacks, building).
5. **Claude avatar** entity + chat screen (streaming status: "editing X.java…", "reloading…").
6. **In-game terminal (optional)**: real Claude Code TUI rendered inside Minecraft (pty4j + VT emulator), so you never alt-tab.

## Risks
- HotswapAgent on Java 25 has little documentation. If enhanced redefinition misbehaves, the fallback is standard hotswap (method bodies only) plus the reload ladder above.
- Channels are a research preview, so the flags or API may change.
- Claude gets full access to the game JVM and your machine through Claude Code. That's normal Claude Code permissions, and the prompts are relayed into the game.
