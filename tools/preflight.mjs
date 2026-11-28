#!/usr/bin/env node
// Preflight: runs before every game launch (play.bat) and in `mc test`. A broken datapack file or a reference to an entry
// that only existed live makes the WHOLE world fail to load ("Errors in the currently selected data pack(s)" -> Safe Mode).
// This finds those before the game starts and repairs them: unknown required tag entries are dropped from the tag,
// unparsable JSON is moved to run/moddeus/quarantine/<time>/, jukebox songs get an inline sound. Report: run/moddeus/preflight.log.
// Usage: node tools/preflight.mjs [--check]   (--check = report only, exit 1 when something is broken)
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const CHECK = process.argv.includes('--check');
// dynamic registries (entries are data files loaded with the world), from the 26.3 vanilla data
const DYN = new Set(["banner_pattern","block_transformer","cat_sound_variant","cat_variant","chat_type","chicken_sound_variant","chicken_variant","context_float_provider","context_int_provider","cow_sound_variant","cow_variant","damage_type","decorated_pot_pattern","dialog","dimension_type","enchantment","enchantment_provider","frog_variant","instrument","jukebox_song","painting_variant","pig_sound_variant","pig_variant","sulfur_cube_archetype","timeline","trade_set","trial_spawner","trim_material","trim_pattern","villager_trade","wolf_sound_variant","wolf_variant","world_clock","worldgen/biome","worldgen/block_state_provider","worldgen/carver","worldgen/density_function","worldgen/feature","worldgen/flat_level_generator_preset","worldgen/material_condition","worldgen/material_rule","worldgen/multi_noise_biome_source_parameter_list","worldgen/noise","worldgen/noise_settings","worldgen/placed_feature","worldgen/processor_list","worldgen/structure","worldgen/structure_set","worldgen/template_pool","worldgen/world_preset","zombie_nautilus_variant"]);
const VANILLA_NS = new Set(['minecraft', 'c', 'fabric', 'neoforge', 'forge']);

// build/resources/main is what the game reads: a file deleted from src while the game was closed stays there (stale) and
// can break the world load. Delete stale copies first.
const stale = [];
{
  const src = path.join(ROOT, 'src/main/resources'), out = path.join(ROOT, 'build/resources/main');
  for (const sub of ['data', 'assets', 'moddeus']) for (const f of walk(path.join(out, sub))) {
    const r = path.relative(out, f);
    if (!fs.existsSync(path.join(src, r))) { stale.push(r.split(path.sep).join('/')); if (!CHECK) try { fs.rmSync(f); } catch {} }
  }
}
// Saved game rules a mod no longer defines ("moddeus:ztest_rule" after a test): the whole game_rules entry failed to parse on
// load. Keep minecraft:* and rules declared by content JSON (type gamerule); drop the rest (backup in run/moddeus/quarantine/).
const ruleFix = [];
try {
  const { read, write } = await import('./nbt.mjs');
  const known = new Set();
  for (const f of walk(path.join(ROOT, 'src/main/resources/moddeus/content'))) {
    try { const j = JSON.parse(fs.readFileSync(f, 'utf8')); if (j.type === 'gamerule') known.add((j.namespace || 'moddeus') + ':' + (j.name || path.basename(f, '.json').replace(/_gamerule$/, ''))); } catch {}
  }
  const saves = path.join(ROOT, 'run/saves');
  for (const w of fs.existsSync(saves) ? fs.readdirSync(saves) : []) {
    const f = path.join(saves, w, 'data/minecraft/game_rules.dat');
    if (!fs.existsSync(f)) continue;
    const nbt = read(fs.readFileSync(f));
    const data = nbt.root.v.get('data');
    if (!data || data.t !== 10) continue;
    const bad = [...data.v.keys()].filter(k => !k.startsWith('minecraft:') && !known.has(k));
    if (!bad.length) continue;
    ruleFix.push(w + ': ' + bad.join(', '));
    if (CHECK) continue;
    const q = path.join(ROOT, 'run/moddeus/quarantine', new Date().toISOString().replace(/[:.]/g, '-'));
    fs.mkdirSync(q, { recursive: true });
    fs.copyFileSync(f, path.join(q, w.replace(/[^a-zA-Z0-9_-]/g, '_') + '-game_rules.dat'));
    bad.forEach(k => data.v.delete(k));
    fs.writeFileSync(f, write(nbt));
  }
} catch (e) { ruleFix.push('game rule check failed: ' + e.message); }

// Fabric registry snapshot (<world>/data/fabric/registry_entries.dat) naming a DELETED mod's namespace makes Fabric stop
// quick play on its "Missing content detected" screen (no world, no API, input from outside doesn't reach it). If every
// missing namespace is gone from the project (no assets/data dir, no content JSON), move the snapshot to quarantine:
// Fabric writes a fresh one on the next save. A namespace that still exists (unfinished registration) is left alone.
const regFix = [];
try {
  const zlib = await import('node:zlib');
  const nsAlive = new Set(['moddeus', 'moddeus_keys', ...VANILLA_NS]);
  for (const d of ['src/main/resources/assets', 'src/main/resources/data']) for (const n of safeList(path.join(ROOT, d))) nsAlive.add(n);
  for (const f of walk(path.join(ROOT, 'src/main/resources/moddeus/content'))) try { nsAlive.add(JSON.parse(fs.readFileSync(f, 'utf8')).namespace || 'moddeus'); } catch {}
  for (const w of safeList(path.join(ROOT, 'run/saves'))) {
    const f = path.join(ROOT, 'run/saves', w, 'data/fabric/registry_entries.dat');
    if (!fs.existsSync(f)) continue;
    let b = fs.readFileSync(f); try { b = zlib.gunzipSync(b); } catch {}
    const ids = new Set(b.toString('latin1').match(/[a-z0-9_.-]+:[a-z0-9_\/.-]+/g) || []);
    const gone = [...new Set([...ids].map(i => i.split(':')[0]))].filter(n => !nsAlive.has(n));
    if (!gone.length) continue;
    regFix.push(w + ': ' + gone.join(', '));
    if (CHECK) continue;
    const q = path.join(ROOT, 'run/moddeus/quarantine', new Date().toISOString().replace(/[:.]/g, '-'));
    fs.mkdirSync(q, { recursive: true });
    fs.renameSync(f, path.join(q, w.replace(/[^a-zA-Z0-9_-]/g, '_') + '-registry_entries.dat'));
  }
} catch (e) { regFix.push('fabric registry snapshot check failed: ' + e.message); }
const roots = []; // [dataDir, mirrorDataDir|null]
roots.push([path.join(ROOT, 'src/main/resources/data'), path.join(ROOT, 'build/resources/main/data')]);
for (const w of safeList(path.join(ROOT, 'run/saves'))) for (const p of safeList(path.join(ROOT, 'run/saves', w, 'datapacks'))) {
  const d = path.join(ROOT, 'run/saves', w, 'datapacks', p, 'data');
  if (fs.existsSync(d)) roots.push([d, null]);
}

function safeList(d) { try { return fs.readdirSync(d); } catch { return []; } }
function walk(d, out = []) { for (const e of safeList(d)) { const p = path.join(d, e); try { if (fs.statSync(p).isDirectory()) walk(p, out); else out.push(p); } catch {} } return out; }
const rel = (root, p) => path.relative(root, p).split(path.sep).join('/');

// everything that exists: data entries per registry, tags, content ids, ids written in Java
const entries = new Set(); // "<registry>|<ns>:<path>"
const tags = new Set();    // "<registry>|<ns>:<path>"
const files = [];          // [root, mirror, file, relPath]
for (const [root, mirror] of roots) for (const f of walk(root)) {
  if (!f.endsWith('.json')) continue;
  const r = rel(root, f); const [ns, ...rest] = r.split('/');
  files.push([root, mirror, f, r]);
  const p = rest.join('/').replace(/\.json$/, '');
  if (p.startsWith('tags/')) { const t = p.slice(5); for (const reg of regOf(t)) tags.add(reg[0] + '|' + ns + ':' + reg[1]); }
  else for (let i = 1; i < rest.length; i++) entries.add(rest.slice(0, i).join('/') + '|' + ns + ':' + rest.slice(i).join('/').replace(/\.json$/, ''));
}
const contentIds = new Set();
for (const f of walk(path.join(ROOT, 'src/main/resources/moddeus/content'))) {
  if (!f.endsWith('.json')) continue;
  let ns = 'moddeus'; try { ns = JSON.parse(fs.readFileSync(f, 'utf8')).namespace || ns; } catch {}
  contentIds.add(ns + ':' + path.basename(f, '.json'));
}
let javaText = '';
for (const f of walk(path.join(ROOT, 'src'))) if (f.endsWith('.java')) try { javaText += fs.readFileSync(f, 'utf8'); } catch {}

/** all (registry, tagPath) splits of a tag file path; the registry is a known dir prefix */
function regOf(t) {
  const parts = t.split('/'); const out = [];
  for (let i = parts.length - 1; i >= 1; i--) out.push([parts.slice(0, i).join('/'), parts.slice(i).join('/')]);
  return out;
}
function tagRegistry(t) { // longest prefix that is a dynamic registry, else the first segment (static registry: item, block...)
  for (const [reg, tp] of regOf(t)) if (DYN.has(reg)) return [reg, tp, true];
  const s = t.split('/'); return [s[0], s.slice(1).join('/'), false];
}
function exists(reg, dynamic, id, isTag) {
  const [ns, p] = id.includes(':') ? id.split(':') : ['minecraft', id];
  if (VANILLA_NS.has(ns)) return true; // vanilla/other loaders: not ours to judge
  if (isTag) return tags.has(reg + '|' + ns + ':' + p);
  if (dynamic) return entries.has(reg + '|' + ns + ':' + p);
  return contentIds.has(ns + ':' + p) || javaText.includes('"' + p + '"') || javaText.includes('"' + ns + ':' + p + '"');
}

const log = []; let broken = 0;
const stamp = new Date().toISOString().replace(/[:.]/g, '-');
function put(root, mirror, f, r, text) {
  if (CHECK) return;
  fs.writeFileSync(f, text);
  if (mirror) { const m = path.join(mirror, r); if (fs.existsSync(path.dirname(m))) fs.writeFileSync(m, text); }
}
function quarantine(root, mirror, f, r) {
  if (CHECK) return;
  const q = path.join(ROOT, 'run/moddeus/quarantine', stamp, r);
  fs.mkdirSync(path.dirname(q), { recursive: true });
  fs.renameSync(f, q);
  if (mirror) try { fs.rmSync(path.join(mirror, r)); } catch {}
}

for (const [root, mirror, f, r] of files) {
  let j;
  try { j = JSON.parse(fs.readFileSync(f, 'utf8')); }
  catch (e) { broken++; log.push('bad JSON (quarantined): ' + r + ' ' + e.message); quarantine(root, mirror, f, r); continue; }
  const [ns, ...rest] = r.split('/'); const p = rest.join('/');
  if (p.startsWith('tags/') && Array.isArray(j.values)) {
    const [reg, , dynamic] = tagRegistry(p.slice(5).replace(/\.json$/, ''));
    const keep = j.values.filter(v => {
      if (v && typeof v === 'object' && v.required === false) return true;
      const id = typeof v === 'string' ? v : v && v.id;
      if (typeof id !== 'string') return true;
      const ok = id.startsWith('#') ? exists(reg, dynamic, id.slice(1), true) : exists(reg, dynamic, id, false);
      if (!ok) log.push('tag ' + r + ': dropped unknown ' + reg + ' entry ' + id);
      return ok;
    });
    if (keep.length !== j.values.length) { broken++; j.values = keep; put(root, mirror, f, r, JSON.stringify(j, null, 2) + '\n'); }
  }
  // loot tables fail the WHOLE world load when they name a missing item/block (recipes/advancements just log): a file whose
  // item/block refs in our namespaces point at nothing belongs to removed content -> quarantine it.
  if (/^(loot_table|recipe|advancement)\//.test(p)) {
    const bad = [];
    (function scan(v, key) {
      if (typeof v === 'string') {
        if (/^(name|item|id|result|base|addition|template|ingredient)$/.test(key) && /^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(v)) {
          const [vns] = v.split(':');
          if (!VANILLA_NS.has(vns) && !exists('item', false, v, false) && !entries.has('loot_table|' + v) && !entries.has('recipe|' + v) && !entries.has('advancement|' + v)) bad.push(v);
        }
      } else if (Array.isArray(v)) v.forEach(x => scan(x, key));
      else if (v && typeof v === 'object') for (const [k, x] of Object.entries(v)) scan(x, k);
    })(j, '');
    if (bad.length) { broken++; log.push(p.split('/')[0] + ' ' + r + ' (quarantined): names missing ' + [...new Set(bad)].join(', ')); quarantine(root, mirror, f, r); continue; }
  }
  if (p.startsWith('jukebox_song/') && typeof j.sound_event === 'string' && !j.sound_event.startsWith('minecraft:')) {
    broken++; log.push('jukebox song ' + r + ': sound_event made inline (a live-registered sound does not exist at world load)');
    j.sound_event = { sound_id: j.sound_event }; put(root, mirror, f, r, JSON.stringify(j, null, 2) + '\n');
  }
}

// Asset refs (per-mod namespaces): content JSON files/item_model/mob skins, items/blockstates -> models, models -> parent +
// textures, equipment layers, particles. Only our namespaces (assets/<ns>/ in src + run/resourcepacks/*-RP) are checked.
// A ref missing in its namespace but present at the same path in another of ours (written under the wrong namespace) is
// copied into place (repair mode); anything else is reported. mc test refs runs this with --check.
let assetRefs = 0;
{
  const RES = path.join(ROOT, 'src/main/resources'), ASRC = path.join(RES, 'assets');
  const aroots = [ASRC, ...safeList(path.join(ROOT, 'run/resourcepacks')).map(p => path.join(ROOT, 'run/resourcepacks', p, 'assets'))];
  const ours = new Set(aroots.flatMap(safeList).filter(n => !VANILLA_NS.has(n)));
  const has = r => aroots.some(a => fs.existsSync(path.join(a, r))); // r = "<ns>/<path>"
  const missing = new Map(); // "<ns>/<path>" -> where it was referenced
  const need = (r, from) => { assetRefs++; const ns = r.split('/')[0]; if (ours.has(ns) && !has(r) && !missing.has(r)) missing.set(r, from); };
  const split = (id, ns0) => { const i = id.indexOf(':'); return i < 0 ? [ns0 || 'minecraft', id] : [id.slice(0, i), id.slice(i + 1)]; };
  const model = (id, from, ns0) => { if (typeof id !== 'string' || id.startsWith('builtin/')) return; const [n, p] = split(id, ns0); need(n + '/models/' + p + '.json', from); };
  const tex = (id, from, ns0) => { if (typeof id !== 'string' || id.startsWith('#')) return; const [n, p] = split(id, ns0); need(n + '/textures/' + p + '.png', from); };
  const readJ = f => { try { return JSON.parse(fs.readFileSync(f, 'utf8')); } catch { return null; } };
  const modelRefs = (v, from, k) => { if (typeof v === 'string') { if (k === 'model') model(v, from); } else if (Array.isArray(v)) v.forEach(x => modelRefs(x, from, k)); else if (v && typeof v === 'object') for (const [kk, x] of Object.entries(v)) modelRefs(x, from, kk); };
  for (const a of aroots) for (const n of safeList(a).filter(n => ours.has(n))) {
    const base = path.join(a, n);
    for (const f of walk(base)) {
      const r = rel(base, f), from = n + '/' + r;
      if (!r.endsWith('.json')) continue;
      const j = readJ(f); if (!j) continue;
      if (r.startsWith('items/') || r.startsWith('blockstates/')) modelRefs(j, from, '');
      else if (r.startsWith('models/')) { model(j.parent, from); for (const t of Object.values(j.textures || {})) tex(t, from); }
      else if (r.startsWith('equipment/')) for (const [layer, list] of Object.entries(j.layers || {})) for (const l of [].concat(list)) if (l && typeof l.texture === 'string') { const [tn, tp] = split(l.texture); need(tn + '/textures/entity/equipment/' + layer + '/' + tp + '.png', from); }
      else if (r.startsWith('particles/')) for (const t of j.textures || []) { const [tn, tp] = split(String(t)); need(tn + '/textures/particle/' + tp + '.png', from); }
    }
  }
  const CONTENT_DIR = path.join(RES, 'moddeus/content');
  for (const f of safeList(CONTENT_DIR).filter(f => f.endsWith('.json'))) {
    const c = readJ(path.join(CONTENT_DIR, f)); if (!c) continue;
    const id = f.slice(0, -5), ns = c.namespace || 'moddeus', from = 'content ' + id;
    for (const r of c.files || []) if (typeof r === 'string' && r.startsWith('assets/')) need(r.slice(7), from);
    const im = c.components && c.components['minecraft:item_model']; if (typeof im === 'string') { const [n, p] = split(im); need(n + '/items/' + p + '.json', from); }
    if (c.type === 'mob' && c.model) { need(ns + '/textures/entity/' + id + '.png', from); if (c.model.glow) need(ns + '/textures/entity/' + id + '_glow.png', from); }
  }
  for (const [r, from] of missing) {
    const rest = r.slice(r.indexOf('/') + 1), src = [...ours].map(o => path.join(ASRC, o, rest)).find(p => fs.existsSync(p));
    broken++;
    if (src && !CHECK) { fs.mkdirSync(path.dirname(path.join(ASRC, r)), { recursive: true }); fs.copyFileSync(src, path.join(ASRC, r)); log.push('asset ' + r + ' (from ' + from + '): copied from ' + rel(ASRC, src)); }
    else log.push('asset ' + r + ' missing (referenced by ' + from + ')' + (src ? '; same path exists as ' + rel(ASRC, src) + ' (wrong namespace)' : ''));
  }
}

if (regFix.length) { broken++; log.push((CHECK ? 'Fabric registry snapshot names deleted mods: ' : 'moved stale Fabric registry snapshot (deleted mods) to quarantine: ') + regFix.join('; ')); }
if (ruleFix.length) { broken++; log.push((CHECK ? 'unknown saved game rules: ' : 'removed unknown saved game rules: ') + ruleFix.join('; ')); }
if (stale.length) { broken++; log.push((CHECK ? 'stale build copies: ' : 'removed stale build copies: ') + stale.slice(0, 8).join(', ') + (stale.length > 8 ? ' (+' + (stale.length - 8) + ')' : '')); }
const head = (CHECK ? 'preflight check: ' : 'preflight: ') + (broken ? broken + ' problem(s)' + (CHECK ? '' : ' repaired') : 'all ' + files.length + ' data files + ' + assetRefs + ' asset refs OK');
try { fs.mkdirSync(path.join(ROOT, 'run/moddeus'), { recursive: true }); fs.appendFileSync(path.join(ROOT, 'run/moddeus/preflight.log'), new Date().toISOString() + ' ' + head + '\n' + log.map(l => '  ' + l + '\n').join('')); } catch {}
console.log(head); for (const l of log) console.log('  ' + l);
if (CHECK && broken) process.exit(1);
