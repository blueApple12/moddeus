// One Gradle build at a time across every mc / game eval process (voice Haiku, main chat, you).
// Without it, parallel builds compiled each other's half-written eval files and deleted them mid-run.
// lock(root, 'content'): one content-writing mc command at a time (several chats/mods share lang/en_us.json, sounds.json,
// tags, content JSON: two at once lost each other's keys). Nested mc calls inherit the holder (env) and don't wait on it.
import fs from 'node:fs';
import path from 'node:path';

const nap = new Int32Array(new SharedArrayBuffer(4));
const held = {};

export function lock(root, name = 'build') {
  if (held[name]) return;
  const env = 'MODDEUS_LOCK_' + name.toUpperCase();
  if (process.env[env]) return; // our parent process holds it
  const f = path.join(root, 'run/moddeus/' + name + '.lock');
  fs.mkdirSync(path.dirname(f), { recursive: true });
  for (let i = 0; ; i++) {
    try { fs.writeFileSync(f, String(process.pid), { flag: 'wx' }); held[name] = f; break; }
    catch {
      try {
        const st = fs.statSync(f), pid = Number(fs.readFileSync(f, 'utf8'));
        let alive = true; try { process.kill(pid, 0); } catch { alive = false; }
        if (!alive || Date.now() - st.mtimeMs > 300000) { fs.rmSync(f, { force: true }); continue; } // stale (crashed holder)
      } catch { continue; }
      if (i === 20) console.log('waiting for another ' + (name === 'build' ? 'build' : 'mc command (other chat)') + '...');
      if (i > 3000) throw new Error(name + ' lock still busy after 5 min: ' + f);
      Atomics.wait(nap, 0, 0, 100);
    }
  }
  process.env[env] = String(process.pid);
  process.on('exit', () => unlock(name));
}

export function unlock(name = 'build') { if (held[name]) { fs.rmSync(held[name], { force: true }); delete held[name]; delete process.env['MODDEUS_LOCK_' + name.toUpperCase()]; } }
