// Physics regression check (run: node tools/mc.mjs game stage PhysicsTest && node tools/mc.mjs game run --file tools/tests/physics.java,
// wait ~16 s, then: node tools/mc.mjs game stage PhysicsTest report  -> every line PASS. Clean up: game run 'Stage.of("PhysicsTest").clear();'
// Covers bugs fixed 2026-11-25: stacks never slept + sank, a block 70% on another body / a world ledge tipped off, piles jittered awake.
Stage s = Stage.of("PhysicsTest");
for (var e : s.level().getEntitiesOfClass(dev.moddeus.content.PhysicsBlock.class, s.box().inflate(48, 64, 48))) e.discard();
s.ready();
var L = s.level();
int stone = net.minecraft.world.level.block.Block.getId(Blocks.STONE.defaultBlockState());
int log = net.minecraft.world.level.block.Block.getId(Blocks.OAK_LOG.defaultBlockState());
double Y = s.pos(0, 0, 0).y;
var tower = new java.util.ArrayList<dev.moddeus.content.PhysicsBlock>();
for (int i = 0; i < 5; i++) tower.add(dev.moddeus.content.Physics.spawn(L, "block#" + stone, s.pos(8, 0.5 + i * 1.2, 8), Vec3.ZERO));
var base = dev.moddeus.content.Physics.spawn(L, "block#" + stone, s.pos(-8, 0.5, -8), Vec3.ZERO);
var top = dev.moddeus.content.Physics.spawn(L, "block#" + stone, s.pos(-7.7, 4.5, -8), Vec3.ZERO);
s.place(8, 0, -8, "minecraft:stone");
var ledge = dev.moddeus.content.Physics.spawn(L, "block#" + stone, s.pos(8.3, 4.5, -8), Vec3.ZERO);
var r = new java.util.Random(4);
var pile = new java.util.ArrayList<dev.moddeus.content.PhysicsBlock>();
for (int i = 0; i < 30; i++) { var b = dev.moddeus.content.Physics.spawn(L, i % 3 == 0 ? "body#0,0,0," + log + ";1,0,0," + log : "block#" + stone, s.pos(r.nextDouble() * 4 - 2, 2 + i * 0.9, r.nextDouble() * 4 - 2), Vec3.ZERO); b.applyImpulse(b.position().add(0.3, 0.2, 0), new Vec3(r.nextDouble() - 0.5, 0, r.nextDouble() - 0.5)); pile.add(b); }
s.after(300, () -> {
  boolean towerOk = true; for (int i = 0; i < 5; i++) towerOk &= tower.get(i).asleep && Math.abs(tower.get(i).getY() - Y - 0.5 - i) < 0.06;
  s.log((towerOk ? "PASS" : "FAIL") + " stack of 5 sleeps, upright, sinks < 6 cm");
  s.log((top.getY() - Y > 1.4 && top.asleep ? "PASS" : "FAIL") + " block 70% on another body stays on top (y=" + String.format("%.2f", top.getY() - Y) + ")");
  s.log((ledge.getY() - Y > 1.4 && ledge.asleep ? "PASS" : "FAIL") + " block 70% on a world ledge stays (y=" + String.format("%.2f", ledge.getY() - Y) + ")");
  int awake = 0; for (var b : pile) if (!b.asleep) awake++;
  s.log((awake == 0 ? "PASS" : "FAIL") + " pile of 30 settles and sleeps (awake " + awake + ")");
});
print("running: report in ~16 s");
