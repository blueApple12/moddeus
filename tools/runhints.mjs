// Hints for game run compile errors: the 26.3 API mistakes in-game chats hit again and again (learned from chat transcripts).
// Add a [regex, hint] pair when a chat wastes a retry on a new one. Guide: .claude/skills/mc-content/GAME-RUN.md
const HINTS = [
  [/method setScreen\(|variable screen\s+location: variable mc/, 'screens live on Gui in 26.3: mc.gui.setScreen(null) closes, mc.gui.screen() reads; client work goes in mc.execute(() -> ...)'],
  [/PermissionSet/, 'permission levels are objects: LevelBasedPermissionSet.OWNER (net.minecraft.server.permissions, auto-imported), e.g. source.withPermission(LevelBasedPermissionSet.OWNER); simplest: cmd("...") runs as op'],
  [/reference to getEntities is ambiguous/, 'cast the null: level.getEntities((Entity) null, box, e -> ...), or level.getEntitiesOfClass(LivingEntity.class, box)'],
  [/method drop in class .*LivingEntity|required: .*ItemStack,boolean,net\.minecraft\.util\.Prediction/, 'drop needs a 3rd arg: e.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY)'],
  [/hurtMarked/, '26.3: e.needsSync = true after setDeltaMovement (no hurtMarked)'],
  [/variable (mc|player|level|server) is already defined/, 'mc, server, player, level, print() (and Quick: me(), world(), cmd()) are predefined: use them, do not redeclare'],
  [/missing return value/, 'the snippet is the body of a String method: end early with plain return; (rewritten for you) or return "text";'],
  [/class (EnderMan|Zombie|Husk|Pig|Cat|Cow|Skeleton|Spider|Creeper|Villager)\b|variable [A-Z_]+ location: class (net\.minecraft\.world\.entity\.)?EntityType\b|package net\.minecraft\.world\.entity\.(monster|animal)\.\w+ does not exist/, 'entity type constants are EntityTypes.HUSK (not EntityType.X); mob classes sit in sub-packages (monster.zombie.Zombie, monster.Enderman, animal.pig.Pig): or match by id / use cmd("summon ...")'],
  [/is not public in dev\.moddeus|has private access|has protected access/, 'not visible: client classes (client/content/<Id>Client) and snippets are in OTHER packages than content/<Id>, so anything they call must be public. Make it public and save (mod classes take any change live); snippets can also use reflection: var f = X.class.getDeclaredField("name"); f.setAccessible(true);'],
  [/cannot infer type for local variable[\s\S]{0,80}'null'/, 'var x = null; does not compile: write the type (Entity x = null;)'],
  [/illegal escape character|unclosed character literal|unclosed string literal/, 'shell quoting broke the Java: write the snippet to a file and run node tools/mc.mjs game run --file <path>'],
  [/getDayTime\(\)/, 'time getters changed in 26.3: cmd("time query daytime") prints it; check names with .tools/jbr/bin/javap -cp <common jar> net.minecraft.world.level.Level'],
  [/class BlockState|class ItemEntity|package Heightmap/, 'auto-imported now (block.state.*, entity.item.*, levelgen.Heightmap): retry; other classes need their full package name'],
];
export function hints(err) {
  const out = [];
  for (const [re, h] of HINTS) if (re.test(err)) out.push('hint: ' + h);
  return out.join('\n');
}
