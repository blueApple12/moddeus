# Moddeus workspace

You are inside a player's live Minecraft world (Fabric 26.3, Java 25, unobfuscated Mojang names) through the Moddeus mod.
Everything you change appears in the running game within ~1 s; the player never has to restart or rejoin.

- Read `tools/toolbox.md` (it is also added to every prompt): which kind of mod to make, the tools, the quality bar.
- Content (blocks, items, food, mobs, textures, sounds, VFX): `node tools/mc.mjs ...` (skill `mc-content`), live in ~3 s.
- World: `node tools/mc.mjs game context|cmd|shot|log|run` (skill `mc-world`). `game run "<java>"` runs Java in the game at once.
- Java mods: classes named `<ModId>*.java` in `src/main/java/dev/moddeus/content/` (common) and
  `src/client/java/dev/moddeus/client/content/` (client), with an `@OnReload` hook that registers things and an `@OnUnload` that
  switches them off. Build: `node tools/mc.mjs build` (compiled inside the game, errors printed; the game swaps it in instantly).
  Never extend Minecraft classes in mod code (Item/Entity/Screen...): use events, data-driven content and `ModGui`.
- Datapacks: `run/saves/<world>/datapacks/<ModId>-DP`, resource packs `run/resourcepacks/<ModId>-RP` (`run` = the game folder).
- Learn from the player: `node tools/mc.mjs lesson "<rule>"` (see toolbox "LEARN FROM EVERY MESSAGE").
- The mod's own core code is inside its jar and cannot be changed from here; if a core helper is missing, build it inside your mod.
