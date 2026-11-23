// One Gradle build at a time across every mc / game eval process (voice Haiku, main chat, you).
// Without it, parallel builds compiled each other's half-written eval files and deleted them mid-run.
import fs from 'node:fs';
import path from 'node:path';

const nap = new Int32Array(new SharedArrayBuffer(4));
let held = null;

export function lock(root) {
  if (held) return;
  const f = path.join(root, 'run/moddeus/build.lock');
  fs.mkdirSync(path.dirname(f), { recursive: true });
  for (let i = 0; ; i++) {
    try { fs.writeFileSync(f, String(process.pid), { flag: 'wx' }); held = f; break; }
    catch {
      try { if (Date.now() - fs.statSync(f).mtimeMs > 60000) { fs.rmSync(f, { force: true }); continue; } } catch { continue; } // stale (crashed holder)
      if (i === 20) console.log('waiting for another build...');
      if (i > 1200) throw new Error('build lock still busy after 2 min: ' + f);
      Atomics.wait(nap, 0, 0, 100);
    }
  }
  process.on('exit', unlock);
}

export function unlock() { if (held) { fs.rmSync(held, { force: true }); held = null; } }
