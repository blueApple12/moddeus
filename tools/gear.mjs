// Armor, tools, blocks and other gear for Moddeus mods: one command each, live, exportable. Dispatched from tools/mc.mjs.
// Java side: src/main/java/dev/moddeus/live/Gear.java (+ client/GearClient.java for worn 3D parts). Guide: .claude/skills/mc-content/GEAR.md
//   tools <set> --of <ingredient> [--tier wood|stone|copper|iron|gold|diamond|netherite] [--durability 1561] [--mining-speed 8] [--damage-bonus 3]
//        [--enchant 10] [--kinds sword,pickaxe,axe,shovel,hoe] [--extra paxel,hammer,excavator] [--handle minecraft:stick] [--no-recipes]
//        -> <set>_sword ... with tool tags (enchanting table + anvil), repair tag, standard recipes; hammer/excavator mine 3x3
//   (armor) mc armor <set> --material iron ... [--defense "3,8,6,3" (helmet,chest,legs,boots)] [--toughness 2] [--knockback 0.1] [--enchant 15]
//        [--durability 33 (x vanilla per-piece base)] [--of <ingredient> (repair + recipes)] [--equip-sound <id>]
//   worn <item> --part "head: x1 y1 z1 x2 y2 z2 [key] [glow] [rz=..] [spin=..] [bob=..]; ..." [--part "arms|body|legs|boots: ..."] --colors "horn=#hex,.."   custom 3D armor model
//   glider <id> [--durability 432] [--of minecraft:phantom_membrane]        elytra-like wings (draw: pixels wings <id>, template entity/equipment/wings/elytra)
//   totem <id> [--effects "regeneration:45:1,absorption:5:1,fire_resistance:40"] [--keep-effects] [--teleport 16] [--sound <id>]   death protection
//   book <id> --title ".." [--author ..] --page ".." [--page ..]...          readable written book / scroll / journal (right-click)
//   key <id> [--name ..]                                                     key item; lock a container: /data merge block X Y Z {lock:{items:"moddeus:<id>"}}
//   reskin <id> --base fishing_rod|shears|<vanilla item> [--name ..] [--durability N] [--of <ingredient>]   own look on a vanilla-behaviour item
//   light <id> --kind lantern|torch [--particle soul_fire_flame] [--light 15]   lantern (hangs) / torch (floor + wall)
//   container <id> [--sound wood] [--hardness 2.5]                           barrel-style block with the 27-slot GUI (textures <id>_top/_top_open/_side/_bottom)
//   storage <id> --of <item> [--beacon]                                      9x storage block + both recipes
//   all: [--tab "<Mod Name>"|combat|tools|...] [--name ..]   remove: mc remove <id>
import fs from 'node:fs';
import path from 'node:path';

export const HELP = fs.readFileSync(new URL(import.meta.url), 'utf8').split('\n').filter(l => l.startsWith('//')).slice(2).map(l => l.slice(3)).join('\n');
const CMDS = ['tools', 'worn', 'glider', 'totem', 'book', 'key', 'reskin', 'light', 'container', 'storage'];
let ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname.replace(/^\/(\w:)/, '$1')), '..'), RES = path.join(ROOT, 'src/main/resources'), A = path.join(RES, 'assets/moddeus'), CONTENT = path.join(RES, 'moddeus/content'), die = m => { console.error('mc: ' + m); process.exit(1); }, opt = {};

let NS = 'moddeus', NSP = 'moddeus:', stamp = () => {}; // NS: the mod's namespace (mc.mjs NS), stamp: mc.mjs nsContent
const writeJson = (f, o) => { fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, JSON.stringify(o, null, 2) + '\n'); };
const readJson = (f, d) => { try { return JSON.parse(fs.readFileSync(f, 'utf8')); } catch { return d; } };
const title = id => id.split('_').map(w => w[0].toUpperCase() + w.slice(1)).join(' ');
const ns = s => s.includes(':') || s.startsWith('#') ? s : (fs.existsSync(path.join(CONTENT, s + '.json')) ? cns(s) + ':' : 'minecraft:') + s;
const cns = s => readJson(path.join(CONTENT, s + '.json'), {}).namespace || 'moddeus'; // a content id's mod namespace (per-mod namespaces)
const one = v => Array.isArray(v) ? v[v.length - 1] : v;
const many = v => v === undefined ? [] : [].concat(v);

function parse(rest) {
  const pos = [], o = {};
  for (let i = 0; i < rest.length; i++) {
    const a = rest[i];
    if (a.startsWith('--')) { const k = a.slice(2), v = rest[i + 1]; const val = v === undefined || v.startsWith('--') ? true : (i++, v); o[k] = o[k] === undefined ? val : many(o[k]).concat(val); }
    else pos.push(a);
  }
  return { pos, opt: o };
}

function setup(ctx) { ({ ROOT, CONTENT, die } = ctx); NS = ctx.NS || 'moddeus'; NSP = NS + ':'; stamp = ctx.stamp || stamp; RES = path.join(ROOT, 'src/main/resources'); A = path.join(RES, 'assets/' + NS); }
function out(files, rel, obj) { writeJson(path.join(A, rel), obj); files.push('assets/' + NS + '/' + rel); }
function lang(key, value) { const f = path.join(A, 'lang/en_us.json'), l = readJson(f, {}); l[key] = value; writeJson(f, Object.fromEntries(Object.entries(l).sort())); }
function tag(t, value, kind = 'item') {
  const [n, name] = t.split(':'), f = path.join(RES, 'data', n, 'tags', kind, name + '.json'), j = readJson(f, { replace: false, values: [] });
  if (!j.values.includes(value)) j.values.push(value);
  writeJson(f, j);
}
function recipe(id, r) { writeJson(path.join(RES, 'data/' + NS + '/recipe', id + '.json'), r); }
const shaped = (pattern, key, result, count = 1) => ({ type: 'minecraft:crafting_shaped', category: 'equipment', pattern, key, result: { id: result, count } });
function content(id, c) { stamp(path.join(CONTENT, id + '.json'), c); writeJson(path.join(CONTENT, id + '.json'), c); }
function texHint(kind, id, tpl) { if (!fs.existsSync(path.join(A, 'textures', kind, id + '.png'))) console.warn('  (draw: mc pixels ' + kind + ' ' + id + (tpl ? '   shape guide: mc template ' + tpl : '') + ')'); }
function itemModel(id, files, parent = 'minecraft:item/generated', tex = NSP + 'item/' + id) {
  out(files, 'models/item/' + id + '.json', { parent, textures: { layer0: tex } });
  out(files, 'items/' + id + '.json', { model: { type: 'minecraft:model', model: NSP + 'item/' + id } });
}
function repairTag(set, ing) { const t = NSP + 'repairs/' + set; tag(t, ns(ing)); return t; }

export async function run(cmd, rest, ctx) {
  if (!CMDS.includes(cmd)) return false;
  setup(ctx);
  const p = parse(rest); opt = p.opt;
  const id = p.pos[0];
  if (!/^[a-z0-9_]+$/.test(id || '')) die('usage:\n' + HELP.split('\n').filter(l => l.trim().startsWith(cmd + ' ') || l.trim().startsWith('(' + cmd)).join('\n'));
  const tab = one(opt.tab);
  const base = { type: 'item', ...(tab ? { tab: String(tab) } : {}) };
  switch (cmd) {
    case 'tools': tools(id, base); break;
    case 'worn': worn(id); break;
    case 'glider': glider(id, base); break;
    case 'totem': totem(id, base); break;
    case 'book': book(id, base); break;
    case 'key': { const c = { ...base, stack: 1, files: [] }; itemModel(id, c.files); content(id, c); lang('item.' + NS + '.' + id, one(opt.name) || title(id)); texHint('item', id, 'item/trial_key');
      console.log('key ' + NSP + id + ('  lock: /data merge block X Y Z {lock:{items:"' + NSP) + id + '"}}  (or a named key: {lock:{components:{"minecraft:custom_name":"Gate Key"}}})'); break; }
    case 'reskin': reskin(id); break;
    case 'light': light(id, tab); break;
    case 'container': container(id, tab); break;
    case 'storage': storage(id, tab); break;
  }
  await ctx.apply();
  return true;
}

// ---- tools ---------------------------------------------------------------------------------------------------
const TOOL_TAG = { sword: 'swords', pickaxe: 'pickaxes', axe: 'axes', shovel: 'shovels', hoe: 'hoes', paxel: 'pickaxes', hammer: 'pickaxes', excavator: 'shovels' };
const PATTERN = { sword: [' I ', ' I ', ' S '], pickaxe: ['III', ' S ', ' S '], axe: ['II ', 'IS ', ' S '], shovel: [' I ', ' S ', ' S '], hoe: ['II ', ' S ', ' S '],
  hammer: ['IBI', 'IBI', ' S '], excavator: [' B ', 'BSB', ' S '], paxel: ['PAS', ' T ', ' T '] };
export const toolTags = (id, type, nsp = NSP) => { // also used by mc item --tool: enchanting table, anvil books and "is a pickaxe" checks all read these tags
  const tags = ['minecraft:' + TOOL_TAG[type], ...(type === 'paxel' ? ['minecraft:axes', 'minecraft:shovels'] : [])];
  tags.forEach(t => tag(t, nsp + id));
  return tags; // content "itemTags": mc remove takes them out again
};
function tools(set, base) {
  const of = one(opt.of); if (!of && !opt['no-recipes']) die('tools: --of <ingredient> (repair item + recipes), or --no-recipes');
  const kinds = String(one(opt.kinds) || 'sword,pickaxe,axe,shovel,hoe').split(',').concat(opt.extra ? String(one(opt.extra)).split(',') : []).map(s => s.trim()).filter(Boolean);
  const repair = of ? repairTag(set, of) : null, handle = ns(one(opt.handle) || 'stick');
  for (const k of ['paxel'].filter(k => kinds.includes(k))) ['#minecraft:mineable/pickaxe', '#minecraft:mineable/axe', '#minecraft:mineable/shovel'].forEach(v => tag('moddeus:mineable/paxel', v, 'block'));
  for (const kind of kinds) {
    if (!PATTERN[kind]) die('unknown tool kind ' + kind + ' (' + Object.keys(PATTERN).join(', ') + ')');
    const id = set + '_' + kind, t = { type: kind, tier: one(opt.tier) || 'iron' };
    for (const [f, k] of [['durability', 'durability'], ['mining-speed', 'mining_speed'], ['damage-bonus', 'damage_bonus'], ['enchant', 'enchant']]) if (opt[f] !== undefined) t[k] = Number(one(opt[f]));
    if (repair) t.repair = repair;
    const c = { ...base, tool: t, files: [] };
    itemModel(id, c.files, 'minecraft:item/handheld');
    c.itemTags = toolTags(id, kind);
    content(id, c);
    lang('item.' + NS + '.' + id, (one(opt.name) || title(set)) + ' ' + title(kind));
    if (kind === 'hammer' || kind === 'excavator') content(id + '_fx', { type: 'itemfx', item: NSP + id, mine: [{ type: 'area', amount: 1 }] });
    if (of && !opt['no-recipes']) {
      const I = ns(of), key = { I, S: handle };
      if (kind === 'hammer' || kind === 'excavator') key.B = I.startsWith('#') ? I : I; // ponytail: hammer = ingots, not a storage block; pass --no-recipes + mc recipe for a block-based one
      if (kind === 'paxel') Object.assign(key, { P: NSP + set + '_pickaxe', A: NSP + set + '_axe', S: NSP + set + '_shovel', T: handle });
      recipe(id, shaped(PATTERN[kind], Object.fromEntries(Object.entries(key).filter(([k]) => PATTERN[kind].join('').includes(k))), NSP + id));
    }
    texHint('item', id, 'item/' + ({ paxel: 'diamond_pickaxe', hammer: 'mace', excavator: 'diamond_shovel' }[kind] || 'diamond_' + kind));
    console.log('tool ' + NSP + id);
  }
}

// ---- armor extras (called from mc.mjs armor) ------------------------------------------------------------------
const ARMOR_TAG = { helmet: 'head_armor', chestplate: 'chest_armor', leggings: 'leg_armor', boots: 'foot_armor' };
const ARMOR_PATTERN = { helmet: ['III', 'I I'], chestplate: ['I I', 'III', 'III'], leggings: ['III', 'I I', 'I I'], boots: ['I I', 'I I'] };
export function armorPost(set, pieces, o, ctx) {
  setup(ctx); opt = o;
  const def = opt.defense ? String(opt.defense).split(',').map(Number) : null, order = ['helmet', 'chestplate', 'leggings', 'boots'];
  const repair = opt.of ? repairTag(set, String(opt.of)) : null;
  for (const p of pieces) {
    const id = set + '_' + p, f = path.join(CONTENT, id + '.json'), c = readJson(f, null);
    if (!c) continue;
    if (def && !isNaN(def[order.indexOf(p)])) c.armor.defense = def[order.indexOf(p)];
    for (const [k, j] of [['toughness', 'toughness'], ['knockback', 'knockback'], ['enchant', 'enchant'], ['durability', 'durability']]) if (opt[k] !== undefined) c.armor[j] = Number(opt[k]);
    if (opt['equip-sound']) c.armor.sound = ns(String(opt['equip-sound']));
    if (repair) c.armor.repair = repair;
    c.itemTags = ['minecraft:' + ARMOR_TAG[p], 'minecraft:trimmable_armor']; // -> enchantable/armor + head/chest/leg/foot (Protection, Thorns, Mending...), smithing trims
    c.itemTags.forEach(t => tag(t, NSP + id));
    writeJson(f, c);
    if (opt.of) recipe(id, shaped(ARMOR_PATTERN[p], { I: ns(String(opt.of)) }, NSP + id));
  }
}

// ---- worn 3D armor (the armor MODEL gets extra cubes as children of head/body/arms/legs: client/GearArmor.java) -----------------
// Grid per bone (author space, px): the bone's own box sits in the CENTER of 0..16: head 4..12 on every axis; body x4..12 y2..14 z6..10;
// arm/leg x6..10 y2..14 z6..10. Front = north (-z), up = +y. "arms"/"legs"/"boots" = right side as written + mirrored left side.
// Tokens after the 6 numbers: <color key> rx=|ry=|rz=<deg> glow spin=<deg/s> bob=<px> pivot=x,y,z (rotation/spin centre, grid px).
const BONES = { head: ['head'], body: ['body'], arms: ['right_arm', 'left_arm'], legs: ['right_leg', 'left_leg'], boots: ['right_leg', 'left_leg'], right_arm: ['right_arm'], left_arm: ['left_arm'], right_leg: ['right_leg'], left_leg: ['left_leg'] };
const CENTER = { head: [0, -4], body: [0, 6], right_arm: [-1, 4], left_arm: [1, 4], right_leg: [0, 6], left_leg: [0, 6] };
function parseWorn(s) {
  return String(s).split(/[;\n]/).map(x => x.trim()).filter(Boolean).map(line => {
    const p = line.split(/\s+/), n = p.slice(0, 6).map(Number);
    if (n.length < 6 || n.some(isNaN)) die('bad cube "' + line + '" (x1 y1 z1 x2 y2 z2 [key] [rx=..] [glow] [spin=..] [bob=..] [pivot=x,y,z])');
    const c = { box: n, key: 'all', rot: [0, 0, 0] };
    for (const t of p.slice(6)) {
      let m;
      if ((m = t.match(/^r([xyz])=(-?[\d.]+)$/))) c.rot['xyz'.indexOf(m[1])] = Number(m[2]);
      else if (t === 'glow') c.glow = true;
      else if ((m = t.match(/^(spin|bob)=(-?[\d.]+)$/))) c[m[1]] = Number(m[2]);
      else if ((m = t.match(/^pivot=(-?[\d.]+),(-?[\d.]+),(-?[\d.]+)$/))) c.pivot = [Number(m[1]), Number(m[2]), Number(m[3])];
      else c.key = t;
    }
    return c;
  });
}
/** author grid cube -> model-space child part (pivot, box relative to it, rotation) for one bone; mirror = left side of a pair */
function toPart(c, bone, mirror) {
  let [x1, y1, z1, x2, y2, z2] = c.box, rot = [...c.rot], piv = c.pivot && [...c.pivot];
  if (mirror) { [x1, x2] = [16 - x2, 16 - x1]; rot = [rot[0], -rot[1], -rot[2]]; if (piv) piv[0] = 16 - piv[0]; }
  const [cx, cy] = CENTER[bone], L = (gx, gy, gz) => [8 - gx + cx, 8 - gy + cy, gz - 8];
  const lo = L(Math.max(x1, x2), Math.max(y1, y2), Math.min(z1, z2)), size = [Math.abs(x2 - x1), Math.abs(y2 - y1), Math.abs(z2 - z1)];
  const pivot = piv ? L(...piv) : lo.map((v, i) => v + size[i] / 2);
  return { bone, pivot, from: lo.map((v, i) => +(v - pivot[i]).toFixed(3)), size, rot: [-rot[0], -rot[1], rot[2]], ...(c.glow ? { glow: true } : {}), ...(c.spin ? { spin: mirror ? -c.spin : c.spin } : {}), ...(c.bob ? { bob: c.bob } : {}), key: c.key };
}
function hex(s) { const v = parseInt(String(s).replace('#', ''), 16); return [(v >> 16) & 255, (v >> 8) & 255, v & 255]; }
function pngWrite(file, w, h, rgba) {
  const zlib = globalThis.__zlib;
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) rgba.copy(raw, y * (w * 4 + 1) + 1, y * w * 4, (y + 1) * w * 4);
  const chunk = (t, d) => { const b = Buffer.alloc(12 + d.length); b.writeUInt32BE(d.length, 0); b.write(t, 4, 'ascii'); d.copy(b, 8); b.writeUInt32BE(zlib.crc32(Buffer.concat([Buffer.from(t, 'ascii'), d])) >>> 0, 8 + d.length); return b; };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]));
}
async function worn(item) {
  globalThis.__zlib = await import('node:zlib');
  const f = path.join(CONTENT, item + '.json'), c = readJson(f, null);
  if (!c) die('worn: no content ' + NSP + item + ' (make it first: mc armor <set>)');
  if (!opt.part) die('worn: --part "head: x1 y1 z1 x2 y2 z2 [key] [glow] ..." (grid: head 4..12 all axes, body x4..12 y2..14 z6..10, arms/legs x6..10 y2..14 z6..10, front = north)');
  const parts = [];
  for (const s of many(opt.part)) {
    const i = s.indexOf(':'), name = s.slice(0, i).trim(), bones = BONES[name];
    if (!bones) die('worn: bone ' + name + ' (' + Object.keys(BONES).join('|') + ')');
    for (const cube of parseWorn(s.slice(i + 1))) bones.forEach((b, k) => parts.push(toPart(cube, b, k === 1)));
  }
  // box-UV sheet: each cube's unwrap (2(d+w) x (d+h)) shelf-packed, painted from --colors per key with per-face shading
  const colors = Object.fromEntries(String(one(opt.colors) || '').split(',').filter(Boolean).map(kv => kv.split('=').map(s => s.trim())));
  const keyColor = k => colors[k] ? hex(colors[k]) : (() => { let h = 0; for (const ch of k) h = (h * 31 + ch.charCodeAt(0)) >>> 0; return [60 + h % 140, 50 + (h >> 8) % 120, 90 + (h >> 16) % 140]; })();
  const dims = parts.map(p => p.size.map(v => Math.max(1, Math.ceil(v))));
  let tw = 64, u = 0, v = 0, rowH = 0;
  for (let i = 0; i < parts.length; i++) {
    const [W, H, D] = dims[i], rw = 2 * (D + W), rh = D + H;
    if (rw > tw) tw = 1 << Math.ceil(Math.log2(rw));
    if (u + rw > tw) { u = 0; v += rowH; rowH = 0; }
    parts[i].uv = [u, v]; u += rw; rowH = Math.max(rowH, rh);
  }
  const th = Math.max(32, 1 << Math.ceil(Math.log2(Math.max(1, v + rowH))));
  const img = Buffer.alloc(tw * th * 4);
  for (let i = 0; i < parts.length; i++) {
    const p = parts[i], [W, H, D] = dims[i], [u0, v0] = p.uv, col = keyColor(p.key);
    const faces = [[u0 + D, v0, W, D, 1.18], [u0 + D + W, v0, W, D, 0.62], [u0, v0 + D, D, H, 0.86], [u0 + D, v0 + D, W, H, 1], [u0 + D + W, v0 + D, D, H, 0.86], [u0 + 2 * D + W, v0 + D, W, H, 0.78]];
    for (const [fx, fy, fw, fh, shade] of faces) for (let y = 0; y < fh; y++) for (let x = 0; x < fw; x++) {
      const edge = !p.glow && (x === 0 || y === 0 || x === fw - 1 || y === fh - 1) && fw > 2 && fh > 2;
      const noise = 1 + (((x * 73856093) ^ (y * 19349663) ^ (i * 83492791)) % 7 - 3) / 50;
      const k = (p.glow ? 1.1 : shade * noise) * (edge ? 0.72 : 1) * (p.glow && (y === 0 || x === 0) ? 1.15 : 1);
      const o = ((fy + y) * tw + fx + x) * 4;
      col.forEach((cv, j) => img[o + j] = Math.max(0, Math.min(255, Math.round(cv * k))));
      img[o + 3] = 255;
    }
  }
  const ns = c.namespace || 'moddeus', texRel = 'textures/entity/equipment/parts/' + item + '.png'; // per-mod namespace (content "namespace")
  pngWrite(path.join(A, '..', ns, texRel), tw, th, img);
  c.worn = parts.map(({ key, ...p }) => ({ ...p, tex: ns + ':' + texRel, tw, th }));
  c.files = [...new Set([...(c.files || []).filter(x => !/_worn_/.test(x)), 'assets/' + ns + '/' + texRel])];
  writeJson(f, c);
  console.log('worn 3D model on ' + NSP + item + ': ' + parts.length + ' cubes (' + parts.filter(p => p.glow).length + ' glowing), sheet ' + tw + 'x' + th + ' -> assets/' + ns + '/' + texRel + '\n  (repaint the sheet if you like: same UV layout until the cubes change; check 3rd person: wear it, F5, sneak, swim)');
}

// ---- glider / totem / book ------------------------------------------------------------------------------------
function glider(id, base) {
  const c = { ...base, durability: Number(one(opt.durability) || 432), rarity: 'uncommon', files: [], components: {
    'minecraft:glider': {},
    'minecraft:equippable': { slot: 'chest', asset_id: NSP + id, equip_sound: 'minecraft:item.armor.equip_elytra', damage_on_hurt: false },
    'minecraft:repairable': { items: ns(one(opt.of) || 'phantom_membrane') },
    'minecraft:enchantable': { value: 1 } } };
  out(c.files, 'equipment/' + id + '.json', { layers: { wings: [{ texture: NSP + id, use_player_texture: true }] } });
  c.files.push('assets/' + NS + '/textures/entity/equipment/wings/' + id + '.png');
  for (const m of [id, id + '_broken']) out(c.files, 'models/item/' + m + '.json', { parent: 'minecraft:item/generated', textures: { layer0: NSP + 'item/' + m } });
  out(c.files, 'items/' + id + '.json', { model: { type: 'minecraft:condition', property: 'minecraft:broken', on_false: { type: 'minecraft:model', model: NSP + 'item/' + id }, on_true: { type: 'minecraft:model', model: NSP + 'item/' + id + '_broken' } } });
  c.itemTags = ['minecraft:enchantable/durability', 'minecraft:enchantable/equippable'];
  c.itemTags.forEach(t => tag(t, NSP + id));
  content(id, c);
  lang('item.' + NS + '.' + id, one(opt.name) || title(id));
  if (!fs.existsSync(path.join(A, 'textures/entity/equipment/wings', id + '.png'))) console.warn('  (draw the wings: mc pixels wings ' + id + ' (64x32)   shape guide: mc template entity/equipment/wings/elytra)');
  texHint('item', id, 'item/elytra'); texHint('item', id + '_broken', 'item/broken_elytra');
  console.log('glider ' + NSP + id + ' (wear in the chest slot, jump while falling; fireworks boost)');
}

function totem(id, base) {
  const effects = String(one(opt.effects) || 'regeneration:45:1,absorption:5:1,fire_resistance:40:0').split(',').filter(Boolean).map(s => {
    const [e, secs, amp] = s.trim().split(':'); return { id: ns(e), duration: Math.round(Number(secs || 30) * 20), amplifier: Number(amp || 0) };
  });
  const death = [...(opt['keep-effects'] ? [] : [{ type: 'minecraft:clear_all_effects' }]), { type: 'minecraft:apply_effects', effects }];
  if (opt.teleport) death.push({ type: 'minecraft:teleport_randomly', diameter: Number(one(opt.teleport)) });
  if (opt.sound) death.push({ type: 'minecraft:play_sound', sound: ns(String(one(opt.sound))) });
  const c = { ...base, stack: 1, rarity: 'uncommon', files: [], components: { 'minecraft:death_protection': { death_effects: death } } };
  itemModel(id, c.files);
  content(id, c);
  lang('item.' + NS + '.' + id, one(opt.name) || title(id));
  texHint('item', id, 'item/totem_of_undying');
  console.log('totem ' + NSP + id + ' (hold it in either hand; saves you from death, plays the totem animation with ITS texture)');
}

function book(id, base) {
  const pages = many(opt.page).map(s => ({ raw: String(s).replace(/\\n/g, '\n') }));
  if (!pages.length) die('book: --page "text" (repeat for more pages; \\n = new line)');
  const t = String(one(opt.title) || title(id)).slice(0, 32);
  const c = { ...base, gear: { kind: 'book' }, stack: 16, files: [], components: { 'minecraft:written_book_content': { title: { raw: t }, author: String(one(opt.author) || 'Unknown'), generation: 0, pages, resolved: true }, 'minecraft:enchantment_glint_override': !!opt.glint } };
  itemModel(id, c.files);
  content(id, c);
  lang('item.' + NS + '.' + id, one(opt.name) || title(id));
  texHint('item', id, 'item/written_book');
  console.log('book ' + NSP + id + ' (' + pages.length + ' pages; right-click to read)');
}

// ---- vanilla-behaviour items with our look ------------------------------------------------------------------------
function reskin(id) {
  const b = String(one(opt.base) || ''); if (!/^[a-z0-9_]+$/.test(b)) die('reskin: --base fishing_rod|shears|<vanilla item id>');
  const files = [];
  const flat = (m, parent = 'minecraft:item/handheld') => out(files, 'models/item/' + m + '.json', { parent, textures: { layer0: NSP + 'item/' + m } });
  if (b === 'fishing_rod') {
    flat(id, 'minecraft:item/handheld_rod'); flat(id + '_cast', 'minecraft:item/handheld_rod');
    out(files, 'items/' + id + '.json', { model: { type: 'minecraft:condition', property: 'minecraft:fishing_rod/cast', on_false: { type: 'minecraft:model', model: NSP + 'item/' + id }, on_true: { type: 'minecraft:model', model: NSP + 'item/' + id + '_cast' } } });
    texHint('item', id + '_cast', 'item/fishing_rod_cast');
  } else { flat(id, b === 'shears' ? 'minecraft:item/generated' : 'minecraft:item/handheld'); out(files, 'items/' + id + '.json', { model: { type: 'minecraft:model', model: NSP + 'item/' + id } }); }
  texHint('item', id, 'item/' + b);
  const name = one(opt.name) || title(id);
  const comps = { 'minecraft:item_model': NSP + id, 'minecraft:item_name': name, ...(opt.durability ? { 'minecraft:max_damage': Number(one(opt.durability)) } : {}) };
  const of = one(opt.of);
  const pat = { fishing_rod: [['  I', ' IS', 'I S'], { I: ns(of || 'stick'), S: 'minecraft:string' }], shears: [[' I', 'I '], { I: ns(of || 'iron_ingot') }] }[b];
  if (of && pat) recipe(id, { type: 'minecraft:crafting_shaped', category: 'equipment', pattern: pat[0], key: pat[1], result: { id: 'minecraft:' + b, components: comps } });
  content(id, { type: 'reskin', base: 'minecraft:' + b, components: comps, files, ...(one(opt.tab) ? { tab: String(one(opt.tab)) } : {}) });
  const give = 'give @s minecraft:' + b + '[' + Object.entries(comps).map(([k, v]) => k.replace('minecraft:', '') + '=' + JSON.stringify(v)).join(',') + ']';
  console.log('reskin ' + id + ' = minecraft:' + b + (' with item_model ' + NSP) + id + ' (all vanilla behaviour: casting line, sheep shearing...)\n/' + give);
}

// ---- blocks -------------------------------------------------------------------------------------------------------
function blockBase(id, shape, tab, extra = {}) {
  const c = { type: 'block', shape, files: [], tags: [], ...(tab ? { tab: String(tab) } : {}), ...extra };
  for (const k of ['hardness', 'resistance', 'light']) if (opt[k] !== undefined) c[k] = Number(one(opt[k]));
  if (opt.sound) c.sound = String(one(opt.sound));
  return c;
}
function loot(id, extraFns) { writeJson(path.join(RES, 'data/' + NS + '/loot_table/blocks', id + '.json'), { type: 'minecraft:block', pools: [{ rolls: 1, conditions: [{ condition: 'minecraft:survives_explosion' }], entries: [{ type: 'minecraft:item', name: NSP + id, ...(extraFns ? { functions: extraFns } : {}) }] }] }); }
function light(id, tab) {
  const kind = String(one(opt.kind) || 'lantern');
  if (!/^(lantern|torch)$/.test(kind)) die('light: --kind lantern|torch');
  const c = blockBase(id, kind, tab); c.hardness ??= kind === 'lantern' ? 3.5 : 0; c.light ??= kind === 'lantern' ? 15 : 14; c.sound ??= kind === 'lantern' ? 'metal' : 'wood';
  if (opt.particle) c.particle = String(one(opt.particle));
  const tex = NSP + 'block/' + id;
  if (kind === 'lantern') {
    out(c.files, 'models/block/' + id + '.json', { parent: 'minecraft:block/template_lantern', textures: { lantern: tex } });
    out(c.files, 'models/block/' + id + '_hanging.json', { parent: 'minecraft:block/template_hanging_lantern', textures: { lantern: tex } });
    out(c.files, 'blockstates/' + id + '.json', { variants: { 'hanging=false': { model: NSP + 'block/' + id }, 'hanging=true': { model: NSP + 'block/' + id + '_hanging' } } });
    itemModel(id, c.files);
    c.tags.push('minecraft:mineable/pickaxe');
    texHint('block', id, 'block/lantern'); texHint('item', id, 'item/lantern');
  } else {
    out(c.files, 'models/block/' + id + '.json', { parent: 'minecraft:block/template_torch', textures: { torch: tex } });
    out(c.files, 'models/block/' + id + '_wall.json', { parent: 'minecraft:block/template_torch_wall', textures: { torch: tex } });
    out(c.files, 'blockstates/' + id + '.json', { variants: { '': { model: NSP + 'block/' + id } } });
    content(id + '_wall', { type: 'gear_part', of: NSP + id }); // keeps the wall block registered (remove both: mc remove <id> <id>_wall)
    out(c.files, 'blockstates/' + id + '_wall.json', { variants: Object.fromEntries([['east', 0], ['north', 270], ['south', 90], ['west', 180]].map(([f, y]) => ['facing=' + f, { model: NSP + 'block/' + id + '_wall', ...(y ? { y } : {}) }])) });
    itemModel(id, c.files, 'minecraft:item/generated', tex);
    texHint('block', id, 'block/torch');
  }
  c.tags.forEach(t => tag(t, NSP + id, 'block'));
  content(id, c); loot(id);
  lang('block.' + NS + '.' + id, one(opt.name) || title(id));
  console.log(kind + (' ' + NSP) + id + ' (light ' + c.light + ')');
}
function container(id, tab) {
  const c = blockBase(id, 'barrel', tab); c.hardness ??= 2.5; c.sound ??= 'wood';
  const t = s => NSP + 'block/' + id + s;
  out(c.files, 'models/block/' + id + '.json', { parent: 'minecraft:block/cube_bottom_top', textures: { top: t('_top'), bottom: t('_bottom'), side: t('_side') } });
  out(c.files, 'models/block/' + id + '_open.json', { parent: 'minecraft:block/cube_bottom_top', textures: { top: t('_top_open'), bottom: t('_bottom'), side: t('_side') } });
  const rot = { up: {}, down: { x: 180 }, north: { x: 90 }, south: { x: 90, y: 180 }, east: { x: 90, y: 90 }, west: { x: 90, y: 270 } };
  out(c.files, 'blockstates/' + id + '.json', { variants: Object.fromEntries(Object.entries(rot).flatMap(([f, r]) => [false, true].map(o => ['facing=' + f + ',open=' + o, { model: NSP + 'block/' + id + (o ? '_open' : ''), ...r }]))) });
  out(c.files, 'items/' + id + '.json', { model: { type: 'minecraft:model', model: NSP + 'block/' + id } });
  c.tags.push('minecraft:mineable/axe'); c.tags.forEach(x => tag(x, NSP + id, 'block'));
  content(id, c); loot(id, [{ function: 'minecraft:copy_components', source: 'block_entity', include: ['minecraft:custom_name'] }]);
  lang('block.' + NS + '.' + id, one(opt.name) || title(id));
  for (const s of ['_top', '_top_open', '_side', '_bottom']) texHint('block', id + s, 'block/barrel' + s);
  console.log('container ' + NSP + id + ' (27 slots, opens like a barrel; rename it in an anvil for a custom GUI title)');
}
function storage(id, tab) {
  const of = one(opt.of); if (!of) die('storage: --of <item> (9 of it = 1 block)');
  const c = blockBase(id, 'cube', tab); c.hardness ??= 5; c.resistance ??= 6; c.sound ??= 'metal'; c.requiresTool = true;
  out(c.files, 'blockstates/' + id + '.json', { variants: { '': { model: NSP + 'block/' + id } } });
  out(c.files, 'models/block/' + id + '.json', { parent: 'minecraft:block/cube_all', textures: { all: NSP + 'block/' + id } });
  out(c.files, 'items/' + id + '.json', { model: { type: 'minecraft:model', model: NSP + 'block/' + id } });
  c.tags.push('minecraft:mineable/pickaxe', 'minecraft:needs_iron_tool', ...(opt.beacon ? ['minecraft:beacon_base_blocks'] : []));
  c.tags.forEach(x => tag(x, NSP + id, 'block'));
  content(id, c); loot(id);
  const I = ns(String(of));
  recipe(id, { type: 'minecraft:crafting_shaped', category: 'building', pattern: ['III', 'III', 'III'], key: { I }, result: { id: NSP + id, count: 1 } });
  recipe(id + '_unpack', { type: 'minecraft:crafting_shapeless', category: 'misc', ingredients: [NSP + id], result: { id: I, count: 9 } });
  lang('block.' + NS + '.' + id, one(opt.name) || title(id));
  texHint('block', id, 'block/diamond_block');
  console.log('storage block ' + NSP + id + ' (9 ' + I + ')');
}
