# Creative tabs + key binds (every mod)

## Creative tab: where the player finds your stuff
Every moddeus item is always in the "Moddeus" catalog tab. On top of that, choose:
- **Small / single thing** (one sword, one food, one block): put it in the fitting VANILLA tab. An emerald sword goes in
  combat, not its own tab (unless the player asked for one).
  `--tab combat | tools | food | building | colored | natural | functional | redstone | ingredients | spawn_eggs`
- **A real mod with several items/blocks/mobs** (Storm Staff, a magic school, a tech line): its OWN tab, named
  after the mod. Its icon is the first item, so make the hero item first. `--tab "Storm Staff"`
- Java content (`mc item|block|armor|mob <id> ... --tab X`): the JSON field `"tab"`. Changing it is live.
- Datapack mods: put `moddeus.json` next to `pack.mcmeta` in `<Id>-DP`:
  ```json
  { "tab": "Storm Staff",
    "icon": "minecraft:carrot_on_a_stick[item_model=\"storm:storm_staff\"]",
    "items": ["<the exact /give item string of every item, with components>", "..."],
    "keys": [{"name": "Snap fingers", "key": "G", "function": "storm:key/snap"}] }
  ```
  It applies on `node tools/mc.mjs game cmd reload` (instant). `items` uses /give syntax (copy it from your give function).
  Switching the mod off hides its tab and disables its keys.

## Key binds: abilities on keys, rebindable
Each mod gets its own section in Options > Controls > Key Binds (titled with the mod's name), so the player sees every key
and can rebind it. Saved rebinds come back after restarts. Taken by Moddeus: **K** (Claude), **G** (mods screen). Avoid vanilla
defaults too (WASD, Space, Shift, Ctrl, Q, E, F, T, L, 1-9, F1-F3, F5). Good free keys: R, V, Z, X, C, B, N, H, J, U, Y, O, P,
Left Alt, Mouse4/Mouse5, Caps Lock.
- Java, common code: `Quick.key("Storm Staff", "Snap fingers", "G", player -> snap(player));` (runs on the server thread as the player). `Quick.keyDown(mod, label)` = held now (charge-ups).
- Java, client code: `Keys.bind(mod, label, key, player -> ...)`, `Keys.bindClient(mod, label, key, () -> ModGui.open(...))`, `Keys.down(mod, label)`.
- Put the binds in the mod's `@OnReload` hook. Re-binding the same mod+label just replaces the action, so it never doubles.
- Datapack: `"keys"` in moddeus.json run `function <ns>:...` as + at the player. Datapacks alone can also read
  movement keys via the `minecraft:player` predicate `input` (forward, backward, left, right, jump, sneak, sprint).
- Key names: A-Z, 0-9, F1-F12, LEFT_ALT, RIGHT_ALT, CTRL, SHIFT, TAB, CAPS_LOCK, SPACE, ENTER, UP/DOWN/LEFT/RIGHT, MOUSE4, MOUSE5, NONE (no default: the player chooses).
- Keys don't fire while a screen is open (chat, inventory, pause).

## Tell the player
After building or changing controls, say them in ONE line, e.g.
"Storm Staff tab in creative. Right-click = cast, Sneak+right-click = second ability, G = snap, R = stone wheel (rebind in Controls > Storm Staff)."
Also put the controls into the item's lore so they're visible in game.
