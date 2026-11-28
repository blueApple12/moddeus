// Sounds toolbox (guide: .claude/skills/mc-content/SOUNDS.md). Called from tools/mc.mjs.
//   synth <name> --preset whoosh|impact|zap|boom|hum|drone|chime|charge|crackle|wind|rumble|roar|step|pop|shimmer|heartbeat[+other...]
//        [--variants 3] [--pitch-shift 1.0 (x base pitch)] [--seconds S] [--seed N] [+ any mc sound flag: --subtitle --attenuation --level ...]
//        procedural SFX with ffmpeg (no download needed): N jittered variants -> one <ns>:<name> event. Loops (hum drone crackle wind) are seamless.
//   sound-test <ns:id> [--here] [--volume 1 --pitch 1 --category master]   client check: variants/files/stream/subtitle + a REAL play
//        (default: 200 blocks east of the player = started but inaudible; --here = at the player, audible)
//   sound-check [ns]   audit every sounds.json: missing files, stereo-but-positional, long non-stream, one-variant one-shots, missing subtitles, orphans
//   sound-hold <item> SRC|id [--volume 0.6 --pitch 1]   loop while the item is held (fades in/out; content "hold_sound")
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { execFileSync, spawnSync } from 'node:child_process';

export const HELP = fs.readFileSync(new URL(import.meta.url), 'utf8').split('\n').filter(l => l.startsWith('//')).slice(1).map(l => l.slice(3)).join('\n');
const CMDS = ['synth', 'sound-test', 'sound-check', 'sound-hold'];
let ROOT, RES, NS, die;

function parse(rest) {
  const pos = [], opt = {};
  for (let i = 0; i < rest.length; i++) {
    const a = rest[i];
    if (a.startsWith('--')) { const v = rest[i + 1]; if (v === undefined || v.startsWith('--')) opt[a.slice(2)] = true; else { opt[a.slice(2)] = v; i++; } }
    else pos.push(a);
  }
  return { pos, opt };
}
const mc = (args, quiet) => { const r = spawnSync(process.execPath, [path.join(ROOT, 'tools/mc.mjs'), ...args], { encoding: 'utf8', cwd: ROOT }); if (!quiet) process.stdout.write(r.stdout || ''); if (r.status) die((r.stderr || r.stdout || '').trim()); return r.stdout || ''; };
const game = java => mc(['game', 'run', java], true).replace(/\ndone in .*$/s, '').trim();
const isRef = v => /^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(String(v)) && !/^[a-z]:[\\/]/i.test(String(v)) && !/^https?:/.test(String(v));

export async function run(cmd, rest, ctx) {
  if (!CMDS.includes(cmd)) return false;
  ({ ROOT, NS, die } = ctx); RES = path.join(ROOT, 'src/main/resources');
  const { pos, opt } = parse(rest);
  switch (cmd) {
    case 'synth': await synth(pos[0], opt, rest); await ctx.apply(); break;
    case 'sound-test': test(pos[0], opt); break;
    case 'sound-check': check(pos[0]); break;
    case 'sound-hold': hold(pos[0], pos[1], opt, rest); await ctx.apply(); break;
  }
  return true;
}

// ---- procedural synthesis ------------------------------------------------------------------------------------
// Each preset = layers {src: lavfi source, af: filters}; d = seconds, p = pitch factor. Loops use whole cycles per d (seamless).
const cyc = (f, d) => Math.max(1, Math.round(f * d)) / d;
const ev = (e, d) => ({ src: `aevalsrc=exprs='${e}':s=44100:d=${d}` });
const noise = (c, d, seed, amp = 0.8) => `anoisesrc=d=${d}:c=${c}:seed=${seed}:a=${amp}:r=44100:n=256`; // n=256: volume eval=frame envelopes step every 6 ms, not 23
const PRESETS = {
  whoosh: { d: 0.7, make: (d, p, s) => [{ src: noise('pink', d, s), af: `bandpass=f=${Math.round(900 * p)}:width_type=o:w=1.6,volume='pow(sin(PI*t/${d}),3)':eval=frame,aphaser=type=t:speed=${(1.2 / d).toFixed(2)}:decay=0.5,highpass=f=180` }] },
  impact: { d: 0.6, make: (d, p, s) => [ev(`exp(-t*9)*sin(2*PI*t*(${55 * p}+${140 * p}*exp(-t*25)))`, d), { src: noise('white', 0.12, s, 0.6), af: `volume='exp(-t*45)':eval=frame,lowpass=f=${Math.round(3500 * p)}` }] },
  zap: { d: 0.5, make: (d, p) => [ev(`exp(-t*7)*(0.7*sin(2*PI*(${300 * p}*t+${(2600 * p / 12).toFixed(1)}*(1-exp(-t*12))))+0.25*sgn(sin(2*PI*(${150 * p}*t+${(1300 * p / 12).toFixed(1)}*(1-exp(-t*12))))))`, d), { af: 'highpass=f=200,aecho=0.6:0.4:35:0.25' }] },
  boom: { d: 2.2, make: (d, p, s) => [{ src: noise('brown', d, s, 1), af: `lowpass=f=${Math.round(600 * p)},volume='exp(-t*2.4)*min(1,t*80)':eval=frame` }, ev(`exp(-t*5)*sin(2*PI*t*(${38 * p}+${90 * p}*exp(-t*12)))`, d)] },
  hum: { d: 4, loop: true, make: (d, p) => { const f = cyc(62 * p, d); return [ev(`(0.5*sin(2*PI*${f}*t)+0.25*sin(2*PI*${2 * f}*t)+0.12*sin(2*PI*${3 * f}*t))*(0.85+0.15*sin(2*PI*${cyc(0.5, d)}*t))`, d)]; } },
  drone: { d: 8, loop: true, make: (d, p, s) => { const f = cyc(44 * p, d); return [ev(`0.4*sin(2*PI*${f}*t)+0.25*sin(2*PI*${cyc(f * 1.5, d)}*t)*(0.6+0.4*sin(2*PI*${cyc(0.25, d)}*t))+0.18*sin(2*PI*${cyc(f * 2.01, d)}*t)`, d), { src: noise('brown', d, s, 0.25), af: 'lowpass=f=220' }]; } },
  chime: { d: 2, make: (d, p) => [ev(`0.5*exp(-t*2.5)*sin(2*PI*${880 * p}*t)+0.3*exp(-t*4)*sin(2*PI*${2428 * p}*t)+0.15*exp(-t*7)*sin(2*PI*${4752 * p}*t)`, d), { af: 'aecho=0.8:0.6:90:0.3' }] },
  charge: { d: 1.5, make: (d, p) => [ev(`(t/${d})*sin(2*PI*(${180 * p}*t+${(900 * p / (2 * d)).toFixed(1)}*t*t))*(0.7+0.3*sin(2*PI*t*(4+14*t/${d})))`, d), { af: 'highpass=f=120' }] },
  crackle: { d: 4, loop: true, make: (d, p, s) => [ev(`if(lt(random(0),0.0016),2*random(1)-1,0)`, d), { af: `lowpass=f=${Math.round(4500 * p)}` }, { src: noise('brown', d, s, 0.3), af: 'lowpass=f=300' }] },
  wind: { d: 8, loop: true, make: (d, p, s) => [{ src: noise('pink', d, s, 0.9), af: `bandpass=f=${Math.round(420 * p)}:width_type=o:w=2,volume='0.55+0.45*sin(2*PI*${cyc(0.25, d)}*t)':eval=frame` }] },
  rumble: { d: 3, make: (d, p, s) => [{ src: noise('brown', d, s, 1), af: `lowpass=f=${Math.round(130 * p)},volume='pow(sin(PI*t/${d}),2)':eval=frame` }] },
  roar: { d: 1.6, make: (d, p, s) => [ev(`(0.6*sin(2*PI*${75 * p}*t+3*sin(2*PI*${31 * p}*t))+0.35*sin(2*PI*${150 * p}*t+2*sin(2*PI*${47 * p}*t)))*min(1,t*6)*exp(-pow(max(0,t-${(d * 0.45).toFixed(2)}),2)*5)`, d), { src: noise('pink', d, s, 0.5), af: `lowpass=f=${Math.round(900 * p)},volume='min(1,t*6)*exp(-pow(max(0,t-${(d * 0.45).toFixed(2)}),2)*5)':eval=frame` }] },
  step: { d: 0.14, make: (d, p, s) => [{ src: noise('white', d, s, 0.9), af: `volume='exp(-t*55)':eval=frame,lowpass=f=${Math.round(1800 * p)},highpass=f=120` }] },
  pop: { d: 0.15, make: (d, p) => [ev(`exp(-t*40)*sin(2*PI*t*(${700 * p}+${600 * p}*exp(-t*60)))`, d)] },
  shimmer: { d: 1.2, make: (d, p) => [ev(`exp(-t*4)*(0.4*sin(2*PI*${2200 * p}*t)+0.3*sin(2*PI*${3310 * p}*t)+0.2*sin(2*PI*${4420 * p}*t))*(0.5+0.5*sin(2*PI*17*t))`, d), { af: 'aecho=0.7:0.5:60:0.3' }] },
  heartbeat: { d: 0.9, make: (d, p) => [ev(`exp(-t*14)*sin(2*PI*${50 * p}*t)+gte(t,0.28)*0.7*exp(-(t-0.28)*14)*sin(2*PI*${45 * p}*(t-0.28))`, d)] },
};

function rnd(seed) { let x = seed >>> 0 || 1; return () => ((x = (x * 1103515245 + 12345) >>> 0) / 4294967296); }
const hash = s => [...s].reduce((h, c) => (h * 31 + c.charCodeAt(0)) >>> 0, 7);

async function synth(name, opt, rest) {
  if (!/^[a-z0-9_]+$/.test(name || '')) die('usage: synth <name> --preset ' + Object.keys(PRESETS).join('|') + '[+...] [--variants 3]');
  const names = String(opt.preset || '').split('+').filter(Boolean);
  if (!names.length || names.some(n => !PRESETS[n])) die('--preset ' + Object.keys(PRESETS).join('|') + ' (mix with +, e.g. impact+boom)');
  const loop = names.some(n => PRESETS[n].loop), count = loop ? 1 : Math.max(1, Math.min(8, Number(opt.variants || 3)));
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mc-synth-')), files = [];
  const R = rnd(Number(opt.seed || hash(name)));
  for (let v = 0; v < count; v++) {
    const jp = v ? 1 + (R() - 0.5) * 0.16 : 1, jd = v && !loop ? 1 + (R() - 0.5) * 0.15 : 1; // each variant: other pitch/length/noise
    const p = Number(opt['pitch-shift'] || 1) * jp, seed = Math.floor(R() * 1e6);
    const layers = names.flatMap(n => { const d = +(Number(opt.seconds || PRESETS[n].d) * jd).toFixed(3); return PRESETS[n].make(d, p, seed).reduce((acc, l) => { if (l.src) acc.push({ ...l, d }); else acc[acc.length - 1].af = [acc[acc.length - 1].af, l.af].filter(Boolean).join(','); return acc; }, []); });
    // loops: noise is not periodic, so its end != its start (click at every loop point). Render X s more and equal-power crossfade that tail
    // into the head: the last sample then flows into the first. Tones already use whole cycles per file.
    const xf = l => loop && l.src.startsWith('anoisesrc') ? Math.min(0.3, l.d / 4) : 0;
    layers.forEach(l => { if (xf(l)) l.src = l.src.replace(/^anoisesrc=d=[\d.]+/, 'anoisesrc=d=' + +(l.d + xf(l)).toFixed(3)); });
    const out = path.join(dir, name + '_' + v + '.wav');
    const args = ['-y', '-loglevel', 'error'];
    layers.forEach(l => args.push('-f', 'lavfi', '-i', l.src));
    const fc = layers.map((l, i) => !xf(l) ? `[${i}:a]${l.af || 'anull'}[l${i}]` : `[${i}:a]${l.af || 'anull'},asplit[h${i}][t${i}];[h${i}]atrim=0:${l.d},afade=t=in:d=${xf(l)}:curve=qsin[hh${i}];` +
      `[t${i}]atrim=start=${l.d},asetpts=PTS-STARTPTS,afade=t=out:d=${xf(l)}:curve=qsin[tt${i}];[hh${i}][tt${i}]amix=inputs=2:normalize=0:duration=first[l${i}]`).join(';') + ';' + layers.map((_, i) => `[l${i}]`).join('') + `amix=inputs=${layers.length}:normalize=0:duration=longest[o]`;
    args.push('-filter_complex', fc, '-map', '[o]', '-ac', '1', '-ar', '44100', out);
    try { execFileSync('ffmpeg', args, { stdio: ['ignore', 'pipe', 'pipe'] }); } catch (e) { die('ffmpeg synth failed: ' + String(e.stderr || e.message).slice(0, 600)); }
    files.push(out);
  }
  // pass every other flag through to mc sound (subtitle, attenuation, level, ns/tab, volume...)
  const pass = [];
  for (let i = 0; i < rest.length; i++) if (rest[i].startsWith('--') && !['--preset', '--variants', '--pitch-shift', '--seconds', '--seed'].includes(rest[i])) { pass.push(rest[i]); if (rest[i + 1] !== undefined && !rest[i + 1].startsWith('--')) pass.push(rest[++i]); } else if (['--preset', '--variants', '--pitch-shift', '--seconds', '--seed'].includes(rest[i]) && rest[i + 1] && !rest[i + 1].startsWith('--')) i++;
  const nsArg = NS !== 'moddeus' && !pass.includes('--ns') ? ['--ns', NS] : [];
  mc(['sound', name, files[0], ...(files.length > 1 ? ['--variants', files.slice(1).join(',')] : []), ...(loop && !pass.includes('--loop') ? ['--loop'] : []),
    ...(opt.subtitle ? [] : ['--subtitle', name.split('_').filter(Boolean).map(w => w[0].toUpperCase() + w.slice(1)).join(' ')]), ...nsArg, ...pass, '--no-apply']);
  fs.rmSync(dir, { recursive: true, force: true });
  console.log('synth ' + NS + ':' + name + ' = ' + names.join('+') + ' x' + count + (loop ? ' (seamless loop: SoundLoops.follow(entity, "' + NS + ':' + name + '", "' + name + '", 1f, 1f, 10))' : ''));
  console.log("preset = sample only: write this mod's own version (guide: .claude/skills/mc-content/SOUNDS.md)");
}

// ---- in-game test ------------------------------------------------------------------------------------------
function test(id, opt) {
  if (!id) die('usage: sound-test <ns:id> [--here]');
  if (!id.includes(':')) id = NS + ':' + id;
  console.log(game('print(dev.moddeus.client.SoundProbe.info("' + id + '"));'));
  const where = opt.here ? 'p.getX(), p.getY(), p.getZ()' : 'p.getX() + 200, p.getY(), p.getZ()';
  // far test at volume 0.001 (-60 dB) unless --volume: STEREO files ignore distance in OpenAL and would play in the player's ears at 200 blocks
  game('var p = mc.player; dev.moddeus.client.SoundProbe.play("' + id + '", ' + where + ', ' + Number(opt.volume || (opt.here ? 1 : 0.001)) + 'f, ' + Number(opt.pitch || 1) + 'f, "' + (opt.category || 'master') + '");');
  spawnSync(process.execPath, ['-e', 'setTimeout(()=>{},400)']);
  const r = game('print(dev.moddeus.client.SoundProbe.result());');
  console.log(r + (opt.here ? '' : '  (played 200 blocks away: started, inaudible to the player)'));
  if (!/play=STARTED/.test(r)) process.exitCode = 1;
}

// ---- audit -------------------------------------------------------------------------------------------------
function check(only) {
  const adir = path.join(RES, 'assets');
  let issues = 0, events = 0;
  const cdir = path.join(RES, 'moddeus/content'), loops = new Set(); // hold_sound loops are one seamless file on purpose
  for (const f of fs.existsSync(cdir) ? fs.readdirSync(cdir).filter(f => f.endsWith('.json')) : []) { try { const s = JSON.parse(fs.readFileSync(path.join(cdir, f), 'utf8')).hold_sound?.sound; if (s) loops.add(s); } catch {} }
  for (const ns of fs.readdirSync(adir).filter(n => (!only || n === only) && fs.existsSync(path.join(adir, n, 'sounds.json')))) {
    const sj = JSON.parse(fs.readFileSync(path.join(adir, ns, 'sounds.json'), 'utf8'));
    const lang = (() => { try { return JSON.parse(fs.readFileSync(path.join(adir, ns, 'lang/en_us.json'), 'utf8')); } catch { return {}; } })();
    const used = new Set();
    for (const [ev, v] of Object.entries(sj)) {
      events++;
      const bad = [];
      if (v.subtitle && !lang[v.subtitle]) bad.push('subtitle key ' + v.subtitle + ' not in lang');
      if (!v.subtitle && !(v.sounds || []).every(e => e.stream)) bad.push('no subtitle'); // streamed music/beds: vanilla gives none
      const files = (v.sounds || []).filter(e => (e.type || 'file') === 'file').map(e => typeof e === 'string' ? { name: e } : e);
      const oneShot = files.length === 1 && !files[0].stream && !files[0].pitch && !(v.sounds || []).some(e => e.type === 'event') && !loops.has(ns + ':' + ev) && !/_(hold|loop)$/.test(ev);
      for (const e of files) {
        const [fns, fp] = e.name.includes(':') ? e.name.split(':') : ['minecraft', e.name];
        const f = path.join(adir, fns, 'sounds', fp + '.ogg');
        used.add(f);
        if (!fs.existsSync(f)) { if (fns === ns) bad.push('MISSING ' + fns + ':sounds/' + fp + '.ogg'); continue; }
        const p = spawnSync('ffprobe', ['-v', 'error', '-show_entries', 'stream=channels:format=duration', '-of', 'csv=p=0', f], { encoding: 'utf8' }).stdout.split(/\s+/).filter(Boolean);
        const ch = Number(p[0]), dur = Number(p[1]);
        if (ch > 1 && !e.stream) bad.push(fp + ': stereo but not stream (no 3D position: use mono)');
        if (oneShot && dur < 3) bad.push('1 variant of a short one-shot: add --variants or --pitch-set 0.9,1,1.1');
        if (dur > 12 && !e.stream) bad.push(fp + ': ' + dur.toFixed(0) + ' s not streamed (all decoded into RAM: --stream)');
      }
      if (bad.length) { issues += bad.length; console.log(ns + ':' + ev + '\n  - ' + bad.join('\n  - ')); }
    }
    const sd = path.join(adir, ns, 'sounds');
    const walk = d => fs.existsSync(d) ? fs.readdirSync(d, { withFileTypes: true }).flatMap(x => x.isDirectory() ? walk(path.join(d, x.name)) : [path.join(d, x.name)]) : [];
    for (const f of walk(sd).filter(f => f.endsWith('.ogg') && !used.has(f))) { issues++; console.log(ns + ': orphan ' + path.relative(adir, f).replace(/\\/g, '/') + ' (no sounds.json entry)'); }
  }
  console.log(events + ' events checked, ' + issues + ' issues');
}

// ---- held-item loop ----------------------------------------------------------------------------------------
function hold(item, src, opt, rest) {
  if (!item || !src) die('usage: sound-hold <item> <SRC|ns:id> [--volume 0.6 --pitch 1]');
  const f = path.join(RES, 'moddeus/content', item + '.json');
  if (!fs.existsSync(f)) die('no content ' + item + ' (moddeus/content/' + item + '.json)');
  const c = JSON.parse(fs.readFileSync(f, 'utf8'));
  if (/^[a-z0-9_]+$/.test(src) && !fs.existsSync(src)) src = NS + ':' + src; // mc.mjs strips <ns>: off positionals (namespace detection)
  let id = src;
  if (!isRef(src)) { mc(['sound', item + '_hold', src, '--loop', '--level', 'quiet', ...(NS !== 'moddeus' ? ['--ns', NS] : []), '--no-apply']); id = (c.namespace || NS) + ':' + item + '_hold'; }
  c.hold_sound = { sound: id, volume: Number(opt.volume || 0.6), pitch: Number(opt.pitch || 1) };
  fs.writeFileSync(f, JSON.stringify(c, null, 2) + '\n');
  console.log('hold sound ' + id + ' on ' + item + ': loops while held in either hand, fades out when put away');
}
