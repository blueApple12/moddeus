// mc on|off "<Mod>": switch a mod on/off live, exactly like the G screen Mods On/Off button (its @OnReload/@OnUnload hooks,
// datapack enable/disable + load/uninstall, resource pack, creative tab items, key binds; state saved in projects.json).
// Use it for "turn on/off <mod or datapack>": a datapack enable alone leaves the mod marked Off and its items hidden.
import { projects } from './projects.mjs';

const norm = s => String(s).toLowerCase().replace(/[^a-z0-9]/g, '');

export async function run(cmd, args, root) {
  const q = norm(args.join(' ').replace(/\b(data ?pack|resource ?pack|mod|the)\b/gi, ''));
  const list = projects(root);
  const p = q && (list.find(x => norm(x.id) === q || norm(x.name) === q) || list.find(x => q.includes(norm(x.id)) || norm(x.name).includes(q) || q.includes(norm(x.name))));
  if (!p) { console.error('mc ' + cmd + ': no mod matches "' + args.join(' ') + '". Mods: ' + list.map(x => x.name).join(', ')); process.exit(1); }
  const on = cmd === 'on';
  await (await import('./game.mjs')).run(['run', 'mc.execute(() -> dev.moddeus.client.Projects.setEnabled("' + p.id + '", ' + on + ')); print("' + (on ? 'on' : 'off') + ': ' + p.name.replace(/"/g, '') + '");']);
}
