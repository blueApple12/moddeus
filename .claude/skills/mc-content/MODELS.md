# MODELS.md: 3D held objects (weapons, tools, armor pieces)

Every held object gets a real 3D model designed for THIS item: parts you place, textures you draw. Code: `tools/models.mjs`.
Shape templates (sword greatsword axe hammer spear scythe staff wand bow gun shield gauntlet helmet crown) are SAMPLES
(`mc model shapes` lists them; each use prints "preset = sample only"): read their `build` in `tools/models.mjs` to learn
proportions and the part frame, then build your own silhouette with `--model custom --parts` (or `--cubes`).

## Build it
1. Silhouette in one sentence: what makes it THIS item (a cracked crystal head, a clockwork ring, a bone hilt)?
2. Parts: 4-12 boxes, rotated where the shape needs it, one or two glowing parts at most.
3. Textures: one per part, drawn (`mc pixels item <id>_<tex>`, PIXEL-ART.md). Auto-painted ramps are placeholders.
4. Look: `mc game studio item <id>` (offscreen sheet PNG: Read it; never the player's view; TESTING.md).

```bash
# A. staff with a floating, tilted crystal cluster (custom parts, blade frame: y = along the shaft, grip at y=0)
mc item frost_mod:rime_staff --model custom --tab "Frost Mod" --glow core --pulse --parts '[
  {"box":[7,-4,7,9,18,9],"tex":"shaft"},
  {"c":[8,22,8],"s":[3,7,3],"tex":"core","r":[0,45,12]},
  {"c":[6,21,8],"s":[2,5,2],"tex":"shard","r":[0,0,35],"pivot":[8,18,8]},
  {"c":[10.5,20,8],"s":[2,4,2],"tex":"shard","r":[0,30,-40],"pivot":[8,18,8]}]'
# B. clawed gauntlet (cubes, worn on the hand: cuff y=0, fingers up, palm -z)
mc item frost_mod:frost_claw --cubes "4 0 5 12 6 11 cuff; 5 6 6 11 11 10 back; 5 11 6 6 16 7 claw rz=-22.5 glow; 8 11 6 9 16 7 claw glow; 10 11 6 11 15 7 claw rz=22.5 glow" --tex "cuff=frost_claw_cuff,back=frost_claw_back,claw=frost_claw_tip" --wear hand
# C. wrist caster held like a gun (gun frame: y = barrel forward, z = up), core lights up when charged (variant, no Java model code)
mc item frost_mod:frost_caster --model custom --hold gun --parts '[{"box":[-1,-2,-1,1,10,1],"tex":"barrel"},
  {"c":[0,3,-2],"s":[2,4,2],"tex":"grip","r":[-20,0,0]},{"c":[0,9,0],"s":[3,3,3],"tex":"core","r":[45,45,0]}]' \
  --variant "charged:glow=core|colors=core=#e8fbff;using:colors=core=#9fe0ff"
```

## Part reference (`--parts` JSON)
- `box:[x1,y1,z1,x2,y2,z2]` or `c:[x,y,z]` + `s:[w,h,d]`; `tex`; `r:[rx,ry,rz]` euler degrees (any angle, applied x then y then z) about the part centre or `pivot`; `glow`; `faces:{"north":{"uv":[0,0,4,4],"texture":"#gem"}}`.
- Frame (custom + most templates): y = along the weapon, grip at y=0, tip +y; x = width; z = thickness centred on 0. The kit puts it on the vanilla sword diagonal, so 1st/3rd person hold it like a sword. gun: y = barrel forward, z = up. Long boxes split automatically to stay in -16..32.
- Hand/head things use model space: hand = upright right hand (cuff y=0, palm -z); head = around the 0..16 head cube, front -z (`equippable: head`). Shields get `blocks_attacks` + a blocking pose.
- Flags: `--colors tex=#hex`, `--mats tex=metal|gold|wood|leather|wrap|gem|crystal|cloth|bone|stone|string|energy` (auto-paint only if the texture is missing; `--repaint` regenerates; `--tex "blade=other_tex"` reuses one). `--glow a,b` = full-bright parts. `--pulse` = 8-frame pulse on glow/energy textures. `--hold blade|bow|gun|shield|hand|head`, `--gui "rx ry rz"`; GUI/ground/frame/shelf auto-fit the real bounds. `--add`/`--p` only tweak a sample template.
- `--cubes` (blocks and quick items): 0..16 grid, `rx=/ry=/rz=` any angle (several = euler), `glow` per cube, `--wear held|hand|head`.

## States: variants driven by YOUR code
`--variant "<cond>:<overrides>;..."` (item definition, earlier entries win), overrides `p=k=v,..|colors=..|mats=..|glow=..`.
- cond: `using` (right-click held), `charged` (custom_model_data flag 0), `cooldown`, `broken`, `damaged`, `selected`, `carried`, `key:key.sneak`, `cmd<N>` (custom_model_data float >= N), `use<seconds>`.
- From the mod's Java (e.g. while a charge builds, cleared on release):
```java
stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(), List.of(true), List.of(), List.of())); // flag 0 = "charged"
stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(charge * 3f), List.of(), List.of(), List.of())); // cmd1/cmd2/cmd3 stages
stack.remove(DataComponents.CUSTOM_MODEL_DATA); // always reset
```
- Moving parts beyond swaps (spinning rings, opening jaws): a client renderer on the real clock (skill mc-content "Animations", VFX-DESIGN.md own geometry).
- `--icon sprite` = flat GUI icon `textures/item/<id>_icon.png` (draw it); `--icon "p=length=12"` = same shape, other params, GUI only.

## Make it unique to this mod
- [ ] Silhouette you could name blind; not a template with new colors.
- [ ] Every part texture drawn with the mod's own ramps.
- [ ] Glow only where the fiction puts energy; each state (charged/using) visibly different.
- [ ] No two items of the mod share a silhouette. Checked in `studio item` (+ `--nbt` per state).

Vanilla 26.3 model JSON facts: element `rotation` = `{origin, axis, angle}` (any angle) or `{origin, x, y, z}`; `light_emission` 0-15; `shade`; face `uv`, `rotation`, `tintindex`; display contexts incl. `on_shelf`; definition types model, composite, condition, select, range_dispatch, special; definition keys `hand_animation_on_swap`, `oversized_in_gui`, `swap_animation_scale`.
