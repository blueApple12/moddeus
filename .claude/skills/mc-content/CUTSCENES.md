# Cutscenes (client/Cutscene.java) — verified live

Camera = invisible client-only Marker set as camera entity: the player stays put and is visible in the shot. Letterbox bars, HUD hidden, input blocked, cursor hidden; Esc / Space skips (`.skippable(false)` to force). Real clock: keeps running during `/tick freeze`. Times in seconds. Ends by itself: restores camera, HUD, FOV.

```java
var c = player.position().add(0, 1, 0);           // in mc game run, or client code
dev.moddeus.client.Cutscene.create()
    .cam(0, c.add(8, 3, 0), c)                    // keyframe: position + look-at point
    .cam(3, c.add(0, 6, 8), c)                    // Catmull-Rom spline through keyframes
    .orbit(3, 9, c, 7, 3, 0, 180)                 // circle: center, radius, height, from/to angle
    .dolly(9, 12, c.add(2,2,2), c.add(10,8,10), c)// straight pull-back
    .follow(12, 15, someEntity, new Vec3(0, 2, -4))  // ride behind an entity (offset turns with it)
    .lookAt(3, 9, () -> boss.getEyePosition())     // aim override on any keyframe path
    .fov(0, 70).fov(9, 40)                        // zoom (interpolated)
    .fadeIn(0, 1).fadeOut(14, 1)                  // or .fade(t, dur, fromARGB, toARGB) / .flash(t, rgb)
    .title(0.5, 3, "THE BOSS", "awakens", 0xFF4040)
    .subtitle(4, 3, "Claude", "Something is wrong...", 0xFF9050)   // or .subtitle(t, dur, "narration")
    .shake(10, 1, 2f)                             // amp ~1-3
    .sound(10, "minecraft:entity.generic.explode", 1f, 0.8f)
    .cmd(10, "summon lightning_bolt ~ ~ ~")       // server command at t
    .server(10, s -> { /* server thread: explosions, spawns, Quick helpers */ })
    .at(10, () -> { /* client thread: VFX */ })
    .bars(true).skippable(true).onEnd(() -> {})
    .play();
```

Named cutscenes (mods): define in client code (e.g. `client/content/<Mod>Cutscenes.java` in its `@OnReload` hook):
`Cutscene.define("Boss.intro", () -> Cutscene.create()...);` then play from anywhere, also server code in singleplayer: `Quick.cutscene("Boss.intro")` (or `Cutscene.play("Boss.intro")` client side). `Cutscene.playing()`, `Cutscene.stop()`.

Directing tips: start with fadeIn, end with fadeOut; 3-6 s per shot; keep the subject in frame (cam(t, pos, lookAt)); combine with VFX (`at`) and real mechanics (`server`) so things actually happen on screen; slow orbit = epic, fast dolly + shake = impact. Test it: play it and `mc game shot` mid-scene.
