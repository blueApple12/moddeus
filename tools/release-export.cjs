// Copies the public (core-only) tree into a target dir: skips personal/IP mods, packs, unlicensed 3rd-party skills.
// Usage: node tools/release-export.cjs <project> <target>  (target = git worktree of branch "public", remote origin = github moddeus)
const fs=require('fs'),path=require('path'),cp=require('child_process');
const [SRC,DST]=process.argv.slice(2);
const files=cp.execSync('git ls-files -co --exclude-standard',{cwd:SRC,encoding:'utf8',maxBuffer:64e6}).trim().split('\n');
const IP=/symbiote|infinity|gauntlet|venom|carnage|creative_?flight|thanos|spider|bugle/i;
const SKIP=f=>IP.test(f)||/^packs\//.test(f)||/^\.agents\//.test(f)||/^\.claude\/skills\/(create-game-vfx|game-vfx|pixel-art)(\/|$)/.test(f)||f==='skills-lock.json';
const DOC=/\.(md|java|mjs)$/;
let n=0,skipped=0;
for(const f of files){if(SKIP(f)){skipped++;continue}const s=path.join(SRC,f);let st;try{st=fs.lstatSync(s)}catch{continue}if(!st.isFile())continue;const d=path.join(DST,f);fs.mkdirSync(path.dirname(d),{recursive:true});
 let buf=fs.readFileSync(s);
 if(DOC.test(f)){let t=buf.toString('utf8');
  if(f.endsWith('.md'))t=t.split('\n').filter(l=>!/symbiote|venom|carnage|spider-man|thanos/i.test(l)).join('\n');
  t=t.replace(/Infinity Gauntlet/g,'Storm Staff').replace(/InfinityGauntlet/g,'StormStaff').replace(/infinity:gauntlet/g,'storm:staff').replace(/infinity:infinity_gauntlet/g,'storm:storm_staff').replace(/infinity:/g,'storm:');buf=Buffer.from(t)}
 if(f==='src/main/resources/assets/moddeus/lang/en_us.json'){const j=JSON.parse(buf.toString());for(const k of Object.keys(j))if(IP.test(k)||IP.test(j[k]))delete j[k];buf=Buffer.from(JSON.stringify(j,null,2)+'\n')}
 if(f==='src/main/resources/assets/moddeus_keys/lang/en_us.json')buf=Buffer.from('{}\n');
 fs.writeFileSync(d,buf);n++}
console.log('copied',n,'skipped',skipped);
