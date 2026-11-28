// Combat & abilities (core live/Combat.java + client/CombatClient.java). Dispatched from tools/mc.mjs. Guide: .claude/skills/mc-content/COMBAT.md
//   node tools/mc.mjs combat <ability> [args]   runs Combat.exec(me(), ...) through game run (on the stage actor when a stage is up). No args = list.
//   Java (mod code): dev.moddeus.live.Combat.dash(player, 1.6) / Combat.exec(caster, "dash", "1.6"). No /combat command.
//   These abilities are engine SAMPLES: a mod's signature abilities are its own Java (content/<Mod>*.java, guide COMBAT.md).
const ABILITIES = `shoot <k=v...>  speed homing bounces pierce damage radius cluster chain boomerang life gravity item trail hit type fire effect
beam [ticks=40] [range=24] [dps=10] [type]   chain [jumps=4] [range=8] [damage=6]   rain [radius=5] [count=40] [ticks=40]
dash [strength=1.6] [iframes=8]   blink [range=12]   grapple [range=32] [speed=1.4]   slam [radius=4] [damage=8]   superjump
perk <double_jump|glide|wall_run> [on|off]   shield [seconds=8] [hits=3]   parry [ticks=10]   iframes <ticks>
summon [entity=wolf] [count=3] [seconds=30]   turret [seconds=20]   decoy [seconds=10]   totem <heal|slow|freeze|damage|pull|push|fire> [radius] [seconds]
zone <kind> [radius=5] [seconds=8] [power]   aura <kind> [radius=4] [seconds=10] [power]   mine <blast|root|launch|freeze> [power=8]
use <name> <cooldownTicks> [cost]   energy <max> [regenPerSec]   blast [radius] [damage] [type]   clear`;
export const HELP = 'combat <ability> [args]   (live/Combat, guide COMBAT.md)\n' + ABILITIES.split('\n').map(l => '  ' + l).join('\n');

export async function run(cmd, rest) {
  if (cmd !== 'combat') return false;
  if (!rest.length) { console.log(HELP); return true; }
  const G = await import('./game.mjs');
  console.log("preset = sample only: write this mod's own version (guide: .claude/skills/mc-content/COMBAT.md)");
  await G.run(['run', 'print(dev.moddeus.live.Combat.exec(me(), ' + rest.map(a => JSON.stringify(String(a))).join(', ') + '));']);
  return true;
}
