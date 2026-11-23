# VFX design: make every effect good AND different
(Deeper background: skills `game-vfx` (lifecycle, budgets, recipes) and `create-game-vfx` (meaning before spectacle).)

The presets (`mc vfx --preset ...`) and layer types are BUILDING BLOCKS, not the answer. If every ability is
"expanding ring + flash + particle burst + shake" in a different color, everything looks the same, and that's a bug the
player has reported. This guide is about deciding WHAT the effect is before choosing layers.

## 1. Write the effect's signature first (one line per ability)
**meaning · material · shape language · motion verb · timing · color story · sound**
- meaning: what must the player understand? (where it hits, how big, that it's charging, that it worked or failed)
- material (pick one that fits the fiction): plasma, fire, embers, smoke, ink, blood, water, ice/glass, crystal, sand/dust, stone/rubble, metal sparks, lightning, light/holy, shadow/void, glitch/digital, paper/runes, petals/leaves, bone, gravity distortion, time (clock shards, afterimages).
- shape language: spiky/shattered, round/soft, angular/geometric, organic/tendrils, linear/slash, columnar, spiral, grid/hex, scattered motes.
- motion verb: implode, explode, shatter, ripple, crawl, drip, rise, rain down, orbit, spiral in, stretch, snap, flicker, unfold, bloom, bleed, freeze, rewind, stutter.
- timing: snappy (2-4 ticks to peak) vs. heavy (slow build, huge payoff) vs. lingering (status), plus a hold or anticipation beat.
- color story: 1 dominant hue, 1 accent, 1 core highlight (often near-white). Value contrast matters more than hue.

**Rule: no two abilities in a mod may share the same material + shape + motion.** Compare against the mod's other effects,
and the last mods you built, before coding. If it reads like a recolor of another effect, redesign it.

## 2. Build by lifecycle, one hero layer
- Phases: **anticipation/telegraph → contact/peak → follow-through → dissipation** (+ lingering status if it means something). The strongest beat lands exactly on the hit and the sound.
- One layer carries the weight (the hero shape). Everything else supports it, so it can be read in a 1-second glance from 10+ blocks away (silhouette test: would it read in grayscale?).
- Use contrast, direction, timing and scale before adding particles. Few particles, placed on purpose.
- Clean up completely: nothing lingers after the effect unless it means something (burning ground = still dangerous).

## 3. Reach for the right technique, not the default
- Textures you DRAW for this effect (`mc vfx-tex` shapes are a start; `pixels vfx <name>` for a unique shape: a specific rune, a claw mark, a cracked-glass star). A custom texture is the fastest way to stop looking generic.
- Layer types beyond ring and flash: pillar (beams, geysers), beam (lasers aligned to the real ray), slash (arcs that match the swing), billboard flipbooks (frames), sky/screen: post effects, a shader (SHADERS.md), a cutscene beat (CUTSCENES.md).
- Real objects: block_display/item_display shards, physics blocks (debris, craters), entities you move, light changes, terrain changes (frost spreading over blocks, then reverting).
- Screen/camera: shake with direction, FOV punch, a brief color grade (post effect) for ultimates only.
- Sound design is half the effect: layer a whoosh/charge + impact + tail; pitch-shift per variant.

## 4. Variety menu (examples, not templates)
| ability | weak, generic version | distinct version |
|---|---|---|
| fire punch | orange ring + particles | a fist-shaped flare that smears along the punch arc, then a scorched handprint decal fades on the block |
| ice spell | blue ring | frost crawls over the ground in hex tiles toward the target; ice pillars snap up in sequence, then shatter into glass shards |
| teleport | purple flash | the body stretches into a vertical slit that zips shut; at the destination, an afterimage unfolds outward |
| heal | green sparkles | petals spiral up from the feet and close into a bud, which blooms with a soft white pulse |
| lightning | yellow line | branching bolt (midpoint displacement) with a 2-tick pre-flash on the target, ground scorch, ozone sparks crawl on metal blocks |
| void/black hole | dark sphere | a lensing ring that bends the background (shader) with an accretion disk of debris, actually pulling blocks in |
| time stop | gray screen | a clock-face sigil cracks across the sky; the world desaturates except the player's effects, and ticking sounds slow down |

## 5. Before saying done
- Watch it in game (`game shot` during the peak frame, and look several times): does each ability read differently from the others at a glance?
- Spam it: no leaks or leftovers, fine on repeat, and it doesn't drop FPS (`game lag`).
- Lined up with the real hit ray or swing. Telegraphs come before damage.
- Ask: "Would a player describe this effect with a different noun than the last one?" If not, redesign it.
