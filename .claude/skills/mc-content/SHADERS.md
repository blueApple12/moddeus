# Shaders in Moddeus (26.3, vanilla pipeline, NOT shaderpacks)

Three kinds. Pick the lowest that does the job:

| Want | Use | Restart? |
|---|---|---|
| Whole-screen effect (tint, vignette, blur, wobble, invert, "stone power" overlay) | **Post effect** (JSON + .fsh) | no |
| Our own glowing / animated geometry (beams, shields, portals, rainbow surfaces) | **Mod core shader** = own RenderPipeline + RenderType | no |
| Change how ALL vanilla things look (water, sky, entities, terrain) | **Vanilla shader override** (same path in our assets) | no |

All files under `src/main/resources/assets/<ns>/shaders/...` and `.../post_effect/`. After edit: `gradlew processResources`; LightReload sees `/shaders/` or `/post_effect/` and re-runs ShaderManager only (~0.5 s, no loading screen).

GLSL rules (26.3): `#version 330` + `#extension GL_ARB_separate_shader_objects : require`; every in/out has `layout(location = N)`; uniforms are std140 **blocks**, not loose uniforms. Vanilla sources to copy from: `unzip -p <client jar> assets/minecraft/shaders/core/<name>.fsh` (jar in `.gradle/loom-cache/minecraftMaven/net/minecraft/`, clientOnly).

Include blocks you can use: `<minecraft:globals.glsl>` (GameTime, ScreenSize, CameraBlockPos...), `<minecraft:dynamictransforms.glsl>` (ModelViewMat, ColorModulator), `<minecraft:projection.glsl>` (ProjMat), `<minecraft:fog.glsl>`. `GameTime` = day fraction 0..1 (`* 1200.0` = 1 per second) and STOPS during `/tick freeze`; for time-stop-proof animation feed time through vertex color / UV instead.

## 1. Post effect (full screen) — verified live

`assets/moddeus/post_effect/purple.json`:
```json
{ "targets": { "swap": {} },
  "passes": [
    { "vertex_shader": "minecraft:core/screenquad", "fragment_shader": "moddeus:post/purple",
      "inputs": [{ "sampler_name": "In", "target": "minecraft:main" }], "output": "swap",
      "uniforms": { "TintConfig": [{ "name": "Strength", "type": "float", "value": 0.6 }] } },
    { "vertex_shader": "minecraft:core/screenquad", "fragment_shader": "minecraft:post/blit",
      "inputs": [{ "sampler_name": "In", "target": "swap" }], "output": "minecraft:main",
      "uniforms": { "BlitConfig": [{ "name": "ColorModulate", "type": "vec4", "value": [1,1,1,1] }] } } ] }
```
`assets/moddeus/shaders/post/purple.fsh`:
```glsl
#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D InSampler;                       // "In" + Sampler
layout(location = 0) in vec2 texCoord;
layout(std140) uniform SamplerInfo { vec2 OutSize; vec2 InSize; };
layout(std140) uniform TintConfig { float Strength; };   // block name = key in "uniforms"
layout(location = 0) out vec4 fragColor;
void main() {
    vec4 c = texture(InSampler, texCoord);
    float v = smoothstep(0.35, 0.85, length(texCoord - 0.5));
    fragColor = vec4(mix(c.rgb, vec3(0.25, 0.0, 0.4), v * Strength), 1.0);
}
```
Turn on/off (client thread):
```java
mc.player.setActivePostEffects(new ArrayList<>(List.of(Identifier.parse("moddeus:purple"))));  // on
mc.player.setActivePostEffects(new ArrayList<>());                                                // off
```
`GameRenderer.getRequestedPostEffects().add(...)` does NOT work: the list is rebuilt every frame from `player.getActivePostEffects()`. Uniform values are fixed in the JSON; to animate, use `GameTime` (Globals block, include globals.glsl) or switch between several JSONs. Extra targets/passes = multi-pass (blur: see vanilla `post_effect/blur.json`). Vanilla ones: `minecraft:creeper`, `spider`, `invert`, `blur`.

## 2. Mod core shader (own geometry) — verified live

`assets/moddeus/shaders/core/rainbow.vsh`:
```glsl
#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;                 // must match the pipeline's vertex format (Position, Color)
layout(location = 0) out vec4 vertexColor;
layout(location = 1) out vec3 localPos;
void main() { gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0); vertexColor = Color; localPos = Position; }
```
`rainbow.fsh`:
```glsl
#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:globals.glsl>
#include <minecraft:dynamictransforms.glsl>
layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec3 localPos;
layout(location = 0) out vec4 fragColor;
void main() {
    vec3 rgb = 0.5 + 0.5 * cos(6.2831 * (GameTime * 1200.0 + localPos.x * 0.3 + vec3(0.0, 0.33, 0.67)));
    fragColor = vec4(rgb, 1.0) * vertexColor * ColorModulator;
}
```
Java (client; build once, keep in a static, lazy-init):
```java
RenderPipeline PIPE = RenderPipeline.builder(RenderPipelines.LIGHTNING_SNIPPET, RenderPipelines.GLOBALS_SNIPPET) // base = a vanilla snippet
    .withLocation(Identifier.parse("moddeus:pipeline/rainbow"))
    .withVertexShader(Identifier.parse("moddeus:core/rainbow"))
    .withFragmentShader(Identifier.parse("moddeus:core/rainbow"))
    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))   // REQUIRED, see crash below
    .build();
RenderType RT = RenderType.create("rainbow", RenderSetup.builder(PIPE).createRenderSetup());
// in a LevelRenderEvents.COLLECT_SUBMITS handler (or VfxClient.late for after-clouds):
ctx.submitNodeCollector().submitCustomGeometry(poseStack, RT, (pose, vc) -> {
    vc.addVertex(pose, -1, -1, 0).setColor(-1); vc.addVertex(pose, 1, -1, 0).setColor(-1);
    vc.addVertex(pose, 1, 1, 0).setColor(-1);   vc.addVertex(pose, -1, 1, 0).setColor(-1);
});
```
- Packages: `com.mojang.renderpearl.api.pipeline.{RenderPipeline, ColorTargetState, BlendFunction}`, `net.minecraft.client.renderer.RenderPipelines`, `net.minecraft.client.renderer.rendertype.{RenderType, RenderSetup}`.
- Snippet = vertex format + depth + bind groups. Pick the vanilla one whose inputs you want: `LIGHTNING_SNIPPET` (Position+Color, fullbright), `ENTITY_SNIPPET` / `EYES_SNIPPET` / `BEACON_BEAM_SNIPPET` (textured; add `RenderSetup.builder(p).withTexture("Sampler0", id)`), `PARTICLE_SNIPPET`. Copy that vanilla pipeline's vsh inputs exactly.
- **CRASH (happened once):** snippets carry NO color target. Without `withColorTargetState(...)` the first draw throws "Render pass color attachment count must match pipeline color target state count" and kills the game. Before the first draw, compare with the vanilla pipeline: `PIPE.getColorTargetStates().size() == RenderPipelines.LIGHTNING.getColorTargetStates().size()` (also `getDepthStencilState()`, `getVertexFormatBindings()`). Never test a new pipeline mid-fight: draw it for a few seconds with an auto-off first.
- Blend: `BlendFunction.TRANSLUCENT`, `ADDITIVE`, `LIGHTNING` (glow), `GLINT`. Depth: inherited from the snippet.
- Pipeline objects can be rebuilt any time (no registration needed for submitCustomGeometry); shader file edits hot-reload.

## 3. Vanilla shader override (changes every vanilla thing using it) — not yet tested here

Put a file at the SAME path in our assets: `src/main/resources/assets/minecraft/shaders/core/entity.fsh` (or `terrain.fsh`, `sky.fsh`, `clouds.fsh`, `particle.fsh`, `rendertype_lightning.fsh`...). Mod assets layer over vanilla, like a resource pack. Rules:
- Start from the vanilla file (unzip -p, above) and change the minimum. Keep every `in`/`out`/uniform block and `#ifdef` (OIT_* defines) or the pipeline won't link.
- Shaders are shared: `entity.fsh` affects every entity; `terrain.fsh` every chunk. Gate with a condition if you want one thing only.
- To turn it off: delete the file + processResources (LightReload reloads shaders).
- Don't confuse with shaderpacks (Iris/OptiFine): those replace the whole renderer; not used here.

## Picking for abilities
Stone/ability screen tint or "power" overlay -> post effect, on while active, off after. Beam/shield/portal surface -> mod core shader on our geometry. Never a vanilla override for one ability.
