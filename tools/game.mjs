// Game tools for Claude Code: see and act in the running world. Used via: node tools/mc.mjs game <sub> ...
//   context                      player, position, looking at, inventory, nearby entities, biome, time
//   cmd "<command>" [...]        run commands as the player with full permissions (prints their output)
//   blocks x1 y1 z1 x2 y2 z2 [--counts]   blocks in a box (max 64^3); --counts = palette only
//   shot [--ui]                  screenshot of what the player sees -> prints PNG path (Read it to look); --ui keeps the Claude screen
//   say "<text>"                 message the player in chat as Claude
//   log [lines] [--grep re]      tail of run/logs/latest.log (hotswap errors, crashes, chat)
//   quick <action> [args]          instant prebuilt action, no compile (launch, safe_landing, tame_hostiles, ...); no args = list
//   eval|run "<java>"              run Java in the live game: compiled in memory, ~0.3s (--gradle = old hotswap path)
//                                In scope: server, player, level, mc (client), print(obj). Expression or statements.
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const die = m => { console.error('game: ' + m); process.exit(1); };

async function api(endpoint, body = {}) {
  let info;
  try { info = JSON.parse(fs.readFileSync(path.join(ROOT, 'run/moddeus/game.json'), 'utf8')); } catch { die('game not running (no run/moddeus/game.json)'); }
  let r;
  try { r = await fetch(`http://127.0.0.1:${info.port}/${endpoint}?t=${info.token}`, { method: 'POST', body: JSON.stringify(body) }); }
  catch { die('game not reachable. Is Minecraft running with a world open?'); }
  const j = await r.json();
  if (j && j.error) die(j.error);
  return j;
}

function print(o) {
  if (typeof o !== 'object' || o === null) return console.log(o);
  for (const [k, v] of Object.entries(o)) {
    if (Array.isArray(v)) { console.log(k + ':'); v.forEach(x => console.log('  ' + (typeof x === 'object' ? JSON.stringify(x) : x))); }
    else if (typeof v === 'object' && v) { console.log(k + ':'); for (const [a, b] of Object.entries(v)) console.log('  ' + a + ': ' + b); }
    else console.log(k + ': ' + v);
  }
}

const EVAL_IMPORTS = ['java.util.*', 'net.minecraft.core.*', 'net.minecraft.core.registries.*', 'net.minecraft.core.component.*',
  'net.minecraft.world.item.*', 'net.minecraft.world.item.enchantment.*', 'net.minecraft.world.entity.*', 'net.minecraft.world.level.block.*',
  'net.minecraft.network.chat.Component', 'net.minecraft.resources.*', 'net.minecraft.server.MinecraftServer', 'net.minecraft.server.level.*',
  'net.minecraft.client.Minecraft', 'net.minecraft.world.phys.*', 'net.minecraft.world.level.*', 'net.minecraft.world.entity.player.*',
  'net.minecraft.world.effect.*', 'net.minecraft.sounds.*', 'net.minecraft.core.particles.*', 'net.minecraft.world.entity.monster.*', 'net.minecraft.world.entity.projectile.*'];

/** Fast path: the game compiles the snippet in memory and runs it now (no Gradle, no files, no hotswap). */
async function runJava(code) {
  const name = 'R' + Date.now().toString(36);
  const body = /;\s*$|[{}]/.test(code.trim()) ? code : `print(${code});`;
  const src = `package dev.moddeus.eval;

${EVAL_IMPORTS.map(i => 'import ' + i + ';').join('\n')}
import dev.moddeus.live.Quick;
import static dev.moddeus.live.Quick.*;

public final class ${name} {
	static final StringBuilder OUT = new StringBuilder();
	static void print(Object o) { OUT.append(o).append('\\n'); }

	public static String run() throws Exception {
		Minecraft mc = Minecraft.getInstance();
		MinecraftServer server = mc.getSingleplayerServer();
		ServerPlayer player = server.getPlayerList().getPlayers().isEmpty() ? null : server.getPlayerList().getPlayers().get(0);
		ServerLevel level = player != null ? (ServerLevel) player.level() : server.overworld();
		try {
${body}
		} catch (Throwable t) {
			java.io.StringWriter w = new java.io.StringWriter();
			t.printStackTrace(new java.io.PrintWriter(w));
			OUT.append("EXCEPTION ").append(w.toString().lines().limit(12).reduce((a, b) -> a + "\\n" + b).orElse(""));
		}
		return OUT.toString();
	}
}
`;
  let info;
  try { info = JSON.parse(fs.readFileSync(path.join(ROOT, 'run/moddeus/game.json'), 'utf8')); } catch { die('game not running'); }
  const r = await fetch(`http://127.0.0.1:${info.port}/run?t=${info.token}`, { method: 'POST', body: JSON.stringify({ name, src }) }).then(x => x.json()).catch(() => null);
  if (r === null || (r.error && /unknown endpoint|no-compiler/.test(r.error))) return false; // old game build: use the Gradle path
  if (r.error) { console.error('game: ' + r.error.replace(/^java\.lang\.IllegalArgumentException: /, '')); process.exitCode = 1; return true; }
  console.log(String(r).trimEnd() || '(no output; use print(...))');
  return true;
}

async function evalJava(code) {
  if (!code) die('usage: game eval "<java>"');
  if (!process.argv.includes('--gradle') && await runJava(code)) return;
  if ((await import('./build.mjs')).portable(ROOT)) die('game run failed (portable mode has no Gradle fallback); see the error above');
  if (!code) die('usage: game eval "<java>"');
  (await import('./lock.mjs')).lock(ROOT); // held until the result is back: a parallel build would delete this eval before it runs
  const name = 'E' + Date.now().toString(36);
  const dir = path.join(ROOT, 'src/client/java/dev/moddeus/eval'); // client set sees both client and common classes
  const outFile = path.join(ROOT, 'run/moddeus/eval', name + '.out');
  fs.mkdirSync(dir, { recursive: true });
  fs.mkdirSync(path.dirname(outFile), { recursive: true });
  for (const f of fs.readdirSync(dir)) if (/^E\w+\.java$/.test(f)) fs.rmSync(path.join(dir, f), { force: true }); // stale (crashed) evals break the build
  const body = /;\s*$|[{}]/.test(code.trim()) ? code : `print(${code});`;
  const src = `package dev.moddeus.eval;

${EVAL_IMPORTS.map(i => 'import ' + i + ';').join('\n')}

/** Generated by \`mc game eval\`; deleted after it runs. */
public final class ${name} {
	static final StringBuilder OUT = new StringBuilder();
	static void print(Object o) { OUT.append(o).append('\\n'); }

	@dev.moddeus.live.OnReload
	static void run() throws Exception {
		MinecraftServer server = dev.moddeus.live.LiveRegistry.server;
		ServerPlayer player = server.getPlayerList().getPlayers().isEmpty() ? null : server.getPlayerList().getPlayers().get(0);
		ServerLevel level = player != null ? (ServerLevel) player.level() : server.overworld();
		Minecraft mc = Minecraft.getInstance(); // client: run UI/render work via mc.execute(() -> ...)
		try {
${body}
		} catch (Throwable t) {
			java.io.StringWriter w = new java.io.StringWriter();
			t.printStackTrace(new java.io.PrintWriter(w));
			OUT.append("EXCEPTION ").append(w.toString().lines().limit(12).reduce((a, b) -> a + "\\n" + b).orElse(""));
		}
		java.nio.file.Files.writeString(java.nio.file.Path.of(${JSON.stringify(outFile)}), OUT.toString());
	}
}
`;
  const file = path.join(dir, name + '.java');
  fs.writeFileSync(file, src);
  try {
    const r = spawnSync(path.join(ROOT, 'gradlew.bat'), ['clientClasses', '-q'], { cwd: ROOT, shell: true, encoding: 'utf8', env: { ...process.env, JAVA_HOME: path.join(ROOT, '.tools/jbr') } });
    if (r.status !== 0) {
      const errs = (r.stdout + r.stderr).split(/\r?\n/).filter(l => /error:|\^|symbol|location/.test(l) && !/^\s*$/.test(l)).slice(0, 20).join('\n');
      console.error('game: compile failed:\n' + (errs || (r.stdout + r.stderr).trim().split(/\r?\n/).slice(-10).join('\n')));
      process.exitCode = 1;
      return;
    }
    for (let i = 0; i < 100; i++) {
      if (fs.existsSync(outFile)) { console.log(fs.readFileSync(outFile, 'utf8').trimEnd() || '(no output; use print(...))'); return; }
      await new Promise(r => setTimeout(r, 200));
    }
    console.error('game: no result after 20s. Check: node tools/mc.mjs game log 30');
    process.exitCode = 1;
  } finally {
    fs.rmSync(file, { force: true });
    fs.rmSync(outFile, { force: true });
  }
}

export async function run(args) {
  const [sub, ...rest] = args;
  const flag = f => { const i = rest.indexOf(f); if (i < 0) return undefined; const v = rest[i + 1]; rest.splice(i, v && !v.startsWith('--') ? 2 : 1); return v && !v.startsWith('--') ? v : true; };
  switch (sub) {
    case 'context': print(await api('context')); break;
    case 'lag': print(await api('lag')); break; // server ms/tick, FPS, which mod/datapack/function costs what
    case 'cmd': case 'command': {
      if (!rest.length) die('usage: game cmd "<command>" [...]');
      for (const r of await api('command', { commands: rest })) console.log('/' + r.command + (r.output ? '\n  ' + r.output.replace(/\n/g, '\n  ') : '  (no output)'));
      if (rest.some(c => /^\/?(reload|datapack)\b/.test(c))) { // datapack edits become undo steps (mc undo <Mod>)
        try { await (await import('./snap.mjs')).run('snap', ['datapack: ' + rest.join('; ').slice(0, 60)], path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')); } catch {}
      }
      break;
    }
    case 'blocks': {
      const counts = flag('--counts');
      const n = rest.map(Number);
      if (n.length !== 6 || n.some(isNaN)) die('usage: game blocks x1 y1 z1 x2 y2 z2 [--counts]');
      print(await api('blocks', { from: n.slice(0, 3), to: n.slice(3), list: !counts }));
      break;
    }
    case 'shot': case 'screenshot': console.log((await api('screenshot', { ui: !!flag('--ui') })).path); break;
    case 'say': await api('say', { text: rest.join(' ') }); console.log('sent'); break;
    case 'log': {
      const grep = flag('--grep');
      const n = Number(rest[0] || 40);
      let lines = fs.readFileSync(path.join(ROOT, 'run/logs/latest.log'), 'utf8').split(/\r?\n/);
      if (grep) lines = lines.filter(l => new RegExp(grep, 'i').test(l));
      console.log(lines.slice(-n).join('\n'));
      break;
    }
    case 'eval': case 'run': await evalJava(rest.filter(a => a !== '--gradle').join(' ')); break;
    case 'quick': { // several actions in one call: quick launch 3 + safe_landing
      const groups = rest.join(' ').split(/\s*\+\s*/).map(g => g.split(/\s+/).filter(Boolean));
      for (const g of groups.length ? groups : [[]]) {
        // "cmd <command>": a vanilla command in the chain (a leading "/" gets mangled into a path by Git Bash)
        const c = g[0] === 'cmd' ? g.slice(1).join(' ') : g[0]?.startsWith('/') || /^[A-Z]:\/Program Files\/Git\//.test(g[0] || '') ? g.join(' ').replace(/^([A-Z]:\/Program Files\/Git)?\//, '') : null;
        if (c !== null) { for (const r of await api('command', { commands: [c] })) console.log('/' + r.command + (r.output ? '  ' + r.output : '')); }
        else console.log(await api('quick', { action: g[0] || '', args: g.slice(1) }));
      }
      break;
    }
    default:
      console.log(fs.readFileSync(fileURLToPath(import.meta.url), 'utf8').split('\n').filter(l => l.startsWith('//')).map(l => l.slice(3)).join('\n'));
  }
}
