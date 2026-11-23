---
name: mc-world
description: See and act in the LIVE Minecraft world - player context, screenshots, run commands, read blocks, message the player, read the game log, and run Java inside the running game. Use whenever a task involves the player's world (give items, build, teleport, check what they see, verify your change worked in-game, debug hotswap errors).
---

# mc-world: see and act in the running game

All through one CLI (game must be running with a world open):

```bash
node tools/mc.mjs game context                    # who/where, looking at, inventory, nearby mobs, biome, time
node tools/mc.mjs game cmd "give @s diamond 5" "time set day"   # any commands, full permissions, prints output
node tools/mc.mjs game blocks x1 y1 z1 x2 y2 z2 [--counts]       # what is built in a box (max 64^3)
node tools/mc.mjs game shot                       # PNG of the player's view -> Read the printed path to look
node tools/mc.mjs game say "Done! Check your hotbar"            # chat message to the player
node tools/mc.mjs game log 40 [--grep Hotswap]    # game log: hotswap results, exceptions, chat
node tools/mc.mjs game eval "<java>"              # Java on the server thread, ~3s, no restart
```

## eval
In scope: `server` (MinecraftServer), `player` (ServerPlayer), `level` (ServerLevel), `mc` (client Minecraft; do UI/render work inside `mc.execute(() -> ...)`), `print(obj)`. Runs on the server thread.
A bare expression is printed. Statements need `;`. Common imports are already there
(java.util, net.minecraft.core/.registries/.component, world.item(.enchantment), world.entity, world.level.block, server.level, resources).
```bash
node tools/mc.mjs game eval "player.getMainHandItem()"
node tools/mc.mjs game eval "var s = new ItemStack(Items.STICK); /* ...components... */ player.getInventory().add(s); print(s);"
```
Mojang names for 26.3. If unsure of an API, check with javap (see CLAUDE.md "Finding Minecraft APIs").
Prefer plain commands (`/give` with components, `/enchant`, `/summon`, `/fill`, `/setblock`) when they can do it; eval for anything else.

## Habits
- After code changes: `node tools/mc.mjs test` (all checks <=10s, no tokens). Never spawn extra Claude sessions just to test.
- Before acting on "this/here/that block", run `context` to resolve what the player means.
- After changing the world or adding content, verify with `shot` (and Read the image) or `blocks`.
- After a code change, `log 30 --grep "Hotswap|Exception"` shows whether the swap applied.
- Tell the player in chat (`say`) when a long task finishes.
