// Weapon kinds for mc item --tool <kind> (Java: src/main/java/dev/moddeus/live/Weapons.java). Guide: .claude/skills/mc-content/WEAPONS.md
//   mc item <id> --tool bow|crossbow|trident|mace|spear|shield|arrow|throwable|gun|wand|staff [--model bow|crossbow|spear|hammer|shield|gun|wand|staff] [keys]
//   projectile keys (arrow, throwable, trident): --damage --gravity --homing 0..1 --fire <s> --effect "poison:100:0,slowness:60:1" --freeze <ticks>
//     --explode <r> [--explode-blocks] --splash <r> --linger heal|slow|freeze|damage|pull|push|fire --lightning --knockback --trail <vfx> --impact-vfx <vfx> --bounces <n>
//   throwable: --speed 1.5 --cooldown <ticks> --sound <id>     trident: --damage (melee) --throw-speed 2.5     spear/mace/trident: --material --damage --attack-speed
//   gun|wand|staff: --shot "speed=3 damage=6 homing=0.2 bounces=2 radius=3 item=moddeus:bullet" | --hitscan [--range 48 --pierce 1 --tracer <vfx>]
//     --ammo <item> --cooldown <ticks> --charge <ticks> [--min-charge 0.3] --auto [--rate 3] --count 6 --spread 0.15 --recoil 0.3 --sound <id> --animation bow|crossbow|trident|spear|none
//   any item: --special "sweep 4 6" | "<combat ability>" [--special-on use|sneak_use|hit] [--special-cooldown 40]
//   arrows get #minecraft:arrows (every bow/crossbow/dispenser fires them) + an auto-painted look (--arrow-colors "head=#..,shaft=#..,fletch=#.."; redraw: mc pixels entity projectiles/<id>)
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';

export const KINDS = ['bow', 'crossbow', 'trident', 'mace', 'spear', 'shield', 'arrow', 'throwable', 'gun', 'wand', 'staff'];
const TAGS = { // enchanting table / anvil / books read these item tags (vanilla 26.3 data/minecraft/tags/item/enchantable)
  bow: ['enchantable/bow', 'enchantable/durability'], crossbow: ['enchantable/crossbow', 'enchantable/durability'],
  trident: ['enchantable/trident', 'enchantable/durability'], mace: ['enchantable/mace', 'enchantable/weapon', 'enchantable/fire_aspect', 'enchantable/durability'],
  spear: ['spears'], shield: ['enchantable/durability'], arrow: ['arrows'], throwable: [], gun: [], wand: [], staff: [] };
const NUM = ['damage', 'gravity', 'homing', 'homing-range', 'fire', 'freeze', 'explode', 'explode-damage', 'splash', 'linger-radius', 'linger-seconds', 'knockback', 'bounces', 'speed', 'throw-speed',
  'inaccuracy', 'cooldown', 'charge', 'min-charge', 'rate', 'count', 'spread', 'recoil', 'range', 'pierce', 'attack-speed', 'trail-every', 'block-delay', 'angle', 'reduction'];
const STR = ['effect', 'linger', 'trail', 'impact-vfx', 'shot', 'ammo', 'sound', 'charge-sound', 'ready-sound', 'muzzle', 'tracer', 'animation', 'damage-type', 'material', 'block-sound', 'break-sound'];
const BOOL = ['lightning', 'explode-blocks', 'hitscan', 'auto'];
const KEY = { 'impact-vfx': 'vfx_hit', 'attack-speed': 'speed' }; // flag -> JSON key (else dashes -> underscores)

/** The content JSON "tool" object for --tool <kind>; also writes the kind's item tags (and the arrow look). */
export function tool(id, opt, ctx) {
  const k = opt.tool, t = { type: k };
  for (const f of NUM) if (opt[f] !== undefined && !(f === 'damage' && opt.damage === true)) t[KEY[f] || f.replace(/-/g, '_')] = Number(opt[f]);
  for (const f of STR) if (opt[f] !== undefined) t[KEY[f] || f.replace(/-/g, '_')] = String(opt[f]);
  for (const f of BOOL) if (opt[f]) t[f.replace(/-/g, '_')] = true;
  const tags = TAGS[k].map(x => 'minecraft:' + x);
  if (['gun', 'wand', 'staff'].includes(k) && opt.durability) tags.push('minecraft:enchantable/durability');
  for (const tg of tags) ctx.tagEdit(tg, (ctx.NSP || 'moddeus:') + id, true, 'item');
  ctx.c.itemTags = tags;
  if (k === 'arrow') paintArrow(id, opt, ctx);
  return t;
}

/** "special": attack on right-click / sneak+right-click / hit for any item. */
export function special(opt) {
  return { do: String(opt.special === true ? 'sweep' : opt.special), on: String(opt['special-on'] || 'use'), cooldown: Number(opt['special-cooldown'] || 40), ...(opt['special-vfx'] ? { vfx: String(opt['special-vfx']) } : {}) };
}

// ---- arrow look: drawn on the vanilla arrow SHAPE masks (entity 32x32 + item 16x16), own 3-shade ramps per part --------
const ENT = ['###..........................###', '.###.........##..##.........###.', '################################', '.###.........##..##.........###.', '###..........................###',
  '..#....#........................', '..#....#........................', '##########......................', '..#....#........................', '..#....#........................'];
const ICON = ['................', '................', '............###.', '..........#####.', '..........####..', '..........####..', '.........##.#...', '........##......',
  '.......##.......', '......##........', '.....##.........', '...###..........', '..####..........', '..###...........', '...#............', '................'];
const hex = h => { const n = parseInt(h.replace('#', ''), 16); return [n >> 16 & 255, n >> 8 & 255, n & 255]; };
const mix = (a, b, f) => a.map((v, i) => Math.round(v + (b[i] - v) * f));
const ramp = c => [mix(c, [40, 30, 80], 0.45), c, mix(c, [255, 245, 200], 0.4)]; // shadow cooler, light warmer
function paintArrow(id, opt, ctx) {
  const col = Object.fromEntries(String(opt['arrow-colors'] || '').split(',').filter(s => s.includes('=')).map(s => s.split('=').map(x => x.trim())));
  const R = { head: ramp(hex(col.head || '#d4dbe4')), shaft: ramp(hex(col.shaft || '#9a7448')), fletch: ramp(hex(col.fletch || '#f0ece4')) };
  const ent = path.join(ctx.A, 'textures/entity/projectiles', id + '.png');
  if (!fs.existsSync(ent) || opt.repaint) writePng(ent, 32, 32, (x, y) => {
    if (y >= ENT.length || ENT[y][x] !== '#') return null;
    if (y >= 5) return R[x < 5 ? 'head' : 'fletch'][1]; // end views
    const part = x <= 3 ? 'head' : x >= 28 ? 'fletch' : 'shaft';
    return R[part][y < 2 ? 2 : y > 2 ? 0 : 1];
  });
  const icon = path.join(ctx.A, 'textures/item', id + '.png');
  if (!opt.texture && !opt.model && !opt.cubes && (!fs.existsSync(icon) || opt.repaint)) writePng(icon, 16, 16, (x, y) => {
    if (ICON[y][x] !== '#') return null;
    const part = x >= 10 && y <= 6 ? 'head' : x <= 5 && y >= 11 ? 'fletch' : 'shaft';
    const edge = [[1, 0], [0, 1]].some(([dx, dy]) => (ICON[y + dy] || '')[x + dx] !== '#'); // bottom/right edge = shadow (light from top-left)
    return R[part][edge ? 0 : (x + y) % 3 === 0 ? 2 : 1];
  });
}
function writePng(file, w, h, px) {
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) { const c = px(x, y), o = y * (w * 4 + 1) + 1 + x * 4; if (c) { raw[o] = c[0]; raw[o + 1] = c[1]; raw[o + 2] = c[2]; raw[o + 3] = 255; } }
  const crc = b => { let c = ~0; for (const v of b) { c ^= v; for (let k = 0; k < 8; k++) c = c >>> 1 ^ (0xedb88320 & -(c & 1)); } return ~c >>> 0; };
  const chunk = (t, d) => { const l = Buffer.alloc(4); l.writeUInt32BE(d.length); const td = Buffer.concat([Buffer.from(t), d]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([l, td, c]); };
  const ih = Buffer.alloc(13); ih.writeUInt32BE(w, 0); ih.writeUInt32BE(h, 4); ih[8] = 8; ih[9] = 6;
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ih), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]));
}
