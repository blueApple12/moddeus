// GenMigrator check, no game needed. Run after a build:
//   .tools/jbr/bin/java -cp build/classes/java/client tools/tests/gens-migrate.java   -> every line PASS
// Covers the 2026-11-27 crash: Brickjaw JOBS (List<Object> of Object[]) still held BrickFight__g32 after a swap, the new code
// cast it to SuperpowersVillainKit__g33$Fight -> ClassCastException in the server tick.
import java.util.*;

public class GensMigrateTest {
	public static class Fight__g1 { public String id = "a"; public List<Object> fx = new ArrayList<>(); public int hp = 5; }
	public static class Fight__g2 { public String id; public List<Object> fx; public int hp; public double added = 7; }
	public static class Fx__g1 { public boolean dead; public Fight__g1 owner; }
	public static class Fx__g2 { public boolean dead; public Fight__g2 owner; }
	public enum Mode__g1 { A, B }
	public enum Mode__g2 { A, B }
	public record Rec__g1(String s, Object f) {}
	public record Rec__g2(String s, Object f) {}

	static int bad;
	static void check(boolean ok, String what) { System.out.println((ok ? "PASS " : "FAIL ") + what); if (!ok) bad++; }

	public static void main(String[] args) throws Exception {
		var mc = Class.forName("dev.moddeus.client.GenMigrator");
		var ctor = mc.getDeclaredConstructor(List.class, List.class); ctor.setAccessible(true);
		Object mig = ctor.newInstance(List.of(Fight__g1.class, Fx__g1.class, Mode__g1.class, Rec__g1.class),
				List.of(Fight__g2.class, Fx__g2.class, Mode__g2.class, Rec__g2.class));
		var fix = mc.getDeclaredMethod("fix", Object.class); fix.setAccessible(true);

		Fight__g1 f = new Fight__g1();
		Fx__g1 x = new Fx__g1(); x.owner = f; f.fx.add(x); // cycle
		List<Object> fights = new java.util.concurrent.CopyOnWriteArrayList<>(List.of(f));
		List<Object> jobs = new ArrayList<>(); jobs.add(new Object[]{f, 3});
		Map<UUID, Object> modes = new HashMap<>(); modes.put(UUID.randomUUID(), Mode__g1.B);
		Set<Object> recs = new HashSet<>(); recs.add(new Rec__g1("r", f));

		fix.invoke(mig, jobs); // jobs first: the fight must still end up the SAME object as in FIGHTS
		fix.invoke(mig, fights);
		fix.invoke(mig, modes);
		fix.invoke(mig, recs);

		check(fights.get(0) instanceof Fight__g2, "List element rebuilt as the new generation");
		Fight__g2 nf = (Fight__g2) fights.get(0);
		check(((Object[]) jobs.get(0))[0] == nf, "Object[] inside a List: same new object as in the other list");
		check("a".equals(nf.id) && nf.hp == 5 && nf.added == 7, "fields copied by name, new field keeps its initializer");
		check(nf.fx.get(0) instanceof Fx__g2 fx2 && fx2.owner == nf, "nested object + cycle");
		check(modes.values().iterator().next() == Mode__g2.B, "enum constant in a Map");
		check(recs.iterator().next() instanceof Rec__g2 r && "r".equals(r.s()) && r.f() == nf, "record in a Set");
		check(fix.invoke(mig, "plain") == "plain" && fix.invoke(mig, (Object) null) == null, "other values untouched");
		boolean threw = false;
		try { fix.invoke(mig, List.of(new Fight__g1())); } catch (java.lang.reflect.InvocationTargetException e) { threw = true; }
		check(threw, "immutable container with old objects throws (field starts fresh)");
		if (bad > 0) System.exit(1);
	}
}
