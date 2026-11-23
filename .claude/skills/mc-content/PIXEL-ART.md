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
