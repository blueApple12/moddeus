#!/usr/bin/env node
// Moddeus fast-path content tool. One command = everything a block/item needs, live in-game.
//
//   node tools/mc.mjs block <id> [--name "Nice Name"] [--texture SRC] [--place-sound SRC] [--break-sound SRC]
//        [--sound stone|wood|metal|glass|wool|sand|snow|slime|amethyst|grass|gravel]
//        [--hardness 1.5] [--resistance 6] [--light 0-15] [--friction 0.6] [--speed 1] [--jump 1]
//        [--requires-tool] [--transparent] [--instabreak] [--no-drop] [--size 16|32|64]
//   all of block|item|armor|mob: [--tab combat|tools|food|building|colored|natural|functional|redstone|ingredients|spawn_eggs|"<Mod Name>"]
//        creative tab: a vanilla one for single small things, the mod's own named tab for a mod with several items
//   node tools/mc.mjs item <id> [--name ..] [--texture SRC] [--stack 64] [--durability N]
//        [--food nutrition,saturation[,always]] [--rarity common|uncommon|rare|epic] [--fire-resistant] [--handheld]
//        [--shape slab|stairs|wall|fence|fence_gate|door|trapdoor|button|pressure_plate|pane|pillar|carpet|glass]
//        [--mine pickaxe|axe|shovel|hoe]   (which tool mines it fast; pair with --requires-tool)
//   node tools/mc.mjs item <id> ... [--tool sword|pickaxe|axe|shovel|hoe --material wood|stone|copper|iron|gold|diamond|netherite]
//        [--damage N] [--attack-speed N] [--components '{"minecraft:enchantment_glint_override":true}']
//   node tools/mc.mjs armor <set> --material iron|gold|diamond|netherite|chainmail|copper [--pieces helmet,chestplate,leggings,boots]
//        -> <set>_helmet ... ; draw the worn look with: pixels armor <set> / pixels armor_legs <set> (64x32)
//   node tools/mc.mjs recipe <id> --result <item> [--count N] and one of:
//        --shaped "DDD, S , S " --key "D=minecraft:diamond,S=minecraft:stick"  |  --shapeless "minecraft:a,#minecraft:planks"
//        --smelt <ingredient> [--xp 0.7] [--time 200]  |  --stonecut <ingredient>
//   node tools/mc.mjs remove-recipe <id>
//   node tools/mc.mjs pixels <block|item|armor|armor_legs> <id> --palette "a=#7a1020,b=#c0304a" --rows "..aa..,.abba.,..."   ('.' = transparent)
//   node tools/mc.mjs particle <id> [--frames N] [--lifetime 20] [--gravity 0] [--size 1] [--size-end S] [--shrink]
//        [--glow true|false] [--fade true|false] [--spin 0.2] [--color "#fff"] [--color-end "#f0a"] [--wobble 0.02] [--swirl 0.05] [--collide]
//        draw frames first: pixels particle <id>_0 ... <id>_N-1 (or pixels particle <id> for 1 frame). Spawn: /particle moddeus:<id> ~ ~1 ~ 0.5 0.5 0.5 0.05 40
//   node tools/mc.mjs mob <id> --template zombie|skeleton|spider|creeper|pig|chicken|iron_golem|bee|blaze [--name ..]
//        [--health 20] [--speed 0.25] [--damage 3] [--armor 0] [--scale 1] [--ai hostile] [--particle moddeus:<id>] [--fire-immune]
//        draw: pixels entity <id> (shape guide printed) + pixels item <id>_spawn_egg. Spawns via egg or /summon moddeus:<id>
//   node tools/mc.mjs vfx <id> --preset smite|nova|slash|portal|beam|aura|explosion|heal|lightning --palette fire|ice|arcane|holy|poison|shadow|blood|lightning|nature|void|sun|"#000,#0f8,#fff"
//        [--scale 1.5] [--layers '<extra layers appended>']   -> generates its textures (<id>_ring, ...) + layers in one go (tools/vfxlib.mjs)
//   node tools/mc.mjs vfx-tex <name> --shape glow|orb|ring|shockwave|flare|star|spark|beam|slash|sigil|swirl|smoke|lightning|crack
//        --palette <p> [--size 32] [--frames 4] [--points 6] [--seed 1] [--thickness 0.08] [--contrast 1]   (procedural texture -> textures/vfx/<name>.png)
//   node tools/mc.mjs vfx <id> --layers '[{"type":"ring","texture":"shock","size":0.5,"size_end":6,"duration":14,"alpha_end":0}, ...]'
//        layer types: billboard ring pillar beam slash particles flash shake sound. Common keys: texture (pixels vfx <name>),
//        delay duration size size_end alpha alpha_end color color_end spin frames loops x y z blend(add|alpha).
//        pillar: height height_end planes | beam: length grow | slash: arc width tilt | particles: particle count rate speed radius pattern(burst|ring|up|inward)
//        flash: color alpha | shake: size(degrees) | sound: sound volume pitch.   Play: /vfx <id>  (or: mc game cmd "vfx <id>")
//        attach: item ... --vfx-use <id> --vfx-hit <id> [--vfx-cooldown 20]   |   mob ... --aura <id> [--aura-every 40]
//   node tools/mc.mjs block|item <id> --cubes "0 0 0 16 2 16; 6 2 6 10 14 10 pole ry=45" --tex "all=lamp_base,pole=lamp_pole"
//        3D model from cubes (0..16 grid, auto UVs, optional rx/ry/rz = +-22.5/45). Blocks get a matching hitbox. Guide: skill mc-content.
//        items: --wear held|hand|head (default from the id: glove/gauntlet -> hand, hat/helmet/mask -> head) positions worn things right. Animations: skill mc-content "Animations".
//   node tools/mc.mjs physics <id> [--preset crate|bouncy_ball|boulder|ice_cube|bomb|balloon|platform|falling_sand|floaty|glass_orb]
//        --texture <block texture: my_block | minecraft:block/stone> [--texture-top ..] [--any-spec-key value ...]
//        keys (see Physics.java): size width height glow spin translucent gravity drag friction bounce rest-speed buoyancy water-drag
//        mass push pushable hit-force hit-lift solid pickup impact-speed impact-sound impact-pitch impact-vfx impact-damage
//        impact-knockback explode explode-breaks-blocks break-speed max-bounces break-vfx health drop lifetime lands-as throw-speed
//        presets live in tools/library/physics/*.json (add new ones there). Spawn: /physics <id> [count]; throw from an item:
//        item <id> ... --throws <physics id> [--throw-speed 1.2] [--throw-cooldown 10] [--consume]
//   node tools/mc.mjs template <vanilla texture, e.g. item/diamond_sword | entity/equipment/humanoid/iron>
//        prints the SHAPE only ('#' = filled, '.' = empty) as a guide to draw your own texture with pixels. No colors copied.
//   node tools/mc.mjs texture <block|item> <id> SRC [--size 16]
//   node tools/mc.mjs sound <name> SRC            (registers moddeus:<name>)
//   node tools/mc.mjs remove <id|"display name"|JavaFeature> [...]   content: files + live unregister; Java: deletes Name*.java in content dirs, unhooks one-line calls, rebuilds, runs @OnUnload
//   node tools/mc.mjs remove-sound <name> [...]
//   node tools/mc.mjs list | apply
//   node tools/mc.mjs game context|cmd|blocks|shot|say|log|eval ...   (see and act in the live world; tools/game.mjs)
//   node tools/mc.mjs test [game cmd blocks eval shot bridge permission mode]   (self-test, each check <=10s; tools/test.mjs)
//
// SRC = local file, direct URL, or web page URL (og:image / first .mp3|.ogg|.wav link).
// Textures for new things are DRAWN with pixels (own palette + shading); recoloring vanilla art is deliberately not supported.
// Every command ends with "apply" (gradlew processResources) unless --no-apply: the running game
// picks it up within ~1s (registry thaw + resource reload). Needs ffmpeg + node 18+.
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { lock, unlock } from './lock.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const RES = path.join(ROOT, 'src/main/resources');
const A = path.join(RES, 'assets/moddeus');
const CONTENT = path.join(RES, 'moddeus/content');
const UA = { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130 Safari/537.36' };

const [cmd, ...rest] = process.argv.slice(2);
const pos = [], opt = {};
for (let i = 0; i < rest.length; i++) {
  const a = rest[i];
  if (a.startsWith('--')) {
    const k = a.slice(2);
    const v = rest[i + 1];
    if (v === undefined || v.startsWith('--')) opt[k] = true; else { opt[k] = v; i++; }
  } else pos.push(a);
}

const writeJson = (f, o) => { fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, JSON.stringify(o, null, 2) + '\n'); };
const readJson = (f, d) => { try { return JSON.parse(fs.readFileSync(f, 'utf8')); } catch { return d; } };
const title = id => id.split('_').map(w => w[0].toUpperCase() + w.slice(1)).join(' ');
const checkId = id => { if (!/^[a-z0-9_]+$/.test(id || '')) die('id must be [a-z0-9_]+, got: ' + id); return id; };
function die(m) { console.error('mc: ' + m); process.exit(1); }

const JAR = (() => {
  const d = path.join(ROOT, '.gradle/loom-cache/minecraftMaven/net/minecraft');
  for (const sub of fs.existsSync(d) ? fs.readdirSync(d) : []) if (sub.startsWith('minecraft-clientOnly'))
    for (const ver of fs.readdirSync(path.join(d, sub))) { const j = path.join(d, sub, ver, `${sub}-${ver}.jar`); if (fs.existsSync(j)) return j; }
  return null;
})();
const TAR = process.platform === 'win32' ? 'C:/Windows/System32/tar.exe' : 'tar'; // Git Bash tar reads "C:" as a remote host
function vanilla(rel, enc = 'utf8') {
  if (!JAR) die('vanilla client jar not found under .gradle/loom-cache');
  try { return execFileSync(TAR, ['-xOf', JAR, 'assets/minecraft/' + rel], { encoding: enc, maxBuffer: 1 << 26, stdio: ['ignore', 'pipe', 'ignore'] }); } catch { return null; }
}

async function fetchSrc(src, kind) {
  if (!/^https?:/.test(src)) { if (!fs.existsSync(src)) die('no such file: ' + src); return src; }
  let r = await fetch(src, { headers: UA });
  if (!r.ok) die(`download failed ${r.status}: ${src}`);
  if ((r.headers.get('content-type') || '').includes('html')) {
    const html = await r.text();
    const re = kind === 'image'
      ? /property="og:image"\s+content="([^"]+)"|content="([^"]+)"\s+property="og:image"/
      : /["'(]([^"'( ]+\.(?:mp3|ogg|wav))/;
    const m = html.match(re);
    if (!m) die('no ' + kind + ' found on page ' + src);
    const found = new URL(m[1] || m[2], src).href;
    console.log('  found ' + kind + ': ' + found);
    r = await fetch(found, { headers: UA });
    if (!r.ok) die('download failed ' + found);
  }
  const ext = (new URL(r.url).pathname.match(/\.[a-z0-9]+$/i) || ['.bin'])[0];
  const tmp = path.join(ROOT, '.tools/dl', 'mc-' + Date.now() + ext);
  fs.mkdirSync(path.dirname(tmp), { recursive: true });
  fs.writeFileSync(tmp, Buffer.from(await r.arrayBuffer()));
  return tmp;
}

const ff = args => execFileSync('ffmpeg', ['-y', '-loglevel', 'error', ...args], { stdio: 'inherit' });

async function texture(kind, id, src, size = 16, out = path.join(A, 'textures', kind, id + '.png'), keepShape = false) {
  const file = await fetchSrc(src, 'image');
  fs.mkdirSync(path.dirname(out), { recursive: true });
  // center-crop square, keep alpha, pixel-art friendly when downscaling a lot
  const fit = keepShape ? 'format=rgba' : `crop='min(iw,ih)':'min(iw,ih)',scale=${size}:${size}:flags=${size <= 32 ? 'area' : 'lanczos'},format=rgba`;
  ff(['-i', file, '-frames:v', '1', '-vf', fit, out]);
  console.log('  texture -> ' + path.relative(ROOT, out));
}

async function sound(name, src) {
  const file = await fetchSrc(src, 'audio');
  const out = path.join(A, 'sounds', name + '.ogg');
  fs.mkdirSync(path.dirname(out), { recursive: true });
  ff(['-i', file, '-ac', '1', '-ar', '44100', '-c:a', 'libvorbis', '-q:a', '6', out]); // mono: positional audio
  const sj = path.join(A, 'sounds.json');
  const s = readJson(sj, {});
  s[name] = { sounds: ['moddeus:' + name], subtitle: 'subtitles.moddeus.' + name };
  writeJson(sj, s);
  lang('subtitles.moddeus.' + name, title(name));
  console.log('  sound -> moddeus:' + name);
  return 'moddeus:' + name;
}

function lang(key, value) {
  const f = path.join(A, 'lang/en_us.json');
  const l = readJson(f, {});
  l[key] = value;
  writeJson(f, Object.fromEntries(Object.entries(l).sort()));
}

async function apply() {
  if (opt['no-apply']) return;
  const r = await (await import('./build.mjs')).resources(ROOT);
  if (!r.ok) die('resources failed:\n' + (r.errors || []).join('\n'));
  console.log('applied: game reloads within ~1s (watch chat / run/logs/latest.log)');
}

const SHAPES = { slab: 'oak_slab', stairs: 'oak_stairs', wall: 'cobblestone_wall', fence: 'oak_fence', fence_gate: 'oak_fence_gate', door: 'oak_door',
  trapdoor: 'oak_trapdoor', button: 'oak_button', pressure_plate: 'oak_pressure_plate', pane: 'glass_pane', pillar: 'oak_log', carpet: 'white_carpet', glass: 'glass' };
const SHAPE_TAGS = { slab: 'slabs', stairs: 'stairs', wall: 'walls', fence: 'fences', fence_gate: 'fence_gates', door: 'doors', trapdoor: 'trapdoors', button: 'buttons', pressure_plate: 'pressure_plates' };

function out(files, rel, obj) { writeJson(path.join(A, rel), obj); files.push('assets/moddeus/' + rel); }

/** Copies the vanilla blockstate + every model it uses for this shape, pointing all textures at ours. */
function shapeAssets(id, shape, files) {
  const tpl = SHAPES[shape], tex = 'moddeus:block/' + id, made = new Set();
  const walk = (o, f) => { if (Array.isArray(o)) o.forEach(x => walk(x, f)); else if (o && typeof o === 'object') for (const k in o) { if (k === 'model' && typeof o[k] === 'string') o[k] = f(o[k]); else walk(o[k], f); } };
  const model = ref => {
    const name = ref.replace(/^minecraft:/, '');
    if (!name.startsWith('block/')) { out(files, 'models/item/' + id + '.json', { parent: 'minecraft:item/generated', textures: { layer0: tex } }); return 'moddeus:item/' + id; }
    const base = name.slice(6), ours = base.includes(tpl) ? base.replace(tpl, id) : id + '_' + base;
    if (!made.has(ours)) {
      made.add(ours);
      const m = JSON.parse(vanilla('models/block/' + base + '.json'));
      for (const k in m.textures || {}) if (!String(m.textures[k]).startsWith('#')) m.textures[k] = tex;
      out(files, 'models/block/' + ours + '.json', m);
    }
    return 'moddeus:block/' + ours;
  };
  const bs = JSON.parse(vanilla('blockstates/' + tpl + '.json'));
  walk(bs, model);
  out(files, 'blockstates/' + id + '.json', bs);
  const it = JSON.parse(vanilla('items/' + tpl + '.json'));
  walk(it, model);
  out(files, 'items/' + id + '.json', it);
}

function tagEdit(tag, value, add) { // tag = "minecraft:walls" (block tags)
  const [ns, name] = tag.split(':');
  const f = path.join(RES, 'data', ns, 'tags/block', name + '.json');
  const t = readJson(f, { replace: false, values: [] });
  t.values = t.values.filter(v => v !== value);
  if (add) t.values.push(value);
  if (t.values.length) writeJson(f, t); else if (fs.existsSync(f)) fs.rmSync(f);
}

// ---- 3D models from cubes -----------------------------------------------------------------------------------
// --cubes "x1 y1 z1 x2 y2 z2 [texkey] [rx=22.5|ry=-45|rz=..] ; ..."  (0..16 pixel grid, 16 = one block)
// --tex "all=my_tex,top=my_top"  texkey -> textures/<kind>/<name>.png (draw each with mc pixels). UVs are automatic:
// each face samples the texture region under its own x/y/z bounds, so a 16x16 texture "wraps" every cube.
function parseCubes(s) {
  return String(s).split(/[;\n]/).map(x => x.trim()).filter(Boolean).map(line => {
    const p = line.split(/\s+/), n = p.slice(0, 6).map(Number);
    if (n.length < 6 || n.some(isNaN) || n.some(v => v < -16 || v > 32)) die('bad cube "' + line + '" (need x1 y1 z1 x2 y2 z2 in -16..32)');
    const cube = { box: n, tex: 'all' };
    for (const t of p.slice(6)) {
      const m = t.match(/^r([xyz])=(-?[\d.]+)$/);
      if (m) { if (![-45, -22.5, 0, 22.5, 45].includes(Number(m[2]))) die('rotation must be -45,-22.5,0,22.5,45'); cube.rot = { axis: m[1], angle: Number(m[2]) }; }
      else cube.tex = t;
    }
    return cube;
  });
}
function cubesModel(id, kind, cubes) {
  const keys = Object.fromEntries(String(opt.tex || '').split(',').filter(Boolean).map(kv => kv.split('=')));
  const textures = {};
  for (const c of cubes) textures[c.tex] = keys[c.tex] ? (keys[c.tex].includes(':') ? keys[c.tex] : 'moddeus:' + kind + '/' + keys[c.tex]) : 'moddeus:' + kind + '/' + id + (c.tex === 'all' ? '' : '_' + c.tex);
  textures.particle = Object.values(textures)[0];
  const elements = cubes.map(c => {
    const [x1, y1, z1, x2, y2, z2] = c.box;
    const e = { from: [Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2)], to: [Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2)], faces: {} };
    for (const f of ['north', 'south', 'east', 'west', 'up', 'down']) e.faces[f] = { texture: '#' + c.tex };
    if (c.rot) e.rotation = { axis: c.rot.axis, angle: c.rot.angle, origin: e.from.map((v, i) => (v + e.to[i]) / 2) };
    return e;
  });
  for (const [k, t] of Object.entries(textures)) if (k !== 'particle' && t.startsWith('moddeus:') && !fs.existsSync(path.join(A, 'textures', t.slice(12) + '.png')))
    console.warn('  (texture ' + t + ' missing: mc pixels ' + kind + ' ' + t.split('/').pop() + ')');
  return { textures, elements, ...(kind === 'block' ? { render_type: 'minecraft:cutout' } : {}), display: kind === 'item' ? WEAR[wearOf(id)] : WEAR.held };
}

// How a cube item sits on the player. held = gripped like a tool/object; hand = worn ON the hand (glove, gauntlet, claw):
// model it as an upright right hand (cuff at y=0, fingers up to y=16, palm facing north/-z); head = worn on the head
// (hat, helmet, mask, crown): model it around a 0..16 head cube, front = north. --wear overrides the guess from the id.
function wearOf(id) {
  if (opt.wear) { if (!WEAR[opt.wear]) die('--wear held|hand|head'); return opt.wear; }
  return /glove|gauntlet|fist|claw|bracer|knuckle|mitt|hand/.test(id) ? 'hand' : /hat|helmet|helm|mask|crown|cap|goggle|hood|halo|visor/.test(id) ? 'head' : 'held';
}
const WEAR = {
  held: { gui: { rotation: [30, 225, 0], scale: [0.625, 0.625, 0.625] }, ground: { translation: [0, 3, 0], scale: [0.25, 0.25, 0.25] },
    fixed: { scale: [0.5, 0.5, 0.5] }, thirdperson_righthand: { rotation: [75, 45, 0], translation: [0, 2.5, 0], scale: [0.375, 0.375, 0.375] },
    firstperson_righthand: { rotation: [0, 45, 0], scale: [0.4, 0.4, 0.4] } },
  hand: { gui: { rotation: [15, 200, 0], scale: [0.8, 0.8, 0.8] }, ground: { translation: [0, 3, 0], scale: [0.3, 0.3, 0.3] }, fixed: { scale: [0.6, 0.6, 0.6] },
    // fingers along the arm in third person; first person = a fist punching forward, back of the hand up, cuff toward the camera
    thirdperson_righthand: { rotation: [-90, 0, 0], translation: [0, 0, 2.5], scale: [0.5, 0.5, 0.5] },
    thirdperson_lefthand: { rotation: [-90, 0, 0], translation: [0, 0, 2.5], scale: [0.5, 0.5, 0.5] },
    firstperson_righthand: { rotation: [-80, 15, 0], translation: [-1, 2.5, 0], scale: [0.6, 0.6, 0.6] },
    firstperson_lefthand: { rotation: [-80, -15, 0], translation: [-1, 2.5, 0], scale: [0.6, 0.6, 0.6] } },
  head: { gui: { rotation: [30, 225, 0], scale: [0.625, 0.625, 0.625] }, ground: { translation: [0, 3, 0], scale: [0.25, 0.25, 0.25] }, fixed: { scale: [0.5, 0.5, 0.5] },
    head: { rotation: [0, 0, 0], translation: [0, 0, 0], scale: [1, 1, 1] },
    thirdperson_righthand: { rotation: [75, 45, 0], translation: [0, 2.5, 0], scale: [0.375, 0.375, 0.375] },
    firstperson_righthand: { rotation: [0, 45, 0], scale: [0.4, 0.4, 0.4] } },
};

async function block(id) {
  checkId(id);
  const c = { type: 'block' };
  if (opt.tab) c.tab = String(opt.tab); // creative tab: combat|tools|food|building|colored|natural|functional|redstone|ingredients|spawn_eggs, or a mod's own tab name
  const shape = opt.shape && opt.shape !== 'cube' ? opt.shape : null;
  if (shape && !SHAPES[shape]) die('unknown --shape ' + shape + ' (' + Object.keys(SHAPES).join(', ') + ')');
  if (shape) c.shape = shape;
  c.files = [];
  c.tags = [];
  if (shape && SHAPE_TAGS[shape]) c.tags.push('minecraft:' + SHAPE_TAGS[shape]);
  if (opt.mine) c.tags.push('minecraft:mineable/' + opt.mine);
  c.tags.forEach(t => tagEdit(t, 'moddeus:' + id, true));
  for (const k of ['hardness', 'resistance', 'light', 'friction', 'speed', 'jump']) if (opt[k] !== undefined) c[k] = Number(opt[k]);
  if (opt.sound) c.sound = opt.sound;
  if (opt['requires-tool']) c.requiresTool = true;
  if (opt.transparent) c.transparent = true;
  if (opt.instabreak) c.instabreak = true;
  if (opt['place-sound']) c.placeSound = await sound(id + '_place', opt['place-sound']);
  if (opt['break-sound']) c.breakSound = await sound(id + '_break', opt['break-sound']);
  if (opt.cubes) {
    const cubes = parseCubes(opt.cubes);
    c.shape = 'model';
    c.cubes = cubes.map(x => x.box);
    out(c.files, 'blockstates/' + id + '.json', { variants: { '': { model: 'moddeus:block/' + id } } });
    out(c.files, 'models/block/' + id + '.json', cubesModel(id, 'block', cubes));
    out(c.files, 'items/' + id + '.json', { model: { type: 'minecraft:model', model: 'moddeus:block/' + id } });
  } else if (shape) shapeAssets(id, shape, c.files);
  else {
    out(c.files, 'blockstates/' + id + '.json', { variants: { '': { model: 'moddeus:block/' + id } } });
    out(c.files, 'models/block/' + id + '.json', { parent: 'minecraft:block/cube_all', textures: { all: 'moddeus:block/' + id }, ...(opt.transparent ? { render_type: 'minecraft:cutout' } : {}) });
    out(c.files, 'items/' + id + '.json', { model: { type: 'minecraft:model', model: 'moddeus:block/' + id } });
  }
  writeJson(path.join(CONTENT, id + '.json'), c);
  const prop = (k, v) => [{ condition: 'minecraft:block_state_property', block: 'moddeus:' + id, properties: { [k]: v } }];
  if (!opt['no-drop']) writeJson(path.join(RES, 'data/moddeus/loot_table/blocks', id + '.json'), {
    type: 'minecraft:block',
    pools: [{ rolls: 1, conditions: [{ condition: 'minecraft:survives_explosion' }, ...(shape === 'door' ? prop('half', 'lower') : [])],
      entries: [{ type: 'minecraft:item', name: 'moddeus:' + id, ...(shape === 'slab' ? { functions: [{ function: 'minecraft:set_count', count: 2, conditions: prop('type', 'double') }] } : {}) }] }],
  });
  lang('block.moddeus.' + id, opt.name || title(id));
  if (opt.texture) await texture('block', id, opt.texture, Number(opt.size || 16));
  else if (!opt.cubes && !fs.existsSync(path.join(A, 'textures/block', id + '.png'))) console.warn('  (no texture yet: draw it with mc pixels block ' + id + ')');
  console.log('block moddeus:' + id + (shape ? ' (' + shape + ')' : '') + ' ready');
}

async function item(id) {
  checkId(id);
  const c = { type: 'item' };
  if (opt.tab) c.tab = String(opt.tab); // creative tab: combat|tools|food|building|colored|natural|functional|redstone|ingredients|spawn_eggs, or a mod's own tab name
  if (opt.stack) c.stack = Number(opt.stack);
  if (opt.durability) c.durability = Number(opt.durability);
  if (opt.rarity) c.rarity = opt.rarity;
  if (opt['fire-resistant']) c.fireResistant = true;
  if (opt.food) { const [n, s, al] = String(opt.food).split(','); c.food = { nutrition: Number(n), saturation: Number(s || 0.3), alwaysEat: al === 'always' || al === 'true' }; }
  if (opt.tool) {
    if (!/^(sword|pickaxe|axe|shovel|hoe)$/.test(opt.tool)) die('--tool sword|pickaxe|axe|shovel|hoe');
    c.tool = { type: opt.tool, material: opt.material || 'iron' };
    if (opt.damage !== undefined) c.tool.damage = Number(opt.damage);
    if (opt['attack-speed'] !== undefined) c.tool.speed = Number(opt['attack-speed']);
  }
  if (opt.components) { try { c.components = JSON.parse(opt.components); } catch (e) { die('--components is not JSON: ' + e.message); } }
  if (opt.cubes && wearOf(id) === 'head') c.components = { 'minecraft:equippable': { slot: 'head' }, ...(c.components || {}) }; // right-click to wear
  if (opt.throws) c.throws = { physics: opt.throws, speed: Number(opt['throw-speed'] || 1.2), ...(opt['throw-cooldown'] ? { cooldown: Number(opt['throw-cooldown']) } : {}), ...(opt.consume ? { consume: true } : {}) };
  if (opt['vfx-use'] || opt['vfx-hit']) c.vfx = { ...(opt['vfx-use'] ? { use: opt['vfx-use'] } : {}), ...(opt['vfx-hit'] ? { hit: opt['vfx-hit'] } : {}), ...(opt['vfx-cooldown'] ? { cooldown: Number(opt['vfx-cooldown']) } : {}) };
  await itemFiles(id, c, opt.tool ? 'item/' + VANILLA_MAT[c.tool.material] + '_' + opt.tool : null, !!(opt.handheld || opt.tool));
}

const VANILLA_MAT = { wood: 'wooden', wooden: 'wooden', stone: 'stone', copper: 'copper', iron: 'iron', gold: 'golden', golden: 'golden', diamond: 'diamond', netherite: 'netherite', chainmail: 'chainmail', leather: 'leather' };

/** Content JSON + item model/definition + lang + texture (--texture, else draw it with pixels; shapeHint = vanilla template to suggest). */
async function itemFiles(id, c, shapeHint, handheld, name = opt.name || title(id)) {
  c.files = [];
  out(c.files, 'models/item/' + id + '.json', opt.cubes ? cubesModel(id, 'item', parseCubes(opt.cubes)) : { parent: handheld ? 'minecraft:item/handheld' : 'minecraft:item/generated', textures: { layer0: 'moddeus:item/' + id } });
  out(c.files, 'items/' + id + '.json', { model: { type: 'minecraft:model', model: 'moddeus:item/' + id } });
  writeJson(path.join(CONTENT, id + '.json'), c);
  lang('item.moddeus.' + id, name);
  if (opt.texture) await texture('item', id, opt.texture, Number(opt.size || 16));
  else if (!opt.cubes && !fs.existsSync(path.join(A, 'textures/item', id + '.png')))
    console.warn('  (no texture yet: draw it with  mc pixels item ' + id + (shapeHint ? '   shape guide: mc template ' + shapeHint : '') + ')');
  console.log('item moddeus:' + id + ' ready');
}

async function armor(set) {
  checkId(set);
  const mat = opt.material || 'iron', vm = VANILLA_MAT[mat];
  if (!vm) die('--material ' + Object.keys(VANILLA_MAT).join('|'));
  const worn = { golden: 'gold' }[vm] || vm;
  const pieces = String(opt.pieces || 'helmet,chestplate,leggings,boots').split(',');
  const eq = [];
  out(eq, 'equipment/' + set + '.json', { layers: { humanoid: [{ texture: 'moddeus:' + set }], humanoid_leggings: [{ texture: 'moddeus:' + set }] } });
  for (const layer of ['humanoid', 'humanoid_leggings']) {
    const f = path.join(A, 'textures/entity/equipment', layer, set + '.png');
    if (!fs.existsSync(f)) console.warn('  (worn look missing: mc pixels ' + (layer === 'humanoid' ? 'armor' : 'armor_legs') + ' ' + set + '   shape guide: mc template entity/equipment/' + layer + '/' + worn + ')');
    eq.push('assets/moddeus/textures/entity/equipment/' + layer + '/' + set + '.png');
  }
  const base = opt.name || title(set);
  for (const p of pieces) {
    const id = set + '_' + p;
    const c = { type: 'item', armor: { type: p, material: mat, asset: 'moddeus:' + set } };
    if (opt.tab) c.tab = String(opt.tab); // creative tab: combat|tools|food|building|colored|natural|functional|redstone|ingredients|spawn_eggs, or a mod's own tab name
    await itemFiles(id, c, 'item/' + vm + '_' + p, false, base + ' ' + title(p));
    c.files.push(...eq);
    writeJson(path.join(CONTENT, id + '.json'), c);
  }
}

const MOB_TEX = { zombie: 'entity/zombie/zombie', skeleton: 'entity/skeleton/skeleton', spider: 'entity/spider/spider', creeper: 'entity/creeper/creeper',
  pig: 'entity/pig/pig_temperate', chicken: 'entity/chicken/chicken_temperate', iron_golem: 'entity/iron_golem/iron_golem', bee: 'entity/bee/bee', blaze: 'entity/blaze/blaze' };

async function mob(id) {
  checkId(id);
  const tpl = opt.template || 'zombie';
  if (!MOB_TEX[tpl]) die('--template ' + Object.keys(MOB_TEX).join('|'));
  const c = { type: 'mob', template: tpl };
  if (opt.tab) c.tab = String(opt.tab); // creative tab: combat|tools|food|building|colored|natural|functional|redstone|ingredients|spawn_eggs, or a mod's own tab name
  for (const k of ['health', 'speed', 'damage', 'armor', 'scale', 'knockback-resistance', 'follow-range', 'particle-count'])
    if (opt[k] !== undefined) c[k.replace(/-/g, '_')] = Number(opt[k]);
  if (opt.ai) c.ai = opt.ai;
  if (opt.particle) c.particle = opt.particle.includes(':') ? opt.particle : 'moddeus:' + opt.particle;
  if (opt['fire-immune']) c.fire_immune = true;
  if (opt.aura) { c.aura = opt.aura; if (opt['aura-every']) c.aura_every = Number(opt['aura-every']); }
  const egg = id + '_spawn_egg';
  c.files = [];
  out(c.files, 'models/item/' + egg + '.json', { parent: 'minecraft:item/generated', textures: { layer0: 'moddeus:item/' + egg } });
  out(c.files, 'items/' + egg + '.json', { model: { type: 'minecraft:model', model: 'moddeus:item/' + egg } });
  c.files.push('assets/moddeus/textures/entity/' + id + '.png', 'assets/moddeus/textures/item/' + egg + '.png');
  writeJson(path.join(CONTENT, id + '.json'), c);
  const name = opt.name || title(id);
  lang('entity.moddeus.' + id, name);
  lang('item.moddeus.' + egg, name + ' Spawn Egg');
  if (!fs.existsSync(path.join(A, 'textures/entity', id + '.png'))) console.warn('  (skin missing: mc pixels entity ' + id + '   shape guide: mc template ' + MOB_TEX[tpl] + ')');
  if (!fs.existsSync(path.join(A, 'textures/item', egg + '.png'))) console.warn('  (egg texture missing: mc pixels item ' + egg + '   shape guide: mc template item/' + tpl + '_spawn_egg)');
  console.log('mob moddeus:' + id + ' (' + tpl + ' body) ready; changing stats/template later needs a game restart');
}

function particle(id) {
  checkId(id);
  const n = Number(opt.frames || 1);
  const names = n > 1 ? [...Array(n).keys()].map(i => id + '_' + i) : [id];
  const missing = names.filter(f => !fs.existsSync(path.join(A, 'textures/particle', f + '.png')));
  if (missing.length) die('draw the frames first: ' + missing.map(f => 'mc pixels particle ' + f).join(' ; '));
  const c = { type: 'particle' };
  for (const k of ['lifetime', 'gravity', 'size', 'size-end', 'spin', 'wobble', 'swirl', 'friction']) if (opt[k] !== undefined) c[k.replace(/-/g, '_')] = Number(opt[k]);
  for (const k of ['color', 'color-end']) if (opt[k]) c[k.replace(/-/g, '_')] = opt[k];
  for (const k of ['glow', 'fade']) if (opt[k] !== undefined) c[k] = String(opt[k]) !== 'false';
  for (const k of ['shrink', 'collide']) if (opt[k]) c[k] = true;
  c.files = [];
  out(c.files, 'particles/' + id + '.json', { textures: names.map(f => 'moddeus:' + f) });
  c.files.push(...names.map(f => 'assets/moddeus/textures/particle/' + f + '.png'));
  writeJson(path.join(CONTENT, id + '.json'), c);
  console.log('particle moddeus:' + id + ' (' + n + ' frame' + (n > 1 ? 's' : '') + ') ready: /particle moddeus:' + id + ' ~ ~1 ~ 0.5 0.5 0.5 0.05 40');
}

function png(out, w, h, rgba) {
  const raw = path.join(ROOT, '.tools/dl', 'px-' + Date.now() + Math.random().toString(36).slice(2) + '.rgba');
  fs.mkdirSync(path.dirname(raw), { recursive: true });
  fs.mkdirSync(path.dirname(out), { recursive: true });
  fs.writeFileSync(raw, rgba);
  ff(['-f', 'rawvideo', '-pix_fmt', 'rgba', '-s', w + 'x' + h, '-i', raw, '-frames:v', '1', out]);
  fs.rmSync(raw);
}

async function vfxTex(name, shape, o) {
  const lib = await import('./vfxlib.mjs');
  const t = lib.texture(shape, o);
  png(path.join(A, 'textures/vfx', name + '.png'), t.w, t.h, t.rgba);
  return name;
}

async function vfx(id) {
  checkId(id);
  let layers;
  try { layers = JSON.parse(opt.layers || '[]'); } catch (e) { die('--layers is not JSON: ' + e.message); }
  if (opt.preset) {
    const lib = await import('./vfxlib.mjs');
    const p = lib.PRESETS[opt.preset];
    if (!p) die('no preset ' + opt.preset + ' (' + Object.keys(lib.PRESETS).join(' ') + ')');
    const want = [];
    const made = p((suffix, shape, o = {}) => { want.push([id + '_' + suffix, shape, o]); return id + '_' + suffix; }, Number(opt.scale || 1));
    for (const [n, shape, o] of want) await vfxTex(n, shape, { palette: opt.palette || 'fire', ...o });
    layers = [...made, ...layers];
    console.log('  generated ' + [...new Set(want.map(w => w[0]))].length + ' textures (' + (opt.palette || 'fire') + ')');
  }
  if (!Array.isArray(layers) || !layers.length) die('--layers needs a JSON array of layers (or use --preset)');
  const missing = layers.filter(l => l.texture && !l.texture.includes(':') && !fs.existsSync(path.join(A, 'textures/vfx', l.texture + '.png'))).map(l => l.texture);
  if (missing.length) die('draw these first: ' + [...new Set(missing)].map(t => 'mc pixels vfx ' + t).join(' ; '));
  const c = { type: 'vfx', layers }; // textures are shared between effects: never tracked for removal
  writeJson(path.join(CONTENT, id + '.json'), c);
  console.log('vfx moddeus:' + id + ' (' + layers.length + ' layers) ready: /vfx ' + id);
}

const SPEC_SKIP = new Set(['preset', 'no-apply', 'name']);
function physics(id) {
  checkId(id);
  const c = { type: 'physics' };
  if (opt.preset) {
    const f = path.join(ROOT, 'tools/library/physics', opt.preset + '.json');
    if (!fs.existsSync(f)) die('no preset ' + opt.preset + ' (have: ' + fs.readdirSync(path.dirname(f)).map(x => x.replace('.json', '')).join(', ') + ')');
    Object.assign(c, readJson(f, {}));
  }
  for (const [k, v] of Object.entries(opt)) {
    if (SPEC_SKIP.has(k)) continue;
    c[k.replace(/-/g, '_')] = v === true || v === 'true' ? true : v === 'false' ? false : (v !== '' && !isNaN(Number(v)) ? Number(v) : v);
  }
  writeJson(path.join(CONTENT, id + '.json'), c);
  console.log('physics moddeus:' + id + ' ready: /physics ' + id + '   spec: ' + JSON.stringify(c));
}

function recipe(id) {
  checkId(id);
  const result = { id: opt.result || 'moddeus:' + id, ...(opt.count ? { count: Number(opt.count) } : {}) };
  let r;
  if (opt.shaped) {
    const key = Object.fromEntries(String(opt.key || '').split(',').filter(Boolean).map(kv => { const [k, v] = kv.split('='); return [k.trim(), v.trim()]; }));
    r = { type: 'minecraft:crafting_shaped', category: 'misc', pattern: String(opt.shaped).split(','), key, result };
  } else if (opt.shapeless) r = { type: 'minecraft:crafting_shapeless', category: 'misc', ingredients: String(opt.shapeless).split(',').map(s => s.trim()), result };
  else if (opt.smelt) r = { type: 'minecraft:smelting', category: 'misc', ingredient: opt.smelt, result, experience: Number(opt.xp || 0.1), cookingtime: Number(opt.time || 200) };
  else if (opt.stonecut) r = { type: 'minecraft:stonecutting', ingredient: opt.stonecut, result };
  else die('recipe needs --shaped/--shapeless/--smelt/--stonecut');
  writeJson(path.join(RES, 'data/moddeus/recipe', id + '.json'), r);
  console.log('recipe moddeus:' + id + ' -> ' + result.id);
}

/** Prints a vanilla texture's SHAPE ('#' filled, '.' empty), never its colors: a guide for drawing with pixels. */
function template(rel) {
  if (!rel) die('usage: template <item/diamond_sword | block/oak_door_top | entity/equipment/humanoid/iron ...>');
  const png = vanilla('textures/' + rel.replace(/\.png$/, '') + '.png', 'buffer');
  if (!png) die('no vanilla texture ' + rel);
  const w = png.readUInt32BE(16), h = png.readUInt32BE(20);
  const raw = execFileSync('ffmpeg', ['-loglevel', 'error', '-i', 'pipe:0', '-frames:v', '1', '-f', 'rawvideo', '-pix_fmt', 'rgba', 'pipe:1'], { input: png, maxBuffer: 1 << 26 });
  console.log(w + 'x' + h + ' shape of ' + rel + ':');
  for (let y = 0; y < h; y++) { let s = ''; for (let x = 0; x < w; x++) s += raw[(y * w + x) * 4 + 3] > 0 ? '#' : '.'; console.log(s); }
}

function pixels(kind, id) {
  checkId(id);
  const OUT = { block: 'textures/block/', item: 'textures/item/', entity: 'textures/entity/', particle: 'textures/particle/', vfx: 'textures/vfx/', armor: 'textures/entity/equipment/humanoid/', armor_legs: 'textures/entity/equipment/humanoid_leggings/' };
  if (!OUT[kind]) die('usage: pixels <block|item|entity|particle|vfx|armor|armor_legs> <id> --palette "a=#hex,..." --rows "aa..,.aa."');
  const pal = Object.fromEntries(String(opt.palette || '').split(',').filter(Boolean).map(kv => { const [k, v] = kv.split('='); return [k.trim(), parseInt(v.trim().replace('#', ''), 16)]; }));
  const rows = String(opt.rows || '').split(/[,/]/).filter(r => r.length);
  const w = Math.max(...rows.map(r => r.length)), h = rows.length;
  if (!w || !h) die('--rows is empty');
  const buf = Buffer.alloc(w * h * 4);
  rows.forEach((r, y) => [...r.padEnd(w, '.')].forEach((ch, x) => {
    const v = pal[ch];
    if (v === undefined) { if (ch !== '.' && ch !== ' ') die('no palette entry for "' + ch + '"'); return; }
    buf.writeUInt32BE(((v << 8) | 0xff) >>> 0, (y * w + x) * 4);
  }));
  const raw = path.join(ROOT, '.tools/dl', 'px-' + Date.now() + '.rgba');
  fs.mkdirSync(path.dirname(raw), { recursive: true });
  fs.writeFileSync(raw, buf);
  const o = path.join(A, OUT[kind] + id + '.png');
  fs.mkdirSync(path.dirname(o), { recursive: true });
  ff(['-f', 'rawvideo', '-pix_fmt', 'rgba', '-s', w + 'x' + h, '-i', raw, '-frames:v', '1', o]);
  fs.rmSync(raw);
  console.log(`  texture ${w}x${h} -> ${path.relative(ROOT, o)}`);
}

function rm(rel) {
  const f = path.join(RES, rel);
  if (fs.existsSync(f)) { fs.rmSync(f); console.log('  - ' + rel); }
}

function removeSound(name) {
  rm('assets/moddeus/sounds/' + name + '.ogg');
  const sj = path.join(A, 'sounds.json');
  const s = readJson(sj, {});
  if (name in s) { delete s[name]; writeJson(sj, s); }
  removeLang(['subtitles.moddeus.' + name]);
}

function removeLang(keys) {
  const f = path.join(A, 'lang/en_us.json');
  const l = readJson(f, {});
  for (const k of keys) delete l[k];
  writeJson(f, l);
}

// ---- removal of anything: content ids, names, Java features ----
const JAVA_DIRS = ['src/main/java/dev/moddeus/content', 'src/client/java/dev/moddeus/client/content'].map(d => path.join(ROOT, d));
// The systems themselves (content types), never removed as a "feature".
const CORE = /^(PhysicsMining|AvatarFeed|TalkScreen|ClaudeOrb|Collisions|Mounts|Mobs|ModTab|ModelBlock|Particles|Physics|PhysicsBlock|PhysicsRenderer|Vfx|VfxClient|ClientContent|MobRenderers?|ParticleClient|CustomParticle)\.java$/;
const norm = s => s.toLowerCase().replace(/^moddeus:/, '').replace(/[^a-z0-9]/g, '');
function javaFeatures() {
  return JAVA_DIRS.flatMap(d => fs.existsSync(d) ? fs.readdirSync(d).filter(f => f.endsWith('.java') && !CORE.test(f)).map(f => path.join(d, f)) : []);
}
function resolveRemove(arg) {
  const n = norm(arg);
  const ids = fs.existsSync(CONTENT) ? fs.readdirSync(CONTENT).map(f => f.replace('.json', '')) : [];
  const exact = ids.find(i => norm(i) === n);
  if (exact) return { id: exact };
  const java = javaFeatures().filter(f => norm(path.basename(f, '.java')).startsWith(n) || n.startsWith(norm(path.basename(f, '.java'))));
  if (java.length) return { java: path.basename(java.sort((a, b) => a.length - b.length)[0], '.java') };
  const named = ids.filter(i => { const c = readJson(path.join(CONTENT, i + '.json'), {}); return norm(c.name || '') === n || norm(i).includes(n) || (c.name && norm(c.name).includes(n)); });
  if (named.length === 1) return { id: named[0] };
  const packs = modPacks(arg);
  if (packs.length) return { packs, name: arg };
  die(named.length ? `"${arg}" matches several: ${named.join(', ')}` : `nothing called "${arg}" (see: mc list)`);
}
/** A mod's pack folders: <Id>-DP in every world's datapacks/, <Id>-RP in run/resourcepacks/. */
function modPacks(id) {
  const own = f => f === id || f.startsWith(id + '-');
  const saves = path.join(ROOT, 'run/saves'), rps = path.join(ROOT, 'run/resourcepacks');
  const dps = fs.existsSync(saves) ? fs.readdirSync(saves).flatMap(w => { const d = path.join(saves, w, 'datapacks'); return fs.existsSync(d) ? fs.readdirSync(d).filter(own).map(f => path.join(d, f)) : []; }) : [];
  return [...dps, ...(fs.existsSync(rps) ? fs.readdirSync(rps).filter(own).map(f => path.join(rps, f)) : [])];
}
/** Switches a mod's packs off in the running game (uninstall fn + datapack disable, resource pack unselect), then deletes them. */
async function removePacks(id, packs) {
  const off = spawnSync(process.execPath, [path.join(ROOT, 'tools/mc.mjs'), 'game', 'run', 'dev.moddeus.client.Projects.packsOff("' + id + '");'], { cwd: ROOT, encoding: 'utf8' });
  if (off.status !== 0) console.log('  (game not reachable: packs deleted on disk only)');
  await new Promise(r => setTimeout(r, 1500)); // let /datapack disable + resource reload finish before the folders go
  for (const p of packs) { fs.rmSync(p, { recursive: true, force: true }); console.log('  deleted ' + path.relative(ROOT, p)); }
}
/** Deletes a Java feature's files (Name*.java in the content dirs), drops one-line calls/imports to it elsewhere, then the build + HotReloader runs its @OnUnload. */
function removeJava(name) {
  const files = JAVA_DIRS.flatMap(d => fs.existsSync(d) ? fs.readdirSync(d).filter(f => f.endsWith('.java') && f.startsWith(name) && !CORE.test(f)).map(f => path.join(d, f)) : []);
  const own = new Set(files.map(f => path.basename(f, '.java')));
  const word = new RegExp('\\b(' + [...own].join('|') + ')\\b');
  const callLine = new RegExp('^\\s*(import\\s+[\\w.]*\\b(' + [...own].join('|') + ')\\s*;|[\\w.]*\\b(' + [...own].join('|') + ')\\.\\w+\\([^;]*\\);|case\\s[^{}]*->[^{}]*\\b(' + [...own].join('|') + ')\\b[^{}]*;)\\s*(//.*)?$'); // also one-line switch cases (Quick actions)
  const edits = [], stuck = [];
  const walk = d => fs.readdirSync(d, { withFileTypes: true }).flatMap(e => e.isDirectory() ? walk(path.join(d, e.name)) : e.name.endsWith('.java') ? [path.join(d, e.name)] : []);
  for (const f of walk(path.join(ROOT, 'src'))) {
    if (files.includes(f)) continue;
    const lines = fs.readFileSync(f, 'utf8').split('\n');
    if (!lines.some(l => word.test(l))) continue;
    const kept = lines.filter(l => !callLine.test(l));
    kept.forEach((l, i) => { if (word.test(l)) stuck.push(path.relative(ROOT, f) + ': ' + l.trim()); });
    edits.push([f, kept.join('\n')]);
  }
  if (stuck.length) die('cannot remove ' + name + ' automatically, still used in:\n  ' + stuck.join('\n  '));
  for (const [f, t] of edits) { fs.writeFileSync(f, t); console.log('  unhooked ' + path.relative(ROOT, f)); }
  for (const f of files) { fs.rmSync(f); console.log('  deleted ' + path.relative(ROOT, f)); }
  console.log('removed ' + name + ' (its @OnUnload switches it off live; class unloads on restart)');
}
async function buildJava() {
  const b = await import('./build.mjs');
  if (!b.report(await b.build(ROOT))) process.exit(1);
}

function remove(id) {
  checkId(id);
  const c = readJson(path.join(CONTENT, id + '.json'), {});
  for (const f of c.files || []) rm(f.replace(/^assets\/moddeus\//, 'assets/moddeus/'));
  for (const t of c.tags || []) tagEdit(t, 'moddeus:' + id, false);
  rm('moddeus/content/' + id + '.json');
  for (const p of ['blockstates/' + id + '.json', 'models/block/' + id + '.json', 'models/item/' + id + '.json', 'items/' + id + '.json',
    'textures/block/' + id + '.png', 'textures/item/' + id + '.png']) rm('assets/moddeus/' + p);
  rm('data/moddeus/loot_table/blocks/' + id + '.json');
  removeLang(['block.moddeus.' + id, 'item.moddeus.' + id, 'entity.moddeus.' + id, 'item.moddeus.' + id + '_spawn_egg']);
  // recipes that make or use it would break the datapack reload
  const rdir = path.join(RES, 'data/moddeus/recipe');
  for (const f of fs.existsSync(rdir) ? fs.readdirSync(rdir) : [])
    if (new RegExp('"moddeus:' + id + '"').test(fs.readFileSync(path.join(rdir, f), 'utf8'))) rm('data/moddeus/recipe/' + f);
  for (const s of [id + '_place', id + '_break']) removeSound(s);
  console.log('removed moddeus:' + id + ' (fully unregistered on next launch)');
}

const t0 = Date.now();
switch (cmd) {
  case 'block': await block(pos[0]); await apply(); break;
  case 'item': await item(pos[0]); await apply(); break;
  case 'armor': await armor(pos[0]); await apply(); break;
  case 'mob': await mob(pos[0]); await apply(); break;
  case 'particle': particle(pos[0]); await apply(); break;
  case 'vfx': await vfx(pos[0]); await apply(); break;
  case 'vfx-tex': { const n = checkId(pos[0]) || pos[0]; await vfxTex(pos[0], opt.shape || 'glow', { palette: opt.palette || 'fire', size: Number(opt.size || 32), frames: Number(opt.frames || 1),
    ...(opt.points ? { points: Number(opt.points) } : {}), ...(opt.seed ? { seed: Number(opt.seed) } : {}), ...(opt.thickness ? { thickness: Number(opt.thickness) } : {}), ...(opt.contrast ? { contrast: Number(opt.contrast) } : {}) });
    console.log('  texture -> textures/vfx/' + pos[0] + '.png'); await apply(); break; }
  case 'physics': physics(pos[0]); await apply(); break;
  case 'recipe': recipe(pos[0]); await apply(); break;
  case 'remove-recipe': pos.forEach(r => rm('data/moddeus/recipe/' + checkId(r) + '.json')); await apply(); break;
  case 'pixels': pixels(pos[0], pos[1]); await apply(); break;
  case 'template': template(pos[0]); break;
  case 'texture': await texture(pos[0], checkId(pos[1]), pos[2], Number(opt.size || 16)); await apply(); break;
  case 'sound': await sound(checkId(pos[0]), pos[1]); await apply(); break;
  case 'remove': { // ids, display names ("knockback stick") or Java features (SurvivalFlight); one build at the end
    if (!pos.length) die('usage: remove <id|name|JavaFeature> [...]');
    let java = false;
    for (const a of pos) {
      const r = resolveRemove(a);
      if (r.java) { removeJava(r.java); java = true; const pk = modPacks(r.java); if (pk.length) await removePacks(r.java, pk); }
      else if (r.packs) await removePacks(r.name, r.packs);
      else remove(r.id);
    }
    if (java) await buildJava(); else await apply();
    break;
  }
  case 'remove-sound': pos.forEach(n => removeSound(checkId(n))); await apply(); break;
  case 'apply': await apply(); break;
  case 'game': await (await import('./game.mjs')).run(rest); break;
  case 'ask': { // voice helper → main Claude chat. --project <name> = that mod's chat, --new "<Mod Name>" = new mod project + chat, --smart = new Opus chat
    const b = JSON.parse(fs.readFileSync(path.join(ROOT, 'run/moddeus/bridge.json'), 'utf8'));
    const a = { type: 'forward' }, words = [];
    for (let i = 0; i < rest.length; i++) {
      if (rest[i] === '--smart') a.smart = true;
      else if (rest[i] === '--project') a.project = rest[++i];
      else if (rest[i] === '--new') a.newProject = rest[++i];
      else words.push(rest[i]);
    }
    a.text = words.join(' ');
    const r = await fetch(`http://127.0.0.1:${b.port}/cmd?t=${b.token}`, { method: 'POST', body: JSON.stringify(a) });
    console.log(r.ok ? 'sent to ' + (a.newProject ? 'new mod project ' + a.newProject : a.project ? 'mod ' + a.project : a.smart ? 'a new Opus chat' : 'the matching mod / main Claude chat') : 'bridge error ' + r.status); break;
  }
  case 'projects': for (const p of (await import('./projects.mjs')).projects(ROOT)) console.log((p.enabled ? 'on ' : 'off') + '  ' + p.id.padEnd(20) + p.name + (p.desc ? ' - ' + p.desc.slice(0, 80) : '')); break;
  case 'project': { // project name <chatKey> "<Name>"   (a New mod chat names itself)
    if (pos[0] !== 'name' || !pos[2]) die('usage: project name <projectId|chatKey> "<Name>"');
    const b = JSON.parse(fs.readFileSync(path.join(ROOT, 'run/moddeus/bridge.json'), 'utf8'));
    const r = await fetch(`http://127.0.0.1:${b.port}/cmd?t=${b.token}`, { method: 'POST', body: JSON.stringify({ type: 'project-name', id: pos[1], s: pos[1], name: pos.slice(2).join(' '), desc: opt.desc || '' }) });
    console.log(r.ok ? 'project named: classes go in ' + (await import('./projects.mjs')).slug(pos.slice(2).join(' ')) + '*.java' : 'bridge error ' + r.status); break;
  }
  case 'test': await (await import('./test.mjs')).run(rest); break;
  case 'build': await buildJava(); break; // compile Java + resources (Gradle in dev, inside the game in portable mode); the game hot-swaps it
  case 'lesson': case 'lessons': { // lesson "<rule>": a permanent rule every chat sees (toolbox.md LESSONS); lessons = list
    const tf = path.join(ROOT, 'tools', 'lessons.md'), H = '== YOUR LESSONS (from player feedback; apply to EVERY mod) ==';
    let t = fs.existsSync(tf) ? fs.readFileSync(tf, 'utf8') : '';
    if (!t.includes(H)) t = t.trimEnd() + (t.trim() ? '\n\n' : '') + H + '\n';
    const builtIn = fs.readFileSync(path.join(ROOT, 'tools', 'toolbox.md'), 'utf8');
    const text = pos.join(' ').replace(/\s+/g, ' ').trim();
    if (cmd === 'lessons' || !text) { console.log(builtIn.slice(builtIn.indexOf('== LESSONS')) + '\n' + t.slice(t.indexOf(H))); break; }
    if ((t + builtIn).toLowerCase().includes(text.toLowerCase())) { console.log('already a lesson'); break; }
    fs.writeFileSync(tf, t.trimEnd() + '\n- ' + text + '\n');
    console.log('lesson added (every chat sees it from the next prompt): ' + text);
    break;
  }
  case 'snap': case 'snaps': case 'undo': case 'redo': await (await import('./snap.mjs')).run(cmd, rest, ROOT); break;
  case 'list':
    for (const f of fs.existsSync(CONTENT) ? fs.readdirSync(CONTENT) : []) console.log(f.replace('.json', '').padEnd(24), fs.readFileSync(path.join(CONTENT, f), 'utf8').replace(/\s+/g, ' ').slice(0, 160));
    for (const f of javaFeatures()) console.log('java: ' + path.basename(f, '.java').padEnd(18), path.relative(ROOT, f).replace(/\\/g, '/'));
    break;
  default:
    console.log(fs.readFileSync(fileURLToPath(import.meta.url), 'utf8').split('\n').filter(l => l.startsWith('//')).map(l => l.slice(3)).join('\n'));
}
if (cmd) console.log(`done in ${((Date.now() - t0) / 1000).toFixed(1)}s`);
