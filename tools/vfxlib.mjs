// VFX library: procedural glow textures + effect presets. Used by "mc vfx-tex" and "mc vfx --preset".
// Every texture is generated here (no vanilla art): intensity shapes x color ramps.
// Additive blending: black = invisible, so textures are color * intensity on black, alpha = intensity.

export const PALETTES = {
  fire:      ['#200400', '#5a0e00', '#a02800', '#e05000', '#ff8a10', '#ffc040', '#ffe890', '#ffffff'],
  ice:       ['#00081a', '#00204a', '#00488a', '#1080c8', '#40b8f0', '#90e0ff', '#d0f6ff', '#ffffff'],
  arcane:    ['#0a0018', '#2a0050', '#500090', '#8020d0', '#b050ff', '#d890ff', '#f0d0ff', '#ffffff'],
  holy:      ['#1a1000', '#4a3000', '#906010', '#d0a030', '#f0d060', '#fff0a0', '#fffae0', '#ffffff'],
  poison:    ['#001000', '#003010', '#106020', '#30a030', '#70d040', '#b0f060', '#e0ffb0', '#ffffff'],
  shadow:    ['#000000', '#08000c', '#180020', '#300040', '#501070', '#7a30a0', '#b070d0', '#e0c0ff'],
  blood:     ['#100000', '#300000', '#600008', '#9a0010', '#d01828', '#ff4050', '#ff9aa0', '#ffffff'],
  lightning: ['#000814', '#001840', '#103080', '#3060d0', '#70a0ff', '#b0d0ff', '#e8f0ff', '#ffffff'],
  nature:    ['#001006', '#00301a', '#0a5a2a', '#209a40', '#50d060', '#a0f080', '#e0ffd0', '#ffffff'],
  void:      ['#000000', '#00000a', '#0a0420', '#1a0840', '#2a1060', '#00d0c0', '#80fff0', '#ffffff'],
  ash:       ['#0c0a09', '#1e1a17', '#2f2925', '#463d36', '#5f544a', '#7d7064', '#a09080', '#c8bcae'],
  sun:       ['#3a0f00', '#7a2400', '#c04a00', '#ff7a10', '#ffa830', '#ffd060', '#fff0a0', '#ffffff'],
};

const hex = h => { const n = parseInt(h.replace('#', ''), 16); return [n >> 16 & 255, n >> 8 & 255, n & 255]; };
/** Custom palette: "fire" or "#000,#f00,#ff0,#fff" (any number of stops). */
export function ramp(p) {
  const stops = (PALETTES[p] || String(p).split(',')).map(hex);
  return t => { t = Math.max(0, Math.min(1, t)) * (stops.length - 1); const i = Math.min(stops.length - 2, Math.floor(t)), f = t - i;
    return stops[i].map((c, k) => Math.round(c + (stops[i + 1][k] - c) * f)); };
}
function hash(x, y, s) { let h = (x * 374761393 + y * 668265263 + s * 982451653) | 0; h = Math.imul(h ^ (h >>> 13), 1274126177); return ((h ^ (h >>> 16)) >>> 0) / 4294967296; }
function noise(x, y, s) { const xi = Math.floor(x), yi = Math.floor(y), xf = x - xi, yf = y - yi, u = xf * xf * (3 - 2 * xf), v = yf * yf * (3 - 2 * yf);
  const a = hash(xi, yi, s), b = hash(xi + 1, yi, s), c = hash(xi, yi + 1, s), d = hash(xi + 1, yi + 1, s); return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v; }
export function fbm(x, y, s, oct = 4) { let v = 0, a = 0.5, f = 1; for (let i = 0; i < oct; i++) { v += a * noise(x * f, y * f, s + i); a /= 2; f *= 2; } return v; }
const gauss = (d, w) => Math.exp(-((d / w) ** 2));
function segDist(px, py, x1, y1, x2, y2) { const t = Math.max(0, Math.min(1, ((px - x1) * (x2 - x1) + (py - y1) * (y2 - y1)) / ((x2 - x1) ** 2 + (y2 - y1) ** 2 || 1))); return Math.hypot(px - (x1 + t * (x2 - x1)), py - (y1 + t * (y2 - y1))); }

/** Shapes: (u, v in -1..1 centered, frame 0..1, o opts) -> intensity 0..1. */
export const SHAPES = {
  glow:      (u, v) => gauss(Math.hypot(u, v), 0.45),
  orb:       (u, v) => { const d = Math.hypot(u, v); return d > 1 ? 0 : 0.35 * gauss(d, 0.7) + gauss(d - 0.82, 0.12) + 0.6 * gauss(Math.hypot(u + 0.3, v + 0.3), 0.18); },
  ring:      (u, v, f, o) => gauss(Math.hypot(u, v) - (o.radius ?? 0.8), o.thickness ?? 0.08) + 0.25 * gauss(Math.hypot(u, v) - (o.radius ?? 0.8) + 0.2, 0.08),
  shockwave: (u, v, f, o) => { const d = Math.hypot(u, v), a = Math.atan2(v, u); return gauss(d - 0.82, 0.07) * (0.55 + 0.45 * fbm(Math.cos(a) * 3 + 5, Math.sin(a) * 3 + f * 4, o.seed)) + 0.3 * gauss(d - 0.7, 0.15) * (d < 0.82 ? 1 : 0); },
  flare:     (u, v) => { const x = Math.abs(u) * 8, y = Math.abs(v) * 8; return Math.max(Math.exp(-x * 0.9 - y * 0.12), Math.exp(-y * 0.9 - x * 0.12)) + 0.7 * gauss(Math.hypot(u, v), 0.25); },
  star:      (u, v, f, o) => { const n = o.points ?? 6, a = Math.atan2(v, u), d = Math.hypot(u, v); return Math.max(0, gauss(d, 0.12 + 0.55 * Math.pow(Math.abs(Math.cos(a * n / 2)), 18))) + 0.5 * gauss(d, 0.2); },
  spark:     (u, v) => Math.max(gauss(u, 0.08) * gauss(v, 0.6), gauss(v, 0.08) * gauss(u, 0.6), gauss(Math.hypot(u, v), 0.2)),
  beam:      (u, v, f, o) => (gauss(u, 0.35) * (0.55 + 0.45 * fbm(u * 2, v * 3 - f * 4, o.seed)) + gauss(u, 0.08) * 0.8) * Math.min(1, (1 - v) * 3),
  slash:     (u, v) => gauss(v, 0.32) * ((u + 1) / 2) ** 1.5 + gauss(v, 0.08) * ((u + 1) / 2) * 0.6,
  sigil:     (u, v, f, o) => { const d = Math.hypot(u, v); if (d > 1) return 0; const n = o.points ?? 6, step = n % 2 ? 2 : 1;
    let m = Math.max(gauss(d - 0.92, 0.035), 0.7 * gauss(d - 0.74, 0.03));
    for (let k = 0; k < n; k++) { const a1 = 2 * Math.PI * k / n - Math.PI / 2, a2 = 2 * Math.PI * (k + (n % 2 ? 2 : (n > 4 ? n / 2 - 1 : 1))) / n - Math.PI / 2;
      m = Math.max(m, 0.8 * gauss(segDist(u, v, Math.cos(a1) * 0.74, Math.sin(a1) * 0.74, Math.cos(a2) * 0.74, Math.sin(a2) * 0.74), 0.035)); }
    const a = Math.atan2(v, u); if (d > 0.76 && d < 0.9) m = Math.max(m, 0.6 * gauss(((a / (2 * Math.PI) * 24) % 1 + 1) % 1 - 0.5, 0.15)); return m; },
  swirl:     (u, v, f, o) => { const d = Math.hypot(u, v), a = Math.atan2(v, u), arms = o.points ?? 3; return d > 1 ? 0 : Math.pow(0.5 + 0.5 * Math.cos(arms * (a + d * 5 - f * 2 * Math.PI)), 4) * gauss(d - 0.5, 0.35); },
  smoke:     (u, v, f, o) => Math.max(0, fbm(u * 2 + 3, v * 2 + f * 2, o.seed, 5) * 1.6 - 0.35) * gauss(Math.hypot(u, v), 0.75),
  lightning: (u, v, f, o) => { let x = 0, best = 9; const s = (o.seed ?? 1) + Math.floor(f * 8); for (let i = 0; i < 16; i++) { const y0 = -1 + i / 8, y1 = y0 + 1 / 8, x1 = x + (hash(i, s, 3) - 0.5) * 0.5; best = Math.min(best, segDist(u, v, x, y0, x1, y1)); x = Math.max(-0.7, Math.min(0.7, x1)); } return gauss(best, 0.05) + 0.35 * gauss(best, 0.2); },
  // cauliflower smoke ball: lumpy edge, solid body (use with lit:true + premul:false + blend alpha)
  puff:      (u, v, f, o) => { const d = Math.hypot(u, v), a = Math.atan2(v, u); const edge = 0.78 + 0.16 * fbm(Math.cos(a) * 2.2 + 9, Math.sin(a) * 2.2 + f * 3, o.seed ?? 4, 3);
    return Math.max(0, Math.min(1, (edge - d) * 6)) * (0.75 + 0.25 * fbm(u * 3 + 1, v * 3 + f * 2, (o.seed ?? 4) + 5, 4)); },
  crack:     (u, v, f, o) => { const d = Math.hypot(u, v); let m = 0; for (let k = 0; k < 7; k++) { const a = 2 * Math.PI * hash(k, 1, o.seed ?? 2); let px = 0, py = 0;
      for (let j = 0; j < 4; j++) { const r = (j + 1) / 4 * 0.95, aa = a + (hash(k, j, 9) - 0.5) * 0.6, nx = Math.cos(aa) * r, ny = Math.sin(aa) * r; m = Math.max(m, gauss(segDist(u, v, px, py, nx, ny), 0.04) * (1 - j * 0.18)); px = nx; py = ny; } } return m + 0.4 * gauss(d, 0.2); },
};

/** Returns {w, h, rgba} — frames stacked vertically (VfxClient plays them with "frames": n). */
/** premul (default true) = glow texture for additive blend. premul:false keeps colors for "blend":"alpha" (smoke).
 *  lit:true colors by light (top bright, bottom dark, bumpy) instead of by intensity: reads as a 3D ball of smoke. */
export function texture(shape, { palette = 'fire', size = 32, frames = 1, seed = 1, contrast = 1, premul = true, lit = false, ...o } = {}) {
  const fn = SHAPES[shape];
  if (!fn) throw new Error('unknown shape ' + shape + ' (' + Object.keys(SHAPES).join(' ') + ')');
  const col = ramp(palette), w = size, h = size * frames, buf = Buffer.alloc(w * h * 4);
  for (let fr = 0; fr < frames; fr++) for (let y = 0; y < size; y++) for (let x = 0; x < w; x++) {
    const u = (x + 0.5) / size * 2 - 1, v = (y + 0.5) / size * 2 - 1;
    const i = Math.max(0, Math.min(1, Math.pow(fn(u, v, frames > 1 ? fr / frames : 0, { seed, ...o }), contrast)));
    const light = lit ? Math.max(0, Math.min(1, 0.55 - v * 0.35 - u * 0.12 + 0.45 * (fbm(u * 3 + 7, v * 3, seed + 11, 4) - 0.5) - Math.hypot(u, v) * 0.15)) : i;
    const [r, g, b] = col(light), k = ((fr * size + y) * w + x) * 4, m = premul ? i : 1;
    buf[k] = r * m; buf[k + 1] = g * m; buf[k + 2] = b * m; buf[k + 3] = Math.round(255 * Math.min(1, i * (premul ? 1.5 : 1.15)));
  }
  return { w, h, rgba: buf };
}

/**
 * Effect presets: (tex(name, shape, opts) registers a texture to generate, s = scale) -> layers.
 * Every preset is a starting point: pass --layers to append, or edit the JSON afterwards.
 */
export const PRESETS = {
  smite: (t, s) => [
    { type: 'sound', sound: 'minecraft:entity.lightning_bolt.thunder', pitch: 1.6, volume: 0.6 },
    { type: 'flash', color: '#ffffff', alpha: 0.5, duration: 8 }, { type: 'shake', size: 1.5, duration: 10 },
    { type: 'pillar', texture: t('beam', 'beam', { frames: 4 }), frames: 4, loops: 3, size: 0.9 * s, height: 0, height_end: 14 * s, spin: 6, duration: 18, alpha_end: 0 },
    { type: 'ring', texture: t('shock', 'shockwave'), size: 0.5, size_end: 7 * s, duration: 14, alpha_end: 0 },
    { type: 'ring', texture: t('sigil', 'sigil', { points: 6 }), size: 3.2 * s, size_end: 3.6 * s, spin: 4, duration: 36, alpha_end: 0 },
    { type: 'billboard', texture: t('flare', 'flare'), y: 1.2, size: 2.6 * s, size_end: 0.2, spin: 12, duration: 14 },
  ],
  nova: (t, s) => [
    { type: 'sound', sound: 'minecraft:entity.generic.explode', pitch: 1.3, volume: 0.8 },
    { type: 'flash', alpha: 0.35, duration: 6 }, { type: 'shake', size: 1, duration: 8 },
    { type: 'billboard', texture: t('orb', 'orb'), y: 1, size: 0.3, size_end: 4 * s, duration: 10, alpha_end: 0 },
    { type: 'billboard', texture: t('glow', 'glow'), y: 1, size: 3 * s, size_end: 6 * s, duration: 14, alpha: 0.8, alpha_end: 0 },
    { type: 'ring', texture: t('ring', 'ring', { thickness: 0.06 }), size: 0.5, size_end: 9 * s, duration: 12, alpha_end: 0 },
    { type: 'billboard', texture: t('star', 'star', { points: 8 }), y: 1, size: 3 * s, size_end: 0, spin: 20, duration: 12 },
  ],
  slash: (t, s) => [
    { type: 'sound', sound: 'minecraft:entity.player.attack.sweep', pitch: 0.9 },
    { type: 'slash', texture: t('slash', 'slash', { size: 32 }), size: 1.8 * s, width: 0.9 * s, arc: 170, tilt: -20, duration: 9, alpha_end: 0.2 },
    { type: 'slash', texture: t('slash', 'slash', { size: 32 }), size: 1.5 * s, width: 0.5 * s, arc: 150, tilt: 25, delay: 3, duration: 8, alpha_end: 0 },
    { type: 'billboard', texture: t('spark', 'spark'), y: 1, size: 1.2 * s, size_end: 0, duration: 6 },
  ],
  portal: (t, s) => [
    { type: 'sound', sound: 'minecraft:block.portal.trigger', pitch: 1.5, volume: 0.5 },
    { type: 'ring', texture: t('sigil', 'sigil', { points: 5 }), size: 0, size_end: 2.5 * s, spin: 3, duration: 80, alpha_end: 0 },
    { type: 'billboard', texture: t('swirl', 'swirl', { frames: 4, points: 3 }), frames: 4, loops: 10, y: 1.5, size: 1.6 * s, spin: -8, duration: 80, alpha_end: 0 },
    { type: 'pillar', texture: t('beam', 'beam', { frames: 4 }), frames: 4, loops: 10, size: 1.2 * s, height: 3 * s, planes: 4, spin: 3, duration: 80, alpha: 0.6, alpha_end: 0 },
  ],
  beam: (t, s) => [
    { type: 'sound', sound: 'minecraft:block.beacon.power_select', pitch: 1.8 },
    { type: 'beam', texture: t('beam', 'beam', { frames: 4 }), frames: 4, loops: 4, size: 0.6 * s, length: 20 * s, grow: 0.2, duration: 16, alpha_end: 0 },
    { type: 'beam', texture: t('glow', 'glow'), size: 1.6 * s, length: 20 * s, grow: 0.2, duration: 16, alpha: 0.5, alpha_end: 0 },
    { type: 'billboard', texture: t('flare', 'flare'), y: 1.4, size: 1 * s, duration: 16, spin: 15, alpha_end: 0 },
  ],
  aura: (t, s) => [
    { type: 'ring', texture: t('sigil', 'sigil', { points: 8 }), size: 1.4 * s, spin: 2, duration: 40, alpha: 0, alpha_end: 0.8 },
    { type: 'pillar', texture: t('beam', 'beam', { frames: 4 }), frames: 4, loops: 4, size: 0.8 * s, height: 2.5 * s, planes: 4, duration: 40, alpha: 0.5, alpha_end: 0 },
  ],
  explosion: (t, s) => [
    { type: 'sound', sound: 'minecraft:entity.generic.explode', volume: 1.2 },
    { type: 'flash', color: '#ffd090', alpha: 0.6, duration: 8 }, { type: 'shake', size: 2.5, duration: 14 },
    { type: 'billboard', texture: t('smoke', 'smoke', { frames: 4 }), frames: 4, y: 1, size: 1, size_end: 5 * s, duration: 24, alpha_end: 0, blend: 'alpha' },
    { type: 'billboard', texture: t('orb', 'orb'), y: 1, size: 0.5, size_end: 3.5 * s, duration: 8, alpha_end: 0 },
    { type: 'ring', texture: t('crack', 'crack'), size: 2.5 * s, duration: 40, alpha_end: 0 },
    { type: 'ring', texture: t('shock', 'shockwave'), size: 0.5, size_end: 8 * s, duration: 12, alpha_end: 0 },
  ],
  mega: (t, s) => [ // huge explosion: double flash, quake, fireball, star burst, 3 shockwaves, mushroom cloud, scorched ground
    { type: 'sound', sound: 'minecraft:entity.generic.explode', volume: 2, pitch: 0.55 },
    { type: 'sound', sound: 'minecraft:entity.lightning_bolt.thunder', volume: 1.5, pitch: 0.7, delay: 4 },
    { type: 'flash', color: '#ffffff', alpha: 0.85, duration: 6 }, { type: 'flash', color: '#ff8a20', alpha: 0.45, delay: 4, duration: 16 },
    { type: 'shake', size: 4, duration: 28 },
    { type: 'billboard', texture: t('core', 'orb'), y: 1, size: 0.5, size_end: 9 * s, duration: 12, alpha_end: 0 },
    { type: 'billboard', texture: t('glow', 'glow'), y: 1, size: 6 * s, size_end: 14 * s, duration: 24, alpha: 0.9, alpha_end: 0 },
    { type: 'billboard', texture: t('star', 'star', { points: 12 }), y: 1, size: 12 * s, size_end: 0, spin: 30, duration: 9 },
    { type: 'billboard', texture: t('flare', 'flare'), y: 1, size: 14 * s, size_end: 0, spin: -12, duration: 10 },
    { type: 'ring', texture: t('shock', 'shockwave'), size: 1, size_end: 16 * s, duration: 14, alpha_end: 0 },
    { type: 'ring', texture: t('shock', 'shockwave'), size: 1, size_end: 24 * s, delay: 4, duration: 20, alpha: 0.6, alpha_end: 0 },
    { type: 'ring', texture: t('ring', 'ring', { thickness: 0.04 }), size: 1, size_end: 32 * s, delay: 8, duration: 24, alpha: 0.5, alpha_end: 0 },
    { type: 'ring', texture: t('crack', 'crack', { size: 64 }), size: 7 * s, duration: 120, alpha_end: 0 },
    { type: 'pillar', texture: t('stem', 'beam', { frames: 4 }), frames: 4, loops: 6, size: 3 * s, height: 0, height_end: 16 * s, planes: 4, spin: 2, duration: 40, alpha_end: 0 },
    { type: 'billboard', texture: t('cap', 'smoke', { frames: 4, palette: '#000000,#1a1410,#3a2a20,#6a4a30,#c06020,#ff9a30' }), frames: 4, loops: 2, y: 16 * s, size: 2, size_end: 12 * s, delay: 6, duration: 70, alpha_end: 0, blend: 'alpha' },
    { type: 'billboard', texture: t('fireball', 'smoke', { frames: 4, seed: 7 }), frames: 4, loops: 3, y: 9 * s, size: 3, size_end: 9 * s, delay: 3, duration: 40, alpha_end: 0 },
    { type: 'billboard', texture: t('dust', 'smoke', { frames: 4, seed: 3, palette: '#000000,#201c18,#403830,#706050,#a09080' }), frames: 4, y: 1.5, size: 4, size_end: 18 * s, delay: 2, duration: 80, alpha: 0.9, alpha_end: 0, blend: 'alpha' },
  ],
  mushroom: (t, s) => { // nuke-style: flash, fireball, rising stem, rolling cap, condensation ring, ground surge, scorch
    const L = [], rnd = (k) => ((Math.sin(k * 127.1) * 43758.5453) % 1 + 1) % 1, smoke = { palette: 'ash', lit: true, premul: false, size: 32 };
    const fireP = t('fire', 'puff', { frames: 2, seed: 2 }), smokeA = t('smoke_a', 'puff', { ...smoke, seed: 4 }), smokeB = t('smoke_b', 'puff', { ...smoke, seed: 9 });
    L.push({ type: 'sound', sound: 'minecraft:entity.generic.explode', volume: 3, pitch: 0.5 },
      { type: 'sound', sound: 'minecraft:entity.warden.sonic_boom', volume: 2, pitch: 0.5, delay: 2 },
      { type: 'sound', sound: 'minecraft:entity.lightning_bolt.thunder', volume: 2, pitch: 0.5, delay: 10 },
      { type: 'flash', color: '#ffffff', alpha: 0.9, duration: 5 }, { type: 'flash', color: '#ff7a20', alpha: 0.45, delay: 3, duration: 24 },
      { type: 'shake', size: 5, duration: 45 },
      { type: 'billboard', texture: t('glow', 'glow'), y: 2, size: 4, size_end: 16 * s, duration: 24, alpha: 0.9, alpha_end: 0 },
      { type: 'ring', texture: t('shock', 'shockwave'), size: 1, size_end: 26 * s, duration: 16, alpha_end: 0, ease: 'out' },
      { type: 'ring', texture: t('crack', 'crack', { size: 64 }), size: 8 * s, duration: 220, alpha_end: 0 },
      { type: 'ring', texture: t('cond', 'ring', { thickness: 0.035, palette: '#000,#334,#889,#dde,#fff' }), y: 8 * s, size: 2, size_end: 20 * s, delay: 10, duration: 34, alpha: 0.7, alpha_end: 0, ease: 'out' });
    // fireball rising into the cap
    for (let k = 0; k < 5; k++) L.push({ type: 'billboard', texture: fireP, frames: 2, loops: 6, x: (rnd(k) - 0.5) * 2, z: (rnd(k + 9) - 0.5) * 2, y: 1, y_end: (6 + k * 1.5) * s,
      size: 2, size_end: (5 + k) * s, spin: (rnd(k + 3) - 0.5) * 6, delay: k * 2, duration: 45, alpha_end: 0, ease: 'out' });
    // stem: stacked puffs rising, staying narrow
    for (let k = 0; k < 9; k++) L.push({ type: 'billboard', texture: k % 2 ? smokeA : smokeB, blend: 'alpha', x: (rnd(k + 20) - 0.5) * 0.8 * s, z: (rnd(k + 40) - 0.5) * 0.8 * s,
      y: 0.5, y_end: (1.5 + k * 1.35) * s, size: 1.5 * s, size_end: (2.6 - k * 0.08) * s, spin: (rnd(k + 60) - 0.5) * 3, delay: 6 + k * 2, duration: 170 - k * 6, alpha: 0.95, alpha_end: 0, ease: 'out' });
    // cap: torus of puffs rolling up and out, plus core
    for (let k = 0; k < 14; k++) { const a = k / 14 * Math.PI * 2, r0 = 1.5 * s, r1 = (4.6 + rnd(k + 80) * 0.8) * s;
      L.push({ type: 'billboard', texture: k % 2 ? smokeA : smokeB, blend: 'alpha', x: Math.cos(a) * r0, z: Math.sin(a) * r0, y: 4 * s, x_end: Math.cos(a) * r1, z_end: Math.sin(a) * r1,
        y_end: (13 + rnd(k + 100) * 1.5) * s, size: 3 * s, size_end: (5.5 + rnd(k) * 1.5) * s, spin: (rnd(k + 120) - 0.5) * 2, delay: 8 + (k % 3), duration: 190, alpha: 0.97, alpha_end: 0, ease: 'out' });
      L.push({ type: 'billboard', texture: fireP, frames: 2, loops: 8, x: Math.cos(a) * r0, z: Math.sin(a) * r0, y: 4 * s, x_end: Math.cos(a) * r1 * 0.8, z_end: Math.sin(a) * r1 * 0.8,
        y_end: 11.5 * s, size: 2.5 * s, size_end: 4.5 * s, delay: 8, duration: 60, alpha: 0.85, alpha_end: 0, ease: 'out' }); }
    for (let k = 0; k < 4; k++) L.push({ type: 'billboard', texture: k % 2 ? smokeB : smokeA, blend: 'alpha', x: (rnd(k + 140) - 0.5) * 2, z: (rnd(k + 150) - 0.5) * 2, y: 5 * s,
      y_end: (15.5 + k * 0.6) * s, size: 4 * s, size_end: 7.5 * s, delay: 8 + k, duration: 195, alpha: 0.97, alpha_end: 0, ease: 'out' });
    // ground surge: dust ring rolling outward along the ground
    for (let k = 0; k < 16; k++) { const a = k / 16 * Math.PI * 2 + 0.2;
      L.push({ type: 'billboard', texture: k % 2 ? smokeA : smokeB, blend: 'alpha', x: Math.cos(a) * 1.5, z: Math.sin(a) * 1.5, y: 0.8, x_end: Math.cos(a) * 11 * s, z_end: Math.sin(a) * 11 * s,
        y_end: 1.6 * s, size: 1.5 * s, size_end: 3.5 * s, delay: 3, duration: 110, alpha: 0.85, alpha_end: 0, ease: 'out' }); }
    return L;
  },
  heal: (t, s) => [
    { type: 'sound', sound: 'minecraft:block.amethyst_block.chime', pitch: 1.2 },
    { type: 'ring', texture: t('ring', 'ring'), size: 2 * s, size_end: 0.5, duration: 20, alpha_end: 0 },
    { type: 'pillar', texture: t('beam', 'beam', { frames: 4 }), frames: 4, loops: 3, size: 0.7 * s, height: 0, height_end: 3 * s, duration: 24, alpha_end: 0 },
    { type: 'billboard', texture: t('star', 'star', { points: 4 }), y: 2.2, size: 0.6 * s, size_end: 0, spin: 10, duration: 20 },
  ],
  lightning: (t, s) => [
    { type: 'sound', sound: 'minecraft:entity.lightning_bolt.impact', volume: 1 },
    { type: 'flash', color: '#e0f0ff', alpha: 0.7, duration: 4 }, { type: 'shake', size: 2, duration: 8 },
    { type: 'pillar', texture: t('bolt', 'lightning', { frames: 4, size: 32 }), frames: 4, loops: 3, size: 1.2 * s, height: 20 * s, planes: 2, duration: 10, alpha_end: 0 },
    { type: 'ring', texture: t('crack', 'crack'), size: 2 * s, duration: 30, alpha_end: 0 },
    { type: 'billboard', texture: t('glow', 'glow'), y: 0.5, size: 3 * s, duration: 8, alpha_end: 0 },
  ],
};
