// Mod projects: each mod = a Java name prefix (content/<Id>*.java, client/content/<Id>*.java) + its own chats + on/off.
// run/moddeus/projects.json = [{ id, name, desc, enabled }]; the bridge owns writes, the game reads "enabled".
import fs from 'node:fs';
import path from 'node:path';

const CORE = /^(PhysicsMining|AvatarFeed|TalkScreen|ClaudeOrb|Collisions|Mounts|Mobs|ModTab|ModelBlock|Particles|Physics|PhysicsBlock|PhysicsRenderer|Vfx|VfxClient|ClientContent|MobRenderers?|ParticleClient|CustomParticle)\.java$/;
const file = root => path.join(root, 'run/moddeus/projects.json');

export function load(root) { try { return JSON.parse(fs.readFileSync(file(root), 'utf8')); } catch { return null; } }
export function save(root, list) { fs.mkdirSync(path.dirname(file(root)), { recursive: true }); fs.writeFileSync(file(root), JSON.stringify(list, null, 1)); }

/** Java class prefix from a spoken name: "portal gun" -> PortalGun. */
export function slug(name) { return String(name).replace(/[^A-Za-z0-9 ]+/g, ' ').trim().split(/\s+/).map(w => w[0].toUpperCase() + w.slice(1)).join('').replace(/^[0-9]+/, '') || 'Mod'; }
const words = s => String(s).replace(/([a-z])([A-Z])/g, '$1 $2').toLowerCase();

/** Projects list; first call seeds it from the existing Java features (one project per name prefix). */
export function projects(root) {
  let list = load(root);
  if (list) return list;
  const dirs = ['src/main/java/dev/moddeus/content', 'src/client/java/dev/moddeus/client/content'].map(d => path.join(root, d));
  const names = dirs.flatMap(d => fs.existsSync(d) ? fs.readdirSync(d) : []).filter(f => f.endsWith('.java') && !CORE.test(f)).map(f => f.slice(0, -5)).sort((a, b) => a.length - b.length);
  list = [];
  for (const n of names) if (!list.some(p => n.startsWith(p.id))) list.push({ id: n, name: words(n).replace(/^./, c => c.toUpperCase()), desc: '', enabled: true });
  save(root, list);
  return list;
}

export function add(root, name, desc = '', fresh = false) {
  const list = projects(root);
  let id = slug(name), i = 2;
  while (list.some(p => p.id === id)) id = slug(name) + i++;
  const p = { id, name: fresh ? 'New mod' + (i > 2 ? ' ' + (i - 1) : '') : String(name).trim(), desc, enabled: true, fresh: fresh || undefined };
  list.push(p); save(root, list);
  return p;
}

/** Names a project. A still-unnamed one ("New mod", no code yet) also gets a new id = class prefix. Returns { p, oldId }. */
export function rename(root, id, name, desc = '') {
  const list = projects(root), p = list.find(x => x.id === id);
  if (!p) return null;
  const oldId = p.id;
  if (p.fresh) {
    let nid = slug(name), i = 2;
    while (list.some(x => x !== p && x.id === nid)) nid = slug(name) + i++;
    p.id = nid; delete p.fresh;
  }
  p.name = String(name).trim();
  if (desc) p.desc = desc;
  save(root, list);
  return { p, oldId };
}

/** The project a request talks about ("make the portal gun shoot faster" -> PortalGun), or null. */
export function match(list, text) {
  const t = ' ' + words(text).replace(/[^a-z0-9]+/g, ' ') + ' ';
  let best = null, bl = 0;
  for (const p of list) for (const k of [p.name.toLowerCase(), words(p.id)]) {
    const kk = k.replace(/[^a-z0-9]+/g, ' ').trim();
    if (kk.length > 2 && t.includes(' ' + kk + ' ') && kk.length > bl) { best = p; bl = kk.length; }
  }
  return best;
}

/** A mod's namespace: snake_case of its name ("Abyssal Ascension" -> abyssal_ascension). Same rule in tools/mc.mjs and ContentLoader. */
export const nsOf = name => String(name).trim().replace(/([a-z0-9])([A-Z])/g, '$1_$2').toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '');

export function prompt(p, key) {
  if (p.fresh) return 'NEW MOD PROJECT (project id ' + p.id + ', not named yet). As soon as you know what the mod is, BEFORE writing code, name it: node tools/mc.mjs project name ' + p.id + ' "<Short Name>" (its namespace = snake_case of the name, e.g. "Portal Gun" -> portal_gun:<id>, used by mc commands given --tab "<Short Name>"; its Java classes then go in <ShortName>*.java, content/ or client/content/; packs in <ShortName>-DP / <ShortName>-RP). Datapack / resource pack mods: put them in run/saves/<world>/datapacks/<ShortName>-DP and run/resourcepacks/<ShortName>-RP (exact names: the G screen On/Off, Undo and Delete find them by that). A datapack needs data/<ns>/function/uninstall.mcfunction that removes everything it added (scoreboards, attributes, tags, entities, effects on players): Off runs it, then /datapack disable; On re-enables and its load runs again. After datapack edits run node tools/mc.mjs game cmd reload (that also saves an undo step). Other mods are other projects: do not change them from here.';
  return 'MOD PROJECT: this chat works on the mod "' + p.name + '" (project id ' + p.id + ')' + (p.desc ? ': ' + p.desc : '') +
    '. Its own namespace is ' + nsOf(p.name) + ' (never moddeus): give every mc content command --tab "' + p.name + '" (or ids as ' + nsOf(p.name) + ':<id>) and drop the mod prefix from ids (' + nsOf(p.name) + ':bow, not ' + nsOf(p.name) + ':' + nsOf(p.name).split('_')[0] + '_bow); assets/data live in assets/' + nsOf(p.name) + '/ and data/' + nsOf(p.name) + '/; Java looks ids up with Identifier.fromNamespaceAndPath("' + nsOf(p.name) + '", id).' +
    ' Name every Java class of this mod ' + p.id + '*.java (content/ or client/content/) so the project owns it: the G screen enables, disables, undoes and deletes it by that prefix. ' +
    'Datapack / resource pack mods: put them in run/saves/<world>/datapacks/' + p.id + '-DP and run/resourcepacks/' + p.id + '-RP (exact names: the G screen On/Off, Undo and Delete find them by that). A datapack needs data/<ns>/function/uninstall.mcfunction that removes everything it added (scoreboards, attributes, tags, entities, effects on players): Off runs it, then /datapack disable; On re-enables and its load runs again. After datapack edits run node tools/mc.mjs game cmd reload (that also saves an undo step). Other mods are other projects: do not change them from here.';
}
