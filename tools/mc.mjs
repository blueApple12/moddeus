#!/usr/bin/env node
// Moddeus fast-path content tool. One command = everything a block/item needs, live in-game.
//
//   node tools/mc.mjs block <id> [--name "Nice Name"] [--texture SRC] [--place-sound SRC] [--break-sound SRC]
//        [--sound stone|wood|metal|glass|wool|sand|snow|slime|amethyst|grass|gravel|<any vanilla SoundType: deepslate copper sculk mud chain...>]
//        [--step-sound|--hit-sound|--fall-sound SRC|id] [--sound-volume 1 --sound-pitch 1] [--ambient-sound SRC|id[@0.02]] (SOUNDS.md)
//        [--hardness 1.5] [--resistance 6] [--light 0-15] [--friction 0.6] [--speed 1] [--jump 1]
//        [--requires-tool] [--transparent] [--instabreak] [--no-drop] [--size 16|32|64]
//   all of block|item|armor|mob: [--tab combat|tools|food|building|colored|natural|functional|redstone|ingredients|spawn_eggs|"<Mod Name>"]
//        creative tab: a vanilla one for single small things, the mod's own named tab for a mod with several items
//   node tools/mc.mjs item <id> [--name ..] [--texture SRC] [--stack 64] [--durability N]
//        [--food nutrition,sat-modifier[,always]] (6,0.8 = steak-like; more: mc food) [--rarity common|uncommon|rare|epic] [--fire-resistant] [--handheld]
//        [--shape slab|stairs|wall|fence|fence_gate|door|trapdoor|button|pressure_plate|pane|pillar|carpet|glass]
//        [--mine pickaxe|axe|shovel|hoe]   (which tool mines it fast; pair with --requires-tool)
//   node tools/mc.mjs item <id> ... [--tool sword|pickaxe|axe|shovel|hoe|bow|crossbow|trident|mace|spear|shield|arrow|throwable|gun|wand|staff (tools/weapons.mjs, WEAPONS.md) --material wood|stone|copper|iron|gold|diamond|netherite]
//        [--damage N] [--attack-speed N] [--components '{"minecraft:enchantment_glint_override":true}']
//   node tools/mc.mjs armor <set> --material iron|gold|diamond|netherite|chainmail|copper [--pieces helmet,chestplate,leggings,boots]
//        -> <set>_helmet ... ; draw the worn look with: pixels armor <set> / pixels armor_legs <set> (64x32)
//   node tools/mc.mjs recipe <id> --result <item> [--count N] and one of:
//        --shaped "DDD, S , S " --key "D=minecraft:diamond,S=minecraft:stick"  |  --shapeless "minecraft:a,#minecraft:planks"
//        --smelt|--blast|--smoke|--campfire <ingredient> [--xp 0.7] [--time 200] | --stonecut <in> | --smithing t,b,a | --brew "awkward + sugar" [--pack Id]
//   node tools/mc.mjs remove-recipe <id>
//   node tools/mc.mjs pixels <block|item|armor|armor_legs> <id> --palette "a=#7a1020,b=#c0304a" --rows "..aa..,.abba.,..."   ('.' = transparent)
//   node tools/mc.mjs particle <id> [--frames N] [--lifetime 20] [--gravity 0] [--size 1] [--size-end S] [--shrink]
//        [--glow true|false] [--fade true|false] [--spin 0.2] [--color "#fff"] [--color-end "#f0a"] [--wobble 0.02] [--swirl 0.05] [--collide]
//        draw frames first: pixels particle <id>_0 ... <id>_N-1 (or pixels particle <id> for 1 frame). Spawn: /particle moddeus:<id> ~ ~1 ~ 0.5 0.5 0.5 0.05 40
//   node tools/mc.mjs mob <id> --template zombie|skeleton|spider|creeper|pig|chicken|iron_golem|bee|blaze [--name ..]
//        [--health 20] [--speed 0.25] [--damage 3] [--armor 0] [--scale 1] [--ai hostile] [--particle moddeus:<id>] [--fire-immune]
//        draw: pixels entity <id> (shape guide printed) + pixels item <id>_spawn_egg. Spawns via egg or /summon moddeus:<id>
//   node tools/mc.mjs vfx <id> --layers '[{"type":"ring","texture":"shock","size":0.5,"size_end":6,"duration":14,"alpha_end":0}, ...]'
//        layer types: billboard ring pillar beam slash particles flash shake sound. Common keys: texture (pixels vfx <name>),
//        delay duration size size_end alpha alpha_end color color_end spin frames loops x y z blend(add|alpha).
//        pillar: height height_end planes | beam: length grow | slash: arc width tilt | particles: particle count rate speed radius pattern(burst|ring|up|inward)
//        flash: color alpha | shake: size(degrees) | sound: sound volume pitch.   Play (Java): Vfx.play(Vfx.id("<ns>:<id>"), pos, dir, followOrNull)
//        attach: item ... --vfx-use <id> --vfx-hit <id> [--vfx-cooldown 20]   |   mob ... --aura <id> [--aura-every 40]
//   node tools/mc.mjs block|item <id> --cubes "0 0 0 16 2 16; 6 2 6 10 14 10 pole ry=45" --tex "all=lamp_base,pole=lamp_pole"
//        3D model from cubes (0..16 grid, auto UVs, rx=/ry=/rz= any angle, several = euler, 'glow' = full-bright). Blocks get a matching hitbox. Guide: skill mc-content.
//        items: --wear held|hand|head (default from the id: glove/gauntlet -> hand, hat/helmet/mask -> head) positions worn things right. Animations: skill mc-content "Animations".
//   node tools/mc.mjs physics <id> --texture <block texture: my_block | minecraft:block/stone> [--texture-top ..] [--any-spec-key value ...]
//        keys (see Physics.java): size width height glow spin translucent gravity drag friction bounce rest-speed buoyancy water-drag
//        mass push pushable hit-force hit-lift solid pickup impact-speed impact-sound impact-pitch impact-vfx impact-damage
//        impact-knockback explode explode-breaks-blocks break-speed max-bounces break-vfx health drop lifetime lands-as throw-speed
//        Spawn (Java): Physics.spawn(level, "<ns>:<id>", pos, velocity); throw from an item:
//        item <id> ... --throws <physics id> [--throw-speed 1.2] [--throw-cooldown 10] [--consume]
//   node tools/mc.mjs template <vanilla texture, e.g. item/diamond_sword | entity/equipment/humanoid/iron>
//        prints the SHAPE only ('#' = filled, '.' = empty) as a guide to draw your own texture with pixels. No colors copied.
//   node tools/mc.mjs texture <block|item> <id> SRC [--size 16]
//   node tools/mc.mjs sound <name> SRC [--variants b.ogg,c.ogg] [--stream (music/loops)] [--volume 0.8] [--pitch 1.2] [--subtitle ".."]   (registers <ns>:<name>)
//        [--pitch-set 0.9,1,1.12] [--weight 3,1] [--event minecraft:x] [--attenuation 32] [--preload] [--stereo|--mono] [--loop] [--max 2.5] [--level loud|normal|quiet|ambient|music] [--raw]
//        sound-test <ns:id> [--here] | sound-check [ns] | sound-hold <item> SRC|id | loops (Java): SoundLoops.follow/at/stop (SOUNDS.md)
//   node tools/mc.mjs remove <id|"display name"|JavaFeature> [...]   content: files + live unregister; Java: deletes Name*.java in content dirs, unhooks one-line calls, rebuilds, runs @OnUnload
//   node tools/mc.mjs remove-sound <name> [...]
//   node tools/mc.mjs list | apply
//   node tools/mc.mjs game context|cmd|blocks|shot|say|log|eval ...   (see and act in the live world; tools/game.mjs)
//   node tools/mc.mjs test [game cmd blocks eval shot bridge permission mode]   (self-test, each check <=10s; tools/test.mjs)
//
// SAMPLES (presets): starting points to READ, never a mod's finished look or behaviour. Each use prints
// "preset = sample only: write this mod's own version (guide: ...)". Then write the mod's own: VFX layers, Java abilities, model parts.
//   vfx <id> --preset smite|nova|slash|portal|beam|aura|explosion|heal|lightning --palette fire|ice|arcane|holy|poison|shadow|blood|lightning|nature|void|sun|"#000,#0f8,#fff" [--scale 1.5]
//   vfx-tex <name> --shape glow|orb|ring|shockwave|flare|star|spark|beam|slash|sigil|swirl|smoke|lightning|crack --palette <p> [--size 32] [--frames 4] [--points 6] [--seed 1] [--thickness 0.08] [--contrast 1]
//   item <id> --model sword|greatsword|axe|hammer|spear|scythe|staff|wand|bow|gun|shield|gauntlet|helmet|crown|custom [--p "length=20,width=4"] [--colors "blade=#9fd8ff"] [--mats "blade=crystal"] [--glow blade] [--parts '[{"box":[..],"tex":"spike","r":[0,0,30]}]'] [--variant "using:glow=blade|colors=blade=#f44;charged:p=length=24"] [--icon sprite|"p=length=10"] [--pulse] [--repaint]
//        model shapes (params + texture keys) | model preview <id> [--state '[custom_model_data={flags:[true]}]'] -> screenshot. Guide: mc-content MODELS.md
//   physics <id> --preset crate|bouncy_ball|boulder|ice_cube|bomb|balloon|platform|falling_sand|floaty|glass_orb   (tools/library/physics/*.json)
//   synth <name> --preset whoosh|impact|zap|boom|hum|drone|chime|charge|crackle|wind|rumble|roar|step|pop|shimmer|heartbeat [--variants 3]   (tools/sounds.mjs)
//   bossattacks (Bosses.attack names), mobai behaviours, combat abilities, item --tool weapon kinds: engine samples (BOSSES.md COMBAT.md WEAPONS.md)
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
let A = path.join(RES, 'assets/moddeus'); // assets/<NS>: set below from the mod
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
// Several chats/mods run mc at the same time: every command that writes shared files (lang/en_us.json, sounds.json, tags,
// content JSON, lessons) holds the content lock, so two chats never lose each other's keys. Read-only/game commands skip it.
const NO_LOCK = new Set(['game', 'test', 'ask', 'list', 'projects', 'lessons', 'snaps', 'log', 'help', 'mobmodel', 'build', 'apply', undefined]);
if (!NO_LOCK.has(cmd)) lock(ROOT, 'content');

const writeJson = (f, o) => { nsContent(f, o); fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, JSON.stringify(o, null, 2) + '\n'); };
/** Content JSON of a mod: "namespace" + its tab (no Moddeus catalog tab). One file name = one id across all mods. */
function nsContent(f, o) {
  if (NS === 'moddeus' || !o || typeof o !== 'object' || path.dirname(path.resolve(f)) !== path.resolve(RES, 'moddeus/content')) return;
  const old = readJson(f, null);
  if (old && (old.namespace || 'moddeus') !== NS) die(path.basename(f, '.json') + ' already belongs to namespace ' + (old.namespace || 'moddeus') + ': pick another id');
  o.namespace = NS;
  if (!o.tab) o.tab = (modOfNs(NS) || { name: title(NS) }).name;
}
const readJson = (f, d) => { try { return JSON.parse(fs.readFileSync(f, 'utf8')); } catch { return d; } };
const title = id => id.split('_').map(w => w[0].toUpperCase() + w.slice(1)).join(' ');
const checkId = id => { if (!/^[a-z0-9_]+$/.test(id || '')) die('id must be [a-z0-9_]+, got: ' + id); return id; };
function die(m) { console.error('mc: ' + m); process.exit(1); }

// Per-mod namespace (ids <ns>:<id>, assets/<ns>/, data/<ns>/; guide mc-content NAMESPACES.md): an "<ns>:<id>" argument, --ns,
// the mod named by --tab/--project (snake_case of its name), or the mod of the content an id extends (pixels item bow_grip); else moddeus.
const snakeNs = s => String(s).trim().replace(/([a-z0-9])([A-Z])/g, '$1_$2').toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '');
const PROJECTS = readJson(path.join(ROOT, 'run/moddeus/projects.json'), []);
const projectOf = n => n && PROJECTS.find(p => [p.name, p.id].some(x => String(x).toLowerCase() === String(n).toLowerCase()));
const NS_CMDS = new Set(['block', 'item', 'armor', 'mob', 'particle', 'vfx', 'vfx-tex', 'physics', 'pixels', 'texture', 'sound', 'synth', 'sound-hold', 'remove-sound', 'effect', 'potion', 'brew', 'enchant', 'attr', 'itemfx',
  'food', 'feast', 'cook', 'recipe', 'quest', 'disc', 'pad', 'crop', 'ore', 'machine', 'economy', 'shop', 'skill', 'hud', 'skyevent', 'secret', // tools/*.mjs modules:
  'tools', 'worn', 'glider', 'totem', 'book', 'key', 'reskin', 'light', 'container', 'storage', 'ambience', 'logic', 'banner', 'gamerule', 'command', 'ptex',
  'mobai', 'spawnrule', 'boss', 'setbonus', 'drop', 'npc', 'trade', 'profession', 'painting']); // they get ctx.NS + ctx.stamp (= nsContent)
let NS = opt.ns ? snakeNs(opt.ns) : 'moddeus';
if (opt.project && !opt.tab && projectOf(opt.project)) opt.tab = projectOf(opt.project).name;
if (NS_CMDS.has(cmd)) {
  for (let i = 0; i < pos.length; i++) { const m = /^([a-z0-9_.-]{2,}):([a-z0-9_]+)$/.exec(pos[i]); if (m && m[1] !== 'minecraft') { NS = m[1]; rest[rest.indexOf(pos[i])] = m[2]; pos[i] = m[2]; } } // modules (effects...) read rest
  const explicit = !!opt.ns || NS !== 'moddeus', own = pos.find(p => /^[a-z0-9_]+$/.test(p) && fs.existsSync(path.join(RES, 'moddeus/content', p + '.json')));
  if (!explicit && own) NS = readJson(path.join(RES, 'moddeus/content', own + '.json'), {}).namespace || 'moddeus'; // existing content keeps its namespace
  else if (NS === 'moddeus' && !opt.ns && projectOf(opt.tab)) NS = snakeNs(projectOf(opt.tab).name);
  if (NS === 'moddeus' && !opt.ns) { // extends existing content: its namespace
    const ids = fs.existsSync(path.join(RES, 'moddeus/content')) ? fs.readdirSync(path.join(RES, 'moddeus/content')).map(f => f.slice(0, -5)) : [];
    const base = pos.filter(p => /^[a-z0-9_]+$/.test(p)).flatMap(p => ids.filter(i => p === i || p.startsWith(i + '_'))).sort((a, b) => b.length - a.length)[0];
    if (base) NS = readJson(path.join(RES, 'moddeus/content', base + '.json'), {}).namespace || 'moddeus';
  }
}
let NSP = NS + ':';
A = path.join(RES, 'assets', NS);
const modOfNs = ns => PROJECTS.find(p => snakeNs(p.name) === ns);

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

const SOURCES = {}; // web audio page -> credits text (written next to the .ogg as <name>.license.txt)
async function fetchSrc(src, kind) {
  if (!/^https?:/.test(src)) { if (!fs.existsSync(src)) die('no such file: ' + src); return src; }
  let r = await fetch(src, { headers: UA });
  if (!r.ok) die(`download failed ${r.status}: ${src}`);
  if ((r.headers.get('content-type') || '').includes('html')) {
    const html = await r.text();
    const re = kind === 'image'
      ? /property="og:image"\s+content="([^"]+)"|content="([^"]+)"\s+property="og:image"/
      : /["'(]([^"'( ]+\.(?:mp3|ogg|wav))/;
    const m = (kind === 'audio' && html.match(/property="og:audio(?::secure_url)?"\s+content="([^"]+)"/)) || html.match(re);
    if (!m) die('no ' + kind + ' found on page ' + src);
    let raw = m[1] || m[2];
    raw = raw.slice(Math.max(0, raw.lastIndexOf('http'))); // freesound pages glue the site in front of the CDN url
    let found = new URL(raw, src).href;
    if (kind === 'audio') {
      if (/cdn\.freesound\.org\/previews\/.*-lq\.mp3$/.test(found)) found = found.replace(/-lq\.mp3$/, '-hq.mp3'); // best preview without an API key
      const lic = (html.match(/creativecommons\.org\/(?:licenses|publicdomain)\/[a-z0-9.\/-]+/i) || [''])[0];
      const t = (html.match(/property="og:title"\s+content="([^"]+)"/) || html.match(/<title>([^<]+)/) || ['', ''])[1];
      SOURCES[src] = 'Source: ' + src + '\nFile: ' + found + '\nTitle: ' + t.trim() + '\nLicense: ' + (lic ? 'https://' + lic : 'UNKNOWN - check the page before shipping') + '\n';
    }
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
  // --variants a,b (random pick like vanilla footsteps) --stream (music/long loops: streamed, stereo unless --mono) --volume --pitch --subtitle "text"
  // clean-up by default (guide SOUNDS.md): trim silence, peak-normalize SFX to -1 dBFS / music to -16 LUFS, 8 ms anti-click fades. --raw skips it,
  // --loop keeps the file seamless (no trim/fades). --stereo|--mono, --max <s> (cut + fade), --preload, --attenuation <blocks>, --weight 3,1,..,
  // --pitch-set 0.9,1,1.12 (each file once per pitch: never sounds repetitive), --event minecraft:entity.zombie.ambient (also play a vanilla event)
  const srcs = [src, ...(opt.variants ? String(opt.variants).split(',').map(x => x.trim()) : [])].filter(Boolean), stream = !!opt.stream, entries = [];
  const stereo = opt.stereo ? true : opt.mono ? false : stream, loop = !!opt.loop, raw = !!opt.raw;
  const weights = opt.weight ? String(opt.weight).split(',').map(Number) : [];
  const pitches = opt['pitch-set'] ? String(opt['pitch-set']).split(',').map(Number) : [opt.pitch ? Number(opt.pitch) : null];
  for (const [i, s1] of srcs.entries()) {
    const n = i ? name + '_' + (i + 1) : name;
    let file = await fetchSrc(s1, 'audio');
    const out = path.join(A, 'sounds', n + '.ogg');
    fs.mkdirSync(path.dirname(out), { recursive: true });
    if (path.resolve(file).toLowerCase() === path.resolve(out).toLowerCase()) { const t = path.join(ROOT, '.tools/dl', 'mc-' + Date.now() + '-' + n + '.ogg'); fs.mkdirSync(path.dirname(t), { recursive: true }); fs.copyFileSync(file, t); file = t; } // re-cleaning our own .ogg: ffmpeg must not overwrite its input
    const af = ['aformat=channel_layouts=' + (stereo ? 'stereo' : 'mono')]; // downmix first so the loudness/peak measurement sees the real output
    if (!raw && !loop && !stream) af.push('silenceremove=start_periods=1:start_threshold=-55dB', 'areverse', 'silenceremove=start_periods=1:start_threshold=-55dB', 'areverse');
    if (opt.max) af.push('atrim=0:' + Number(opt.max), 'afade=t=out:st=' + Math.max(0, Number(opt.max) - 0.15) + ':d=0.15');
    if (!raw) {
      const g = gainTo(file, af, String(opt.level || (stream ? 'music' : 'normal'))); // linear gain: keeps the dynamics
      if (g) af.push('volume=' + g.toFixed(2) + 'dB');
      if (!loop && !stream) af.push('afade=t=in:d=0.008', 'areverse', 'afade=t=in:d=0.008', 'areverse');
    }
    ff(['-i', file, ...(af.length ? ['-af', af.join(',')] : []), '-ac', stereo ? '2' : '1', '-ar', '44100', '-c:a', 'libvorbis', '-q:a', '6', out]); // mono: positional (stereo never fades with distance)
    for (const p of pitches) {
      const e = { name: NSP + n };
      if (stream) e.stream = true;
      if (opt.preload) e.preload = true;
      if (opt.attenuation) e.attenuation_distance = Number(opt.attenuation);
      if (weights[i] && weights[i] !== 1) e.weight = weights[i];
      if (opt.volume) e.volume = Number(opt.volume);
      if (p && p !== 1) e.pitch = p;
      entries.push(Object.keys(e).length > 1 ? e : e.name);
    }
    if (SOURCES[s1]) { fs.writeFileSync(path.join(A, 'sounds', n + '.license.txt'), SOURCES[s1]); console.log('  ' + SOURCES[s1].split('\n')[3]); }
    console.log('  ' + n + '.ogg ' + audioInfo(out));
  }
  for (const ev of opt.event ? String(opt.event).split(',') : []) entries.push({ name: ev.trim().includes(':') ? ev.trim() : 'minecraft:' + ev.trim(), type: 'event' });
  const sj = path.join(A, 'sounds.json');
  const s = readJson(sj, {});
  const keep = new Set(entries.map(e => String(e.name || e)));
  for (const e of s[name]?.sounds || []) { const n = String(e.name || e); if (e.type !== 'event' && n.startsWith(NSP) && !keep.has(n)) rm('assets/' + NS + '/sounds/' + n.slice(NSP.length) + '.ogg'); } // fewer variants than last time: drop the old files
  s[name] = { sounds: entries, subtitle: 'subtitles.' + NS + '.' + name };
  writeJson(sj, s);
  lang('subtitles.' + NS + '.' + name, typeof opt.subtitle === 'string' ? opt.subtitle : title(name));
  console.log('  sound -> ' + NSP + name + ' (' + entries.length + ' entries' + (stereo ? ', STEREO: not positional' : '') + ')');
  return NSP + name;
}

/** Linear gain (dB) to a vanilla-like loudness (measured on 26.3 assets: roar/explode -13..-16 LUFS, mob say/bow -21, cave/ambient
 *  beds -27..-32, music -16..-18), true peak capped at -1 dBTP. Too short to measure (I = -inf): by peak instead. */
const LEVELS = { loud: [-14, -1], normal: [-20, -4], quiet: [-27, -9], ambient: [-30, -12], music: [-17, -1] };
function gainTo(file, af, level) {
  const [I, P] = LEVELS[level] || die('--level loud|normal|quiet|ambient|music');
  const r = spawnSync('ffmpeg', ['-hide_banner', '-i', file, '-af', [...af, 'loudnorm=print_format=json'].join(','), '-f', 'null', '-'], { encoding: 'utf8' });
  const s = r.stderr || '', a = s.lastIndexOf('{');
  let j; try { j = JSON.parse(s.slice(a, s.indexOf('}', a) + 1)); } catch { return 0; }
  const i = Number(j.input_i), tp = Number(j.input_tp);
  const g = Number.isFinite(i) && i > -60 ? Math.min(I - i, -1 - tp) : P - tp;
  return Number.isFinite(g) ? Math.max(-30, Math.min(30, g)) : 0;
}

/** "1.24 s, mono, peak -1.0 dB" for the log (ffprobe + volumedetect). */
function audioInfo(f) {
  const p = spawnSync('ffprobe', ['-v', 'error', '-show_entries', 'stream=channels:format=duration', '-of', 'default=nw=1', f], { encoding: 'utf8' }).stdout || '';
  const ch = /channels=(\d+)/.exec(p), d = /duration=([\d.]+)/.exec(p);
  const v = /max_volume:\s*(-?[\d.]+) dB/.exec(spawnSync('ffmpeg', ['-hide_banner', '-i', f, '-af', 'volumedetect', '-f', 'null', '-'], { encoding: 'utf8' }).stderr || '');
  return (d ? Number(d[1]).toFixed(2) + ' s' : '?') + ', ' + (ch && ch[1] === '1' ? 'mono' : 'stereo') + (v ? ', peak ' + v[1] + ' dB' : '');
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

function out(files, rel, obj) { writeJson(path.join(A, rel), obj); files.push('assets/' + NS + '/' + rel); }

/** Copies the vanilla blockstate + every model it uses for this shape, pointing all textures at ours. */
function shapeAssets(id, shape, files) {
  const tpl = SHAPES[shape], tex = NSP + 'block/' + id, made = new Set();
  const walk = (o, f) => { if (Array.isArray(o)) o.forEach(x => walk(x, f)); else if (o && typeof o === 'object') for (const k in o) { if (k === 'model' && typeof o[k] === 'string') o[k] = f(o[k]); else walk(o[k], f); } };
  const model = ref => {
    const name = ref.replace(/^minecraft:/, '');
    if (!name.startsWith('block/')) { out(files, 'models/item/' + id + '.json', { parent: 'minecraft:item/generated', textures: { layer0: tex } }); return NSP + 'item/' + id; }
    const base = name.slice(6), ours = base.includes(tpl) ? base.replace(tpl, id) : id + '_' + base;
    if (!made.has(ours)) {
      made.add(ours);
      const m = JSON.parse(vanilla('models/block/' + base + '.json'));
      for (const k in m.textures || {}) if (typeof m.textures[k] === 'object') m.textures[k] = { ...m.textures[k], sprite: tex }; else if (!String(m.textures[k]).startsWith('#')) m.textures[k] = tex; // keep 26.x {sprite, force_translucent} (glass)
      out(files, 'models/block/' + ours + '.json', m);
    }
    return NSP + 'block/' + ours;
  };
  const bs = JSON.parse(vanilla('blockstates/' + tpl + '.json'));
  walk(bs, model);
  out(files, 'blockstates/' + id + '.json', bs);
  const it = JSON.parse(vanilla('items/' + tpl + '.json'));
  walk(it, model);
  out(files, 'items/' + id + '.json', it);
}

function tagEdit(tag, value, add, kind = 'block') { // tag = "minecraft:walls" (block tags; kind 'item' = item tags)
  const [ns, name] = tag.split(':');
  const f = path.join(RES, 'data', ns, 'tags', kind, name + '.json');
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
      if (m) (cube.rot ||= {})[m[1]] = Number(m[2]); // any angle; several axes = euler (Rz*Ry*Rx)
      else if (t === 'glow') cube.glow = true; // full-bright part (light_emission 15)
      else cube.tex = t;
    }
    return cube;
  });
}
function cubesModel(id, kind, cubes) {
  const keys = Object.fromEntries(String(opt.tex || '').split(',').filter(Boolean).map(kv => kv.split('=')));
  const textures = {};
  for (const c of cubes) textures[c.tex] = keys[c.tex] ? (keys[c.tex].includes(':') ? keys[c.tex] : NSP + kind + '/' + keys[c.tex]) : NSP + kind + '/' + id + (c.tex === 'all' ? '' : '_' + c.tex);
  textures.particle = Object.values(textures)[0];
  const elements = cubes.map(c => {
    const [x1, y1, z1, x2, y2, z2] = c.box;
    const e = { from: [Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2)], to: [Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2)], faces: {} };
    for (const f of ['north', 'south', 'east', 'west', 'up', 'down']) e.faces[f] = { texture: '#' + c.tex };
    const ax = Object.keys(c.rot || {}), origin = e.from.map((v, i) => (v + e.to[i]) / 2);
    if (ax.length === 1) e.rotation = { axis: ax[0], angle: c.rot[ax[0]], origin };
    else if (ax.length) e.rotation = { x: c.rot.x || 0, y: c.rot.y || 0, z: c.rot.z || 0, origin };
    if (c.glow) { e.light_emission = 15; e.shade = false; }
    return e;
  });
  for (const [k, t] of Object.entries(textures)) if (k !== 'particle' && t.startsWith(NSP) && !fs.existsSync(path.join(A, 'textures', t.slice(NSP.length) + '.png')))
    console.warn('  (texture ' + t + ' missing: mc pixels ' + kind + ' ' + t.split('/').pop() + ' NOW: until every named texture exists the model fails to bake ("Multiple atlases") and renders as nothing)');
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
  c.tags.forEach(t => tagEdit(t, NSP + id, true));
  for (const k of ['hardness', 'resistance', 'light', 'friction', 'speed', 'jump']) if (opt[k] !== undefined) c[k] = Number(opt[k]);
  if (opt.sound) c.sound = opt.sound;
  if (opt['requires-tool']) c.requiresTool = true;
  if (opt.transparent) c.transparent = true;
  if (opt.instabreak) c.instabreak = true;
  if (opt['place-sound']) c.placeSound = await sound(id + '_place', opt['place-sound']);
  if (opt['break-sound']) c.breakSound = await sound(id + '_break', opt['break-sound']);
  // full SoundType (SOUNDS.md): a sound id (ns:x / minecraft:x) is used as is, a file/URL is imported as <id>_<kind>
  const sref = async (k, v) => /^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(String(v)) && !/^[a-z]:[\\/]/i.test(String(v)) && !/^https?:/.test(String(v)) ? String(v) : sound(id + '_' + k, v);
  for (const k of ['step', 'hit', 'fall']) if (opt[k + '-sound']) c[k + 'Sound'] = await sref(k, opt[k + '-sound']);
  if (opt['sound-volume']) c.soundVolume = Number(opt['sound-volume']);
  if (opt['sound-pitch']) c.soundPitch = Number(opt['sound-pitch']);
  if (opt['ambient-sound']) { // random ambient like vanilla animateTick: --ambient-sound SRC|id[@chance per sampled tick, default 0.02]
    const [src0, ch] = String(opt['ambient-sound']).split(/@(?=[\d.]+$)/);
    c.ambient_sound = { sound: await sref('ambient', src0), chance: Number(ch || 0.02), volume: Number(opt['ambient-volume'] || 0.6) };
  }
  if (opt.cubes) {
    const cubes = parseCubes(opt.cubes);
    c.shape = 'model';
    c.cubes = cubes.map(x => x.box);
    out(c.files, 'blockstates/' + id + '.json', { variants: { '': { model: NSP + 'block/' + id } } });
    out(c.files, 'models/block/' + id + '.json', cubesModel(id, 'block', cubes));
    out(c.files, 'items/' + id + '.json', { model: { type: 'minecraft:model', model: NSP + 'block/' + id } });
  } else if (shape) shapeAssets(id, shape, c.files);
  else {
    out(c.files, 'blockstates/' + id + '.json', { variants: { '': { model: NSP + 'block/' + id } } });
    out(c.files, 'models/block/' + id + '.json', { parent: 'minecraft:block/cube_all', textures: { all: NSP + 'block/' + id }, ...(opt.transparent ? { render_type: 'minecraft:cutout' } : {}) });
    out(c.files, 'items/' + id + '.json', { model: { type: 'minecraft:model', model: NSP + 'block/' + id } });
  }
  writeJson(path.join(CONTENT, id + '.json'), c);
  const prop = (k, v) => [{ condition: 'minecraft:block_state_property', block: NSP + id, properties: { [k]: v } }];
  if (!opt['no-drop']) writeJson(path.join(RES, 'data/' + NS + '/loot_table/blocks', id + '.json'), {
    type: 'minecraft:block',
    pools: [{ rolls: 1, conditions: [{ condition: 'minecraft:survives_explosion' }, ...(shape === 'door' ? prop('half', 'lower') : [])],
      entries: [{ type: 'minecraft:item', name: NSP + id, ...(shape === 'slab' ? { functions: [{ function: 'minecraft:set_count', count: 2, conditions: prop('type', 'double') }] } : {}) }] }],
  });
  lang('block.' + NS + '.' + id, opt.name || title(id));
  if (opt.texture) await texture('block', id, opt.texture, Number(opt.size || 16));
  else if (!opt.cubes && !fs.existsSync(path.join(A, 'textures/block', id + '.png'))) console.warn('  (no texture yet: draw it with mc pixels block ' + id + ')');
  console.log('block ' + NSP + id + (shape ? ' (' + shape + ')' : '') + ' ready');
}

async function item(id) {
  checkId(id);
  const c = { type: 'item' };
  if (opt.tab) c.tab = String(opt.tab); // creative tab: combat|tools|food|building|colored|natural|functional|redstone|ingredients|spawn_eggs, or a mod's own tab name
  if (opt.stack) c.stack = Number(opt.stack);
  if (opt.durability) c.durability = Number(opt.durability);
  if (opt.rarity) c.rarity = opt.rarity;
  if (opt['fire-resistant']) c.fireResistant = true;
  if (opt.food) { const [n, s, al] = String(opt.food).split(','); c.food = { nutrition: Number(n), saturation: Math.round(Number(n) * Number(s || 0.6) * 200) / 100, alwaysEat: al === 'always' || al === 'true' }; } // s = vanilla saturation modifier (0.6 normal, 0.8 meat): saturation = n x s x 2
  if (opt.tool) {
    const WK = await import('./weapons.mjs'), wk = WK.KINDS.includes(opt.tool); // bow crossbow trident mace spear shield arrow throwable gun wand staff: tools/weapons.mjs + live/Weapons.java
    if (!wk && !/^(sword|pickaxe|axe|shovel|hoe)$/.test(opt.tool)) die('--tool sword|pickaxe|axe|shovel|hoe|' + WK.KINDS.join('|'));
    if (wk) console.log("preset = sample only: write this mod's own version (guide: .claude/skills/mc-content/WEAPONS.md)");
    c.tool = wk ? WK.tool(id, opt, { tagEdit, A, c, NSP }) : { type: opt.tool, material: opt.material || 'iron' }; // weapon kinds also write their enchantable/ammo tags
    if (!wk) c.itemTags = [...(c.itemTags || []), ...(await import('./gear.mjs')).toolTags(id, opt.tool, NSP)]; // enchanting table/anvil + 'is a pickaxe' checks read these item tags
    if (opt.damage !== undefined) c.tool.damage = Number(opt.damage);
    if (opt['attack-speed'] !== undefined) c.tool.speed = Number(opt['attack-speed']);
  }
  if (opt.special) c.special = (await import('./weapons.mjs')).special(opt); // right-click/hit attack on any item (live/Weapons)
  if (opt.components) { try { c.components = JSON.parse(opt.components); } catch (e) { die('--components is not JSON: ' + e.message); } }
  if (opt.cubes && wearOf(id) === 'head') c.components = { 'minecraft:equippable': { slot: 'head' }, ...(c.components || {}) }; // right-click to wear
  if (opt.throws) c.throws = { physics: opt.throws, speed: Number(opt['throw-speed'] || 1.2), ...(opt['throw-cooldown'] ? { cooldown: Number(opt['throw-cooldown']) } : {}), ...(opt.consume ? { consume: true } : {}) };
  if (opt['vfx-use'] || opt['vfx-hit']) c.vfx = { ...(opt['vfx-use'] ? { use: opt['vfx-use'] } : {}), ...(opt['vfx-hit'] ? { hit: opt['vfx-hit'] } : {}), ...(opt['vfx-cooldown'] ? { cooldown: Number(opt['vfx-cooldown']) } : {}) };
  await itemFiles(id, c, opt.tool === 'bow' ? 'item/bow' : opt.tool ? 'item/' + VANILLA_MAT[c.tool.material] + '_' + opt.tool : null, !!(opt.handheld || opt.tool));
}

const VANILLA_MAT = { wood: 'wooden', wooden: 'wooden', stone: 'stone', copper: 'copper', iron: 'iron', gold: 'golden', golden: 'golden', diamond: 'diamond', netherite: 'netherite', chainmail: 'chainmail', leather: 'leather' };

/** Content JSON + item model/definition + lang + texture (--texture, else draw it with pixels; shapeHint = vanilla template to suggest). */
async function itemFiles(id, c, shapeHint, handheld, name = opt.name || title(id)) {
  c.files = [];
  if (opt.model && opt.model !== 'custom') console.log("preset = sample only: write this mod's own version (guide: .claude/skills/mc-content/MODELS.md)");
  const M = opt.model ? await (await import('./models.mjs')).item(id, opt, { A, out, files: c.files, c, ns: NS }) : null; // 3D shape templates (tools/models.mjs)
  out(c.files, 'models/item/' + id + '.json', M ? M.model : opt.cubes ? cubesModel(id, 'item', parseCubes(opt.cubes)) : { parent: handheld ? 'minecraft:item/handheld' : 'minecraft:item/generated', textures: { layer0: NSP + 'item/' + id } });
  out(c.files, 'items/' + id + '.json', M ? M.definition : { model: { type: 'minecraft:model', model: NSP + 'item/' + id } });
  writeJson(path.join(CONTENT, id + '.json'), c);
  lang('item.' + NS + '.' + id, name);
  if (opt.texture) await texture('item', id, opt.texture, Number(opt.size || 16));
  else if (!opt.cubes && !opt.model && !fs.existsSync(path.join(A, 'textures/item', id + '.png')))
    console.warn('  (no texture yet: draw it with  mc pixels item ' + id + (shapeHint ? '   shape guide: mc template ' + shapeHint : '') + ')');
  console.log('item ' + NSP + id + ' ready');
}

async function armor(set) {
  checkId(set);
  const mat = opt.material || 'iron', vm = VANILLA_MAT[mat];
  if (!vm) die('--material ' + Object.keys(VANILLA_MAT).join('|'));
  const worn = { golden: 'gold' }[vm] || vm;
  const pieces = String(opt.pieces || 'helmet,chestplate,leggings,boots').split(',');
  const eq = [];
  out(eq, 'equipment/' + set + '.json', { layers: { humanoid: [{ texture: NSP + set }], humanoid_leggings: [{ texture: NSP + set }] } });
  for (const layer of ['humanoid', 'humanoid_leggings']) {
    const f = path.join(A, 'textures/entity/equipment', layer, set + '.png');
    if (!fs.existsSync(f)) console.warn('  (worn look missing: mc pixels ' + (layer === 'humanoid' ? 'armor' : 'armor_legs') + ' ' + set + '   shape guide: mc template entity/equipment/' + layer + '/' + worn + ')');
    eq.push('assets/' + NS + '/textures/entity/equipment/' + layer + '/' + set + '.png');
  }
  const base = opt.name || title(set);
  for (const p of pieces) {
    const id = set + '_' + p;
    const c = { type: 'item', armor: { type: p, material: mat, asset: NSP + set } };
    if (opt.tab) c.tab = String(opt.tab); // creative tab: combat|tools|food|building|colored|natural|functional|redstone|ingredients|spawn_eggs, or a mod's own tab name
    await itemFiles(id, c, 'item/' + vm + '_' + p, false, base + ' ' + title(p));
    c.files.push(...eq);
    writeJson(path.join(CONTENT, id + '.json'), c);
  }
  (await import('./gear.mjs')).armorPost(set, pieces, opt, { ROOT, CONTENT, die, NS }); // stats, enchant/trim tags, repair + recipes (tools/gear.mjs)
}

const MOB_TEX = { zombie: 'entity/zombie/zombie', skeleton: 'entity/skeleton/skeleton', spider: 'entity/spider/spider', creeper: 'entity/creeper/creeper',
  pig: 'entity/pig/pig_temperate', chicken: 'entity/chicken/chicken_temperate', iron_golem: 'entity/iron_golem/iron_golem', bee: 'entity/bee/bee', blaze: 'entity/blaze/blaze' };

async function mob(id) {
  checkId(id);
  const tpl = opt.template || 'zombie';
  if (!MOB_TEX[tpl] && !opt.model) console.warn('  (template ' + tpl + ' keeps its vanilla look until you add --model <preset>; mc mobmodel lists presets)');
  const c = { type: 'mob', template: tpl };
  if (opt.tab) c.tab = String(opt.tab); // creative tab: combat|tools|food|building|colored|natural|functional|redstone|ingredients|spawn_eggs, or a mod's own tab name
  for (const k of ['health', 'speed', 'damage', 'armor', 'scale', 'knockback-resistance', 'follow-range', 'particle-count'])
    if (opt[k] !== undefined) c[k.replace(/-/g, '_')] = Number(opt[k]);
  if (opt.ai) c.ai = opt.ai;
  if (opt.particle) c.particle = opt.particle.includes(':') ? opt.particle : NSP + opt.particle;
  if (opt['fire-immune']) c.fire_immune = true;
  if (opt.aura) { c.aura = opt.aura; if (opt['aura-every']) c.aura_every = Number(opt['aura-every']); }
  const egg = id + '_spawn_egg';
  c.files = [];
  out(c.files, 'models/item/' + egg + '.json', { parent: 'minecraft:item/generated', textures: { layer0: NSP + 'item/' + egg } });
  out(c.files, 'items/' + egg + '.json', { model: { type: 'minecraft:model', model: NSP + 'item/' + egg } });
  c.files.push('assets/' + NS + '/textures/entity/' + id + '.png', 'assets/' + NS + '/textures/item/' + egg + '.png');
  await (await import('./life.mjs')).mobExtras(id, c, opt, { A, ROOT, png, die, NS, CONTENT }); // 3D model, hitbox, food/tame/mount, sounds, drops (tools/life.mjs)
  writeJson(path.join(CONTENT, id + '.json'), c);
  const name = opt.name || title(id);
  lang('entity.' + NS + '.' + id, name);
  lang('item.' + NS + '.' + egg, name + ' Spawn Egg');
  if (!fs.existsSync(path.join(A, 'textures/entity', id + '.png'))) console.warn('  (skin missing: mc pixels entity ' + id + '   shape guide: mc template ' + MOB_TEX[tpl] + ')');
  if (!fs.existsSync(path.join(A, 'textures/item', egg + '.png'))) console.warn('  (egg texture missing: mc pixels item ' + egg + '   shape guide: mc template item/' + tpl + '_spawn_egg)');
  console.log('mob ' + NSP + id + ' (' + tpl + ' body) ready; changing stats/template later needs a game restart');
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
  out(c.files, 'particles/' + id + '.json', { textures: names.map(f => NSP + f) });
  c.files.push(...names.map(f => 'assets/' + NS + '/textures/particle/' + f + '.png'));
  writeJson(path.join(CONTENT, id + '.json'), c);
  console.log('particle ' + NSP + id + ' (' + n + ' frame' + (n > 1 ? 's' : '') + ') ready: /particle ' + NSP + id + ' ~ ~1 ~ 0.5 0.5 0.5 0.05 40');
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
    console.log("preset = sample only: write this mod's own version (guide: .claude/skills/mc-content/VFX-DESIGN.md)");
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
  console.log('vfx ' + NSP + id + ' (' + layers.length + ' layers) ready. Play from Java: Vfx.play(Vfx.id("' + NSP + id + '"), pos, dir, null)');
}

const SPEC_SKIP = new Set(['preset', 'no-apply', 'name']);
function physics(id) {
  checkId(id);
  const c = { type: 'physics' };
  if (opt.preset) {
    console.log("preset = sample only: write this mod's own version (guide: .claude/skills/mc-content/SKILL.md)");
    const f = path.join(ROOT, 'tools/library/physics', opt.preset + '.json');
    if (!fs.existsSync(f)) die('no preset ' + opt.preset + ' (have: ' + fs.readdirSync(path.dirname(f)).map(x => x.replace('.json', '')).join(', ') + ')');
    Object.assign(c, readJson(f, {}));
  }
  for (const [k, v] of Object.entries(opt)) {
    if (SPEC_SKIP.has(k)) continue;
    c[k.replace(/-/g, '_')] = v === true || v === 'true' ? true : v === 'false' ? false : (v !== '' && !isNaN(Number(v)) ? Number(v) : v);
  }
  writeJson(path.join(CONTENT, id + '.json'), c);
  console.log('physics ' + NSP + id + ' ready: Physics.spawn(level, "' + NSP + id + '", pos, vel)   spec: ' + JSON.stringify(c));
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
  const OUT = { block: 'textures/block/', item: 'textures/item/', entity: 'textures/entity/', particle: 'textures/particle/', vfx: 'textures/vfx/', armor: 'textures/entity/equipment/humanoid/', armor_legs: 'textures/entity/equipment/humanoid_leggings/', wings: 'textures/entity/equipment/wings/', mob_effect: 'textures/mob_effect/' };
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
  rm('assets/' + NS + '/sounds/' + name + '.ogg');
  if (fs.existsSync(path.join(A, 'sounds', name + '.license.txt'))) rm('assets/' + NS + '/sounds/' + name + '.license.txt');
  const sj = path.join(A, 'sounds.json');
  const s = readJson(sj, {});
  for (const e of s[name]?.sounds || []) { const n = String(e.name || e).replace(NSP, ''); if (n !== name && !n.includes(':')) { rm('assets/' + NS + '/sounds/' + n + '.ogg'); rm('assets/' + NS + '/sounds/' + n + '.license.txt'); } } // --variants
  if (name in s) { delete s[name]; writeJson(sj, s); }
  removeLang(['subtitles.' + NS + '.' + name]);
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
const norm = s => s.toLowerCase().replace(/^[a-z0-9_.-]+:/, '').replace(/[^a-z0-9]/g, '');
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
/** Deletes a whole mod project: its packs, content (tags, recipes, lang, sounds), Java (unhooked), every file it owns, its project entry. */
async function removeMod(p) {
  const S = await import('./share.mjs'), P = await import('./projects.mjs');
  const o = S.owned(ROOT, p.id);
  const java = o.disk.some(f => f.endsWith('.java'));
  if (java) removeJava(p.id); // first: it refuses (exits) when core code still uses the mod, before anything is gone
  const pk = modPacks(p.id);
  if (pk.length) await removePacks(p.id, pk);
  for (const id of o.content) remove(id);
  const gone = new Set();
  for (const f of o.disk) if (fs.existsSync(f)) {
    fs.rmSync(f, { force: true }); console.log('  - ' + path.relative(ROOT, f));
    if (f.startsWith(RES)) { const b = path.join(ROOT, 'build/resources/main', path.relative(RES, f)); fs.rmSync(b, { force: true }); } // stale copy the game would still load
    gone.add(path.dirname(f));
  }
  for (const top of ['assets', 'data']) for (const d of [path.join(RES, top, o.ns), path.join(ROOT, 'build/resources/main', top, o.ns)]) // its namespace: nothing of it left
    if (o.ns && o.ns !== 'moddeus' && o.ns !== 'minecraft' && fs.existsSync(d)) { fs.rmSync(d, { recursive: true, force: true }); console.log('  deleted ' + path.relative(ROOT, d)); }
  for (const f of ['assets/moddeus/lang/en_us.json', 'assets/moddeus/sounds.json']) { // shared files: drop its keys
    const j = readJson(path.join(RES, f), null); if (!j) continue;
    const ks = Object.keys(j).filter(o.key); if (!ks.length) continue;
    for (const k of ks) delete j[k]; writeJson(path.join(RES, f), j); console.log('  ' + ks.length + ' keys out of ' + f);
  }
  for (let d of gone) while (d.startsWith(RES + path.sep) && fs.existsSync(d) && !fs.readdirSync(d).length) { fs.rmdirSync(d); d = path.dirname(d); }
  P.save(ROOT, P.projects(ROOT).filter(x => x.id !== p.id));
  console.log('deleted mod ' + p.name + ' (' + o.disk.length + ' files' + (pk.length ? ', ' + pk.length + ' packs' : '') + '; items stay registered, unused, until the next launch)');
  return java;
}
async function buildJava() {
  const b = await import('./build.mjs');
  if (!b.report(await b.build(ROOT))) process.exit(1);
}

function remove(id) {
  checkId(id);
  const c = readJson(path.join(CONTENT, id + '.json'), {});
  NS = c.namespace || 'moddeus'; NSP = NS + ':'; A = path.join(RES, 'assets', NS); // its mod's namespace
  for (const f of c.files || []) rm(f.replace(/^assets\/moddeus\//, 'assets/moddeus/'));
  for (const t of c.tags || []) tagEdit(t, NSP + id, false);
  for (const t of c.itemTags || []) tagEdit(t, NSP + id, false, 'item');
  rm('moddeus/content/' + id + '.json');
  for (const p of ['blockstates/' + id + '.json', 'models/block/' + id + '.json', 'models/item/' + id + '.json', 'items/' + id + '.json',
    'textures/block/' + id + '.png', 'textures/item/' + id + '.png']) rm('assets/' + NS + '/' + p);
  rm('data/' + NS + '/loot_table/blocks/' + id + '.json');
  rm('data/moddeus/loot_table/blocks/' + id + '.json'); // made before it got its namespace: a loot table of a missing block stops the world from loading
  { const a = A; A = path.join(RES, 'assets/moddeus'); removeLang(['block.moddeus.' + id, 'item.moddeus.' + id]); A = a; }
  removeLang(['block.' + NS + '.' + id, 'item.' + NS + '.' + id, 'entity.' + NS + '.' + id, 'item.' + NS + '.' + id + '_spawn_egg']);
  // recipes that make or use it would break the datapack reload
  const rdir = path.join(RES, 'data', NS, 'recipe');
  for (const f of fs.existsSync(rdir) ? fs.readdirSync(rdir) : [])
    if (fs.statSync(path.join(rdir, f)).isFile() && new RegExp('"' + NSP + id + '"').test(fs.readFileSync(path.join(rdir, f), 'utf8'))) rm('data/' + NS + '/recipe/' + f);
  for (const k of ['place', 'break', 'step', 'hit', 'fall', 'ambient', 'hold', 'hurt', 'death', 'attack']) removeSound(id + '_' + k); // block SoundType / mob / hold sounds made from files
  console.log('removed ' + NSP + id + ' (fully unregistered on next launch)');
}

const t0 = Date.now();
{ // one id = one content file: refuse to silently replace another kind (a boss spec once overwrote its own mob's JSON)
  const KIND = { block: 'block', item: 'item', mob: 'mob', vfx: 'vfx', physics: 'physics', boss: 'boss', setbonus: 'setbonus', drop: 'drop', npc: 'npc', effect: 'effect', potion: 'potion', enchant: 'enchant' };
  const want = KIND[cmd], id0 = pos && pos[0];
  if (want && id0 && !opt.force) {
    const f = path.join(CONTENT, id0 + '.json');
    if (fs.existsSync(f)) { let t; try { t = JSON.parse(fs.readFileSync(f, 'utf8')).type || 'block'; } catch {}
      if (t && t !== want && !(want === 'item' && t === 'block')) die(id0 + ' is already a ' + t + ' (moddeus/content/' + id0 + '.json): use another id (e.g. ' + id0 + '_' + want + ') or --force to replace it'); }
  }
}
switch (cmd) {
  case 'block': await block(pos[0]); await apply(); break;
  case 'item': await item(pos[0]); await apply(); break;
  case 'armor': await armor(pos[0]); await apply(); break;
  case 'mob': await mob(pos[0]); await apply(); if (opt.model) await (await import('./life.mjs')).afterMob(pos[0], NS); break;
  case 'particle': particle(pos[0]); await apply(); break;
  case 'vfx': await vfx(pos[0]); await apply(); break;
  case 'vfx-tex': { console.log("preset = sample only: write this mod's own version (guide: .claude/skills/mc-content/VFX-DESIGN.md)"); const n = checkId(pos[0]) || pos[0]; await vfxTex(pos[0], opt.shape || 'glow', { palette: opt.palette || 'fire', size: Number(opt.size || 32), frames: Number(opt.frames || 1),
    ...(opt.points ? { points: Number(opt.points) } : {}), ...(opt.seed ? { seed: Number(opt.seed) } : {}), ...(opt.thickness ? { thickness: Number(opt.thickness) } : {}), ...(opt.contrast ? { contrast: Number(opt.contrast) } : {}) });
    console.log('  texture -> textures/vfx/' + pos[0] + '.png'); await apply(); break; }
  case 'physics': physics(pos[0]); await apply(); break;
  case 'remove-recipe': pos.forEach(r => rm('data/moddeus/recipe/' + checkId(r) + '.json')); await apply(); break;
  case 'pixels': pixels(pos[0], pos[1]); await apply(); break;
  case 'template': template(pos[0]); break;
  case 'model': await (await import('./models.mjs')).cli(pos, opt, ROOT); break; // model shapes | model preview <id>
  case 'texture': await texture(pos[0], checkId(pos[1]), pos[2], Number(opt.size || 16)); await apply(); break;
  case 'sound': await sound(checkId(pos[0]), pos[1]); await apply(); break;
  case 'remove': { // ids, display names ("knockback stick") or Java features (SurvivalFlight); one build at the end
    if (!pos.length) die('usage: remove <id|name|JavaFeature> [...]');
    let java = false;
    const mods = (await import('./projects.mjs')).projects(ROOT);
    for (const a of pos) {
      const mod = mods.find(p => [p.id, p.name].some(s => s.toLowerCase() === a.toLowerCase())); // a whole mod (G screen Delete)
      if (mod) { if (await removeMod(mod)) java = true; continue; }
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
  case 'dimension': await (await import('./dimension.mjs')).run(rest); break;
  case 'struct': case 'terrain': case 'worldgen': await (await import('./structures.mjs')).run(cmd, rest); break; // builds, terrain, live worldgen (tools/structures.mjs) // datapack/java dimensions, loaded live (tools/dimension.mjs)
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
  case 'on': case 'off': await (await import('./modtoggle.mjs')).run(cmd, rest, ROOT); break; // on|off "<Mod>": like the G screen Mods On/Off (hooks, datapack, RP, tab, keys)
  case 'project': { // project name <chatKey> "<Name>"   (a New mod chat names itself)
    if (pos[0] !== 'name' || !pos[2]) die('usage: project name <projectId|chatKey> "<Name>"');
    const b = JSON.parse(fs.readFileSync(path.join(ROOT, 'run/moddeus/bridge.json'), 'utf8'));
    const r = await fetch(`http://127.0.0.1:${b.port}/cmd?t=${b.token}`, { method: 'POST', body: JSON.stringify({ type: 'project-name', id: pos[1], s: pos[1], name: pos.slice(2).join(' '), desc: opt.desc || '' }) });
    console.log(r.ok ? 'project named: classes go in ' + (await import('./projects.mjs')).slug(pos.slice(2).join(' ')) + '*.java' : 'bridge error ' + r.status); break;
  }
  case 'test': await (await import('./test.mjs')).run(rest); break;
  case 'export': { // export <Mod>: one <Name>.moddeus.zip in exports/ (the creator keeps all rights; README + manifest inside)
    const S = await import('./share.mjs'), b = await import('./build.mjs');
    let r; try { r = S.exportMod(ROOT, rest.join(' ')); } catch (e) { die(e.message); }
    console.log('Exported ' + r.files + ' files (' + r.kinds.join(', ') + ') to ' + r.file);
    // + a normal Fabric jar (works without Moddeus): fresh classes first
    if (!b.report(await b.build(ROOT))) die('build failed: jar not exported');
    let j; try { j = S.exportJar(ROOT, rest.join(' '), S.runtimeJar(ROOT)); } catch (e) { die('jar: ' + e.message); }
    console.log('Fabric jar (no Moddeus needed, just Fabric API): ' + j.file + ' (mod id ' + j.modid + ', ' + j.classes + ' classes, ' + j.files + ' files)');
    for (const w of j.warn) console.log('note: ' + w);
    break;
  }
  case 'import': { // import <file.moddeus.zip> [--force]: puts the mod in place, builds, reloads, switches it on
    const force = rest.includes('--force'), f = rest.filter(a => a !== '--force').join(' ');
    let r; try { r = (await import('./share.mjs')).importMod(ROOT, path.resolve(f), force); } catch (e) { die(e.message); }
    console.log('Imported ' + r.name + ': ' + r.files + ' files (' + r.kinds.join(', ') + ')');
    if (r.kinds.includes('java')) await buildJava(); else await apply();
    const G = await import('./game.mjs');
    if (r.datapacks.length) await G.run(['cmd', 'reload']); // /reload enables new datapack folders
    await G.run(['run', 'mc.execute(() -> dev.moddeus.client.Projects.setEnabled("' + r.id + '", true));']);
    try { const b = JSON.parse(fs.readFileSync(path.join(ROOT, 'run/moddeus/bridge.json'), 'utf8')); await fetch(`http://127.0.0.1:${b.port}/cmd?t=${b.token}`, { method: 'POST', body: JSON.stringify({ type: 'project-set', id: r.id, enabled: true }) }); } catch {}
    console.log(r.name + ' is on. Its chat: G > Mods > ' + r.name); break;
  }
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
  default: { // boss | setbonus | drop | npc live in tools/extra.mjs
    if (await (await import('./sounds.mjs')).run(cmd, rest, { ROOT, CONTENT, apply, die, NS, stamp: nsContent })) break; // synth | sound-test | sound-check | sound-hold (tools/sounds.mjs, SOUNDS.md)
    const X = await import('./extra.mjs');
    if (await X.run(cmd, rest, { ROOT, CONTENT, apply, die, NS, stamp: nsContent })) break;
    if (await (await import('./namespace.mjs')).run(cmd, rest, { ROOT, CONTENT, apply, die, NS, stamp: nsContent })) break; // namespace "<Mod>": move a mod to its own namespace (tools/namespace.mjs)
    if (await (await import('./bosses.mjs')).run(cmd, rest, { ROOT, CONTENT, apply, die, NS, stamp: nsContent })) break; // mobai | spawnrule | bossattacks (tools/bosses.mjs)
    if (await (await import('./life.mjs')).run(cmd, rest, { ROOT, CONTENT, A, png, apply, die, NS, stamp: nsContent })) break; // mobmodel | trade | profession (tools/life.mjs)
    if (await (await import('./combat.mjs')).run(cmd, rest)) break;
    if (await (await import('./food.mjs')).run(cmd, rest, { ROOT, CONTENT, apply, die, NS, stamp: nsContent })) break; // food | feast | cook (tools/food.mjs)
    const FX = await import('./effects.mjs'); // effect | potion | brew | enchant | attr | itemfx
    if (await FX.run(cmd, rest, { ROOT, CONTENT, apply, die, NS, stamp: nsContent })) break;
    if (await (await import('./world.mjs')).run(cmd, rest, { ROOT, CONTENT, apply, die, NS, stamp: nsContent })) break; // ambience | logic | banner | gamerule | command | ptex (tools/world.mjs)
    if (await (await import('./gear.mjs')).run(cmd, rest, { ROOT, CONTENT, apply, die, NS, stamp: nsContent })) break; // tools | worn | glider | totem | book | key | reskin | light | container | storage (tools/gear.mjs)
    const SY = await import('./systems.mjs'); // recipe | quest | disc | pad | crop | ore | machine | economy | shop | skill | hud | skyevent | secret
    if (await SY.run(cmd, rest, { ROOT, CONTENT, apply, die, NS, stamp: nsContent })) break;
    console.log(fs.readFileSync(fileURLToPath(import.meta.url), 'utf8').split('\n').filter(l => l.startsWith('//')).map(l => l.slice(3)).join('\n') + '\n' + X.HELP + '\n' + FX.HELP + '\n' + SY.HELP);
  }
}
if (cmd) console.log(`done in ${((Date.now() - t0) / 1000).toFixed(1)}s`);
