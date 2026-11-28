// World feel + presentation for Moddeus mods: one command each, live, exportable. Dispatched from tools/mc.mjs.
// Java side: src/main/java/dev/moddeus/live/WorldFeel.java (+ client/WorldFeelClient.java). Guide: .claude/skills/mc-content/WORLD.md
//   ambience <id> --biomes "plains,#minecraft:is_forest"|all  + any of:
//        sky/fog/light: --sky "#hex" --fog "#hex" --fog-start 0 --fog-end 64 --water-fog "#hex" --water-fog-end 24 --clouds "#AARRGGBB" --cloud-height 140
//          --sunset "#AARRGGBB" --stars 0.8 --sky-light "#hex" --sky-light-factor 0.5 --block-light "#hex" --ambient-light "#hex" --night-vision "#hex"
//        colors: --water "#hex" --grass "#hex" --foliage "#hex"        sound: --music SRC|id [--music-delay 20-600] [--music-volume 0.8]
//          --loop SRC|id  --mood SRC|id [--mood-delay 6000]  --additions SRC|id[@0.011]        particles: --particles "moddeus:ember@0.01,minecraft:white_ash@0.1"
//        gameplay: --monsters-burn false --fast-lava true ... any attribute: --attr "minecraft:gameplay/bees_stay_in_hive=true" | --attributes '{vanilla json}'
//        SRC = file/URL (imported as moddeus:<id>_music|_loop|_mood|_add). Biome borders blend like vanilla. Off: mc remove <id>_ambience
//   logic <id> --mode and|or|xor|nand|nor|xnor|not|latch|toggle|pulse|delay|repeater|random|clock      gates: input back (+ left/right), output front
//        | player|mob|item|light|daylight|night|rain|time|gamerule|score (sensors: power all sides) | output (runs commands on a signal)
//        [--ticks 20 (pulse/delay/clock)] [--on 2 (clock on-ticks)] [--radius 6 --entity zombie --analog --per 3 --every 10] [--rule <gamerule> | --objective <obj>]
//        [--cmd "say on;; playsound ..." --cmd-off ".." --cmd-tick ".." --every 20 --sound <id> --vfx <id>] [--name ..] [--color "#ff4a3a"]
//   banner <id> --texture SRC [--name "Sun Sigil"] [--no-item (loom without an item)]   pattern + loom item <id>_banner_pattern + shield texture
//   gamerule <id> [--int] [--default true|5] [--min 0] [--category misc|player|mobs|spawning|drops|updates|chat]   /gamerule moddeus:<id> ..; Java WorldFeel.rule/ruleOn
//   command <name> --run "line;; line $(arg)" [--args "n:int,who:player,msg:text"] [--op 0|2|4] [--optional-args]   types: word string text int number bool player players entity
//        ONLY when the player explicitly asks for a slash command. Abilities belong on items, keys and GUIs (Java), not commands.
//   ptex <id> --shape spark|glow|orb|ring|star|flare|smoke|swirl|... [--palette fire] [--frames 4] [--size 8]   particle frames for mc particle (then: mc particle <id> --frames 4)
//   toasts: Java only, WorldFeel.toast(player, "minecraft:item", "Title", "text", 0xFFE0B040)
//   all: [--tab "<Mod Name>"]   remove: mc remove <id> (ambience: <id>_ambience, command: <name>_command, gamerule: <id>_gamerule)
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { execFileSync, spawnSync } from 'node:child_process';

export const HELP = fs.readFileSync(new URL(import.meta.url), 'utf8').split('\n').filter(l => l.startsWith('//')).slice(2).map(l => l.slice(3)).join('\n');
const CMDS = ['ambience', 'logic', 'banner', 'gamerule', 'command', 'ptex'];
let ROOT, RES, A, CONTENT, die, opt, apply, NS = 'moddeus', NSP = 'moddeus:', stamp = () => {}; // NS: the mod's namespace (mc.mjs NS), stamp: mc.mjs nsContent

export async function run(cmd, rest, ctx) {
  if (!CMDS.includes(cmd)) return false;
  ({ ROOT, CONTENT, die, apply } = ctx); NS = ctx.NS || 'moddeus'; NSP = NS + ':'; stamp = ctx.stamp || stamp; RES = path.join(ROOT, 'src/main/resources'); A = path.join(RES, 'assets/' + NS);
  const p = parse(rest); opt = p.opt;
  const id = p.pos[0];
  if (!/^[a-z0-9_]+$/.test(id || '')) die('usage: ' + cmd + ' <id> ...  (id: a-z 0-9 _)\n' + HELP.split('\n').filter(l => l.trim().startsWith(cmd + ' ') || l.startsWith('     ')).join('\n'));
  const tab = one(opt.tab);
  let reload = false;
  switch (cmd) {
    case 'ambience': await ambience(id, tab); break;
    case 'logic': logic(id, tab); break;
    case 'banner': await banner(id, tab); reload = true; break;
    case 'gamerule': reload = true; spec(id + '_gamerule', { type: 'gamerule', rule: NS === 'moddeus' ? id : NSP + id, kind: opt.int ? 'int' : 'bool', ...(opt.default !== undefined ? { default: opt.int ? Number(one(opt.default)) : String(one(opt.default)) !== 'false' } : {}), ...(opt.min !== undefined ? { min: Number(one(opt.min)) } : {}), category: one(opt.category) || 'misc' }, tab);
      console.log('  /gamerule ' + NSP + id + ' <value>; datapacks: execute if function / Java WorldFeel.ruleOn(server, "' + id + '")'); break;
    case 'command': {
      if (!opt.run) die('command needs --run "line;; line"');
      const args = one(opt.args) ? String(one(opt.args)).split(',').map(s => s.trim()).filter(Boolean) : [];
      spec(id + '_command', { type: 'command', name: id, args, run: [].concat(opt.run).flatMap(r => String(r).split(';;')).map(s => s.trim()).filter(Boolean), op: Number(one(opt.op) || 0), ...(opt['optional-args'] ? { optional_args: true } : {}) }, tab);
      console.log('  /' + id + (args.length ? ' ' + args.map(a => '<' + a.split(':')[0] + '>').join(' ') : '')); break;
    }
    case 'ptex': await ptex(id); break;
  }
  await apply();
  if (reload) game('cmd', 'reload');
  return true;
}

// ---- ambience ----------------------------------------------------------------------------------------------
const VIS = { sky: 'visual/sky_color', fog: 'visual/fog_color', 'water-fog': 'visual/water_fog_color', clouds: 'visual/cloud_color', sunset: 'visual/sunrise_sunset_color',
  'sky-light': 'visual/sky_light_color', 'block-light': 'visual/block_light_tint', 'ambient-light': 'visual/ambient_light_color', 'night-vision': 'visual/night_vision_color' };
const NUM = { 'fog-start': 'visual/fog_start_distance', 'fog-end': 'visual/fog_end_distance', 'water-fog-end': 'visual/water_fog_end_distance', 'cloud-height': 'visual/cloud_height',
  stars: 'visual/star_brightness', 'sky-light-factor': 'visual/sky_light_factor', 'music-volume': 'audio/music_volume' };
const BOOL = { 'monsters-burn': 'gameplay/monsters_burn', 'fast-lava': 'gameplay/fast_lava', 'water-evaporates': 'gameplay/water_evaporates', 'can-start-raid': 'gameplay/can_start_raid',
  'respawn-anchor-works': 'gameplay/respawn_anchor_works', 'bees-stay-in-hive': 'gameplay/bees_stay_in_hive', 'piglins-zombify': 'gameplay/piglins_zombify', 'creaking-active': 'gameplay/creaking_active' };

async function ambience(id, tab) {
  const at = {}, k = s => 'minecraft:' + s;
  for (const [f, a] of Object.entries(VIS)) if (opt[f]) at[k(a)] = color(one(opt[f]));
  for (const [f, a] of Object.entries(NUM)) if (opt[f] !== undefined) at[k(a)] = Number(one(opt[f]));
  for (const [f, a] of Object.entries(BOOL)) if (opt[f] !== undefined) at[k(a)] = String(one(opt[f])) !== 'false';
  if (opt.music) {
    const [lo, hi] = String(one(opt['music-delay']) || '20-600').split('-').map(Number);
    const m = { sound: await snd(id + '_music', one(opt.music), true), min_delay: lo, max_delay: hi || lo, replace_current_music: true };
    at[k('audio/background_music')] = { default: m, creative: m };
  }
  if (opt.loop || opt.mood || opt.additions) {
    const s = { mood: { sound: opt.mood ? await snd(id + '_mood', one(opt.mood)) : 'minecraft:ambient.cave', tick_delay: Number(one(opt['mood-delay']) || 6000), block_search_extent: 8, offset: 2.0 } };
    if (opt.loop) s.loop = await snd(id + '_loop', one(opt.loop), true);
    if (opt.additions) { const [src, ch] = String(one(opt.additions)).split('@'); s.additions = { sound: await snd(id + '_add', src), tick_chance: Number(ch || 0.0111) }; }
    at[k('audio/ambient_sounds')] = s;
  }
  if (opt.particles) at[k('visual/ambient_particles')] = { argument: String(one(opt.particles)).split(',').map(s => { const [p, pr] = s.trim().split('@'); return { particle: { type: p.includes(':') ? p : 'minecraft:' + p }, probability: Number(pr || 0.01) }; }), modifier: 'append' };
  for (const a of [].concat(opt.attr || [])) { const i = String(a).indexOf('='); if (i < 0) die('--attr key=value'); const key = String(a).slice(0, i), v = String(a).slice(i + 1); at[key.includes(':') ? key : k(key)] = (() => { try { return JSON.parse(v); } catch { return v; } })(); }
  if (opt.attributes) Object.assign(at, JSON.parse(one(opt.attributes)));
  const fx = {};
  for (const [f, key] of [['water', 'water_color'], ['grass', 'grass_color'], ['foliage', 'foliage_color'], ['dry-foliage', 'dry_foliage_color']]) if (opt[f]) fx[key] = color(one(opt[f]));
  if (!Object.keys(at).length && !Object.keys(fx).length) die('ambience needs at least one setting (--sky, --fog, --music, --loop, --particles, --water ...)');
  const biomes = String(one(opt.biomes) || 'all').split(',').map(s => s.trim()).filter(Boolean).map(b => b === 'all' || b.startsWith('#') || b.includes(':') ? b : 'minecraft:' + b);
  spec(id + '_ambience', { type: 'ambience', biomes, ...(Object.keys(at).length ? { attributes: at } : {}), ...(Object.keys(fx).length ? { effects: fx } : {}) }, tab);
}

const color = c => { const s = String(c); return /^#?[0-9a-fA-F]{6,8}$/.test(s) ? '#' + s.replace('#', '').toLowerCase() : die('bad color ' + s); };
/** sound id, or a file/URL imported as moddeus:<name> through mc sound */
async function snd(name, src, stream) {
  src = String(src);
  if (!/^https?:/.test(src) && !fs.existsSync(src)) { if (/\.(ogg|mp3|wav|flac|m4a)$/i.test(src)) die('no such file: ' + src); return src.includes(':') ? src : 'minecraft:' + src; }
  mc(['sound', name, src, '--ns', NS, '--no-apply', ...(stream ? ['--stream'] : [])]);
  return NSP + name;
}

// ---- logic blocks ---------------------------------------------------------------------------------------------
const GATES = ['and', 'or', 'xor', 'nand', 'nor', 'xnor', 'not', 'latch', 'toggle', 'pulse', 'delay', 'repeater', 'random'];
const SENSORS = ['clock', 'player', 'mob', 'entity', 'item', 'light', 'sky', 'daylight', 'night', 'rain', 'weather', 'time', 'gamerule', 'score', 'output'];
const LABEL = { and: 'AND', or: 'OR', xor: 'XOR', nand: 'NND', nor: 'NOR', xnor: 'XNR', not: 'NOT', latch: 'RS', toggle: 'T', pulse: 'PLS', delay: 'DLY', repeater: 'RPT', random: 'RND',
  clock: 'CLK', player: 'PLR', mob: 'MOB', entity: 'ENT', item: 'ITM', light: 'LUX', sky: 'SUN', daylight: 'SUN', night: 'MON', rain: 'RAI', weather: 'RAI', time: 'TIM', gamerule: 'GR', score: 'SCR', output: 'OUT' };

function logic(id, tab) {
  const mode = String(one(opt.mode) || 'or');
  if (!GATES.includes(mode) && !SENSORS.includes(mode)) die('--mode: ' + [...GATES, ...SENSORS].join('|'));
  const gate = GATES.includes(mode), files = ['moddeus/content/' + id + '_logic.json'];
  const s = { type: 'logic', block: id, mode };
  for (const key of ['ticks', 'on', 'radius', 'per', 'every']) if (opt[key] !== undefined) s[key] = Number(one(opt[key]));
  for (const key of ['entity', 'rule', 'objective', 'cmd', 'cmd-off', 'cmd-tick', 'sound', 'vfx']) if (opt[key] !== undefined) s[key.replace(/-/g, '_')] = String([].concat(opt[key]).join(';;'));
  if (opt.analog) s.analog = true;
  spec(id + '_logic', s, tab);
  const accent = rgb(one(opt.color) || (mode === 'output' ? '#e0b040' : gate ? '#ff4a3a' : '#40d8c0'));
  for (const on of [false, true]) {
    png(files, 'textures/block/' + id + '_top' + (on ? '_on' : '') + '.png', topTex(LABEL[mode], accent, on, gate));
    const m = gate
      ? { parent: 'minecraft:block/block', textures: { top: NSP + 'block/' + id + '_top' + (on ? '_on' : ''), side: NSP + 'block/' + id + '_side', particle: NSP + 'block/' + id + '_side' },
          elements: [{ from: [0, 0, 0], to: [16, 2, 16], faces: { up: { texture: '#top' }, down: { texture: '#side', cullface: 'down' }, north: { texture: '#side', uv: [0, 14, 16, 16] }, south: { texture: '#side', uv: [0, 14, 16, 16] }, east: { texture: '#side', uv: [0, 14, 16, 16] }, west: { texture: '#side', uv: [0, 14, 16, 16] } } }] }
      : { parent: 'minecraft:block/cube_bottom_top', textures: { top: NSP + 'block/' + id + '_top' + (on ? '_on' : ''), side: NSP + 'block/' + id + '_side' + (on ? '_on' : ''), bottom: NSP + 'block/' + id + '_side' } };
    out(files, 'models/block/' + id + (on ? '_on' : '') + '.json', m);
    if (!gate || !on) png(files, 'textures/block/' + id + '_side' + (on ? '_on' : '') + '.png', sideTex(accent, on, gate));
  }
  const rot = { south: 0, west: 90, north: 180, east: 270 }, variants = {};
  for (const f of Object.keys(rot)) for (let p = 0; p < 16; p++) variants['facing=' + f + ',power=' + p] = { model: NSP + 'block/' + id + (p ? '_on' : ''), ...(rot[f] ? { y: rot[f] } : {}) };
  out(files, 'blockstates/' + id + '.json', { variants });
  out(files, 'items/' + id + '.json', { model: { type: 'minecraft:model', model: NSP + 'block/' + id } });
  writeJson(path.join(RES, 'data/' + NS + '/loot_table/blocks', id + '.json'), { type: 'minecraft:block', pools: [{ rolls: 1, condition: { type: 'minecraft:survives_explosion' }, entries: [{ type: 'minecraft:item', name: NSP + id }] }] });
  lang('block.' + NS + '.' + id, one(opt.name) || title(id));
  const c = { type: 'block', shape: 'logic', files, sound: gate ? 'stone' : 'metal', ...(gate ? { instabreak: true } : { hardness: 1.5 }) };
  if (tab) c.tab = tab;
  writeJson(path.join(CONTENT, id + '.json'), c);
  const ins = ['and', 'xor', 'nand', 'xnor', 'latch'].includes(mode) ? 'left + right' : ['or', 'nor'].includes(mode) ? 'back/left/right' : 'back';
  console.log('  logic block ' + NSP + id + ' (' + mode + (gate ? ': input ' + ins + ', output front (place it facing away from you)' : mode === 'output' ? ': reacts to a signal from any side' : ': powers every side') + ')');
}

const FONT = { A: '010101111101101', B: '110101110101110', C: '011100100100011', D: '110101101101110', E: '111100110100111', G: '011100101101011', H: '101101111101101', I: '111010010010111',
  K: '101101110101101', L: '100100100100111', M: '101111111101101', N: '110101101101101', O: '010101101101010', P: '110101110100100', R: '110101110101101', S: '011100010001110',
  T: '111010010010010', U: '101101101101111', X: '101101010101101', Y: '101101010010010', Z: '111001010100111', W: '101101111111101' };
function topTex(label, accent, on, gate) {
  const cv = canvas(), base = gate ? [74, 76, 84] : [58, 61, 70];
  cv.noise(base, 0.1); cv.frame(base);
  const ink = on ? accent : shade(accent, -0.62), glow = shade(accent, 0.45);
  if (gate) { // arrow toward the output (texture top = north = output side in the model)
    for (let y = 2; y <= 7; y++) cv.px(7, y, ink), cv.px(8, y, ink);
    for (let i = 0; i < 3; i++) for (let x = 7 - i; x <= 8 + i; x++) cv.px(x, 2 + i, ink);
    if (on) cv.px(7, 2, glow), cv.px(8, 2, glow);
  } else { // lens
    for (let y = 2; y <= 7; y++) for (let x = 5; x <= 10; x++) { const d = Math.hypot(x - 7.5, y - 4.5); if (d < 2.9) cv.px(x, y, d < 1.4 && on ? glow : ink); }
  }
  const w = label.length * 4 - 1, x0 = Math.floor((16 - w) / 2);
  [...label].forEach((ch, i) => { const g = FONT[ch] || FONT.O; for (let r = 0; r < 5; r++) for (let c = 0; c < 3; c++) if (g[r * 3 + c] === '1') cv.px(x0 + i * 4 + c, 9 + r, on ? shade(accent, 0.25) : shade(accent, -0.5)); });
  return cv;
}
function sideTex(accent, on, gate) {
  const cv = canvas(), base = gate ? [74, 76, 84] : [58, 61, 70];
  cv.noise(base, 0.1); cv.frame(base);
  if (!gate) for (let x = 4; x < 12; x++) cv.px(x, 7, on ? accent : shade(accent, -0.65)), cv.px(x, 8, on ? shade(accent, -0.2) : shade(accent, -0.75));
  return cv;
}

// ---- banner patterns ------------------------------------------------------------------------------------------
async function banner(id, tab) {
  if (!opt.texture) die('banner needs --texture SRC (image file or direct image URL; dark or opaque parts become the pattern)');
  const src = await fetchImg(one(opt.texture));
  const mask = (w, h) => { const raw = execFileSync('ffmpeg', ['-loglevel', 'error', '-i', src, '-frames:v', '1', '-vf', 'scale=' + w + ':' + h + ':flags=area,format=rgba', '-f', 'rawvideo', '-'], { maxBuffer: 1 << 24 });
    let hasAlpha = false; for (let i = 3; i < raw.length; i += 4) if (raw[i] < 200) { hasAlpha = true; break; }
    const m = []; for (let i = 0; i < w * h; i++) { const r = raw[i * 4], g = raw[i * 4 + 1], b = raw[i * 4 + 2], a = raw[i * 4 + 3] / 255; const v = hasAlpha ? a : (1 - (0.299 * r + 0.587 * g + 0.114 * b) / 255); m.push(v > 0.45 ? 255 : 0); } return m; };
  const files = [];
  for (const [dir, fw, fh] of [['banner', 20, 40], ['shield', 12, 22]]) {
    const cv = canvas(64, 64), m = mask(fw, fh);
    for (let y = 0; y < fh; y++) for (let x = 0; x < fw; x++) if (m[y * fw + x]) { cv.px(1 + x, 1 + y, [255, 255, 255]); if (dir === 'banner') cv.px(1 + fw + 1 + (fw - 1 - x), 1 + y, [255, 255, 255]); }
    png(files, 'textures/entity/' + dir + '/' + id + '.png', cv);
  }
  const name = one(opt.name) || title(id);
  const COLORS = ['white', 'orange', 'magenta', 'light_blue', 'yellow', 'lime', 'pink', 'gray', 'light_gray', 'cyan', 'purple', 'blue', 'brown', 'green', 'red', 'black'];
  for (const c of COLORS) lang('block.' + NS + '.banner.' + id + '.' + c, title(c) + ' ' + name);
  // the real data file too: a live-only entry that a tag names makes the world fail to load after a restart
  const dataFiles = ['data/' + NS + '/banner_pattern/' + id + '.json', 'data/' + NS + '/tags/banner_pattern/pattern_item/' + id + '.json'];
  writeJson(path.join(RES, dataFiles[0]), { asset_id: NSP + id, translation_key: 'block.' + NS + '.banner.' + id });
  writeJson(path.join(RES, dataFiles[1]), { values: [NSP + id] });
  const nir = path.join(RES, 'data/minecraft/tags/banner_pattern/no_item_required.json'), t = readJson(nir, { replace: false, values: [] });
  t.values = t.values.filter(v => v !== NSP + id); if (opt['no-item']) t.values.push(NSP + id);
  if (t.values.length) writeJson(nir, t); else fs.rmSync(nir, { force: true });
  spec(id, { type: 'banner_pattern', asset_id: NSP + id, translation_key: 'block.' + NS + '.banner.' + id, files: [...files, ...dataFiles] }, tab);
  if (!opt['no-item']) { // loom item: paper with the pattern on it
    const icon = canvas(), m = mask(8, 12), paper = [222, 206, 170];
    icon.rect(3, 1, 10, 14, paper); for (let i = 3; i < 13; i++) icon.px(i, 1, shade(paper, 0.2)), icon.px(i, 14, shade(paper, -0.3)); for (let j = 1; j < 15; j++) icon.px(3, j, shade(paper, 0.1)), icon.px(12, j, shade(paper, -0.25));
    for (let y = 0; y < 12; y++) for (let x = 0; x < 8; x++) if (m[y * 8 + x]) icon.px(4 + x, 2 + y, [92, 64, 44]);
    const tmp = path.join(ROOT, '.tools/dl/' + id + '_pattern_icon.png'); fs.mkdirSync(path.dirname(tmp), { recursive: true }); fs.writeFileSync(tmp, encode(icon));
    mc(['item', id + '_banner_pattern', '--ns', NS, '--name', name + ' Banner Pattern', '--texture', tmp, '--stack', '1', '--components', JSON.stringify({ 'minecraft:provides_banner_patterns': '#' + NSP + 'pattern_item/' + id }), '--no-apply', ...(tab ? ['--tab', tab] : [])]);
  }
  console.log('  banner pattern ' + NSP + id + (opt['no-item'] ? ' (in the loom without an item)' : ' + loom item ' + NSP + id + '_banner_pattern') + '; remove: mc remove ' + id + (opt['no-item'] ? '' : ' ' + id + '_banner_pattern'));
}

async function fetchImg(src) {
  if (!/^https?:/.test(src)) { if (!fs.existsSync(src)) die('no such file: ' + src); return src; }
  const r = await fetch(src, { headers: { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0) Chrome/130' } });
  if (!r.ok || (r.headers.get('content-type') || '').includes('html')) die('need a direct image URL: ' + src);
  const f = path.join(ROOT, '.tools/dl', 'img-' + Date.now()); fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, Buffer.from(await r.arrayBuffer())); return f;
}

// ---- particle frames from the procedural VFX generators --------------------------------------------------------
async function ptex(id) {
  const lib = await import('./vfxlib.mjs');
  const n = Number(one(opt.frames) || 1), size = Number(one(opt.size) || 8);
  const t = lib.texture(String(one(opt.shape) || 'spark'), { palette: String(one(opt.palette) || 'fire'), size, frames: n, seed: Number(one(opt.seed) || 1), points: Number(one(opt.points) || 5), premul: false });
  const fr = t.w * t.w * 4;
  for (let i = 0; i < n; i++) { const cv = { w: t.w, h: t.w, d: Buffer.from(t.rgba.subarray(i * fr, (i + 1) * fr)) }; const f = path.join(A, 'textures/particle', (n > 1 ? id + '_' + i : id) + '.png'); fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, encode(cv)); }
  console.log('  ' + n + ' particle frame(s) -> textures/particle/' + id + (n > 1 ? '_0..' + (n - 1) : '') + '.png; next: mc particle ' + id + (n > 1 ? ' --frames ' + n : '') + ' --glow true ...');
}

// ---- helpers ---------------------------------------------------------------------------------------------------
function mc(args) { const r = spawnSync(process.execPath, [path.join(ROOT, 'tools/mc.mjs'), ...args], { cwd: ROOT, encoding: 'utf8' }); process.stdout.write((r.stdout || '') + (r.stderr || '')); if (r.status !== 0) die('mc ' + args[0] + ' failed'); }
function game(...args) { const r = spawnSync(process.execPath, [path.join(ROOT, 'tools/mc.mjs'), 'game', ...args], { cwd: ROOT, encoding: 'utf8' }); if (r.status !== 0) console.log('  (game not reachable: run "mc game cmd reload" later)'); }
function parse(rest) {
  const pos = [], o = {};
  for (let i = 0; i < rest.length; i++) {
    const a = rest[i];
    if (!a.startsWith('--')) { pos.push(a); continue; }
    const k = a.slice(2), v = rest[i + 1] !== undefined && !rest[i + 1].startsWith('--') ? rest[++i] : true;
    o[k] = o[k] === undefined ? v : [].concat(o[k], v);
  }
  return { pos, opt: o };
}
function spec(file, o, tab) { if (tab) o.tab = tab; writeJson(path.join(CONTENT, file + '.json'), o); console.log('  ' + o.type + ' -> moddeus/content/' + file + '.json'); }
function out(files, rel, obj) { writeJson(path.join(A, rel), obj); files.push('assets/' + NS + '/' + rel); }
function png(files, rel, cv) { const f = path.join(A, rel); fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, encode(cv)); files.push('assets/' + NS + '/' + rel); }
function writeJson(f, o) { stamp(f, o); fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, JSON.stringify(o, null, 2) + '\n'); }
function readJson(f, d) { try { return JSON.parse(fs.readFileSync(f, 'utf8')); } catch { return d; } }
function lang(k, v) { const f = path.join(A, 'lang/en_us.json'), l = readJson(f, {}); l[k] = v; writeJson(f, Object.fromEntries(Object.entries(l).sort())); }
const one = v => Array.isArray(v) ? v[v.length - 1] : v;
const title = s => s.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
const rgb = s => { const v = parseInt(String(s).replace('#', ''), 16); return [v >> 16 & 255, v >> 8 & 255, v & 255]; };
const shade = (c, f) => c.map(x => Math.max(0, Math.min(255, Math.round(f >= 0 ? x + (255 - x) * f : x * (1 + f)))));
function canvas(w = 16, h = 16) {
  const d = Buffer.alloc(w * h * 4); let seed = 7;
  const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
  const cv = { w, h, d,
    px(x, y, c, a = 255) { x |= 0; y |= 0; if (x < 0 || y < 0 || x >= w || y >= h) return; const i = (y * w + x) * 4; d[i] = c[0]; d[i + 1] = c[1]; d[i + 2] = c[2]; d[i + 3] = a; },
    rect(x, y, rw, rh, c) { for (let j = y; j < y + rh; j++) for (let i = x; i < x + rw; i++) cv.px(i, j, c); },
    noise(c, amt) { for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) cv.px(x, y, shade(c, (rnd() - 0.5) * amt)); },
    frame(c) { for (let i = 0; i < w; i++) { cv.px(i, 0, shade(c, -0.45)); cv.px(i, h - 1, shade(c, -0.45)); cv.px(0, i, shade(c, -0.45)); cv.px(w - 1, i, shade(c, -0.45)); cv.px(i, 1, shade(c, 0.15)); cv.px(1, i, shade(c, 0.15)); } } };
  return cv;
}
function encode(cv) {
  const { w, h, d } = cv, raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) { raw[y * (w * 4 + 1)] = 0; d.copy(raw, y * (w * 4 + 1) + 1, y * w * 4, (y + 1) * w * 4); }
  const ih = Buffer.alloc(13); ih.writeUInt32BE(w, 0); ih.writeUInt32BE(h, 4); ih[8] = 8; ih[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ih), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]);
}
function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type), data]), crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td) >>> 0);
  return Buffer.concat([len, td, crc]);
}
let CRC; function crc32(b) { if (!CRC) { CRC = new Int32Array(256); for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; CRC[n] = c; } } let c = -1; for (const x of b) c = CRC[(c ^ x) & 255] ^ (c >>> 8); return c ^ -1; }
