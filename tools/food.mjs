// Food family: foods, drinks, soups, golden foods, feasts/pies/cakes, cooking recipes (live, exportable). Dispatched from tools/mc.mjs.
// Runtime: vanilla food/consumable components + live/Food.java (feast blocks) + client/FoodTooltip.java (hunger/saturation/effects tooltip). Guide: .claude/skills/mc-content/FOOD.md
//   food <item> [--like snack|fruit|bread|meat|fish|raw|poison|fast|soup|meal|drink|honey|milk|golden|enchanted|chorus] [--name ..] [--texture <src> | --color "#c33"]
//        [--nutrition 6] [--saturation 9.6 | --sat-mod 0.8] [--always] [--eat 1.6] [--fast] [--drink] [--bowl|--bottle|--bucket|--returns <item>] [--stack 16]
//        [--effects "regeneration:5:1@0.5,moddeus:frost:10"] [--remove poison,wither] [--clear] [--teleport 16] [--cooldown 1] [--sound <id>] [--no-particles]
//        [--glint] [--rarity uncommon|rare|epic] [--compost 0.3|0.5|0.65|0.85|1] [--run "effect give @s glowing 5"] [--give] [--tab ..]
//        new id: makes the item (procedural bowl/bottle/food sprite unless --texture); moddeus item: live edit; vanilla item or --give: /give with components
//   feast <id> --name "Berry Pie" [--style cake|pie] [--servings 4] [--food 3,0.6 (per serving: nutrition,sat-mod)] [--effects ..] [--actions "heal:2"]
//        [--serving <item id> (made if new) --container bowl] [--leftover [--leftover-drop bowl]] [--color "#c2304a" --base "#d9a35b"] [--always] [--tab ..]
//        placeable cake/pie/feast block: right-click eats a slice, or with --serving: right-click with the container takes a serving (Farmer's Delight)
//   cook <ingredient> --result <item> [--xp 0.35] [--time 200] [--only smelt,smoke,campfire]   furnace 200 + smoker 200 + campfire 600 ticks like vanilla food
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync, execFileSync } from 'node:child_process';
import { canvas, encode, hex, sh, texFruit } from './systems.mjs';

export const HELP = fs.readFileSync(new URL(import.meta.url), 'utf8').split('\n').filter(l => l.startsWith('//')).slice(2).map(l => l.slice(3)).join('\n');
const CMDS = ['food', 'feast', 'cook'];

// Vanilla 26.3 values (read from the live game's components): nutrition / ABSOLUTE saturation / extras.
export const LIKE = {
  snack: { n: 2, s: 0.4 },                                          // cookie, sweet berries
  fruit: { n: 4, s: 2.4 },                                          // apple
  bread: { n: 5, s: 6 },                                            // bread, baked potato
  meat: { n: 8, s: 12.8 },                                          // steak, cooked porkchop
  fish: { n: 6, s: 9.6 },                                           // cooked salmon
  raw: { n: 2, s: 1.2, effects: 'hunger:30@0.3' },                  // raw chicken
  poison: { n: 2, s: 3.2, effects: 'poison:5' },                    // spider eye
  fast: { n: 1, s: 0.6, eat: 0.8 },                                 // dried kelp
  soup: { n: 6, s: 7.2, returns: 'bowl', stack: 1 },                // mushroom/beetroot stew (rabbit stew 10/12)
  meal: { n: 10, s: 12, returns: 'bowl', stack: 16 },               // Farmer's Delight meals: bowl, stack 16
  drink: { n: 0, s: 0, drink: true, returns: 'glass_bottle', stack: 16 },
  honey: { n: 6, s: 1.2, always: true, eat: 2, drink: true, sound: 'item.honey_bottle.drink', remove: 'poison', returns: 'glass_bottle', stack: 16 },
  milk: { n: 0, s: 0, drink: true, clear: true, returns: 'bucket', stack: 1 },
  golden: { n: 4, s: 9.6, always: true, effects: 'regeneration:5:1,absorption:120' },
  enchanted: { n: 4, s: 9.6, always: true, effects: 'regeneration:20:1,resistance:300,fire_resistance:300,absorption:120:3', glint: true, rarity: 'rare' },
  chorus: { n: 4, s: 2.4, always: true, teleport: 16, cooldown: 1 },
};
const COMPOST = [[0.3, 'low'], [0.5, 'low_medium'], [0.65, 'medium'], [0.85, 'medium_high'], [1, 'always_add_one']];
// Block display transforms of minecraft:block/block (feast item = 3D model of the full feast)
const BLOCK_DISPLAY = { gui: { rotation: [30, 225, 0], scale: [0.625, 0.625, 0.625] }, ground: { translation: [0, 3, 0], scale: [0.25, 0.25, 0.25] }, fixed: { scale: [0.5, 0.5, 0.5] },
  thirdperson_righthand: { rotation: [75, 45, 0], translation: [0, 2.5, 0], scale: [0.375, 0.375, 0.375] }, firstperson_righthand: { rotation: [0, 45, 0], scale: [0.4, 0.4, 0.4] }, firstperson_lefthand: { rotation: [0, 225, 0], scale: [0.4, 0.4, 0.4] } };

let ROOT, CONTENT, R, A, opt, NS = 'moddeus', NSP = 'moddeus:', stamp = () => {}; // NS: the mod's namespace (mc.mjs NS), stamp: mc.mjs nsContent

export async function run(cmd, rest, ctx) {
  if (!CMDS.includes(cmd)) return false;
  ({ ROOT, CONTENT } = ctx); NS = ctx.NS || 'moddeus'; NSP = NS + ':'; stamp = ctx.stamp || stamp; R = path.join(ROOT, 'src/main/resources'); A = path.join(R, 'assets/' + NS);
  const p = parse(rest); opt = p.opt;
  const id = p.pos[0];
  if (!/^[a-z0-9_:]+$/.test(id || '')) ctx.die('usage:\n' + HELP);
  if (cmd === 'food') await food(id, ctx);
  else if (cmd === 'feast') await feast(id, ctx);
  else cook(id, ctx);
  await ctx.apply();
  return true;
}

// ---- food -------------------------------------------------------------------------------------------------------
/** Vanilla food + consumable (+ remainder, cooldown, stack, rarity, glint, compost) components from a preset + flags. */
function components(o) {
  const L = o.like ? LIKE[o.like] || die('--like ' + Object.keys(LIKE).join('|')) : {};
  const v = k => o[k] !== undefined ? o[k] : L[k];
  const comp = {};
  const n = Number(v('nutrition') ?? L.n ?? 4);
  const sat = o['sat-mod'] !== undefined ? n * Number(o['sat-mod']) * 2 : Number(v('saturation') ?? L.s ?? n * 1.2); // vanilla: saturation = nutrition x modifier x 2 (0.6 "normal")
  const drink = !!v('drink');
  if (n > 0 || sat > 0 || v('always')) comp['minecraft:food'] = { nutrition: n, saturation: Math.round(sat * 100) / 100, can_always_eat: !!v('always') };
  const on = [];
  if (v('effects')) { const groups = {}; for (const s of String(v('effects')).split(',')) { const [body, prob] = s.trim().split('@'); const q = body.split(':'); let eid, r;
      if (q.length >= 2 && isNaN(Number(q[1]))) { eid = q[0] + ':' + q[1]; r = q.slice(2); } else { eid = 'minecraft:' + q[0]; r = q.slice(1); }
      (groups[prob || 1] ||= []).push({ id: eid, duration: Math.max(1, Math.round(Number(r[0] ?? 10) * 20)), amplifier: Number(r[1] || 0), show_icon: true }); }
    for (const [prob, effects] of Object.entries(groups)) on.push({ type: 'minecraft:apply_effects', effects, probability: Number(prob) }); }
  if (v('remove')) on.push({ type: 'minecraft:remove_effects', effects: String(v('remove')).split(',').map(s => ns(s.trim())) });
  if (v('clear')) on.push({ type: 'minecraft:clear_all_effects' });
  if (v('teleport')) on.push({ type: 'minecraft:teleport_randomly', diameter: Number(v('teleport')) });
  const secs = o.fast ? 0.8 : Number(v('eat') ?? 1.6);
  const sound = ns(v('sound') || (drink ? 'entity.generic.drink' : 'entity.generic.eat'));
  comp['minecraft:consumable'] = { consume_seconds: secs, animation: drink ? 'drink' : 'eat', ...(sound !== 'minecraft:entity.generic.eat' ? { sound } : {}), // default sound left out: parses without a world
    has_consume_particles: !drink && !o['no-particles'], on_consume_effects: on };
  const ret = o.bowl ? 'bowl' : o.bottle ? 'glass_bottle' : o.bucket ? 'bucket' : v('returns');
  if (ret) comp['minecraft:use_remainder'] = { id: ns(ret), count: 1 };
  const stack = v('stack') ?? (o.bowl || o.bucket ? 1 : o.bottle ? 16 : undefined);
  if (stack !== undefined) comp['minecraft:max_stack_size'] = Number(stack);
  if (v('cooldown')) comp['minecraft:use_cooldown'] = { seconds: Number(v('cooldown')) };
  if (v('glint')) comp['minecraft:enchantment_glint_override'] = true;
  if (v('rarity')) comp['minecraft:rarity'] = String(v('rarity'));
  if (o.compost) { const c = Number(o.compost); comp['minecraft:compostable'] = { layers: 'minecraft:compostable/' + COMPOST.reduce((a, b) => Math.abs(b[0] - c) < Math.abs(a[0] - c) ? b : a)[1] }; }
  return { comp, kind: ret === 'bowl' ? 'bowl' : ret === 'glass_bottle' || drink ? 'bottle' : L.glint || o.like === 'golden' ? 'golden' : 'food' };
}

async function food(id, ctx) {
  const raw = id.replace(/^moddeus:/, ''), ours = !id.startsWith('minecraft:') && fs.existsSync(path.join(CONTENT, raw + '.json'));
  const vanilla = !ours && (id.startsWith('minecraft:') || (!id.includes(':') && vanillaItem(id)));
  const { comp, kind } = components(flat(opt));
  if (ours) { // edit: only the parts the flags name, keep the rest
    const given = ks => ks.some(k => opt[k] !== undefined);
    if (!given(['like', 'nutrition', 'saturation', 'sat-mod', 'always'])) delete comp['minecraft:food'];
    if (!given(['like', 'eat', 'fast', 'drink', 'effects', 'remove', 'clear', 'teleport', 'sound', 'no-particles'])) delete comp['minecraft:consumable'];
  }
  if (opt.run) onEat(vanilla ? ns(id) : NSP + raw, String(one(opt.run)));
  if (vanilla || opt.give) {
    const it = vanilla ? ns(id) : NSP + raw;
    const give = 'give @s ' + it + '[' + Object.entries(comp).map(([k, v]) => k + '=' + JSON.stringify(v)).join(',') + ']';
    console.log('/' + give + '\n(datapack: this /give or an item_modifier set_components; a vanilla item keeps its own food otherwise)');
    gameCmd(give);
    return;
  }
  if (!/^[a-z0-9_]+$/.test(raw)) die('food id: a-z 0-9 _');
  const f = path.join(CONTENT, raw + '.json');
  const o = ours ? readJson(f, {}) : { type: 'item', files: [] };
  o.components = Object.assign(o.components || {}, comp);
  delete o.food; // the old "food" key is replaced by the vanilla components (live re-bind)
  if (opt.tab) o.tab = one(opt.tab);
  if (opt.run) for (const r of ['data/' + NS + '/advancement/eat/' + raw + '.json', 'data/' + NS + '/function/eat/' + raw + '.mcfunction']) if (!o.files.includes(r)) o.files.push(r);
  if (!ours) itemAssets(raw, o, kind, one(opt.color));
  write(f, o);
  console.log((ours ? 'updated' : 'new food') + (' ' + NSP) + raw + ' ' + JSON.stringify(comp['minecraft:food'] || {}) + (comp['minecraft:use_remainder'] ? ' -> ' + comp['minecraft:use_remainder'].id : ''));
  if (opt.texture) spawnSync(process.execPath, [path.join(ROOT, 'tools/mc.mjs'), 'texture', 'item', raw, String(one(opt.texture)), '--ns', NS, '--no-apply'], { cwd: ROOT, stdio: 'inherit' });
  else if (!ours) console.log('  placeholder ' + kind + ' sprite: redraw it appetizing with mc pixels item ' + raw + ' (FOOD.md "Textures")');
}

/** On-eat commands: vanilla consume_item advancement -> function (runs as the eater), revoked so it fires every time. Works in datapacks and exported jars. */
function onEat(item, cmds) {
  const name = item.split(':')[1];
  write(path.join(R, 'data/' + NS + '/advancement/eat', name + '.json'), { criteria: { eat: { trigger: 'minecraft:consume_item', conditions: { item: { items: item } } } }, rewards: { function: NSP + 'eat/' + name } });
  const f = path.join(R, 'data/' + NS + '/function/eat', name + '.mcfunction');
  fs.mkdirSync(path.dirname(f), { recursive: true });
  fs.writeFileSync(f, cmds.split(';').map(s => s.trim().replace(/^\//, '')).filter(Boolean).join('\n') + ('\nadvancement revoke @s only ' + NSP + 'eat/') + name + '\n');
  console.log('  on eat: data/' + NS + '/function/eat/' + name + '.mcfunction');
}

function itemAssets(id, o, kind, color) {
  const c = color || { bowl: '#b5651d', bottle: '#e0457b', golden: '#f2c23a', food: '#d8402c' }[kind];
  png(o.files, 'textures/item/' + id + '.png', kind === 'bowl' ? texBowl(c) : kind === 'bottle' ? texBottle(c) : texFruit(c));
  out(o.files, 'models/item/' + id + '.json', { parent: 'minecraft:item/generated', textures: { layer0: NSP + 'item/' + id } });
  out(o.files, 'items/' + id + '.json', { model: { type: 'minecraft:model', model: NSP + 'item/' + id } });
  lang('item.' + NS + '.' + id, one(opt.name) || title(id));
}

// ---- feast ------------------------------------------------------------------------------------------------------
async function feast(id, ctx) {
  if (!/^[a-z0-9_]+$/.test(id)) die('feast id: a-z 0-9 _');
  const style = one(opt.style) || 'cake', h = style === 'pie' ? 5 : 8, servings = Math.max(1, Math.min(opt.leftover ? 6 : 7, Number(one(opt.servings) || 4)));
  const color = one(opt.color) || (style === 'pie' ? '#8e1f3d' : '#f4f0f4'), base = one(opt.base) || (style === 'pie' ? '#d99a4e' : '#c98b4a');
  const [nu, sm] = String(one(opt.food) || '2,0.1').split(',').map(Number);
  const o = { type: 'feast', servings, food: { nutrition: nu, saturation: Math.round(nu * (sm ?? 0.1) * 2 * 100) / 100, ...(opt.always ? { always: true } : {}) }, files: [] };
  if (opt.tab) o.tab = one(opt.tab);
  if (opt.actions) o.actions = String(one(opt.actions));
  if (opt.effects) o.effects = components({ effects: one(opt.effects) }).comp['minecraft:consumable'].on_consume_effects.flatMap(e => e.effects.map(x => ({ id: x.id, duration: x.duration, amplifier: x.amplifier, probability: e.probability })));
  if (opt.leftover) { o.leftover = true; if (opt['leftover-drop'] && opt['leftover-drop'] !== true) o.leftover_drop = ns(one(opt['leftover-drop'])); }
  if (opt.serving) {
    const sid = String(one(opt.serving)).replace(/^moddeus:/, '');
    o.serving = sid.includes(':') ? sid : NSP + sid; o.container = ns(one(opt.container) || 'bowl');
    if (!sid.includes(':') && !fs.existsSync(path.join(CONTENT, sid + '.json'))) { // a meal served in the container (Farmer's Delight: bowl, stack 16)
      const so = { like: 'meal', nutrition: nu, 'sat-mod': sm ?? 0.6, returns: o.container.split(':')[1], ...(opt.effects ? { effects: one(opt.effects) } : {}) };
      const s = { type: 'item', files: [], components: components(so).comp, ...(o.tab ? { tab: o.tab } : {}) };
      const save = opt.name; opt.name = (one(opt.name) || title(id)) + ' Serving'; itemAssets(sid, s, 'bowl', color); opt.name = save;
      write(path.join(CONTENT, sid + '.json'), s);
      console.log('  serving item ' + NSP + sid + ' (' + nu + ' hunger, returns the ' + o.container + ')');
      delete o.food; delete o.effects; // a feast with servings is not eaten directly
    }
  }
  // textures + models: one element per bite stage (vanilla cake geometry, own height), plate for the leftover
  const T = k => NSP + 'block/' + id + '_' + k;
  const tex = feastTextures(style, hex(color), hex(base), h);
  for (const [k, cv] of Object.entries(tex)) png(o.files, 'textures/block/' + id + '_' + k + '.png', cv);
  const model = (x1, inner) => ({ parent: 'minecraft:block/block', textures: { particle: T('side'), bottom: T('bottom'), top: T('top'), side: T('side'), inside: T('inner') }, render_type: 'minecraft:cutout',
    elements: [{ from: [x1, 0, 1], to: [15, h, 15], faces: { down: { texture: '#bottom', cullface: 'down' }, up: { texture: '#top' }, north: { texture: '#side' }, south: { texture: '#side' }, west: { texture: inner ? '#inside' : '#side' }, east: { texture: '#side' } } }] });
  const variants = {};
  const idx = b => b === 0 ? 0 : Math.min(6, Math.round(b * 7 / servings));
  for (let b = 0; b < servings; b++) out(o.files, 'models/block/' + id + (b ? '_slice' + idx(b) : '') + '.json', model(1 + 2 * idx(b), b > 0));
  if (o.leftover) out(o.files, 'models/block/' + id + '_leftover.json', { parent: 'minecraft:block/block', textures: { particle: T('plate'), plate: T('plate') },
    elements: [{ from: [2, 0, 2], to: [14, 1, 14], faces: { down: { texture: '#plate', cullface: 'down' }, up: { texture: '#plate' }, north: { texture: '#plate' }, south: { texture: '#plate' }, west: { texture: '#plate' }, east: { texture: '#plate' } } }] });
  for (let b = 0; b <= 6; b++) variants['bites=' + b] = { model: NSP + 'block/' + id + (b >= servings ? (o.leftover ? '_leftover' : (idx(servings - 1) ? '_slice' + idx(servings - 1) : '')) : b ? '_slice' + idx(b) : '') };
  out(o.files, 'blockstates/' + id + '.json', { variants });
  out(o.files, 'models/item/' + id + '.json', { parent: NSP + 'block/' + id, display: BLOCK_DISPLAY });
  out(o.files, 'items/' + id + '.json', { model: { type: 'minecraft:model', model: NSP + 'item/' + id } });
  // loot: an untouched feast drops itself; a started one drops nothing (like cake), the leftover its drop via right-click
  write(path.join(R, 'data/' + NS + '/loot_table/blocks', id + '.json'), { type: 'minecraft:block', pools: [{ rolls: 1, conditions: [{ condition: 'minecraft:survives_explosion' }, { condition: 'minecraft:block_state_property', block: NSP + id, properties: { bites: '0' } }], entries: [{ type: 'minecraft:item', name: NSP + id }] }] });
  o.files.push('data/' + NS + '/loot_table/blocks/' + id + '.json');
  lang('block.' + NS + '.' + id, one(opt.name) || title(id));
  write(path.join(CONTENT, id + '.json'), o);
  console.log('feast ' + NSP + id + ' (' + style + ', ' + servings + ' servings' + (o.serving ? ' -> ' + o.serving + ' with a ' + o.container : ', ' + nu + ' hunger each') + (o.leftover ? ', leftover plate' : '') + ')');
  console.log('  textures ' + id + '_top/_side/_inner/_bottom' + (o.leftover ? '/_plate' : '') + ': redraw with mc pixels block ' + id + '_top ... (side: band at row ' + (16 - h) + ', the model shows rows ' + (16 - h) + '-15)');
}

function feastTextures(style, c, b, h) {
  const top = canvas(), side = canvas(), inner = canvas(), bottom = canvas(), plate = canvas(), y0 = 16 - h;
  if (style === 'pie') {
    top.noise(sh(c, -0.1), 0.15);
    for (let i = 0; i < 16; i++) for (let j = 0; j < 16; j++) if ((i + 1) % 5 < 2 || (j + 2) % 5 < 2) top.px(i, j, sh(b, ((i * 7 + j * 3) % 5 === 0 ? 0.25 : 0) + (i + j < 12 ? 0.08 : -0.08))); // lattice crust over the filling
    for (let i = 0; i < 16; i++) { top.px(i, 0, sh(b, -0.3)); top.px(0, i, sh(b, -0.2)); top.px(i, 15, sh(b, -0.35)); top.px(15, i, sh(b, -0.35)); }
    side.noise(b, 0.12); for (let x = 0; x < 16; x++) { side.px(x, y0, sh(b, 0.3)); if (x % 3 === 0) side.px(x, y0 + 1, sh(b, 0.15)); side.px(x, 15, sh(b, -0.35)); }
    inner.noise(c, 0.18); for (let x = 0; x < 16; x++) { inner.px(x, y0, sh(b, 0.2)); inner.px(x, 15, sh(b, -0.3)); inner.px(x, 14, sh(b, -0.15)); }
    for (const [x, y] of [[3, y0 + 2], [9, y0 + 1], [12, y0 + 3]]) inner.px(x, y, sh(c, 0.4));
  } else {
    top.noise(c, 0.06);
    for (const [x, y, k] of [[3, 4, 0], [10, 3, 1], [6, 9, 2], [12, 11, 0], [4, 13, 1], [13, 6, 2], [8, 6, 1]]) top.px(x, y, [[226, 60, 70], [90, 170, 230], [250, 210, 80]][k]); // sprinkles
    for (let i = 0; i < 16; i++) { top.px(i, 0, sh(c, -0.15)); top.px(0, i, sh(c, -0.1)); top.px(i, 15, sh(c, -0.25)); top.px(15, i, sh(c, -0.25)); }
    side.noise(b, 0.1); for (let x = 0; x < 16; x++) { side.px(x, y0, sh(c, 0.05)); side.px(x, y0 + 1, sh(c, -0.08)); if ((x * 5) % 7 < 3) side.px(x, y0 + 2, sh(c, -0.15)); side.px(x, 15, sh(b, -0.3)); } // frosting band + drips
    inner.noise(sh(b, 0.1), 0.18); for (let x = 0; x < 16; x++) { inner.px(x, y0, sh(c, 0.05)); inner.px(x, y0 + 1, sh(c, -0.1)); inner.px(x, y0 + 4, sh(c, -0.05)); } // frosting + cream layer
  }
  bottom.noise(sh(b, -0.25), 0.1);
  plate.noise([232, 228, 220], 0.05); for (let i = 1; i < 15; i++) { plate.px(i, 1, [200, 196, 190]); plate.px(1, i, [200, 196, 190]); plate.px(i, 14, [176, 170, 164]); plate.px(14, i, [176, 170, 164]); }
  for (const [x, y] of [[5, 6], [9, 8], [7, 10], [10, 5]]) plate.px(x, y, sh(c, -0.2)); // crumbs
  return { top, side, inner, bottom, ...(opt.leftover ? { plate } : {}) };
}

// ---- cook -------------------------------------------------------------------------------------------------------
function cook(ing, ctx) {
  const result = one(opt.result) || die('cook <ingredient> --result <item>');
  const xp = Number(one(opt.xp) ?? 0.35), t = Number(one(opt.time) || 200), only = String(one(opt.only) || 'smelt,smoke,campfire').split(',');
  const kinds = { smelt: ['smelting', t], smoke: ['smoking', t], campfire: ['campfire_cooking', t * 3] }; // vanilla 26.3 data: beef 200 / 200 / 600, 0.35 xp
  const res = item(result), name = res.split(':')[1];
  for (const k of only) {
    const [type, time] = kinds[k.trim()] || die('--only smelt,smoke,campfire');
    const f = path.join(R, 'data/' + NS + '/recipe', name + '_from_' + type + '.json');
    write(f, { type: 'minecraft:' + type, category: 'food', cookingtime: time, experience: xp, ingredient: item(ing), result: { id: res } });
    console.log('  ' + type + ' ' + item(ing) + ' -> ' + res + ' (' + time + ' ticks, ' + xp + ' xp)');
  }
}

// ---- textures ---------------------------------------------------------------------------------------------------
function texBowl(color) { // wooden bowl, glossy stew surface, top-left light
  const cv = canvas(), s = hex(color), w = [150, 98, 54];
  for (let y = 9; y <= 14; y++) { const hw = [7, 7, 6.5, 5.5, 4.5, 3.5][y - 9]; for (let x = 0; x < 16; x++) { const dx = x - 7.5; if (Math.abs(dx) <= hw) cv.px(x, y, Math.abs(dx) > hw - 1 || y === 14 ? sh(w, -0.55) : sh(w, dx < -2 ? 0.2 : dx > 2 ? -0.25 : 0)); } }
  for (let y = 6; y <= 9; y++) for (let x = 1; x < 15; x++) { const dx = (x - 7.5) / 6.5, dy = (y - 7.8) / 1.9; if (dx * dx + dy * dy <= 1) cv.px(x, y, sh(s, dy < -0.3 ? 0.25 : dx > 0.5 ? -0.2 : 0)); }
  for (const [x, y, k] of [[4, 7, 0.45], [5, 7, 0.3], [9, 8, -0.3], [11, 7, 0.15], [7, 8, -0.15]]) cv.px(x, y, sh(s, k)); // chunks + gloss
  for (let x = 1; x < 15; x++) cv.px(x, 9, sh(w, 0.35)); // rim highlight
  for (const [x, y] of [[6, 4], [7, 3], [9, 4], [10, 2]]) cv.px(x, y, [236, 236, 240], 120); // steam
  return cv;
}
function texBottle(color) { // cork, glass neck, round body with liquid + highlight
  const cv = canvas(), l = hex(color), g = [190, 214, 230];
  cv.rect(7, 1, 2, 2, [150, 104, 60]); cv.px(7, 1, [184, 136, 84]);
  cv.rect(7, 3, 2, 3, g);
  for (let y = 5; y < 16; y++) for (let x = 2; x < 14; x++) { const dx = x - 7.5, dy = y - 10.2, r = Math.hypot(dx, dy); if (r <= 5.2) cv.px(x, y, r > 4.3 ? sh(g, -0.25) : y >= 8 ? sh(l, dx < -1 ? 0.2 : dx > 2 ? -0.25 : 0) : sh(g, 0.2), r > 4.3 ? 255 : y >= 8 ? 255 : 150); }
  for (const [x, y] of [[5, 8], [5, 9], [6, 8]]) cv.px(x, y, [255, 255, 255]); // glass highlight
  cv.px(9, 13, sh(l, 0.35)); return cv;
}

// ---- helpers ----------------------------------------------------------------------------------------------------
function gameCmd(c) { spawnSync(process.execPath, [path.join(ROOT, 'tools/mc.mjs'), 'game', 'cmd', c], { cwd: ROOT, stdio: 'inherit' }); }
function vanillaItem(name) { try { const d = path.join(ROOT, '.gradle/loom-cache/minecraftMaven/net/minecraft'), dir = fs.readdirSync(d).find(x => x.startsWith('minecraft-clientOnly')), v = fs.readdirSync(path.join(d, dir))[0];
  const jar = path.join(d, dir, v, fs.readdirSync(path.join(d, dir, v)).find(f => f.endsWith('.jar') && !f.includes('sources'))); return execFileSync('unzip', ['-l', jar, 'assets/minecraft/items/' + name + '.json']).toString().includes(name + '.json'); } catch { return false; } }
function item(s) { s = String(s).trim(); if (s.includes(':')) return s; return fs.existsSync(path.join(CONTENT, s + '.json')) ? cns(s) + ':' + s : 'minecraft:' + s; }
function flat(o) { const r = {}; for (const [k, v] of Object.entries(o)) r[k] = one(v); return r; }
function out(files, rel, json) { write(path.join(A, rel), json); files.push('assets/' + NS + '/' + rel); }
function png(files, rel, cv) { const f = path.join(A, rel); fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, encode(cv)); files.push('assets/' + NS + '/' + rel); }
function write(f, j) { stamp(f, j); fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, JSON.stringify(j, null, 2) + '\n'); }
const cns = s => readJson(path.join(CONTENT, s + '.json'), {}).namespace || 'moddeus'; // a content id's mod namespace (per-mod namespaces)
function readJson(f, d) { try { return JSON.parse(fs.readFileSync(f, 'utf8')); } catch { return d; } }
function lang(k, v) { const f = path.join(A, 'lang/en_us.json'), l = readJson(f, {}); l[k] = v; write(f, l); }
function die(m) { console.error(m); process.exit(1); }
const one = v => Array.isArray(v) ? v[v.length - 1] : v;
const ns = s => (String(s).includes(':') || String(s).startsWith('#') ? String(s) : 'minecraft:' + s);
const title = s => s.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
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
