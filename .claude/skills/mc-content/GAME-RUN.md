# game run snippets, undoable builds, mod on/off

Learned from the in-game chat transcripts: the mistakes below cost retries in many chats.

## game run
- `node tools/mc.mjs game run '<java>'`: body of a `String run()` method on the server thread. Predefined: `mc`, `server`, `player`, `level`, `print(x)`, Quick (`me() world() cmd() every() after()`). Do not redeclare them.
- Quotes, backslashes or non-ASCII in the Java: write it to a file, then `game run --file <path>` (no shell quoting).
- `return;` ends early (rewritten to return the output); `return "text";` also works.
- Auto-imported: java.util, core, item, entity (+ monster, projectile, item), level, block (+ state), phys, effect, sounds, particles, server.level, server.permissions, levelgen.Heightmap, `Builds`, `Stage`, and the engine classes `Vfx Physics Combat Bosses SoundLoops Systems WorldFeel`.
- Compile errors print a `hint:` line for known 26.3 traps (`tools/runhints.mjs`; add a `[regex, hint]` pair when you hit a new one):
  - screens: `mc.gui.setScreen(null)` / `mc.gui.screen()` (not `mc.setScreen`)
  - permissions: `LevelBasedPermissionSet.OWNER` (not an int)
  - `level.getEntities((Entity) null, box, pred)` (a bare null is ambiguous)
  - `living.drop(stack, false, Prediction.SERVER_ONLY)`; `e.needsSync = true` (no hurtMarked)
  - `EntityTypes.HUSK` (not `EntityType.HUSK`); mob classes in sub-packages
  - private/package members of mod classes: make them public and save (hot-swaps), or use reflection

## No Moddeus slash commands: call the engine in Java
The old engine commands (/vfx /bossattack /mobai /mobvariant /bossarena /bosschest /combat /soundloop /coins /pay /shop /skills /skyevent /toast /cc /physics) are gone (`live/Api.java` strips them). Call the same engines from the mod's class or a `game run` test:
| Old | Java |
|---|---|
| vfx | `Vfx.play(Vfx.id("ns:id"), pos, dir, followOrNull)`; inline layers `Bosses.fx(pos, dir, follow, Bosses.L("ring", "<tex>", "size", 3, ...))` |
| bossattack / mobai / mobvariant | `Bosses.attack(boss, "slam r=6 color=#ff4020")`, `Bosses.behave(e, "flying", "height=6")`, `Bosses.variant(e, "fiery,giant scale=1.5")` |
| bossarena / bosschest | `Bosses.arena(level, pos, Bosses.args("r=12 h=4 style=end name=x"))`, `Bosses.arenaUndo("x")`, `Bosses.chest(level, vec, "minecraft:chests/end_city_treasure", "#ffd040")` |
| combat | `Combat.exec(caster, "dash", "1.6")` or direct: `Combat.dash(p, 1.6)`, `Combat.use(p, "Dash", 40, 20)` |
| soundloop | `SoundLoops.follow(e, "ns:hum", "hum", 1f, 1f, 10)`, `SoundLoops.at(level, pos, ...)`, `SoundLoops.stop(e, "hum")` |
| coins / pay / shop / skills / skyevent | `Systems.coins(p)`, `addCoins(p, n)`, `openShop(p, id)`, `trade(p, id, row, sell)`, `skillLevel(p, id)`, `startSky(server, id)`, `stopSky(server)` |
| toast | `WorldFeel.toast(p, "minecraft:item", "Title", "text", 0xFFE0B040)` |
| physics | `Physics.spawn(level, "ns:id", pos, vel)`, `Quick.physics(state, pos[, vel])` |
Preset names inside these (attack, behaviour, ability, physics preset) are samples: read their code (`live/Bosses.java`, `live/Combat.java`, `tools/library/physics/`) and write the mod's own (BOSSES.md, COMBAT.md, VFX-DESIGN.md). Datapacks use vanilla commands only. `mc command` (a slash command the MOD defines) only when the player asks for one; abilities go on items, keys (`Quick.key`) and GUIs (`ModGui`).

## Mod class skeleton (`src/main/java/dev/moddeus/content/<Id>.java`, hot-reloaded as generations)
```java
public final class FrostMod {
  public static boolean enabled = true;
  static final String MOD = "Frost Mod";
  @OnReload public static void hook() {
    enabled = true;                                        // first
    ServerTickEvents.END_SERVER_TICK.register(FrostMod::tick);   // method refs, never lambdas
    UseItemCallback.EVENT.register(FrostMod::use);
    Quick.key(MOD, "Frost Nova", "R", FrostMod::nova);
  }
  @OnUnload public static void off() { enabled = false; /* clear lists, stop loops, perks off */ }
  static void tick(MinecraftServer s) { if (!enabled) return; /* ... */ }
  public static InteractionResult use(Player p, Level l, InteractionHand h) { if (!enabled) return InteractionResult.PASS; /* ... */ return InteractionResult.PASS; }
  static void nova(ServerPlayer p) { if (!enabled || !Combat.use(p, "Frost Nova", 80, 0)) return; /* ... */ }
}
```
- Mod classes may register in `hook()` every reload: Gens rewrites `Event.register` to stable slots. Core classes register once per JVM (`if (System.getProperties().putIfAbsent("mymod.x", Boolean.TRUE) == null) ...`, see `live/Api.java`). Never rename a registered method. Real example: `content/AbyssalAscension.java` hook()/off().
- Test on your stage, never the player: `mc game stage FrostMod`, then `game run 'Stage s = Stage.of("FrostMod"); s.hold("frost_mod:wand"); print(s.use());'`, finish with `game stage FrostMod clear` (TESTING.md).

## Undoable builds (statues, landmarks, "turn it into diamond")
Players say "remove it" / "revert" a lot, and raw setBlock loops can't put back the trees, grass and water that were there.
```java
Builds.build("zeus_statue", b -> {
  for (...) b.set(x, y, z, Blocks.QUARTZ_BLOCK.defaultBlockState());   // also b.fill(a, c, state), b.clear(a, c)
});
```
- Each block's old state and its block entity NBT are recorded, like `mc struct`. Removal = `node tools/mc.mjs struct undo`. It restores the exact old terrain and also drains fluids that flowed in. `struct ops` lists the history, `struct redo` rebuilds.
- An exception inside the job rolls the build back.
- File: `src/main/java/dev/moddeus/live/Builds.java` (uses `Structures` undo, 64 ops / 4M blocks of history).

## Mod on/off
`node tools/mc.mjs on|off "<Mod>"` does the same as the G screen Mods On/Off: hooks, datapack enable + load (or uninstall + disable), resource pack, creative tab items and keys, saved in projects.json. Fuzzy names work ("creative flight datapack"). A bare `/datapack enable` leaves the mod marked Off with its items hidden.
