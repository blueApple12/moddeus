package dev.moddeus.client;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.HumanoidArm;

/**
 * Pose hub for players / humanoids (needs HumanoidPoseMixin + FirstPersonArmMixin, i.e. a game restart after they were added).
 * A mod registers a Pose (third person: set model.rightArm.xRot ... after vanilla posed it) and/or an Fp (first person:
 * transform the PoseStack before the empty arm is drawn). Keyed by mod name; hot-reload safe (re-register in @OnReload).
 * entityId = AvatarRenderState.id for players/mannequins, -1 for other humanoids.
 */
public final class BendPoses {
	public interface Pose { void apply(HumanoidModel<?> m, int entityId, HumanoidRenderState s); }
	public interface Fp { void apply(PoseStack ps, boolean rightArm); }
	static Map<String, Pose> poses; static Map<String, Fp> fps;
	static int errors;
	static Map<String, Pose> poses() { if (poses == null) poses = new ConcurrentHashMap<>(); return poses; }
	static Map<String, Fp> fps() { if (fps == null) fps = new ConcurrentHashMap<>(); return fps; }
	public static void pose(String mod, Pose p) { if (p == null) poses().remove(mod); else poses().put(mod, p); }
	public static void firstPerson(String mod, Fp f) { if (f == null) fps().remove(mod); else fps().put(mod, f); }
	public static void clear(String mod) { poses().remove(mod); fps().remove(mod); }
	public static boolean active() { return true; }

	public static void apply(HumanoidModel<?> m, HumanoidRenderState s) {
		if (poses == null || poses.isEmpty()) return;
		int id = s instanceof AvatarRenderState a ? a.id : -1;
		for (Pose p : poses.values()) try { p.apply(m, id, s); } catch (Throwable t) { if (errors++ < 5) dev.moddeus.Moddeus.LOG.warn("pose", t); }
	}
	public static void firstPerson(PoseStack ps, HumanoidArm arm) {
		if (fps == null || fps.isEmpty()) return;
		for (Fp f : fps.values()) try { f.apply(ps, arm == HumanoidArm.RIGHT); } catch (Throwable t) { if (errors++ < 5) dev.moddeus.Moddeus.LOG.warn("fp pose", t); }
	}
}
