# Pixel art for textures (items, blocks, VFX, GUI)
(Full reference: skill `pixel-art`, with patterns.md and sharp_edges.md.)

`node tools/mc.mjs pixels ...` lets you draw every texture. Good pixel art is a skill; these rules make 16x16 art read well.
- **Palette ramps with hue shifting**: 3-5 shades per material; shadows shift cool (toward blue/purple), highlights warm (toward yellow), ~10-20° hue per step. Saturation peaks in the midtones. Avoid "darker = add black".
- **Contrast**: ~20% lightness between adjacent shades or the form disappears. Check at 1x size.
- **Light from top-left**, consistently. Shade by form (what faces the light), never **pillow shading** (dark edges, light center all around).
- **Selective outline (selout)**: the outline takes a darker shade of the neighbouring color, darker on the shadow side, lighter on the lit side. No pure black outline everywhere.
- **No auto anti-aliasing**: pixels are fully opaque or fully transparent. Manual AA only between inner colors, never against the transparent background (halos).
- **Silhouette first**: fill the shape in one flat color. Is it recognizable? Then shade. Spend detail where the eye goes (gem, blade edge, face).
- **Lines**: 1-2-3 slopes, no doubled corners ("jaggies"), no 1px-wide limbs on things meant to read (use 2px).
- **Palette size**: 8-16 colors per item is plenty; reuse ramps across a mod so its items look like one set.
- **Dithering**: only for big gradients (sky, glow fields), never on small items or faces.
- **Animation**: move colors before positions (subpixel), give anticipation. 4 strong frames beat 12 weak ones.
- **Mod identity**: give each mod its own ramp set and material language (e.g. gauntlet = gold + gems with specular dots; necro = bone + ink). Don't draw every mod in the same purple-glow style.

## How (tools)
- `mc pixels <block|item|entity|particle|vfx|armor|armor_legs|wings|mob_effect> <ns:id> --palette "a=#hex,b=#hex" --rows "row,row,..."` ('.' = transparent, rows split by `,` or `/`) writes `textures/<kind>/<id>.png`. A redraw with the same size and mask goes live in ~1 ms (`client/Pixels`); new textures light-reload.
- `mc template item/diamond_sword` prints a vanilla SHAPE mask only ('#'/'.', no colors): draw over it with your own palette.
- 32 px and up (VFX, 64x64 skins, 64x32 armor): generate the rows with a small Node script (masks, radial falloff, noise, ramps) and pass its output; never type 64 rows by hand or flood-fill one flat color.
- Animated: vertical strip (16 x 16·N) + `<texture>.png.mcmeta` `{"animation": {"frametime": 2, "interpolate": true}}`. VFX layers use `frames`/`loops` instead.
- VFX textures: additive blend makes black invisible: bright hero shape on black/transparent, near-white core. `mc vfx-tex --shape` output is a sample (prints "preset = sample only"): read `tools/vfxlib.mjs` for the falloff math, then draw this mod's own shape.

```bash
# item: icicle shard, 5-step hue-shifted ramp (indigo shadow -> cyan -> warm white), lit edge top-left, selout on the shadow side
mc pixels item frost_mod:icicle --palette "a=#1e2a5a,b=#2f5aa0,c=#4fa0d8,d=#9fe0f0,e=#fff8e0" \
  --rows "......ea,.....edb,....edb.,...dcb..,..dcb...,.cba....,cba.....,a......."
# VFX: three claw rakes, hot core fading to red tips (on transparent: additive)
mc pixels vfx frost_mod_claw --palette "w=#fff0d0,o=#ff8040,r=#a01808" \
  --rows "r..r..r.,.o..o..o,.w..w..w,..w..w..w,..o..o..o,...r..r..r"
```
Unique per mod: own ramp set (hues, shadow direction), own material language, own VFX shapes; never reuse another mod's textures.
