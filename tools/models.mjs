// Moddeus 3D model kit: shape templates (sword ... crown) with parameters, rotated parts (any angle, any axis),
// per-face UVs, glow parts, auto-painted textures, display transforms that fit hand/3rd person/GUI/ground/frame,
// item-definition variants (using / charged / cooldown / key / custom_model_data), a separate GUI icon, and a preview.
// Used by: mc item <id> --model <shape> ...   |   mc model shapes   |   mc model preview <id>.   Guide: .claude/skills/mc-content/MODELS.md
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { execFileSync } from 'node:child_process';

// ---- math (degrees; R = Rz*Ry*Rx like the game's euler rotation) -------------------------------------------
const D = Math.PI / 180;
const mul = (a, b) => a.map((r, i) => [0, 1, 2].map(j => r[0] * b[0][j] + r[1] * b[1][j] + r[2] * b[2][j]));
const app = (m, v) => m.map(r => r[0] * v[0] + r[1] * v[1] + r[2] * v[2]);
const add = (a, b) => a.map((x, i) => x + b[i]), sub = (a, b) => a.map((x, i) => x - b[i]);
function rx(a) { const c = Math.cos(a * D), s = Math.sin(a * D); return [[1, 0, 0], [0, c, -s], [0, s, c]]; }
function ry(a) { const c = Math.cos(a * D), s = Math.sin(a * D); return [[c, 0, s], [0, 1, 0], [-s, 0, c]]; }
function rz(a) { const c = Math.cos(a * D), s = Math.sin(a * D); return [[c, -s, 0], [s, c, 0], [0, 0, 1]]; }
const euler = ([x = 0, y = 0, z = 0]) => mul(rz(z), mul(ry(y), rx(x)));
const I3 = [[1, 0, 0], [0, 1, 0], [0, 0, 1]];
const r4 = v => Math.round(v * 1e4) / 1e4;
/** Matrix -> model JSON rotation: none, {axis, angle} or {x, y, z} (decomposed for Rz*Ry*Rx). */
function rotJson(R, origin) {
  const near = (a, b) => Math.abs(a - b) < 1e-5;
  if (R.every((r, i) => r.every((v, j) => near(v, I3[i][j])))) return null;
  const sy = Math.max(-1, Math.min(1, -R[2][0]));
  let y = Math.asin(sy), x, z;
  if (Math.abs(sy) > 0.99999) { x = 0; z = Math.atan2(-R[0][1], R[1][1]); } else { x = Math.atan2(R[2][1], R[2][2]); z = Math.atan2(R[1][0], R[0][0]); }
  const deg = [x, y, z].map(v => r4(v / D)), nz = deg.filter(v => !near(v, 0));
  const o = origin.map(r4);
  if (nz.length === 1) { const k = deg.findIndex(v => !near(v, 0)); return { origin: o, axis: 'xyz'[k], angle: deg[k] }; }
  return { origin: o, x: deg[0], y: deg[1], z: deg[2] };
}

// ---- parts ------------------------------------------------------------------------------------------------
// A part = { tex, c:[x,y,z] centre, s:[w,h,d] size, r:[rx,ry,rz] own rotation (about pivot, default its centre), glow, faces }
// in the template's LOCAL frame: y = along the weapon (grip at y=0, tip toward +y), x = width, z = thickness (centred on 0).
const P = (tex, x, y, z, w, h, d, o = {}) => ({ tex, c: [x, y, z], s: [w, h, d], ...o });
/** centred box from y0..y1 */
const Y = (tex, x, y0, y1, w, d, o = {}) => P(tex, x, (y0 + y1) / 2, o.z || 0, w, y1 - y0, d, o);
/** Straight string strands from each tip to point n (x/y plane) at depth z. */
function strands(tips, n, z = 0) {
  return tips.map(t => { const dx = n[0] - t[0], dy = n[1] - t[1]; return P('string', (t[0] + n[0]) / 2, (t[1] + n[1]) / 2, z, Math.hypot(dx, dy) + 0.2, 0.4, 0.4, { r: [0, 0, Math.atan2(dy, dx) / D] }); });
}

/** A chain of segments starting at p0 heading at angle a0 (deg, in the x/y plane, 90 = +y) bending by 'bend' per segment. */
function chain(tex, p0, a0, n, len, bend, w0, w1, d, o = {}) {
  const out = []; let p = p0.slice(), a = a0;
  for (let i = 0; i < n; i++) {
    const w = w0 + (w1 - w0) * (n > 1 ? i / (n - 1) : 0), dir = [Math.cos(a * D), Math.sin(a * D), 0];
    out.push(P(tex, p[0] + dir[0] * len / 2, p[1] + dir[1] * len / 2, p[2] || 0, len + 0.3, w, d, { ...o, r: [0, 0, a] }));
    p = add(p, dir.map(v => v * len)); a += bend;
  }
  return out;
}

// ---- shapes -------------------------------------------------------------------------------------------------
// hold: blade = gripped like a vanilla sword (sprite diagonal), bow, gun, shield, hand (worn on the hand), head (worn on the head).
// tex: texture key -> [material, base color]. Materials: metal gold wood leather wrap gem crystal cloth bone stone string energy.
const SHAPES = {
  sword: { hold: 'blade', about: 'one-handed sword', p: { length: 13, width: 3, thick: 1, guard: 7, handle: 4.5, tip: 3, gem: 1, fuller: 1, curve: 0 },
    tex: { blade: ['metal', '#c9d2de'], fuller: ['metal', '#8f9bb0'], guard: ['gold', '#d9a93a'], handle: ['wrap', '#5a3420'], pommel: ['gold', '#d9a93a'], gem: ['gem', '#e0304a'] },
    build: (p) => blade(p) },
  greatsword: { hold: 'blade', about: 'huge two-handed sword (longer, wider, spiked guard)', p: { length: 23, width: 5, thick: 1.4, guard: 12, handle: 7, tip: 5, gem: 1, fuller: 1, curve: 0, spikes: 1 },
    tex: { blade: ['metal', '#b9c2cf'], fuller: ['metal', '#6f7a90'], guard: ['metal', '#3c3f4a'], handle: ['wrap', '#3a2418'], pommel: ['metal', '#3c3f4a'], gem: ['gem', '#40c8ff'] },
    build: (p) => blade(p) },
  axe: { hold: 'blade', about: 'battle axe (one or two bits)', p: { handle: 16, head: 7, bit: 6, thick: 1.5, double: 0, spike: 1 },
    tex: { handle: ['wood', '#7a5230'], head: ['metal', '#9aa3b0'], edge: ['metal', '#e4ebf3'], band: ['metal', '#4a4e58'] },
    build: (p) => {
      const top = p.handle - 4, out = [Y('handle', 0, -4, top + 1, 1.6, 1.6), Y('band', 0, top - p.head + 1, top - p.head + 2.2, 2.2, 2.2), Y('band', 0, -4.2, -3, 2, 2)];
      const sides = p.double ? [1, -1] : [1];
      for (const sx of sides) {
        const cy = top - p.head / 2 + 1;
        out.push(P('head', sx * (0.8 + p.bit / 2), cy, 0, p.bit, p.head * 0.55, p.thick));
        out.push(P('head', sx * (0.8 + p.bit * 0.75), cy + p.head * 0.22, 0, p.bit * 0.55, p.head * 0.42, p.thick, { r: [0, 0, sx * 28] }));
        out.push(P('head', sx * (0.8 + p.bit * 0.75), cy - p.head * 0.22, 0, p.bit * 0.55, p.head * 0.42, p.thick, { r: [0, 0, -sx * 28] }));
        out.push(P('edge', sx * (0.9 + p.bit), cy, 0, 1, p.head * 0.95, p.thick * 0.8));
      }
      out.push(Y('head', 0, top - p.head + 2, top + 1.5, 2.4, 2.2));
      if (p.spike && !p.double) out.push(P('band', -2.2, top - p.head / 2 + 1, 0, 3, 1.2, 1.2, { r: [0, 0, -12] }));
      if (p.spike) out.push(P('band', 0, top + 2.3, 0, 1.2, 1.6, 1.2, { r: [0, 45, 0] }));
      return out;
    } },
  hammer: { hold: 'blade', about: 'war hammer / mace (spikes=1 for a mace)', p: { handle: 15, head: 10, size: 6, spikes: 0, gem: 1 },
    tex: { handle: ['wood', '#6b4426'], head: ['metal', '#8d96a3'], band: ['gold', '#c99a34'], spike: ['metal', '#d0d7e0'], gem: ['gem', '#ffb020'] },
    build: (p) => {
      const top = p.handle - 4, s = p.size, cy = top + s / 2 - 1;
      const out = [Y('handle', 0, -4, top, 1.8, 1.8), Y('band', 0, -4.6, -3.2, 2.4, 2.4), Y('band', 0, top - 2.5, top - 1.2, 2.4, 2.4)];
      if (p.spikes) {
        out.push(P('head', 0, cy, 0, s, s, s), P('band', 0, cy, 0, s + 0.6, 1.2, s + 0.6));
        for (const [x, y, z] of [[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, 0, 1], [0, 0, -1]]) out.push(P('spike', x * s / 2, cy + y * s / 2, z * s / 2, 1.6, 1.6, 1.6, { r: [45, 45, 0] }));
      } else {
        out.push(P('head', 0, cy, 0, p.head, s, s), P('band', p.head / 2 - 0.8, cy, 0, 1.4, s + 0.6, s + 0.6), P('band', -p.head / 2 + 0.8, cy, 0, 1.4, s + 0.6, s + 0.6));
        out.push(P('head', p.head / 2 + 0.6, cy, 0, 1.2, s - 1.6, s - 1.6), P('spike', -p.head / 2 - 1.2, cy, 0, 2.6, 1.6, 1.6, { r: [0, 0, 0] }));
      }
      if (p.gem) out.push(P('gem', 0, cy, 0, 1.8, 1.8, s + 0.4, { glow: true }));
      return out;
    } },
  spear: { hold: 'blade', about: 'spear / lance (long shaft, leaf blade, tassel)', p: { shaft: 26, head: 8, width: 3.2, tassel: 1, butt: 7 },
    tex: { shaft: ['wood', '#7a5230'], head: ['metal', '#cfd6e0'], socket: ['gold', '#c99a34'], tassel: ['cloth', '#c02030'] },
    build: (p) => {
      const top = p.shaft - p.butt, h = p.head;
      const out = [Y('shaft', 0, -p.butt, top, 1.4, 1.4), Y('socket', 0, top - 1, top + 1.2, 2.2, 2.2), Y('socket', 0, -p.butt - 1, -p.butt, 1.8, 1.8)];
      out.push(Y('head', 0, top + 1, top + 1 + h * 0.55, p.width, 0.9), Y('head', 0, top + 1 + h * 0.55, top + 1 + h * 0.8, p.width * 0.6, 0.9));
      out.push(P('head', 0, top + 1 + h * 0.8, 0, p.width * 0.42, p.width * 0.42, 0.9, { r: [0, 0, 45] }));
      out.push(Y('socket', 0, top + 1, top + 1 + h * 0.75, 0.6, 1.3));
      if (p.tassel) out.push(P('tassel', 1.3, top - 2.5, 0, 1, 4, 1, { r: [0, 0, 12] }), P('tassel', -1.1, top - 2.2, 0.3, 1, 3.4, 1, { r: [0, 0, -10] }));
      return out;
    } },
  scythe: { hold: 'blade', about: 'scythe (long shaft, curved blade, can glow)', p: { shaft: 24, blade: 5, seg: 3.4, bend: -14, width: 3, glow: 0 },
    tex: { shaft: ['wood', '#4a3020'], blade: ['metal', '#aab3c0'], edge: ['metal', '#eef2f8'], band: ['metal', '#3c3f4a'] },
    build: (p) => {
      const top = p.shaft - 6;
      const out = [Y('shaft', 0, -6, top + 1, 1.5, 1.5), Y('band', 0, top - 1.5, top + 1.5, 2.4, 2.4), Y('band', 0, 3, 4.2, 2, 2)];
      out.push(...chain('blade', [-0.8, top, 0], 178, p.blade, p.seg, p.bend, p.width, 0.8, 0.8, { glow: !!p.glow }));
      out.push(...chain('edge', [-0.8, top - p.width / 2 + 0.2, 0], 180, p.blade, p.seg, p.bend * 1.1, 0.8, 0.5, 0.9, { glow: !!p.glow }));
      out.push(P('band', 1.2, top + 0.5, 0, 2.2, 1.2, 1.2, { r: [0, 0, 30] }));
      return out;
    } },
  staff: { hold: 'blade', about: 'mage staff (claw prongs holding a glowing orb)', p: { shaft: 24, orb: 3.4, prongs: 4, rings: 2 },
    tex: { shaft: ['wood', '#5a3a22'], ring: ['gold', '#d9a93a'], prong: ['gold', '#c99a34'], orb: ['energy', '#7a5cff'] },
    build: (p) => {
      const top = p.shaft - 8, o = p.orb;
      const out = [Y('shaft', 0, -8, top + 1, 1.6, 1.6), Y('ring', 0, top, top + 1.6, 2.6, 2.6)];
      for (let i = 0; i < p.rings; i++) out.push(Y('ring', 0, top - 4 - i * 3, top - 3.2 - i * 3, 2.2, 2.2));
      out.push(P('orb', 0, top + 1.6 + o * 0.75, 0, o, o, o, { r: [45, 35.26, 0], glow: true }));
      for (let i = 0; i < p.prongs; i++) {
        const a = i * 360 / p.prongs + 45, cx = Math.cos(a * D) * o * 0.55, cz = Math.sin(a * D) * o * 0.55;
        out.push(P('prong', cx, top + 1.4 + o * 0.6, cz, 0.8, o * 1.3, 0.8, { r: [-Math.sin(a * D) * 22, 0, Math.cos(a * D) * -22] }));
      }
      return out;
    } },
  wand: { hold: 'blade', about: 'short magic wand (tapered, glowing tip)', p: { length: 13, tip: 2, rings: 1 },
    tex: { shaft: ['wood', '#3a2418'], ring: ['gold', '#e0b040'], tip: ['energy', '#40e0ff'], handle: ['wrap', '#6a1f30'] },
    build: (p) => {
      const L = p.length - 2;
      const out = [Y('handle', 0, -2.5, 2, 1.6, 1.6), Y('shaft', 0, 2, 2 + L * 0.5, 1.3, 1.3), Y('shaft', 0, 2 + L * 0.5, 2 + L, 1, 1), Y('ring', 0, 1.6, 2.6, 2, 2), Y('ring', 0, -3, -2.2, 1.9, 1.9)];
      for (let i = 1; i < p.rings; i++) out.push(Y('ring', 0, 2 + L * 0.5 - 0.4, 2 + L * 0.5 + 0.4, 1.6, 1.6));
      out.push(P('tip', 0, 2 + L + p.tip * 0.4, 0, p.tip, p.tip, p.tip, { r: [45, 35.26, 0], glow: true }));
      return out;
    } },
  bow: { hold: 'bow', about: 'recurve bow (curved limbs + string; arrow=1 nocks an arrow: used by the pull stages)', p: { limb: 6, seg: 2.6, bend: 15, thick: 1.4, arrow: 0 },
    tex: { limb: ['wood', '#7a5230'], grip: ['wrap', '#4a2a18'], string: ['string', '#e8e2d0'], tip: ['gold', '#c99a34'], arrow: ['wood', '#8a6a40'], arrowhead: ['metal', '#cfd6e0'], fletch: ['cloth', '#eeeeee'] },
    build: (p) => {
      const out = [Y('grip', 0, -2.2, 2.2, p.thick + 0.6, p.thick + 0.6)];
      const up = chain('limb', [0, 1.8, 0], 90, p.limb, p.seg, -p.bend, p.thick, p.thick * 0.7, p.thick);
      const dn = chain('limb', [0, -1.8, 0], -90, p.limb, p.seg, p.bend, p.thick, p.thick * 0.7, p.thick);
      out.push(...up, ...dn);
      const end = (seg) => { const a = seg.r[2] * D, L = seg.s[0] - 0.3; return [seg.c[0] + Math.cos(a) * L / 2, seg.c[1] + Math.sin(a) * L / 2]; };
      const t1 = end(up.at(-1)), t2 = end(dn.at(-1));
      out.push(P('tip', t1[0], t1[1], 0, 1.4, 1.4, 1.4), P('tip', t2[0], t2[1], 0, 1.4, 1.4, 1.4));
      const sx = (t1[0] + t2[0]) / 2;
      const d = Math.max(0, Math.min(1, p.draw || 0)) * (p.nock || 4.5), sy = (t1[1] + t2[1]) / 2; // draw 0..1 pulls the string back into a V (pull stages)
      if (d < 0.05) out.push(P('string', sx, sy, 0, 0.4, t1[1] - t2[1], 0.4)); else out.push(...strands([t1, t2], [sx + d, sy]));
      if (p.arrow) { const nx = sx + d - 0.2, L = 15, z = p.thick / 2 + 0.6; // nocked arrow: shaft from the string through the grip, glowing head, fletching
        out.push(P('arrow', nx - L / 2, 0, z, L, 0.5, 0.5), P('arrowhead', nx - L - 0.4, 0, z, 2, 2, 0.5, { r: [0, 0, 45], glow: true }),
          P('fletch', nx - 1.8, 0.65, z, 3.2, 0.9, 0.3), P('fletch', nx - 1.8, -0.65, z, 3.2, 0.9, 0.3)); }
      return out;
    } },
  crossbow: { hold: 'gun', about: 'crossbow (stock + prod + string; draw=0..1 cocks the string, load=1 bolt / 2 firework rocket)', p: { stock: 12, limb: 4, seg: 1.8, bend: 14, thick: 1.3, draw: 0, load: 0 },
    tex: { stock: ['wood', '#6e4a2c'], prod: ['metal', '#8d96a3'], string: ['string', '#e8e2d0'], latch: ['metal', '#4a4e58'], arrow: ['wood', '#9a7448'], arrowhead: ['metal', '#d4dbe4'], fletch: ['cloth', '#f0ece4'], rocket: ['cloth', '#c8323a'], rocket_tip: ['cloth', '#efe6d0'] },
    build: (p) => {
      const front = p.stock * 0.55, back = -p.stock * 0.45, out = [Y('stock', 0, back, front, 1.8, 1.6), Y('stock', 0, back - 1.5, back + 1, 2.2, 2.4), P('latch', 0, -0.5, 1, 1.2, 1.4, 0.5)];
      const r = chain('prod', [0.9, front - 0.6, 0], 0, p.limb, p.seg, -p.bend, p.thick, p.thick * 0.7, p.thick);
      const l = chain('prod', [-0.9, front - 0.6, 0], 180, p.limb, p.seg, p.bend, p.thick, p.thick * 0.7, p.thick);
      out.push(P('prod', 0, front - 0.6, 0, 2.4, p.thick + 0.4, p.thick + 0.4), ...r, ...l);
      const end = (seg) => { const a = seg.r[2] * D, L = seg.s[0] - 0.3; return [seg.c[0] + Math.cos(a) * L / 2, seg.c[1] + Math.sin(a) * L / 2]; };
      const t1 = end(r.at(-1)), t2 = end(l.at(-1)), ny = t1[1] + (-0.5 - t1[1]) * Math.max(0, Math.min(1, p.draw || 0)), z = 1.1;
      out.push(...strands([t1, t2], [0, ny], z));
      if (p.load === 1) { const L = front + 2.5 - ny; // bolt on the rail, head past the prod
        out.push(Y('arrow', 0, ny, ny + L - 1.2, 0.55, 0.55, { z }), P('arrowhead', 0, ny + L - 0.6, z, 1.5, 1.5, 0.5, { r: [0, 0, 45] }), P('fletch', 0.5, ny + 1.3, z, 0.5, 2.4, 0.3), P('fletch', -0.5, ny + 1.3, z, 0.5, 2.4, 0.3)); }
      if (p.load === 2) out.push(Y('rocket', 0, ny + 0.3, front + 1.5, 1.5, 1.5, { z: z + 0.4 }), Y('rocket_tip', 0, front + 1.5, front + 2.8, 1, 1, { z: z + 0.4 }));
      return out;
    } },
  gun: { hold: 'gun', about: 'blaster/gun: y = barrel forward, z = up', p: { barrel: 10, body: 7, height: 3.5, grip: 5, mag: 1, scope: 1, glow: 1 },
    tex: { body: ['metal', '#3a3f4a'], barrel: ['metal', '#9aa3b0'], grip: ['leather', '#2a2420'], trim: ['gold', '#d0a030'], core: ['energy', '#30f0a0'] },
    build: (p) => {
      const h = p.height, out = [];
      out.push(P('body', 0, 1.5, 0, 2.6, p.body, h));
      out.push(Y('barrel', 0, 1.5 + p.body / 2, 1.5 + p.body / 2 + p.barrel, 1.6, 1.6, { z: h * 0.15 }));
      out.push(Y('trim', 0, 1.5 + p.body / 2 + p.barrel - 1.2, 1.5 + p.body / 2 + p.barrel, 2.2, 2.2, { z: h * 0.15 }));
      out.push(P('grip', 0, -1.2, -h / 2 - p.grip / 2 + 0.6, 2, 2.4, p.grip, { r: [-18, 0, 0] }));
      out.push(P('trim', 0, 1.2, -h / 2 - 0.9, 0.6, 2.4, 1.6));
      if (p.mag) out.push(P('body', 0, 3.2, -h / 2 - 1.6, 1.6, 1.8, 3.2, { r: [8, 0, 0] }));
      if (p.scope) out.push(P('barrel', 0, 2, h / 2 + 0.8, 1.4, 4, 1.4), P('trim', 0, 4.2, h / 2 + 0.8, 1.6, 0.6, 1.6));
      if (p.glow) out.push(P('core', 0, 2.2, 0, 2.9, 3, h * 0.45, { glow: true }), P('core', 0, 1.5 + p.body / 2 + p.barrel + 0.2, h * 0.15, 1, 0.4, 1, { glow: true }));
      return out;
    } },
  shield: { hold: 'shield', about: 'shield (plate, rim, boss, optional glowing emblem); blocks attacks', p: { width: 12, height: 16, rim: 1, boss: 1, emblem: 1 },
    tex: { plate: ['wood', '#6b4426'], rim: ['metal', '#8d96a3'], boss: ['metal', '#c0c8d4'], emblem: ['energy', '#ffcc30'], handle: ['leather', '#3a2418'] },
    build: (p) => {
      const w = p.width, h = p.height, out = [P('plate', 0, 0, 0, w, h, 1)];
      if (p.rim) out.push(P('rim', 0, h / 2 - 0.5, -0.1, w + 0.4, 1, 1.4), P('rim', 0, -h / 2 + 0.5, -0.1, w + 0.4, 1, 1.4), P('rim', w / 2 - 0.5, 0, -0.1, 1, h, 1.4), P('rim', -w / 2 + 0.5, 0, -0.1, 1, h, 1.4));
      if (p.boss) out.push(P('boss', 0, 0, -0.8, 3, 3, 1, { r: [0, 0, 45] }));
      if (p.emblem) out.push(P('emblem', 0, h * 0.25, -0.6, 2.2, 2.2, 0.4, { r: [0, 0, 45], glow: true }), P('emblem', 0, -h * 0.25, -0.6, 2.2, 2.2, 0.4, { r: [0, 0, 45], glow: true }));
      out.push(P('handle', 0, 0, 1.4, 1.6, 6, 1.6));
      return out;
    } },
  gauntlet: { hold: 'hand', about: 'armored gauntlet worn on the hand (cuff y=0, fingers up, palm -z)', p: { gem: 1, spikes: 1, cuff: 5 },
    tex: { plate: ['metal', '#8d96a3'], dark: ['metal', '#454a55'], gold: ['gold', '#d9a93a'], gem: ['gem', '#a040ff'] },
    raw: true, build: (p) => {
      const c = p.cuff, out = [];
      out.push(P('dark', 8, c / 2, 8, 10, c, 8), P('gold', 8, c - 0.5, 8, 10.6, 1, 8.6), P('plate', 8, c + 3.2, 8, 9, 6.4, 6));
      out.push(P('plate', 8, c + 3.4, 11.4, 8, 5, 1.2, { r: [-6, 0, 0] }));
      for (let i = 0; i < 4; i++) { const x = 4.9 + i * 2.1; out.push(P('dark', x, c + 8, 8.4, 1.8, 3.4, 2.2), P('plate', x, c + 10.6, 8.6, 1.7, 2.4, 2, { r: [-12, 0, 0] }), P('gold', x, c + 6.7, 9.8, 1.9, 0.9, 0.8)); }
      out.push(P('plate', 3, c + 4.5, 7.6, 2, 4.2, 2.2, { r: [0, 0, 28] }));
      if (p.gem) out.push(P('gem', 8, c + 3.4, 12.2, 2.4, 2.4, 1, { r: [0, 0, 45], glow: true }));
      if (p.spikes) for (let i = 0; i < 4; i++) out.push(P('gold', 4.9 + i * 2.1, c + 6.9, 10.6, 0.9, 0.9, 1.6, { r: [-30, 0, 0] }));
      return out;
    } },
  helmet: { hold: 'head', about: 'knight helmet around the 0..16 head (eye slit, nose guard, crest/horns)', p: { crest: 1, horns: 0, visor: 1 },
    tex: { shell: ['metal', '#9aa3b0'], trim: ['gold', '#c99a34'], crest: ['cloth', '#b02030'], horn: ['bone', '#e8dcc0'], eyes: ['energy', '#40e0ff'] },
    raw: true, build: (p) => {
      const out = [P('shell', 8, 16.5, 8, 17.4, 1, 17.4), P('shell', 8, 8, 16.7, 17.4, 16, 1), P('shell', -0.2, 8, 8, 1, 16, 16.4), P('shell', 16.2, 8, 8, 1, 16, 16.4)];
      out.push(P('shell', 8, 13, -0.7, 17.4, 6, 1), P('trim', 8, 10.2, -1, 17.6, 0.8, 1.2));
      if (p.visor) out.push(P('shell', 8, 3.5, -0.7, 17.4, 7, 1), P('shell', 8, 7.8, -1.0, 1.6, 2.6, 1.2), P('eyes', 4.5, 8.4, -0.4, 4, 0.8, 0.4, { glow: true }), P('eyes', 11.5, 8.4, -0.4, 4, 0.8, 0.4, { glow: true }));
      out.push(P('trim', 8, 16.2, 8, 17.8, 0.8, 17.8));
      if (p.crest) out.push(P('crest', 8, 18.5, 9, 1.4, 3, 14), P('crest', 8, 18, 17.5, 1.4, 4, 2, { r: [-30, 0, 0] }));
      if (p.horns) for (const s of [-1, 1]) out.push(P('horn', 8 + s * 9.5, 14, 8, 4, 1.8, 1.8, { r: [0, 0, s * 30] }), P('horn', 8 + s * 11.6, 16.8, 8, 1.4, 3.2, 1.4, { r: [0, 0, s * -12] }));
      return out;
    } },
  crown: { hold: 'head', about: 'crown sitting on the head (band, points, glowing gems)', p: { points: 3, height: 3.5, gems: 1 },
    tex: { band: ['gold', '#e8b830'], point: ['gold', '#f0c850'], gem: ['gem', '#e02848'], pearl: ['bone', '#f4f0e8'] },
    raw: true, build: (p) => {
      const out = [], y = 16.6, n = p.points, hgt = p.height;
      for (const [cx, cz, w, d] of [[8, -0.5, 17.4, 1], [8, 16.5, 17.4, 1], [-0.5, 8, 1, 16], [16.5, 8, 1, 16]]) out.push(P('band', cx, y, cz, w, 2.2, d));
      for (let side = 0; side < 4; side++) for (let i = 0; i < n; i++) {
        const t = (i + 0.5) / n * 16, pos = [[t, -0.5], [16 - t, 16.5], [-0.5, 16 - t], [16.5, t]][side];
        out.push(P('point', pos[0], y + 1 + hgt / 2, pos[1], 1.4, hgt, 1, { r: side < 2 ? [0, 0, 0] : [0, 90, 0] }));
        out.push(P('pearl', pos[0], y + 1.4 + hgt, pos[1], 1, 1, 1, { r: [45, 35.26, 0] }));
      }
      if (p.gems) out.push(P('gem', 8, y, -1.1, 2, 2, 0.6, { r: [0, 0, 45], glow: true }), P('gem', 3.5, y, -1.05, 1.2, 1.2, 0.5, { r: [0, 0, 45], glow: true }), P('gem', 12.5, y, -1.05, 1.2, 1.2, 0.5, { r: [0, 0, 45], glow: true }));
      return out;
    } },
  custom: { hold: 'blade', about: 'your own parts only (--parts), in the blade frame unless --hold says otherwise', p: {}, tex: {}, build: () => [] },
};

function blade(p) {
  const out = [], hl = p.handle, b0 = 1.6, W = p.width, T = p.thick;
  out.push(Y('handle', 0, -hl / 2 - 0.4, hl / 2 - 0.4, Math.max(1.4, W * 0.4), Math.max(1.4, W * 0.4)));
  out.push(P('pommel', 0, -hl / 2 - 1.2, 0, 1.8, 1.8, 1.8, { r: [45, 35.26, 0] }));
  const gy = hl / 2 - 0.4, gh = Math.max(1.2, W * 0.45);
  out.push(Y('guard', 0, gy, gy + gh, p.guard, Math.max(2, T + 1)));
  for (const s of [-1, 1]) out.push(P('guard', s * (p.guard / 2 + 0.3), gy + gh, 0, 1.2, gh + 0.6, Math.max(1.6, T + 0.6), { r: [0, 0, s * -25] }));
  if (p.spikes) for (const s of [-1, 1]) out.push(P('guard', s * (W / 2 + 0.5), gy + gh + 1, 0, 1, 2.2, T + 0.4, { r: [0, 0, s * -20] }));
  if (p.gem) out.push(P('gem', 0, gy + gh / 2, 0, gh * 0.9, gh * 0.9, Math.max(2.4, T + 1.6), { r: [0, 0, 45], glow: true }));
  const by = gy + gh, L = p.length, tip = Math.min(p.tip, L * 0.4);
  if (p.curve) out.push(...chain('blade', [0, by, 0], 90, 4, (L - tip) / 4, p.curve, W, W * 0.9, T));
  else out.push(Y('blade', 0, by, by + L - tip, W, T));
  if (p.fuller && !p.curve) out.push(Y('fuller', 0, by + 0.6, by + (L - tip) * 0.75, Math.max(0.6, W * 0.25), T + 0.15));
  if (!p.curve) { const s = W / Math.SQRT2; out.push(P('blade', 0, by + L - tip, 0, s, s, T, { r: [0, 0, 45], sy: tip / (W / 2) })); }
  return out;
}

// ---- holds: local frame -> model space + display -----------------------------------------------------------
// blade: the vanilla sword sprite convention (grip at (3.2,3.2), tip toward the top-right), so the vanilla
// handheld transforms hold it exactly like a sword. bow: limbs along the sprite's other diagonal like the vanilla bow.
const HANDHELD = {
  thirdperson_righthand: { rotation: [0, -90, 55], translation: [0, 4, 0.5], scale: [0.85, 0.85, 0.85] },
  thirdperson_lefthand: { rotation: [0, 90, -55], translation: [0, 4, 0.5], scale: [0.85, 0.85, 0.85] },
  firstperson_righthand: { rotation: [0, -90, 25], translation: [1.13, 3.2, 1.13], scale: [0.68, 0.68, 0.68] },
  firstperson_lefthand: { rotation: [0, 90, -25], translation: [1.13, 3.2, 1.13], scale: [0.68, 0.68, 0.68] } };
const HOLDS = {
  blade: { frame: rz(-45), grip: [3.2, 3.2, 8], display: HANDHELD },
  bow: { frame: rz(-45), grip: [4.6, 11.4, 8], display: {
    thirdperson_righthand: { rotation: [-80, 260, -40], translation: [-1, -2, 2.5], scale: [0.9, 0.9, 0.9] },
    thirdperson_lefthand: { rotation: [-80, -280, 40], translation: [-1, -2, 2.5], scale: [0.9, 0.9, 0.9] },
    firstperson_righthand: { rotation: [0, -90, 25], translation: [1.13, 3.2, 1.13], scale: [0.68, 0.68, 0.68] },
    firstperson_lefthand: { rotation: [0, 90, -25], translation: [1.13, 3.2, 1.13], scale: [0.68, 0.68, 0.68] } } },
  gun: { frame: rz(-45), grip: [5, 5, 8], display: {
    thirdperson_righthand: { rotation: [-90, 0, -60], translation: [2, 0.1, -3], scale: [0.9, 0.9, 0.9] },
    thirdperson_lefthand: { rotation: [-90, 0, 30], translation: [2, 0.1, -3], scale: [0.9, 0.9, 0.9] },
    firstperson_righthand: { rotation: [-90, 0, -55], translation: [1.13, 3.2, 1.13], scale: [0.68, 0.68, 0.68] },
    firstperson_lefthand: { rotation: [-90, 0, 35], translation: [1.13, 3.2, 1.13], scale: [0.68, 0.68, 0.68] } } },
  shield: { frame: I3, grip: [8, 8, 8], display: {
    thirdperson_righthand: { rotation: [0, 90, 0], translation: [-3, 0, 2], scale: [0.85, 0.85, 0.85] },
    thirdperson_lefthand: { rotation: [0, 90, 0], translation: [-3, 0, 2], scale: [0.85, 0.85, 0.85] },
    firstperson_righthand: { rotation: [0, 180, 5], translation: [-6, 2, -6], scale: [0.9, 0.9, 0.9] },
    firstperson_lefthand: { rotation: [0, 180, 5], translation: [6, 2, -6], scale: [0.9, 0.9, 0.9] } },
    using: { // blocking pose
    thirdperson_righthand: { rotation: [45, 155, 0], translation: [-3.5, 4, -2], scale: [0.85, 0.85, 0.85] },
    thirdperson_lefthand: { rotation: [45, 155, 0], translation: [4, 2, 2.5], scale: [0.85, 0.85, 0.85] },
    firstperson_righthand: { rotation: [0, 180, -5], translation: [-8, 3, -6], scale: [1, 1, 1] },
    firstperson_lefthand: { rotation: [0, 180, -5], translation: [5, 4, -6], scale: [1, 1, 1] } } },
  hand: { frame: null, display: {
    thirdperson_righthand: { rotation: [-90, 0, 0], translation: [0, 0, 2.5], scale: [0.5, 0.5, 0.5] },
    thirdperson_lefthand: { rotation: [-90, 0, 0], translation: [0, 0, 2.5], scale: [0.5, 0.5, 0.5] },
    firstperson_righthand: { rotation: [-80, 15, 0], translation: [-1, 2.5, 0], scale: [0.6, 0.6, 0.6] },
    firstperson_lefthand: { rotation: [-80, -15, 0], translation: [-1, 2.5, 0], scale: [0.6, 0.6, 0.6] } }, gui: [15, 200, 0] },
  head: { frame: null, display: {
    head: { rotation: [0, 0, 0], translation: [0, 0, 0], scale: [1, 1, 1] },
    thirdperson_righthand: { rotation: [75, 45, 0], translation: [0, 2.5, 0], scale: [0.375, 0.375, 0.375] },
    firstperson_righthand: { rotation: [0, 45, 0], scale: [0.4, 0.4, 0.4] } }, gui: [30, 225, 0] },
};

/** parts (local) -> model elements. Splits boxes that leave the -16..32 model range. */
function toElements(parts, hold) {
  const H = HOLDS[hold], els = [];
  const place = (pt) => {
    let R = euler(pt.r || [0, 0, 0]);
    let s = pt.s.slice();
    if (pt.sy) s[1] *= 1; // reserved
    let c = pt.c.slice();
    if (pt.pivot) c = add(pt.pivot, app(R, sub(pt.c, pt.pivot)));
    if (H.frame) { c = add(H.grip, app(H.frame, c)); R = mul(H.frame, R); }
    return { R, c, s };
  };
  const emit = (pt, depth = 0) => {
    const { R, c, s } = place(pt);
    const from = sub(c, s.map(v => v / 2)), to = add(c, s.map(v => v / 2));
    if ((from.some(v => v < -16) || to.some(v => v > 32)) && depth < 4) { // split along its longest axis
      const k = s.indexOf(Math.max(...s)), half = s.slice(); half[k] /= 2;
      const ax = [0, 0, 0]; ax[k] = s[k] / 4;
      const Rl = euler(pt.r || [0, 0, 0]);
      for (const sg of [-1, 1]) emit({ ...pt, s: half, c: add(pt.c, app(Rl, ax.map(v => v * sg))), pivot: pt.pivot }, depth + 1);
      return;
    }
    if (from.some(v => v < -16) || to.some(v => v > 32)) throw new Error(`model too big: a '${pt.tex}' box reaches ${from.concat(to).map(r4)} (vanilla allows -16..32): use smaller length/shaft/blade params`);
    const e = { from: from.map(r4), to: to.map(r4) };
    const rot = rotJson(R, c);
    if (rot) e.rotation = rot;
    if (pt.glow) e.light_emission = 15;
    if (pt.glow) e.shade = false;
    e.faces = {};
    const dims = { north: [s[0], s[1]], south: [s[0], s[1]], east: [s[2], s[1]], west: [s[2], s[1]], up: [s[0], s[2]], down: [s[0], s[2]] };
    const seed = els.length * 7;
    for (const [f, [w, h]] of Object.entries(dims)) {
      const fw = Math.min(16, Math.max(0.5, w)), fh = Math.min(16, Math.max(0.5, h));
      const u = (seed * 3) % Math.max(1, 16 - fw), v = (seed * 5) % Math.max(1, 16 - fh);
      e.faces[f] = { uv: [u, v, u + fw, v + fh].map(r4), texture: '#' + pt.tex, ...(pt.faces && pt.faces[f] || {}) };
    }
    els.push(e);
  };
  for (const pt of parts) emit(pt);
  return els;
}

/** Display: hold transforms + GUI/ground/fixed auto-fitted to the model's real bounds. */
function display(els, hold, guiRot, extra = {}) {
  const pts = [];
  for (const e of els) {
    const c = e.from.map((v, i) => (v + e.to[i]) / 2), h = e.from.map((v, i) => (e.to[i] - v) / 2);
    let R = I3;
    if (e.rotation) R = e.rotation.axis ? { x: rx, y: ry, z: rz }[e.rotation.axis](e.rotation.angle) : euler([e.rotation.x, e.rotation.y, e.rotation.z]);
    for (const sx of [-1, 1]) for (const sy of [-1, 1]) for (const sz of [-1, 1]) pts.push(add(c, app(R, [h[0] * sx, h[1] * sy, h[2] * sz])));
  }
  const fit = (rot, size, base = [0, 0, 0]) => { // rotation as the game applies it: Rx*Ry*Rz
    const G = mul(rx(rot[0]), mul(ry(rot[1]), rz(rot[2])));
    const q = pts.map(p => app(G, sub(p, [8, 8, 8])));
    const mn = [0, 1].map(k => Math.min(...q.map(v => v[k]))), mx = [0, 1].map(k => Math.max(...q.map(v => v[k])));
    const s = Math.min(1.6, size / Math.max(mx[0] - mn[0], mx[1] - mn[1], 1));
    const t = [-(mn[0] + mx[0]) / 2 * s + base[0], -(mn[1] + mx[1]) / 2 * s + base[1], base[2]];
    return { rotation: rot, translation: t.map(r4), scale: [r4(s), r4(s), r4(s)] };
  };
  const H = HOLDS[hold];
  const gr = guiRot || H.gui || (hold === 'shield' ? [15, 160, 0] : [0, 0, 0]);
  return { ...H.display, gui: fit(gr, 15.5), ground: fit([0, 0, 0], 8, [0, 3, 0]), fixed: fit([0, 180, 0], 14), on_shelf: fit([0, 180, 0], 14), ...extra };
}

// ---- textures (painted per material; never overwrite a texture you drew unless --repaint) ------------------------
function hex2rgb(h) { const n = parseInt(String(h).replace('#', ''), 16); return [n >> 16 & 255, n >> 8 & 255, n & 255]; }
function rgb2hsl([r, g, b]) { r /= 255; g /= 255; b /= 255; const mx = Math.max(r, g, b), mn = Math.min(r, g, b), l = (mx + mn) / 2; let h = 0, s = 0;
  if (mx !== mn) { const d = mx - mn; s = l > 0.5 ? d / (2 - mx - mn) : d / (mx + mn); h = mx === r ? (g - b) / d + (g < b ? 6 : 0) : mx === g ? (b - r) / d + 2 : (r - g) / d + 4; h /= 6; }
  return [h, s, l]; }
function hsl2rgb([h, s, l]) { if (!s) return [l, l, l].map(v => Math.round(v * 255)); const q = l < 0.5 ? l * (1 + s) : l + s - l * s, p = 2 * l - q;
  const f = t => { t = (t + 1) % 1; return t < 1 / 6 ? p + (q - p) * 6 * t : t < 1 / 2 ? q : t < 2 / 3 ? p + (q - p) * (2 / 3 - t) * 6 : p; };
  return [f(h + 1 / 3), f(h), f(h - 1 / 3)].map(v => Math.round(Math.max(0, Math.min(1, v)) * 255)); }
/** 5-step ramp: shadows cooler (toward blue), highlights warmer (toward yellow). */
function ramp(base) {
  const [h, s, l] = rgb2hsl(hex2rgb(base));
  const shift = (dl, dh) => { let hh = h + dh; return hsl2rgb([(hh + 1) % 1, Math.min(1, s * (dl < 0 ? 1.1 : 0.9)), Math.max(0.03, Math.min(0.97, l + dl))]); };
  const toward = (target, amt) => { let d = target - h; if (d > 0.5) d -= 1; if (d < -0.5) d += 1; return d * amt; };
  return [shift(-0.26, toward(0.66, 0.12)), shift(-0.13, toward(0.66, 0.06)), shift(0, 0), shift(0.11, toward(0.14, 0.05)), shift(0.22, toward(0.14, 0.1))];
}
function rnd(seed) { let x = seed * 2654435761 >>> 0; return () => ((x = (x ^ (x << 13)) >>> 0, x = (x ^ (x >>> 17)) >>> 0, x = (x ^ (x << 5)) >>> 0) % 10000) / 10000; }
function paint(mat, base, frames = 1) {
  const R = ramp(base), W = 16, H = 16 * frames, px = new Uint8Array(W * H * 4), r = rnd(base.length * 97 + mat.length * 31 + parseInt(base.slice(1), 16) % 997);
  for (let f = 0; f < frames; f++) for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
    const n = r();
    let k = 2;
    switch (mat) {
      case 'metal': k = (x + y * 2) % 9 === 0 ? 4 : (x * 3 + y) % 11 === 0 ? 3 : n < 0.12 ? 1 : 2; break;
      case 'gold': k = (x + y) % 6 === 0 ? 4 : (x + y) % 6 === 3 ? 1 : n < 0.15 ? 3 : 2; break;
      case 'wood': k = (x % 4 === 0) ? 1 : (x % 4 === 2 && n < 0.5) ? 3 : n < 0.08 ? 0 : 2; break;
      case 'wrap': k = (x + y) % 4 < 2 ? 1 : (x + y) % 4 === 2 ? 3 : 2; break;
      case 'leather': k = n < 0.15 ? 1 : n > 0.9 ? 3 : 2; break;
      case 'gem': k = (x + y) % 5 === 0 ? 4 : (x - y + 16) % 7 === 0 ? 1 : n < 0.3 ? 3 : 2; break;
      case 'crystal': k = (x + y) % 8 < 2 ? 4 : (x * 2 + y) % 9 < 2 ? 1 : 3; break;
      case 'cloth': k = (y % 3 === 0) ? 1 : n < 0.2 ? 3 : 2; break;
      case 'bone': k = n < 0.1 ? 1 : n > 0.85 ? 3 : 3; break;
      case 'stone': k = n < 0.25 ? 1 : n > 0.8 ? 3 : 2; break;
      case 'string': k = n < 0.2 ? 3 : 4; break;
      case 'energy': { const pulse = frames > 1 ? Math.sin(f / frames * Math.PI * 2) : 0; k = Math.max(1, Math.min(4, Math.round(3 + pulse + ((x * 5 + y * 3 + f * 4) % 7 === 0 ? 1 : 0) - (n < 0.1 ? 1 : 0)))); break; }
      default: k = 2;
    }
    const c = R[k], i = ((f * 16 + y) * W + x) * 4;
    px[i] = c[0]; px[i + 1] = c[1]; px[i + 2] = c[2]; px[i + 3] = 255;
  }
  return { w: W, h: H, px };
}
const CRC = (() => { const t = new Uint32Array(256); for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0; } return t; })();
function crc32(b) { let c = 0xffffffff; for (const x of b) c = CRC[(c ^ x) & 255] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; }
function writePng(file, { w, h, px }) {
  const chunk = (type, data) => { const len = Buffer.alloc(4); len.writeUInt32BE(data.length); const td = Buffer.concat([Buffer.from(type), data]); const c = Buffer.alloc(4); c.writeUInt32BE(crc32(td)); return Buffer.concat([len, td, c]); };
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) { raw[y * (w * 4 + 1)] = 0; Buffer.from(px.buffer, y * w * 4, w * 4).copy(raw, y * (w * 4 + 1) + 1); }
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]));
}

// ---- options -----------------------------------------------------------------------------------------------
const kv = (s, sep = ',') => Object.fromEntries(String(s || '').split(sep).map(x => x.trim()).filter(Boolean).map(x => { const i = x.indexOf('='); return i < 0 ? [x, '1'] : [x.slice(0, i).trim(), x.slice(i + 1).trim()]; }));
const num = o => Object.fromEntries(Object.entries(o).map(([k, v]) => [k, isNaN(Number(v)) ? v : Number(v)]));
function die(m) { console.error('mc: ' + m); process.exit(1); }

/** One model JSON (+ textures) for a shape with params. Returns { model, textures: {key: ref} }. */
function buildModel(id, shapeName, spec, ctx, texPrefix) {
  const S = SHAPES[shapeName]; if (!S) die('unknown --model ' + shapeName + ' (' + Object.keys(SHAPES).join(', ') + ')');
  const p = { ...S.p, ...spec.params };
  const hold = spec.hold || S.hold;
  if (!HOLDS[hold]) die('--hold blade|bow|gun|shield|hand|head');
  let parts = S.build(p);
  if (spec.parts) parts = parts.concat(spec.parts.map(normPart));
  if (spec.glow.length) parts = parts.map(pt => spec.glow.includes(pt.tex) ? { ...pt, glow: true } : pt);
  if (spec.noglow.length) parts = parts.map(pt => spec.noglow.includes(pt.tex) ? { ...pt, glow: false } : pt);
  const H = S.raw ? { ...HOLDS[hold], frame: null } : HOLDS[hold];
  if (!parts.length) die('no parts: --model custom needs --parts \'[{"box":[x1,y1,z1,x2,y2,z2],"tex":"name"}]\'');
  const els = toElements(parts, hold);
  const textures = {};
  for (const pt of parts) if (!textures[pt.tex]) {
    const [mat0, col0] = (S.tex[pt.tex] || ['metal', '#a0a8b4']);
    const mat = spec.mats[pt.tex] || mat0, col = spec.colors[pt.tex] || col0;
    const ref = spec.texmap[pt.tex] || (texPrefix + '_' + pt.tex);
    if (ref.includes(':')) { textures[pt.tex] = ref; continue; }
    textures[pt.tex] = (ctx.ns || 'moddeus') + ':item/' + ref;
    const f = path.join(ctx.A, 'textures/item', ref + '.png');
    const pulse = spec.pulse && (mat === 'energy' || parts.some(q => q.tex === pt.tex && q.glow));
    if (!fs.existsSync(f) || spec.repaint) {
      writePng(f, paint(mat, col, pulse ? 8 : 1));
      if (pulse) fs.writeFileSync(f + '.mcmeta', JSON.stringify({ animation: { frametime: 3, interpolate: true } }) + '\n'); else if (fs.existsSync(f + '.mcmeta')) fs.rmSync(f + '.mcmeta');
    }
    ctx.files.push('assets/' + (ctx.ns || 'moddeus') + '/textures/item/' + ref + '.png');
    if (pulse) ctx.files.push('assets/' + (ctx.ns || 'moddeus') + '/textures/item/' + ref + '.png.mcmeta');
  }
  textures.particle = Object.values(textures)[0];
  const extra = spec.display || {};
  const model = { gui_light: hold === 'head' || hold === 'hand' ? 'side' : 'front', textures, elements: els, display: display(els, hold, spec.gui, extra) };
  if (spec.guiLight) model.gui_light = spec.guiLight;
  return { model, hold };
}
function toElementsWrap(parts, hold, H) { return toElements(parts, hold, H); }
function normPart(q) { // free-form part: {box:[x1,y1,z1,x2,y2,z2]} or {c,s}; tex; r:[x,y,z]; pivot; glow; faces
  if (q.box) { const b = q.box; return { ...q, c: [(b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2], s: [Math.abs(b[3] - b[0]), Math.abs(b[4] - b[1]), Math.abs(b[5] - b[2])], tex: q.tex || 'part' }; }
  if (!q.c || !q.s) die('part needs box:[x1,y1,z1,x2,y2,z2] or c:[x,y,z]+s:[w,h,d]: ' + JSON.stringify(q));
  return { tex: 'part', ...q };
}

function specFrom(o, base = {}) {
  let parts = null;
  if (o.parts || o.add) { try { parts = JSON.parse(o.parts || o.add); } catch (e) { die('--parts is not JSON: ' + e.message); } }
  return {
    params: { ...(base.params || {}), ...num(kv(o.p || o.params)) },
    colors: { ...(base.colors || {}), ...kv(o.colors) },
    mats: { ...(base.mats || {}), ...kv(o.mats) },
    texmap: { ...(base.texmap || {}), ...kv(o.tex) },
    glow: [...(base.glow || []), ...String(o.glow || '').split(/[,+]/).filter(Boolean)],
    noglow: [...(base.noglow || []), ...String(o.noglow || '').split(/[,+]/).filter(Boolean)],
    parts: parts || base.parts || null,
    hold: o.hold || base.hold, gui: o.gui ? String(o.gui).split(/[ ,]/).map(Number) : base.gui,
    pulse: o.pulse !== undefined ? !!o.pulse && o.pulse !== 'false' : base.pulse, repaint: !!o.repaint, guiLight: o['gui-light'] || base.guiLight, display: base.display,
  };
}

/** Wraps a definition in a condition for one variant. */
function wrap(cond, on, off) {
  const m = cond.match(/^(using|charged|broken|damaged|carried|selected|cooldown|key:([\w.]+)|cmd(\d+(?:\.\d+)?)|use(\d+(?:\.\d+)?))$/);
  if (!m) die('variant condition must be using|charged|cooldown|broken|damaged|carried|selected|key:<key.x>|cmd<N>|use<seconds>, got ' + cond);
  const c = (property, x = {}) => ({ type: 'minecraft:condition', property: 'minecraft:' + property, ...x, on_true: on, on_false: off });
  if (cond === 'using') return c('using_item');
  if (cond === 'charged') return c('custom_model_data', { index: 0 });
  if (/^(broken|damaged|carried|selected)$/.test(cond)) return c(cond === 'selected' ? 'selected' : cond);
  if (m[2]) return c('keybind_down', { keybind: m[2] });
  if (cond === 'cooldown') return { type: 'minecraft:range_dispatch', property: 'minecraft:cooldown', entries: [{ threshold: 0.001, model: on }], fallback: off };
  if (m[3]) return { type: 'minecraft:range_dispatch', property: 'minecraft:custom_model_data', index: 0, entries: [{ threshold: Number(m[3]), model: on }], fallback: off };
  return c('using_item', { on_true: { type: 'minecraft:range_dispatch', property: 'minecraft:use_duration', scale: 0.05, entries: [{ threshold: Number(m[4]), model: on }], fallback: off }, on_false: off });
}

/** mc item <id> --model <shape> ...: writes models/item/<id>*.json + textures; returns { model, definition }. */
export async function item(id, opt, ctx) {
  const shapeName = String(opt.model);
  const S = SHAPES[shapeName]; if (!S) die('unknown --model ' + shapeName + ' (' + Object.keys(SHAPES).join(', ') + ')');
  const spec = specFrom(opt);
  const { model, hold } = buildModel(id, shapeName, spec, ctx, id);
  const ref = n => ({ type: 'minecraft:model', model: (ctx.ns || 'moddeus') + ':item/' + n });
  let def = ref(id);
  // variants: --variant "using:glow=blade,colors=blade=#f44|..."; later entries in the list lose to earlier ones
  const vars = String(opt.variant || '').split(';').map(s => s.trim()).filter(Boolean);
  for (const v of vars.reverse()) {
    const i = v.indexOf(':'), cond = (i < 0 ? v : v.slice(0, i)).trim(), body = i < 0 ? '' : v.slice(i + 1);
    const o = {}; for (const seg of body.split('|')) { const j = seg.indexOf('='); if (j > 0) o[seg.slice(0, j).trim()] = seg.slice(j + 1).trim(); }
    const name = id + '_' + cond.replace(/[^a-z0-9]+/g, '_').replace(/_+$/, '');
    const vs = specFrom(o, { ...spec, display: hold === 'shield' && cond === 'using' ? HOLDS.shield.using : spec.display });
    const texPrefix = o.colors || o.mats ? name : id;
    if (!o.colors && !o.mats) vs.repaint = false;
    const built = buildModel(name, shapeName, vs, ctx, texPrefix);
    ctx.out(ctx.files, 'models/item/' + name + '.json', built.model);
    def = wrap(cond, ref(name), def);
  }
  if (hold === 'shield' && !vars.some(v => v.startsWith('using'))) {
    const name = id + '_using', built = buildModel(name, shapeName, { ...spec, repaint: false, display: HOLDS.shield.using }, ctx, id);
    ctx.out(ctx.files, 'models/item/' + name + '.json', built.model);
    def = wrap('using', ref(name), def);
  }
  if (ctx.c && ctx.c.tool && ctx.c.tool.type === 'bow') { // real bow: vanilla pull stages (use_duration x 0.05 s: <0.65 / 0.65 / 0.9), outermost so they win over a 'using' variant
    const uv = vars.find(v => /^using\b/.test(v)), o = {};
    if (uv) for (const seg of uv.slice(uv.indexOf(':') + 1).split('|')) { const j = seg.indexOf('='); if (j > 0) o[seg.slice(0, j).trim()] = seg.slice(j + 1).trim(); }
    const b0 = spec.params.bend ?? (S.p.bend || 0);
    const stage = (k, add) => { // bow shape: limbs bend further each stage; reuses the 'using' variant's colors/textures
      const name = id + '_pulling_' + k, so = { ...o };
      if (shapeName === 'bow') so.p = [o.p, 'bend=' + (b0 + add), 'arrow=1', 'draw=' + [0.4, 0.7, 1][k]].filter(Boolean).join(','); // every stage shows the nocked arrow like vanilla bow_pulling_N
      const vs = specFrom(so, spec); vs.repaint = false;
      ctx.out(ctx.files, 'models/item/' + name + '.json', buildModel(name, shapeName, vs, ctx, o.colors || o.mats ? id + '_using' : id).model);
      return ref(name);
    };
    const p0 = stage(0, 4), p1 = stage(1, 9), p2 = stage(2, 15);
    def = { type: 'minecraft:condition', property: 'minecraft:using_item', on_false: def,
      on_true: { type: 'minecraft:range_dispatch', property: 'minecraft:use_duration', scale: 0.05, entries: [{ threshold: 0.65, model: p1 }, { threshold: 0.9, model: p2 }], fallback: p0 } };
  }
  if (ctx.c && ctx.c.tool && ctx.c.tool.type === 'crossbow') { // vanilla crossbow item definition: charge_type (arrow / rocket) > using: crossbow/pull 0 / 0.58 / 1.0
    const st = (name, p) => { const so = { p: [spec.params && Object.entries(spec.params).map(([k, v]) => k + '=' + v).join(','), p].filter(Boolean).join(',') };
      const vs = specFrom(so, spec); vs.repaint = false; ctx.out(ctx.files, 'models/item/' + id + '_' + name + '.json', buildModel(id + '_' + name, shapeName, vs, ctx, id).model); return ref(id + '_' + name); };
    const p0 = st('pulling_0', 'draw=0.35'), p1 = st('pulling_1', 'draw=0.7'), p2 = st('pulling_2', 'draw=1'), arrow = st('arrow', 'draw=1,load=1'), rocket = st('firework', 'draw=1,load=2');
    def = { type: 'minecraft:select', property: 'minecraft:charge_type', cases: [{ when: 'arrow', model: arrow }, { when: 'rocket', model: rocket }],
      fallback: { type: 'minecraft:condition', property: 'minecraft:using_item', on_false: def,
        on_true: { type: 'minecraft:range_dispatch', property: 'minecraft:crossbow/pull', entries: [{ threshold: 0.58, model: p1 }, { threshold: 1, model: p2 }], fallback: p0 } } };
  }
  if (opt.icon) { // a different model in the GUI: --icon sprite (textures/item/<id>_icon.png) or --icon "k=v|k=v" (same shape, other params)
    let icon;
    if (opt.icon === 'sprite') { icon = id + '_icon'; ctx.out(ctx.files, 'models/item/' + icon + '.json', { parent: 'minecraft:item/generated', textures: { layer0: (ctx.ns || 'moddeus') + ':item/' + icon } });
      if (!fs.existsSync(path.join(ctx.A, 'textures/item', icon + '.png'))) console.warn('  (draw the icon: mc pixels item ' + icon + ')'); ctx.files.push('assets/' + (ctx.ns || 'moddeus') + '/textures/item/' + icon + '.png'); }
    else { icon = id + '_icon'; const o = {}; for (const seg of String(opt.icon).split('|')) { const j = seg.indexOf('='); if (j > 0) o[seg.slice(0, j).trim()] = seg.slice(j + 1).trim(); }
      ctx.out(ctx.files, 'models/item/' + icon + '.json', buildModel(icon, shapeName, { ...specFrom(o, spec), repaint: false }, ctx, id).model); }
    def = { type: 'minecraft:select', property: 'minecraft:display_context', cases: [{ when: ['gui'], model: ref(icon) }], fallback: def };
  }
  const definition = { model: def, ...(hold === 'shield' || /greatsword|scythe|spear|staff/.test(shapeName) ? { swap_animation_scale: 1.5 } : {}) };
  // real function where data can do it: worn things go on the head, shields block
  const comp = {};
  if (hold === 'head') comp['minecraft:equippable'] = { slot: 'head' };
  if (hold === 'shield') comp['minecraft:blocks_attacks'] = { block_delay_seconds: 0.1, block_sound: 'minecraft:item.shield.block', disabled_sound: 'minecraft:item.shield.break' };
  if (Object.keys(comp).length) ctx.c.components = { ...comp, ...(ctx.c.components || {}) };
  console.log('  model: ' + shapeName + ' (' + model.elements.length + ' elements, hold ' + hold + ')' + (vars.length ? ', variants: ' + vars.map(v => v.split(':')[0]).join(' ') : '') + ' -> preview: mc model preview ' + id);
  return { model, definition };
}

// ---- mc model shapes | preview ---------------------------------------------------------------------------------
export async function cli(pos, opt, ROOT) {
  const sub = pos[0];
  if (sub === 'shapes' || !sub) {
    for (const [k, s] of Object.entries(SHAPES)) console.log(k.padEnd(11) + s.about + '\n' + ' '.repeat(11) + 'p: ' + Object.entries(s.p).map(([a, b]) => a + '=' + b).join(',') + '   tex: ' + Object.entries(s.tex).map(([a, [m, c]]) => a + '(' + m + ' ' + c + ')').join(' '));
    return;
  }
  if (sub === 'preview') {
    const id = pos[1]; if (!id) die('usage: model preview <id> [--state "<item components>"] [--keep]');
    const full = id.includes(':') ? id : (() => { try { return JSON.parse(fs.readFileSync(path.join(ROOT, 'src/main/resources/moddeus/content', id + '.json'), 'utf8')).namespace || 'moddeus'; } catch { return 'moddeus'; } })() + ':' + id; // its mod's namespace
    const spec = full + (opt.state ? String(opt.state) : '');
    const mc = (...a) => execFileSync(process.execPath, [path.join(ROOT, 'tools/mc.mjs'), ...a], { cwd: ROOT, encoding: 'utf8' });
    const q = s => '"' + s.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
    console.log(mc('game', 'run', 'dev.moddeus.live.ModelPreview.show(' + q(spec) + ', ' + (opt.hand === false || opt['no-hand'] ? 'false' : 'true') + ');').trim());
    await new Promise(r => setTimeout(r, Number(opt.wait || 900)));
    const shot = mc('game', 'shot').trim().split('\n').find(l => /\.png/.test(l));
    if (!opt.keep) mc('game', 'run', 'dev.moddeus.live.ModelPreview.clear();');
    console.log('preview: ' + shot + '   (left: 3rd person on a mannequin; right: GUI, item frame, ground; your hand: 1st person)');
    return;
  }
  die('usage: model shapes | model preview <id>');
}
export { SHAPES, HOLDS };
