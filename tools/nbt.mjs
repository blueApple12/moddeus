// Minimal NBT (gzipped or raw) read/write that keeps every tag type exactly, for small save files (game_rules.dat).
import zlib from 'node:zlib';

export function read(buf) {
  let gz = false;
  try { buf = zlib.gunzipSync(buf); gz = true; } catch {}
  let i = 0;
  const str = () => { const n = buf.readUInt16BE(i); i += 2; const s = buf.toString('utf8', i, i + n); i += n; return s; };
  function val(t) {
    switch (t) {
      case 1: return { t, v: buf.readInt8(i++) };
      case 2: { const v = buf.readInt16BE(i); i += 2; return { t, v }; }
      case 3: { const v = buf.readInt32BE(i); i += 4; return { t, v }; }
      case 4: { const v = buf.readBigInt64BE(i); i += 8; return { t, v }; }
      case 5: { const v = buf.readFloatBE(i); i += 4; return { t, v }; }
      case 6: { const v = buf.readDoubleBE(i); i += 8; return { t, v }; }
      case 7: { const n = buf.readInt32BE(i); i += 4; const v = buf.subarray(i, i + n); i += n; return { t, v }; }
      case 8: return { t, v: str() };
      case 9: { const et = buf[i++], n = buf.readInt32BE(i); i += 4; const v = []; for (let k = 0; k < n; k++) v.push(val(et)); return { t, et, v }; }
      case 10: { const v = new Map(); for (;;) { const tt = buf[i++]; if (tt === 0) return { t, v }; const name = str(); v.set(name, val(tt)); } }
      case 11: { const n = buf.readInt32BE(i); i += 4; const v = buf.subarray(i, i + 4 * n); i += 4 * n; return { t, n, v }; }
      case 12: { const n = buf.readInt32BE(i); i += 4; const v = buf.subarray(i, i + 8 * n); i += 8 * n; return { t, n, v }; }
      default: throw new Error('bad NBT tag ' + t);
    }
  }
  const t = buf[i++], name = str();
  return { gz, name, root: val(t) };
}

export function write({ gz, name, root }) {
  const parts = [];
  const u8 = n => { const b = Buffer.alloc(1); b.writeUInt8(n); parts.push(b); };
  const s = x => { const d = Buffer.from(x, 'utf8'), b = Buffer.alloc(2); b.writeUInt16BE(d.length); parts.push(b, d); };
  function val(n) {
    const b8 = Buffer.alloc(8), b4 = Buffer.alloc(4), b2 = Buffer.alloc(2);
    switch (n.t) {
      case 1: { const b = Buffer.alloc(1); b.writeInt8(n.v); parts.push(b); break; }
      case 2: b2.writeInt16BE(n.v); parts.push(b2); break;
      case 3: b4.writeInt32BE(n.v); parts.push(b4); break;
      case 4: b8.writeBigInt64BE(n.v); parts.push(b8); break;
      case 5: b4.writeFloatBE(n.v); parts.push(b4); break;
      case 6: b8.writeDoubleBE(n.v); parts.push(b8); break;
      case 7: b4.writeInt32BE(n.v.length); parts.push(b4, Buffer.from(n.v)); break;
      case 8: s(n.v); break;
      case 9: u8(n.et); b4.writeInt32BE(n.v.length); parts.push(b4); n.v.forEach(val); break;
      case 10: for (const [k, c] of n.v) { u8(c.t); s(k); val(c); } u8(0); break;
      case 11: case 12: b4.writeInt32BE(n.n); parts.push(b4, Buffer.from(n.v)); break;
    }
  }
  u8(root.t); s(name); val(root);
  const out = Buffer.concat(parts);
  return gz ? zlib.gzipSync(out) : out;
}
