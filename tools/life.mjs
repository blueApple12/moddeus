// Entities + life for Moddeus: 3D cube-model mobs (presets, auto UV + painted skin, animations, hitbox), animal life
// (food/breeding/taming/riding), sounds, drops, villager trades + custom professions. Called from tools/mc.mjs.
// Guide: .claude/skills/mc-content/LIFE.md
//   node tools/mc.mjs mob <id> --template <any vanilla mob: zombie wolf horse cow villager ghast ...> --model <preset|file.json>
//        [--p "k=v,.."] [--colors "body=#hex,head=#hex,.."] [--glow eyes,core] [--add '<bones json>'] [--clips '<json>'] [--repaint]
//        [--food "wheat,moddeus:berry"] [--tame bone] [--mount "fly=1 speed=0.4 saddle=1"] [--pet]
//        [--sounds "ambient=moddeus:x,hurt=..,death=..,step=..,attack=.."] [--drop "moddeus:fang 1-2 0.5"]...
//   node tools/mc.mjs mobmodel                       presets + params + bone roles
//   node tools/mc.mjs trade <id> --profession farmer|moddeus:<prof>|wandering_trader --wants "20 wheat [+ 1 book]" --gives "2 moddeus:x" [--level 1] [--uses 12] [--xp 5]
//   node tools/mc.mjs painting <id> --size 2x1 --file <png (16 px per block)> [--title "Name"] [--author "Me"]   (painting_variant: active after the next world load)
//   node tools/mc.mjs profession <id> --name "Blacksmith" --workstation moddeus:<block> [--sound <id>] [--colors "apron=#hex,trim=#hex"]
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';

export const HELP = fs.readFileSync(new URL(import.meta.url), 'utf8').split('\n').filter(l => l.startsWith('//')).slice(2).map(l => l.slice(3)).join('\n');
const CMDS = ['mobmodel', 'trade', 'profession', 'painting'];

// ---------- presets: y up from the ground (0), front = -z, 16 px = 1 block --------------------------------------
const box = (from, to, c, extra = {}) => ({ from, to, c, ...extra });
const bone = (name, pivot, cubes, anim, extra = {}) => ({ name, pivot, cubes, ...(anim ? { anim } : {}), ...extra });
const num = (p, k, d) => p[k] !== undefined ? Number(p[k]) : d;

function eyesOn(b, c = 'eye') { b.cubes[0].eyes = c; return b; }

const PRESETS = {
  biped: { about: 'humanoid: head arms legs (zombie/knight/villager-like). p: head=8 body=12 width=8 legs=12 arm=4 horns=0 tail=0',
    colors: { body: '#4a6a3a', head: '#6a8a50', limb: '#3a4a2e', eye: '#ffe060', horn: '#d8d0b8' },
    build(p) {
      const hd = num(p, 'head', 8), bh = num(p, 'body', 12), bw = num(p, 'width', 8), lg = num(p, 'legs', 12), ar = num(p, 'arm', 4), top = lg + bh;
      const bones = [
        bone('body', [0, top, 0], [box([-bw / 2, lg, -2], [bw / 2, top, 2], 'body')], 'body'),
        eyesOn(bone('head', [0, top, 0], [box([-hd / 2, top, -hd / 2], [hd / 2, top + hd, hd / 2], 'head')], 'head')),
        bone('arm_r', [-bw / 2 - ar / 2, top - 2, 0], [box([-bw / 2 - ar, top - 12, -ar / 2], [-bw / 2, top, ar / 2], 'limb')], 'arm:0'),
        bone('arm_l', [bw / 2 + ar / 2, top - 2, 0], [box([bw / 2, top - 12, -ar / 2], [bw / 2 + ar, top, ar / 2], 'limb')], 'arm:1'),
        bone('leg_r', [-2, lg, 0], [box([-4, 0, -2], [0, lg, 2], 'limb')], 'leg:1'),
        bone('leg_l', [2, lg, 0], [box([0, 0, -2], [4, lg, 2], 'limb')], 'leg:0'),
      ];
      if (num(p, 'horns', 0)) bones.push(bone('horns', [0, top + hd, 0], [box([-hd / 2, top + hd, -1], [-hd / 2 + 2, top + hd + 5, 1], 'horn'), box([hd / 2 - 2, top + hd, -1], [hd / 2, top + hd + 5, 1], 'horn')], null, { parent: 'head' }));
      if (num(p, 'tail', 0)) bones.push(bone('tail', [0, lg + 2, 2], [box([-1, lg - 6, 2], [1, lg + 2, 4], 'body')], 'tail'));
      return bones;
    } },
  quadruped: { about: 'four legs (wolf/bear/boar/cow-like). p: length=14 width=8 height=8 legs=6 leg=3 head=7 snout=3 ears=1 tail=1 horns=0 tusks=0',
    colors: { body: '#7a5a3a', belly: '#b89a70', head: '#8a6a44', limb: '#5a4028', snout: '#3a2a20', eye: '#ffd040', horn: '#e8e0c8' },
    build(p) {
      const L = num(p, 'length', 14), W = num(p, 'width', 8), H = num(p, 'height', 8), lg = num(p, 'legs', 6), lw = num(p, 'leg', 3), hs = num(p, 'head', 7), sn = num(p, 'snout', 3);
      const fz = -L / 2 + lw / 2 + 1, bz = L / 2 - lw / 2 - 1, lx = W / 2 - lw / 2, hy = lg + H - Math.round(hs / 2);
      const leg = (n, x, z, a) => bone(n, [x, lg, z], [box([x - lw / 2, 0, z - lw / 2], [x + lw / 2, lg, z + lw / 2], 'limb')], a);
      const head = eyesOn(bone('head', [0, hy, -L / 2 + 1], [box([-hs / 2, hy - 1, -L / 2 + 1 - hs], [hs / 2, hy - 1 + hs, -L / 2 + 1], 'head')], 'head'));
      if (sn) head.cubes.push(box([-Math.ceil(hs / 3), hy - 1, -L / 2 + 1 - hs - sn], [Math.ceil(hs / 3), hy + 2, -L / 2 + 1 - hs], 'snout'));
      const bones = [
        bone('body', [0, lg + H / 2, 0], [box([-W / 2, lg, -L / 2], [W / 2, lg + H, L / 2], 'body'), box([-W / 2 + 1, lg - 1, -L / 2 + 2], [W / 2 - 1, lg, L / 2 - 2], 'belly')], 'breathe'),
        head, leg('leg_rf', -lx, fz, 'leg:1'), leg('leg_lf', lx, fz, 'leg:0'), leg('leg_rh', -lx, bz, 'leg:0'), leg('leg_lh', lx, bz, 'leg:1'),
      ];
      const ht = hy - 1 + hs, hz = -L / 2 + 1 - hs;
      if (num(p, 'ears', 1)) { bones.push(bone('ear_r', [-hs / 2 + 1, ht, hz + 2], [box([-hs / 2, ht, hz + 1], [-hs / 2 + 2, ht + 3, hz + 3], 'head')], 'ear:0', { parent: 'head' }), bone('ear_l', [hs / 2 - 1, ht, hz + 2], [box([hs / 2 - 2, ht, hz + 1], [hs / 2, ht + 3, hz + 3], 'head')], 'ear:1', { parent: 'head' })); }
      if (num(p, 'horns', 0)) bones.push(bone('horns', [0, ht, hz + 3], [box([-hs / 2 - 2, ht - 1, hz + 2], [-hs / 2, ht + 4, hz + 4], 'horn'), box([hs / 2, ht - 1, hz + 2], [hs / 2 + 2, ht + 4, hz + 4], 'horn')], null, { parent: 'head' }));
      if (num(p, 'tusks', 0)) head.cubes.push(box([-Math.ceil(hs / 3) - 1, hy - 1, hz - sn], [-Math.ceil(hs / 3), hy + 3, hz - sn + 1], 'horn'), box([Math.ceil(hs / 3), hy - 1, hz - sn], [Math.ceil(hs / 3) + 1, hy + 3, hz - sn + 1], 'horn'));
      if (num(p, 'tail', 1)) bones.push(bone('tail', [0, lg + H - 1, L / 2], [box([-1, lg + H - 8, L / 2], [1, lg + H - 1, L / 2 + 2], 'body')], 'tail'));
      return bones;
    } },
  bird: { about: 'two legs + wings (chicken/owl/parrot-like). p: size=1 crest=1 tail=1',
    colors: { body: '#d8d0c0', head: '#e8e0d0', wing: '#b8b0a0', beak: '#f0a020', limb: '#d08020', eye: '#202020', crest: '#d02020' },
    build(p) {
      const bones = [
        bone('body', [0, 7, 0], [box([-3, 4, -4], [3, 10, 4], 'body')], 'breathe'),
        eyesOn(bone('head', [0, 9, -3], [box([-2, 9, -6], [2, 14, -2], 'head'), box([-1, 10, -8], [1, 12, -6], 'beak')], 'head')),
        bone('wing_r', [-3, 10, 0], [box([-4, 5, -3], [-3, 10, 4], 'wing')], 'wing:0'),
        bone('wing_l', [3, 10, 0], [box([3, 5, -3], [4, 10, 4], 'wing')], 'wing:1'),
        bone('leg_r', [-1.5, 4, 0], [box([-2, 0, -1], [-1, 4, 0], 'limb'), box([-3, 0, -3], [0, 1, 0], 'limb')], 'leg:1'),
        bone('leg_l', [1.5, 4, 0], [box([1, 0, -1], [2, 4, 0], 'limb'), box([0, 0, -3], [3, 1, 0], 'limb')], 'leg:0'),
      ];
      if (num(p, 'crest', 1)) bones.push(bone('crest', [0, 14, -4], [box([-0.5, 14, -6], [0.5, 16, -3], 'crest')], null, { parent: 'head' }));
      if (num(p, 'tail', 1)) bones.push(bone('tail', [0, 9, 4], [box([-2, 8, 4], [2, 12, 7], 'wing')], 'tail*0.5'));
      return bones;
    } },
  flyer: { about: 'hovering body + big wings (bat/wisp/manta-like). p: span=12 body=6 tail=1',
    colors: { body: '#3a2a4a', head: '#4a3a5a', wing: '#2a1a3a', eye: '#ff40a0' },
    build(p) {
      const s = num(p, 'span', 12), b = num(p, 'body', 6), y = 14;
      const bones = [
        bone('body', [0, y, 0], [box([-b / 2, y - b / 2, -b / 2], [b / 2, y + b / 2, b / 2], 'body')], 'bob breathe'),
        eyesOn(bone('head', [0, y + b / 2, -b / 2], [box([-b / 2 + 1, y, -b / 2 - 4], [b / 2 - 1, y + b / 2 + 1, -b / 2], 'head')], 'head', { parent: 'body' })),
        bone('wing_r', [-b / 2, y + 1, 0], [box([-b / 2 - s, y, -3], [-b / 2, y + 1, 5], 'wing')], 'wing:0*1.2', { parent: 'body' }),
        bone('wing_l', [b / 2, y + 1, 0], [box([b / 2, y, -3], [b / 2 + s, y + 1, 5], 'wing')], 'wing:1*1.2', { parent: 'body' }),
      ];
      if (num(p, 'tail', 1)) bones.push(bone('tail', [0, y - 1, b / 2], [box([-1, y - 2, b / 2], [1, y, b / 2 + 8], 'body')], 'tail', { parent: 'body' }));
      return bones;
    } },
  serpent: { about: 'segmented body, head + jaw (worm/snake/eel/sandworm-like). p: segments=6 size=6 length=6 taper=1 fins=0',
    colors: { body: '#5a7a2a', head: '#6a8a3a', jaw: '#4a5a20', eye: '#ffe000', fin: '#c0d060' },
    build(p) {
      const n = num(p, 'segments', 6), s0 = num(p, 'size', 6), len = num(p, 'length', 6), tp = num(p, 'taper', 1);
      const z0 = -(n * len) / 2, hy = s0 / 2;
      const bones = [eyesOn(bone('head', [0, hy, z0], [box([-s0 / 2 - 1, 0, z0 - s0], [s0 / 2 + 1, s0 * 0.7 | 0, z0], 'head')], 'head lunge')),
        bone('jaw', [0, 1, z0], [box([-s0 / 2, 0, z0 - s0 + 1], [s0 / 2, 1, z0], 'jaw')], 'jaw', { parent: 'head' })];
      let parent = null;
      for (let i = 0; i < n; i++) {
        const s = Math.max(2, Math.round(s0 - (tp ? i * (s0 - 2) / Math.max(1, n - 1) : 0))), z = z0 + i * len;
        const b = bone('seg' + i, [0, s / 2, z], [box([-s / 2, 0, z], [s / 2, s, z + len], 'body')], 'segment:' + (i + 1), parent ? { parent } : {});
        if (num(p, 'fins', 0) && i % 2 === 0) b.cubes.push(box([-0.5, s, z + 1], [0.5, s + 2, z + len - 1], 'fin'));
        bones.push(b); parent = 'seg' + i;
      }
      return bones;
    } },
  spider: { about: 'eight legs, thorax + abdomen (spider/crab/scorpion-like). p: size=1 legs=14 tail=0 (scorpion tail)',
    colors: { body: '#2a2228', belly: '#4a2a30', head: '#3a2a30', limb: '#2a2026', eye: '#ff2020', fin: '#a02020' },
    build(p) {
      const ll = num(p, 'legs', 14), bones = [
        bone('thorax', [0, 7, -1], [box([-4, 4, -4], [4, 9, 2], 'body')], 'body'),
        eyesOn(bone('head', [0, 7, -4], [box([-3, 4, -9], [3, 9, -4], 'head')], 'head*0.5')),
        bone('abdomen', [0, 7, 2], [box([-5, 3, 2], [5, 11, 13], 'belly')], 'breathe*2'),
      ];
      [-3, -1, 1, 3].forEach((z, i) => {
        bones.push(bone('leg_r' + i, [-4, 7, z], [box([-4 - ll, 6, z - 1], [-4, 8, z + 1], 'limb')], 'crawl:' + (i % 2) + '*1', { rot: [0, (i - 1.5) * 18, -30] }));
        bones.push(bone('leg_l' + i, [4, 7, z], [box([4, 6, z - 1], [4 + ll, 8, z + 1], 'limb')], 'crawl:' + ((i + 1) % 2) + '*-1', { rot: [0, -(i - 1.5) * 18, 30] }));
      });
      if (num(p, 'tail', 0)) bones.push(bone('stinger', [0, 10, 12], [box([-1.5, 10, 11], [1.5, 22, 14], 'fin'), box([-1, 20, 6], [1, 23, 11], 'fin')], 'sway', { parent: 'abdomen' }));
      return bones;
    } },
  slime: { about: 'bouncy cube with a core (slime/jelly/ooze). p: size=12 core=6',
    colors: { body: '#60d080', head: '#40a060', eye: '#103020' },
    build(p) {
      const s = num(p, 'size', 12), c = num(p, 'core', 6);
      return [bone('core', [0, s / 2, 0], [box([-c / 2, s / 2 - c / 2, -c / 2], [c / 2, s / 2 + c / 2, c / 2], 'head', { eyes: 'eye' })], 'breathe*2'),
        bone('shell', [0, 0, 0], [box([-s / 2, 0, -s / 2], [s / 2, s, s / 2], 'body')], 'breathe*4')];
    } },
  golem: { about: 'huge biped with long arms (golem/troll/titan boss). p: width=18 body=12 legs=10 arm=6 armlen=26 head=8',
    colors: { body: '#7a7468', head: '#8a8478', limb: '#6a6458', eye: '#ff6020', horn: '#40ff90' },
    build(p) {
      const w = num(p, 'width', 18), bh = num(p, 'body', 12), lg = num(p, 'legs', 10), ar = num(p, 'arm', 6), al = num(p, 'armlen', 26), hd = num(p, 'head', 8), top = lg + bh;
      return [
        bone('body', [0, lg + 4, 0], [box([-w / 2, lg + 4, -5], [w / 2, top, 5], 'body'), box([-w / 2 + 3, lg, -3], [w / 2 - 3, lg + 4, 3], 'limb')], 'body breathe'),
        eyesOn(bone('head', [0, top - 2, -3], [box([-hd / 2, top - 2, -hd / 2 - 3], [hd / 2, top + hd - 2, hd / 2 - 3], 'head')], 'head')),
        bone('arm_r', [-w / 2 - ar / 2, top - 3, 0], [box([-w / 2 - ar, top - al, -ar / 2], [-w / 2, top, ar / 2], 'limb')], 'arm:0*0.6'),
        bone('arm_l', [w / 2 + ar / 2, top - 3, 0], [box([w / 2, top - al, -ar / 2], [w / 2 + ar, top, ar / 2], 'limb')], 'arm:1*0.6'),
        bone('leg_r', [-4, lg, 0], [box([-7, 0, -3], [-1, lg, 3], 'limb')], 'leg:1*0.7'),
        bone('leg_l', [4, lg, 0], [box([1, 0, -3], [7, lg, 3], 'limb')], 'leg:0*0.7'),
      ];
    } },
  dragon: { about: 'boss: four legs, long neck + head with jaw, huge wings, long tail. p: length=24 width=14 height=12 legs=10 neck=3 tail=5 span=30 horns=1',
    colors: { body: '#6a1a20', belly: '#d0a060', head: '#7a2028', jaw: '#5a1218', limb: '#4a1016', wing: '#3a0a14', eye: '#ffd000', horn: '#e8dcc0' },
    build(p) {
      const L = num(p, 'length', 24), W = num(p, 'width', 14), H = num(p, 'height', 12), lg = num(p, 'legs', 10), nk = num(p, 'neck', 3), tl = num(p, 'tail', 5), sp = num(p, 'span', 30);
      const lx = W / 2 - 2, fz = -L / 2 + 3, bz = L / 2 - 3, top = lg + H;
      const leg = (n, x, z, a) => bone(n, [x, lg + 2, z], [box([x - 2.5, 0, z - 2.5], [x + 2.5, lg + 2, z + 2.5], 'limb'), box([x - 3, 0, z - 4], [x + 3, 1, z + 1], 'limb')], a);
      const bones = [bone('body', [0, lg + H / 2, 0], [box([-W / 2, lg, -L / 2], [W / 2, top, L / 2], 'body'), box([-W / 2 + 2, lg - 1, -L / 2 + 2], [W / 2 - 2, lg, L / 2 - 2], 'belly')], 'breathe body'),
        leg('leg_rf', -lx, fz, 'leg:1*0.8'), leg('leg_lf', lx, fz, 'leg:0*0.8'), leg('leg_rh', -lx, bz, 'leg:0*0.8'), leg('leg_lh', lx, bz, 'leg:1*0.8'),
        bone('wing_r', [-W / 2, top - 1, -2], [box([-W / 2 - sp, top - 2, -6], [-W / 2, top - 1, 8], 'wing'), box([-W / 2 - sp, top - 1, -6], [-W / 2, top, -4], 'limb')], 'wing:0*0.8', { parent: 'body' }),
        bone('wing_l', [W / 2, top - 1, -2], [box([W / 2, top - 2, -6], [W / 2 + sp, top - 1, 8], 'wing'), box([W / 2, top - 1, -6], [W / 2 + sp, top, -4], 'limb')], 'wing:1*0.8', { parent: 'body' })];
      let par = 'body', z = -L / 2, y = top - 4;
      for (let i = 0; i < nk; i++) { bones.push(bone('neck' + i, [0, y + 3, z], [box([-3, y, z - 6], [3, y + 6, z], 'body')], 'look*0.3 wave:' + i, { parent: par })); par = 'neck' + i; z -= 6; y += 2; }
      const head = eyesOn(bone('head', [0, y + 3, z], [box([-5, y, z - 12], [5, y + 8, z], 'head')], 'look*0.4 lunge', { parent: par }));
      bones.push(head, bone('jaw', [0, y + 1, z - 1], [box([-4, y - 2, z - 11], [4, y + 1, z - 1], 'jaw')], 'jaw*1.4', { parent: 'head' }));
      if (num(p, 'horns', 1)) bones.push(bone('horns', [0, y + 8, z - 2], [box([-5, y + 7, z - 3], [-3, y + 14, z], 'horn'), box([3, y + 7, z - 3], [5, y + 14, z], 'horn')], null, { parent: 'head' }));
      par = 'body'; z = L / 2; y = top - 5;
      for (let i = 0; i < tl; i++) { const s = Math.max(2, 6 - i); bones.push(bone('tail' + i, [0, y + s / 2, z], [box([-s / 2, y, z], [s / 2, y + s, z + 7], 'body')], 'segment:' + (i + 1), { parent: par })); par = 'tail' + i; z += 7; }
      return bones;
    } },
};

// ---------- texture: box-UV packing + painting ------------------------------------------------------------------
const hex = h => { h = String(h).replace('#', ''); return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16)]; };
const mix = (a, b, t) => a.map((v, i) => Math.round(v + (b[i] - v) * t));
const shade = (c, k) => k >= 0 ? mix(c, [255, 236, 190], k) : mix(c, [18, 16, 46], -k); // warm highlights, cool shadows
const hash = (x, y, s) => { let h = (x * 374761393 + y * 668265263 + s * 2147483647) | 0; h = (h ^ (h >>> 13)) * 1274126177; return ((h ^ (h >>> 16)) >>> 0) / 4294967296; };
const dims = c => [0, 1, 2].map(i => Math.max(1, Math.round(c.to[i] - c.from[i])));

function pack(cubes) {
  const items = cubes.map(c => { const [w, h, d] = dims(c); return { c, W: 2 * (w + d), H: d + h }; }).sort((a, b) => b.H - a.H);
  for (const tw of [64, 128, 256]) {
    let x = 0, y = 0, row = 0, ok = true;
    for (const it of items) {
      if (it.W > tw) { ok = false; break; }
      if (x + it.W > tw) { x = 0; y += row; row = 0; }
      it.c.uv = [x, y]; x += it.W; row = Math.max(row, it.H);
    }
    const used = y + row;
    if (ok && used <= tw * 2) { let th = 32; while (th < used) th *= 2; return [tw, th]; }
  }
  throw new Error('model too big for a 256 texture');
}

function paint(cubes, tw, th, colors, glowKeys, pattern, seed) {
  const px = Buffer.alloc(tw * th * 4), gl = Buffer.alloc(tw * th * 4);
  let anyGlow = false;
  const put = (buf, x, y, c, a = 255) => { if (x < 0 || y < 0 || x >= tw || y >= th) return; const i = (y * tw + x) * 4; buf[i] = c[0]; buf[i + 1] = c[1]; buf[i + 2] = c[2]; buf[i + 3] = a; };
  for (const cb of cubes) {
    const [w, h, d] = dims(cb), [u, v] = cb.uv, base = hex(colors[cb.c] || (/^#[0-9a-f]{6}$/i.test(cb.c || '') ? cb.c : null) || colors.body || '#808080'), glow = glowKeys.includes(cb.c);
    // faces: [x, y, w, h, kind]
    const faces = [[u + d, v, w, d, 'top'], [u + d + w, v, w, d, 'bottom'], [u, v + d, d, h, 'side'], [u + d, v + d, w, h, 'front'], [u + d + w, v + d, d, h, 'side'], [u + 2 * d + w, v + d, w, h, 'side']];
    for (const [fx, fy, fw, fh, kind] of faces) for (let j = 0; j < fh; j++) for (let i = 0; i < fw; i++) {
      let k = kind === 'top' ? 0.16 : kind === 'bottom' ? -0.3 : 0.1 - 0.28 * (fh > 1 ? j / (fh - 1) : 0);
      k += (hash(fx + i, fy + j, seed) - 0.5) * 0.12;
      if (i === 0 || j === 0 || i === fw - 1 || j === fh - 1) k -= 0.1;
      if (pattern === 'stripes' && kind !== 'bottom' && (i + (kind === 'top' ? j : 0)) % 4 === 0) k -= 0.22;
      if (pattern === 'spots' && hash(Math.floor((fx + i) / 2), Math.floor((fy + j) / 2), seed + 7) > 0.82) k -= 0.25;
      if (pattern === 'scales' && (i + (j % 2) * 1) % 2 === 0 && j % 2 === 0) k -= 0.12;
      const col = shade(base, k);
      put(px, fx + i, fy + j, col);
      if (glow) { put(gl, fx + i, fy + j, shade(base, 0.2)); anyGlow = true; }
    }
    if (cb.eyes) { // two eyes on the front face, upper third
      const fx = u + d, fy = v + d, ey = fy + Math.max(1, Math.floor(h / 3)), e = hex(colors[cb.eyes] || '#ffe060'), ew = w >= 6 ? 2 : 1, gap = Math.max(1, Math.floor(w / 4));
      for (const ex of [fx + gap - (ew > 1 ? 1 : 0), fx + w - gap - 1]) for (let i = 0; i < ew; i++) {
        put(px, ex + i, ey, shade(e, 0.1)); if (h >= 6) put(px, ex + i, ey + 1, shade(e, -0.35));
        if (glowKeys.includes('eyes') || glowKeys.includes(cb.eyes)) { put(gl, ex + i, ey, shade(e, 0.25)); if (h >= 6) put(gl, ex + i, ey + 1, e); anyGlow = true; }
      }
    }
  }
  return { px, gl, anyGlow };
}

function egg(colors) {
  const a = hex(colors.body || '#808080'), b = hex(colors.eye || colors.head || '#ffffff'), px = Buffer.alloc(16 * 16 * 4);
  for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
    const dx = (x - 7.5) / 5.6, dy = (y - 8.6) / (y < 8.6 ? 7.4 : 6.6), r = dx * dx + dy * dy;
    if (r > 1) continue;
    let c = shade(a, 0.25 - 0.5 * (y / 15) - (dx > 0.3 ? 0.08 : 0));
    if (hash(x >> 1, y >> 1, 3) > 0.72) c = shade(b, 0.1 - 0.3 * (y / 15));
    if (r > 0.78) c = shade(a, -0.55);
    if (r < 0.2 && x < 7 && y < 6) c = shade(a, 0.45);
    const i = (y * 16 + x) * 4; px[i] = c[0]; px[i + 1] = c[1]; px[i + 2] = c[2]; px[i + 3] = 255;
  }
  return px;
}

const kv = s => Object.fromEntries(String(s || '').split(/[,;]/).map(x => x.trim()).filter(Boolean).map(x => { const [k, ...v] = x.split('='); return [k.trim(), v.join('=').trim()]; }));
let CONTENT_DIR = null; // set per call: a bare id of our content resolves to its mod's namespace, anything else to minecraft:
const ns = s => { if (s.includes(':')) return s; const f = CONTENT_DIR && path.join(CONTENT_DIR, s + '.json'); return (f && fs.existsSync(f) ? JSON.parse(fs.readFileSync(f, 'utf8')).namespace || 'moddeus' : 'minecraft') + ':' + s; };
const many = v => v === undefined ? [] : Array.isArray(v) ? v : [v];

/** mc mob: model/texture/hitbox/life keys, added to the content JSON c before it is written. */
export async function mobExtras(id, c, opt, ctx) {
  const A = ctx.A, NS = ctx.NS || path.basename(A); // the mob's namespace: skin, glow, loot all go under it
  CONTENT_DIR = ctx.CONTENT || path.join(A, '..', '..', 'moddeus', 'content');
  if (opt.model) {
    let bones, colors = {}, src = String(opt.model);
    const P = kv(opt.p);
    if (PRESETS[src]) { bones = PRESETS[src].build(P); colors = { ...PRESETS[src].colors }; }
    else { const j = JSON.parse(fs.existsSync(src) ? fs.readFileSync(src, 'utf8') : src); bones = j.bones || j; colors = j.colors || {}; if (j.clips && !opt.clips) opt.clips = JSON.stringify(j.clips); if (j.glow && !opt.glow) opt.glow = [].concat(j.glow).join(','); }
    if (opt.add) bones.push(...JSON.parse(opt.add));
    Object.assign(colors, kv(opt.colors));
    const cubes = bones.flatMap(b => b.cubes || []);
    const [tw, th] = pack(cubes);
    const glow = String(opt.glow || '').split(',').map(s => s.trim()).filter(Boolean);
    const scale = Number(P.scale || opt['model-scale'] || 1);
    const model = { tw, th, bones: bones.map(b => ({ ...b, cubes: (b.cubes || []).map(({ c: k, eyes, ...r }) => r) })) };
    if (scale !== 1) model.scale = scale;
    if (opt.clips) model.clips = JSON.parse(opt.clips);
    if (opt.translucent) model.translucent = true;
    const tex = path.join(A, 'textures/entity', id + '.png'), gtex = path.join(A, 'textures/entity', id + '_glow.png');
    const { px, gl, anyGlow } = paint(cubes, tw, th, colors, glow, P.pattern, id.length * 31 + 7);
    // a kept (maybe hand-drawn) skin only fits the SAME UV layout: a changed model repaints, else its UVs land on the old skin's pixels
    model.layout = cubes.map(cb => cb.uv + ':' + dims(cb)).join('|').length + '-' + [...cubes.map(cb => cb.uv + ':' + dims(cb)).join('|')].reduce((h, ch) => (h * 31 + ch.charCodeAt(0)) | 0, 7);
    model.paint = [...JSON.stringify([colors, glow, P.pattern || ''])].reduce((h, ch) => (h * 31 + ch.charCodeAt(0)) | 0, 7);
    let oldLayout = null, oldPaint;
    try { const om = JSON.parse(fs.readFileSync(path.join(CONTENT_DIR, id + '.json'), 'utf8')).model; oldLayout = om?.layout ?? null; oldPaint = om?.paint; } catch {}
    // new colors/glow repaint too (a hand-drawn skin with an unchanged palette is kept; old specs without a paint key repaint only for explicit --colors)
    const repaint = opt.repaint || oldLayout !== model.layout || (oldPaint !== undefined ? oldPaint !== model.paint : !!opt.colors);
    if (!fs.existsSync(tex) || repaint) ctx.png(tex, tw, th, px);
    if (anyGlow && (!fs.existsSync(gtex) || repaint)) ctx.png(gtex, tw, th, gl);
    if (anyGlow || fs.existsSync(gtex)) { model.glow = true; c.files.push(path.relative(path.join(A, '..', '..'), gtex).replace(/\\/g, '/')); }
    const egg16 = path.join(A, 'textures/item', id + '_spawn_egg.png');
    if (!fs.existsSync(egg16) || opt['repaint-egg']) ctx.png(egg16, 16, 16, egg(colors));
    c.model = model;
    // hitbox from the rest-pose bounds (px -> blocks); long bodies get extra part hitboxes along z
    const mn = [1e9, 1e9, 1e9], mx = [-1e9, -1e9, -1e9];
    for (const cb of cubes) for (let i = 0; i < 3; i++) { mn[i] = Math.min(mn[i], cb.from[i], cb.to[i]); mx[i] = Math.max(mx[i], cb.from[i], cb.to[i]); }
    const bw = (mx[0] - mn[0]) / 16 * scale, bl = (mx[2] - mn[2]) / 16 * scale, bh = Math.max(0.3, mx[1] / 16 * scale);
    const legal = s => Math.round(Math.min(Math.max(s, 0.3), 16) * 100) / 100;
    if (bl > Math.max(bw, 1) * 1.6 && bl > 2) {
      const w = legal(Math.min(bw, 6)); c.hitbox = [w, legal(bh)];
      const n = Math.ceil(bl / w), cz = (mn[2] + mx[2]) / 2 / 16 * scale;
      c.parts = [];
      for (let i = 0; i < n; i++) { const z = Math.round((mn[2] / 16 * scale + (i + 0.5) * bl / n) * 100) / 100; if (Math.abs(z) > w / 2) c.parts.push({ z: -z, w, h: legal(bh * 0.8) }); }
      c.parts = c.parts.filter(Boolean); if (!c.parts.length) delete c.parts; void cz;
    } else c.hitbox = [legal(Math.max(bw, bl * 0.8)), legal(bh)];
    console.log('  model: ' + bones.length + ' bones, ' + cubes.length + ' cubes, skin ' + tw + 'x' + th + ' (redraw: mc pixels entity ' + id + ' or --repaint), hitbox ' + c.hitbox.join('x') + (c.parts ? ' + ' + c.parts.length + ' part hitboxes' : ''));
  }
  if (opt.food) c.food = String(opt.food).split(',').map(s => ns(s.trim()));
  if (opt.tame) c.tame = ns(String(opt.tame === true ? 'bone' : opt.tame));
  if (opt['tame-chance']) c.tame_chance = Number(opt['tame-chance']);
  const behave = {};
  if (opt.pet) behave.pet = opt.pet === true ? '' : String(opt.pet);
  if (opt.mount) behave.mount = opt.mount === true ? '' : String(opt.mount);
  if (Object.keys(behave).length) c.behave = behave;
  if (opt.sounds) { // ids, or files/URLs (imported mono at vanilla loudness as <id>_<kind>; SOUNDS.md)
    c.sounds = kv(opt.sounds); for (const k in c.sounds) c.sounds[k] = /[\\/]|\.(ogg|mp3|wav|flac|m4a|aac|opus|webm)$|^https?:/i.test(c.sounds[k]) ? mobSound(ctx, id + '_' + k, c.sounds[k]) : ns(c.sounds[k]);
    if (opt['ambient-every']) c.sounds.ambient_every = Number(opt['ambient-every']); // avg ticks between ambient sounds (default 100; vanilla mobs ~80-120)
  }
  const drops = many(opt.drop);
  if (drops.length) {
    const pools = drops.map(s => {
      const [item, cnt = '1', ch] = String(s).trim().split(/\s+/), [a, b] = cnt.split('-').map(Number);
      const e = { type: 'minecraft:item', name: ns(item), functions: [{ function: 'minecraft:set_count', count: b ? { type: 'minecraft:uniform', min: a, max: b } : a }, { function: 'minecraft:enchanted_count_increase', enchantment: 'minecraft:looting', count: { type: 'minecraft:uniform', min: 0, max: 1 } }] };
      const pool = { rolls: 1, entries: [e] };
      if (ch) pool.conditions = [{ condition: 'minecraft:random_chance_with_enchanted_bonus', enchantment: 'minecraft:looting', unenchanted_chance: Number(ch), enchanted_chance: { type: 'minecraft:linear', base: Math.min(1, Number(ch) + 0.1), per_level_above_first: 0.05 } }];
      return pool;
    });
    const f = path.join(ctx.ROOT, 'src/main/resources/data', NS, 'loot_table/entities', id + '.json');
    fs.mkdirSync(path.dirname(f), { recursive: true });
    fs.writeFileSync(f, JSON.stringify({ type: 'minecraft:entity', pools }, null, 2));
    c.files.push('data/' + NS + '/loot_table/entities/' + id + '.json');
  }
}

/** After apply: load the spec now and switch an existing mob type to the cube renderer if needed. */
export async function afterMob(id, ns = 'moddeus') {
  await (await import('./game.mjs')).run(['run', 'dev.moddeus.live.ContentLoader.loadAll(); dev.moddeus.client.content.CubeMobs.rebindIfNeeded(net.minecraft.resources.Identifier.parse("' + ns + ':' + id + '")); print("model live");']);
}

function stackOf(s) { const m = String(s).trim().match(/^(\d+)?\s*([a-z0-9_:.\/]+)(?:\*(\d+))?$/); if (!m) throw new Error('bad stack "' + s + '" (use "3 emerald")'); return { item: ns(m[2]), count: Number(m[1] || m[3] || 1) }; }

export async function run(cmd, rest, ctx) {
  if (!CMDS.includes(cmd)) return false;
  const pos = [], opt = {};
  for (let i = 0; i < rest.length; i++) { const a = rest[i]; if (a.startsWith('--')) { const k = a.slice(2), v = rest[i + 1] && !rest[i + 1].startsWith('--') ? rest[++i] : true; opt[k] = opt[k] === undefined ? v : [].concat(opt[k], v); } else pos.push(a); }
  const one = v => Array.isArray(v) ? v[v.length - 1] : v;
  const NS = ctx.NS || 'moddeus', NSP = NS + ':'; CONTENT_DIR = ctx.CONTENT; // the mod's namespace (mc.mjs NS)
  if (cmd === 'mobmodel') {
    for (const [k, v] of Object.entries(PRESETS)) console.log(k.padEnd(10) + v.about + '\n' + ' '.repeat(10) + 'colors: ' + Object.keys(v.colors).join(' '));
    console.log('\nbone roles (bone "anim"): head look leg:<0|1> arm:<0|1> reach wing:<0|1> tail jaw segment:<i> wave:<i> crawl:<0|1>*<+-1> breathe bob spin sway ear:<0|1> body lunge   (*amp scales; several = space separated)\nclips: {"attack":{"len":10,"bones":{"jaw":{"rx":[[0,0],[5,40],[10,0]]}}}}  names idle walk attack hurt death sit; channels rx ry rz (deg) x y z (px) s');
    return true;
  }
  const id = pos[0];
  if (!/^[a-z0-9_]+$/.test(id || '')) ctx.die('usage: ' + cmd + ' <id> ...\n' + HELP);
  if (cmd === 'painting') {
    const [w, h] = String(one(opt.size) || '1x1').split('x').map(Number), src = one(opt.file);
    if (!src || !fs.existsSync(src)) ctx.die('painting: --file <png>, ' + (w * 16) + 'x' + (h * 16) + ' px (draw it, or mc texture a web image first)');
    const D = path.join(ctx.ROOT, 'src/main/resources/data');
    fs.mkdirSync(path.join(D, NS, 'painting_variant'), { recursive: true });
    fs.writeFileSync(path.join(D, 'moddeus/painting_variant', id + '.json'), JSON.stringify({ asset_id: NSP + id, width: w, height: h,
      title: { text: String(one(opt.title) || id.replace(/_/g, ' ')), color: 'yellow' }, author: { text: String(one(opt.author) || 'Moddeus'), color: 'gray' } }, null, 2));
    const tf = path.join(D, 'minecraft/tags/painting_variant/placeable.json'), tag = fs.existsSync(tf) ? JSON.parse(fs.readFileSync(tf, 'utf8')) : { replace: false, values: [] };
    if (!tag.values.includes(NSP + id)) tag.values.push(NSP + id);
    fs.mkdirSync(path.dirname(tf), { recursive: true }); fs.writeFileSync(tf, JSON.stringify(tag, null, 2));
    fs.mkdirSync(path.join(ctx.A, 'textures/painting'), { recursive: true }); fs.copyFileSync(src, path.join(ctx.A, 'textures/painting', id + '.png'));
    console.log('painting ' + NSP + id + ' ' + w + 'x' + h + ': painting variants are world-load registries, so it appears in random paintings after the next world load (give: /give @s painting[painting/variant="' + NSP + id + '"])');
    await ctx.apply();
    return true;
  }
  let o;
  if (cmd === 'trade') {
    const [w1, w2] = String(one(opt.wants) || ctx.die('trade: --wants "20 wheat [+ 1 book]"')).split('+');
    o = { type: 'trade', profession: ns(String(one(opt.profession) || 'farmer')), level: Number(one(opt.level) || 1), wants: stackOf(w1), gives: stackOf(one(opt.gives) || ctx.die('trade: --gives "2 moddeus:x"')), uses: Number(one(opt.uses) || 12), xp: Number(one(opt.xp) || 2) };
    if (w2) o.wants2 = stackOf(w2);
  } else {
    const ws = one(opt.workstation) || ctx.die('profession: --workstation <block id>');
    o = { type: 'profession', name: one(opt.name) || id.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase()), workstation: ns(String(ws)) };
    if (opt.sound) o.sound = ns(String(one(opt.sound)));
    o.files = ['assets/' + NS + '/textures/entity/villager/profession/' + id + '.png'];
    const tex = path.join(ctx.A, 'textures/entity/villager/profession', id + '.png');
    if (!fs.existsSync(tex) || opt.repaint) {
      const col = { apron: '#6a4a2a', trim: '#d0a040', ...kv(one(opt.colors)) }, a = hex(col.apron), t = hex(col.trim), px = Buffer.alloc(64 * 64 * 4);
      const put = (x, y, c) => { const i = (y * 64 + x) * 4; px[i] = c[0]; px[i + 1] = c[1]; px[i + 2] = c[2]; px[i + 3] = 255; };
      for (let y = 44; y < 64; y++) for (let x = 6; x < 14; x++) put(x, y, y === 44 || y === 63 ? t : shade(a, 0.08 - 0.25 * (y - 44) / 19 + (hash(x, y, 5) - 0.5) * 0.1)); // robe front = apron
      for (let x = 0; x < 28; x++) put(x, 43, t); // belt line around the robe
      ctx.png(tex, 64, 64, px);
      console.log('  painted apron overlay textures/entity/villager/profession/' + id + '.png (redraw: mc pixels entity villager/profession/' + id + ')');
    }
  }
  if (opt.tab) o.tab = String(one(opt.tab));
  ctx.stamp?.(path.join(ctx.CONTENT, id + '.json'), o);
  fs.writeFileSync(path.join(ctx.CONTENT, id + '.json'), JSON.stringify(o, null, 2));
  console.log(cmd + ' ' + id + ' -> moddeus/content/' + id + '.json');
  await ctx.apply();
  return true;
}

/** A mob sound from a file/URL: imported through mc sound into the mob's namespace (loud for death/roar). */
function mobSound(ctx, name, src) {
  const nsx = path.basename(ctx.A);
  execFileSync(process.execPath, [path.join(ctx.ROOT, 'tools/mc.mjs'), 'sound', name, src, '--ns', nsx, '--no-apply', ...(/_(death|roar)$/.test(name) ? ['--level', 'loud'] : [])], { stdio: 'inherit' });
  return nsx + ':' + name;
}
