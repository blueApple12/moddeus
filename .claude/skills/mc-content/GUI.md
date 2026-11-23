# Mod GUIs: screens, HUDs, menus (Moddeus)

Toolkit: `dev.moddeus.client.ModGui` (client code, e.g. `client/content/<Id>Gui.java`). Immediate mode: your GUI is a
lambda that draws the whole thing every frame. No Minecraft subclass in mod code, so it stays on the instant reload: edit
the code while the GUI is open and the change shows up in about 10 ms.

```java
ModGui.open("Stone Forge", ui -> { ... });            // a screen (Esc closes; open(title, view, true) pauses the game)
ModGui.hud("Storm Staff", "stones", ui -> { ... }); // HUD overlay drawn every frame; same mod+id = replaced
ModGui.close(); ModGui.removeHud(mod, id); ModGui.isOpen("Stone Forge");
```
Open it from a key bind (`Keys.bindClient(mod, "Open forge", "R", () -> ModGui.open(...))`), an item use, or a block.
Server work from a button: `mc.getSingleplayerServer().execute(() -> ...)` or a key-style server action.

## What `ui` gives you
- Layout + input: `ui.w, ui.h` (screen size), `ui.mx, ui.my`, `hover(x,y,w,h)`, `click(...)`, `rightClick(...)`, `mouseDown()`, `scroll`, `key(glfw)`, `typed()`.
- Time (real clock, keeps running in /tick freeze): `ui.t` seconds open, `ui.dt`, `ui.open` 0..1 eased open animation.
- Motion: `anim(id, target, speed)` smooth per-id values (hover glow, sliding drawers, counting numbers), `easeOut`, `easeInOut`, `pulse(t, hz)`.
- Color: `alpha(argb, a)`, `mix(a, b, f)`, `hsv(h, s, v)`.
- Drawing: `rect`, `gradient`, `border`, `round(x,y,w,h,r,fill,border)`, `glow(...)`, `panel(...)` (glow + rounded box + open scale), `line(x1,y1,x2,y2,thick,c)` (any angle), `arc(cx,cy,r,thick,from,to,c)` (turns, 0 = up), `ring`, `disc`, `item(stack,x,y[,size])`, `image(texture,x,y,w,h)` (your own drawn GUI textures in `assets/moddeus/textures/gui/`), `text`, `shadowText`, `center`, `big(s,x,y,scale,c,center)`, `wrap(s,x,y,width,c)`, `tooltip(text|stack)`, `clip/unclip` (scroll areas), `push/pop` + `ui.g.pose()` (translate/rotate/scale anything).
- Widgets: they only give BEHAVIOUR plus a plain look. Restyle them with their color arguments, or draw your own and use `click`/`anim`. Available: `button`, `iconButton`, `toggle`, `slider`, `bar`, `tabs`, `radial` + `radialPos` (ability wheels), `clickSound`, `sound(id, pitch)`.

## Make it AWESOME, and make it ITS OWN
Every mod's GUI should look like it belongs to that mod and nothing else. Before you code, write one line:
**"theme · shape language · motion · palette"**. Examples:
- Storm Staff: cosmic relic · six gem sockets on a golden gauntlet silhouette · gems pulse and orbit · gold + each stone's color.
- Necromancer tome: cursed book · torn parchment page, ink blots, a skull seal · ink bleeds in, text writes itself · bone, ink black, sickly green.
- Mech suit: military HUD · angled corners, scanlines, brackets · panels slide in with a boot-up flicker · amber on near-black.
- Alchemy table: brass workbench · round flasks and dials · liquids slosh and fill · copper, teal, glass highlights.
- Ice magic: frozen glass · shards and hexagons · frost spreads from the edges, cracks on close · white, pale cyan, deep blue.

Pick the archetype that fits: radial wheel, book/codex, holographic terminal, inventory grid, skill tree, map, carousel of
cards, gauge cluster, crafting altar, minimal corner HUD. **Do not default to "dark rounded panel + orange buttons"**.
That's the toolkit's neutral fallback, not a style.

## Quality checklist
- **Motion has meaning.** It opens with its own entrance (slide, unfold, ignite, glitch-in, ink-spread) using `ui.open`/`anim`. Hover reacts within ~80 ms. Clicks give feedback (scale pop, flash, a fitting sound, not only the default click).
- **Hierarchy.** One focal element, e.g. the selected ability, big and bright. Secondary info is smaller and dimmer. Keep body text ≥ 1x scale; use `big` only for titles and numbers.
- **Readability.** Contrast text against its backdrop (dark plate or shadowText). Nothing important under the crosshair or hotbar. HUDs stay small and live at a screen edge, sized by `ui.w/ui.h` (never fixed pixel positions for 1080p only).
- **Show state, not walls of text.** Cooldown = `arc` dial, charge = `bar`, selection = glow + scale, locked = desaturated + lock icon. Tooltips carry the details.
- **Uses the real items.** `item(stack)` icons, the mod's own textures (`image`), the same colors as the mod's VFX, so GUI and world feel like one thing.
- **Clean.** HUD parts disappear when not relevant (item not held, ability idle): fade them out with `anim`, don't leave them on screen forever. `@OnUnload` → `ModGui.removeHud`.
- **Test it.** Open it in game, then `game shot --ui` and Read the PNG. Check a small window (`ui.w` ~ 400) and a big one. Click everything.

## HUD example (cooldown dial that only shows while it matters)
```java
ModGui.hud("Frost Staff", "cooldown", ui -> {
    float cd = FrostStaff.cooldownLeft();                                   // 0..1
    float vis = ui.anim("vis", cd > 0 || FrostStaff.held() ? 1 : 0, 10);
    if (vis < 0.02f) return;
    int cx = ui.w / 2 + 30, cy = ui.h - 52;
    ui.disc(cx, cy, 11, Ui.alpha(0xFF08131F, 0.7f * vis));
    ui.arc(cx, cy, 10, 2f, 0, 1 - cd, Ui.alpha(Ui.mix(0xFF8FD8FF, 0xFFFFFFFF, Ui.pulse(ui.t, 2) * (cd == 0 ? 1 : 0)), vis));
    ui.item(FrostStaff.icon(), cx - 8, cy - 8);
});
```
(`Ui` = `ModGui.Ui`.)
