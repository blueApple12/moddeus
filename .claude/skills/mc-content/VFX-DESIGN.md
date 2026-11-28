# VFX design: every effect written for THIS mod
(Background: skills `game-vfx` (lifecycle, budgets) and `create-game-vfx` (meaning before spectacle).)

There are no finished effects to pick. `mc vfx --preset` / `mc vfx-tex --shape` are SAMPLES (each use prints
"preset = sample only"): read `tools/vfxlib.mjs` to see how a nova or a slash is layered, then write your own. If every
ability is "ring + flash + burst + shake" in another color, the mods all look the same: that is a bug the player reported.

## 1. Write the signature first (one line per ability)
**meaning · material · shape · motion · timing · color · sound**
- meaning: what must the player read? (where it hits, how big, charging, worked/failed)
- material: plasma, fire, embers, smoke, ink, blood, water, ice/glass, crystal, sand, rubble, sparks, lightning, holy light, void, glitch, runes, petals, bone, gravity, time (clock shards, afterimages).
- shape: spiky/shattered, soft, geometric, tendrils, slash, column, spiral, hex grid, scattered motes.
- motion: implode, shatter, ripple, crawl, drip, rise, rain, orbit, stretch, snap, flicker, unfold, bloom, freeze, rewind, stutter.
- timing: snappy (2-4 ticks to peak), heavy (slow build, huge payoff) or lingering (status), plus an anticipation beat.
- color: 1 dominant hue, 1 accent, 1 near-white core. Value contrast beats hue.

**No two abilities (in this mod or your last mods) share material + shape + motion.** Recolor = redesign.

## 2. Lifecycle, one hero layer
telegraph → contact/peak (on the real hit + sound) → follow-through → dissipation. One hero shape carries the read at 10+
blocks (grayscale silhouette test); the rest supports it. Few particles, placed on purpose. Nothing lingers unless it means
something (burning ground = still dangerous).

## 3. The engine: four ways to draw (cheap → fully custom)
| Way | API | Use for |
|---|---|---|
| Layer def (JSON) | `mc vfx <ns>:<id> --layers '[...]'` + textures `mc pixels vfx <name>`; play: `Vfx.play(Vfx.id("ns:id"), Vec3 pos, Vec3 dir, Entity followOrNull)` | fixed-shape effects; edits apply even while playing |
| Layers built per cast | `Bosses.fx(Vec3 pos, Vec3 dir, Entity follow, Bosses.L(type, texture, key, value, ...)...)` | size/length/color from the real cast (charge, distance) |
| Own geometry | client class `client/content/<Id>Client.java`: `LevelRenderEvents.COLLECT_SUBMITS.register(<Id>Client::render)`, draw with `VfxClient.late(ctx, poseStack, RenderTypes.eyes(tex), (pose, vc) -> ...)` | anything layers can't do: spikes, tubes, cracks, bodies, lensing |
| Real things | `Quick.physics(state, pos, vel)`, `Physics.spawn(level, "ns:id", pos, vel)`, `Combat.display(level, pos, item, scale, billboard)`, block edits, `SoundLoops.at/follow` | debris, craters, frost that spreads and reverts, hums |

- Layer types: billboard ring pillar beam slash sphere spiral orbit shards particles flash shake sound. Keys (`tools/mc.mjs` header): delay duration size/size_end alpha/alpha_end color/color_end spin frames loops x/y/z → x_end/y_end/z_end, ease out|in|inout, face, pitch, blend add|alpha; beam `fit` = length of `dir`.
- Own geometry pattern (read `content/AbyssalAscension.java` Fx + `client/content/AbyssalAscensionClient.java`): the server class keeps a static list of live effects (same JVM), the client class draws them camera-relative on the REAL clock (`System.currentTimeMillis()`, keeps animating in `/tick freeze`), removes finished ones. `eyes` = additive glow (fade by darkening; alpha is ignored), `entityTranslucent` = solid-looking. `late` draws after water and clouds.
- Draw your own textures (`mc pixels vfx`, black = invisible in additive): a specific rune, claw mark or cracked-glass star stops "generic" fastest. Camera: directional shake, FOV punch, post effect (SHADERS.md), cutscene beat (CUTSCENES.md) for ultimates only. Sound is half the effect: charge + impact + tail.
- Player-facing extras (titles, screen effects, your own geometry) skip on a stage: `if (Stage.isStage(pos)) { Stage.note(pos, "fx <name>"); return; }` (`Vfx.play` does this itself).

## 4. Worked examples (four different mechanics: copy the technique, not the look)
**A. Geyser: JSON def, telegraph then jet.** Crack ring first (anticipation), the jet 6 ticks later on the sound.
```bash
mc pixels vfx frost_mod_crack ... ; mc pixels vfx frost_mod_jet ...     # draw both: jagged crack, streaky jet
mc vfx frost_mod:geyser --layers '[{"type":"ring","texture":"frost_mod_crack","size":0.3,"size_end":2.5,"duration":8,"blend":"alpha"},
  {"type":"pillar","texture":"frost_mod_jet","delay":6,"size":0.6,"height":0.5,"height_end":7,"ease":"out","duration":14,"alpha_end":0,"planes":4},
  {"type":"sound","delay":6,"sound":"frost_mod:geyser_burst"}]'
```
```java
Vec3 at = Combat.ground(p.level(), Combat.aim(p, 20));
Vfx.play(Vfx.id("frost_mod:geyser"), at, new Vec3(0, 1, 0), null);
Bosses.job(p, 7, (j, age) -> { if (age == 6) Combat.blast(p, at, 2.5, 9, "moddeus:frost", 1.2); }); // damage lands with the jet
```
**B. Ray fitted to the real hit (layers per cast).**
```java
Vec3 from = Combat.hand(p), to = Combat.aim(p, 30);
Bosses.fx(from, to.subtract(from), null,
    Bosses.L("beam", "frost_mod_ray", "fit", true, "size", 0.3, "size_end", 0.05, "grow", 0.1, "duration", 10, "color", "#bff4ff"),
    Bosses.L("billboard", "frost_mod_star", "x", to.x - from.x, "y", to.y - from.y, "z", to.z - from.z, "size", 1.6, "size_end", 0, "duration", 8));
for (LivingEntity e : Combat.enemies(p, to, 1.5)) Combat.damage(e, p, 8, "moddeus:laser");
```
**C. Ice spikes snapping up in sequence (own geometry).** Server: `public static List<Spikes> SPIKES` (start ms, from, dir) filled on use. Client:
```java
public static void render(LevelRenderContext ctx) {
    Minecraft mc = Minecraft.getInstance();
    if (!enabled || mc.level == null) return;
    Vec3 cp = mc.gameRenderer.mainCamera().position();
    long now = System.currentTimeMillis();
    FrostMod.SPIKES.removeIf(s -> now - s.start > 1500);
    for (FrostMod.Spikes s : FrostMod.SPIKES) {
        float age = (now - s.start) / 1000f;
        VfxClient.late(ctx, ctx.poseStack(), RenderTypes.eyes(WHITE), (pose, vc) -> {
            for (int k = 0; k < 10; k++) {
                float h = 1.8f * Mth.clamp((age - k * 0.06f) * 6, 0, 1) * Mth.clamp((1.5f - age) * 2, 0, 1); // up one by one, sink at the end
                Vec3 b = s.from.add(s.dir.scale(1.5 + k)).subtract(cp);
                for (int i = 0; i < 4; i++) { // 4-sided spike: base corner, next corner, tip, tip
                    double a0 = i * Math.PI / 2, a1 = a0 + Math.PI / 2;
                    v(pose, vc, b.x + Math.cos(a0) * .35, b.y, b.z + Math.sin(a0) * .35, 0xFF3A7AC0);
                    v(pose, vc, b.x + Math.cos(a1) * .35, b.y, b.z + Math.sin(a1) * .35, 0xFF3A7AC0);
                    v(pose, vc, b.x, b.y + h, b.z, 0xFFE8FBFF); v(pose, vc, b.x, b.y + h, b.z, 0xFFE8FBFF);
                }
            }
        });
    }
}
static void v(PoseStack.Pose p, VertexConsumer vc, double x, double y, double z, int argb) {
    vc.addVertex(p, (float) x, (float) y, (float) z).setColor(argb).setUv(0.5f, 0.5f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(p, 0, 1, 0);
}
```
(`WHITE` = a small white texture you drew with `mc pixels vfx`; the server damages spike k at tick ~k*1.2 so hits match the geometry.)

**D. Quake: real blocks torn up along a spiral front.**
```java
ServerLevel l = (ServerLevel) p.level(); Vec3 c = p.position();
SoundLoops.at(l, c, "frost_mod:rumble", "quake", 1f, 0.8f, 10);
Bosses.job(p, 30, (j, age) -> {
    double r = 1 + age * 0.25, a = age * 2.4;          // front moves out 5 blocks/s on a golden-angle spiral, not a ring
    BlockPos b = BlockPos.containing(c.add(Math.cos(a) * r, -1, Math.sin(a) * r));
    BlockState s = l.getBlockState(b);
    if (!s.isAir()) { l.removeBlock(b, false); Quick.physics(s, Vec3.atCenterOf(b).add(0, 1, 0), new Vec3(0, 0.6, 0)); }
    if (age == 29) SoundLoops.stop("at:quake");
});
```

## 5. Make it unique to this mod (check before done)
- [ ] Own textures drawn for this mod (no preset shape textures, no other mod's).
- [ ] Own palette: the mod's dominant/accent/core, not the last mod's purple glow.
- [ ] Own timing per ability (snappy vs heavy vs lingering differ inside the mod).
- [ ] Own mechanic: the effect follows the real ray/hit/area, telegraph before damage.
- [ ] Own sound layers (charge + impact + tail), pitched per ability.
- [ ] No two abilities share a look; "would a player name this effect with a different noun than the last one?"
- [ ] Tested on the stage (TESTING.md: report lists every `vfx`/`fx` note), spammed with no leftovers, `game lag` clean.
