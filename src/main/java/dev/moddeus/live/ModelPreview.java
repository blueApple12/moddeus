package dev.moddeus.live;

import java.util.Locale;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * Model preview (mc model preview <id>): puts the item on a mannequin (3rd person), on item displays in GUI / item
 * frame / ground mode in front of the player, and briefly in the player's own hand (1st person), for one screenshot.
 * Never moves the camera. clear() removes everything and gives the hand item back (auto after 10 s).
 */
public final class ModelPreview {
	static ItemStack saved;
	static int slot = -1;
	static long shownAt;

	public static String show(String spec, boolean hand) {
		clear();
		ServerPlayer p = Quick.me();
		if (p == null) return "no player";
		double yaw = Math.toRadians(p.getYRot());
		double fx = -Math.sin(yaw), fz = Math.cos(yaw), rx = -Math.cos(yaw), rz = -Math.sin(yaw);
		var look = p.getLookAngle(); // follow the view (also when looking down), never move the camera
		var eye = p.getEyePosition();
		double dist = Math.max(1.6, Math.min(2.4, p.pick(4.0, 1f, false).getLocation().distanceTo(eye) - 0.7)); // in front of the ground/wall you look at
		double cx = eye.x + look.x * dist, cy = eye.y + look.y * dist, cz = eye.z + look.z * dist;
		double x = cx - fx * 3.0, y = cy - 0.9, z = cz - fz * 3.0, ey = cy + 0.15;
		float face = p.getYRot() + 180;
		String tag = "Tags:[\"mdl_preview\",\"%s\"],Invulnerable:1b,NoGravity:1b,";
		// mannequin a bit left of centre, turned so its right (item) hand shows
		Quick.cmd(f("summon mannequin %.3f %.3f %.3f {" + tag + "Rotation:[%.1ff,0f],immovable:1b,hide_description:1b}",
			x + fx * 3.0 - rx * 0.6, y, z + fz * 3.0 - rz * 0.6, "mdl_m", face - 55));
		String[] modes = { "gui", "fixed", "ground" };
		for (int i = 0; i < modes.length; i++) {
			double side = 0.45 + i * 0.75;
			Quick.cmd(f("summon item_display %.3f %.3f %.3f {" + tag + "item_display:\"%s\",Rotation:[%.1ff,0f],brightness:{sky:15,block:15},"
				+ "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],scale:[0.8f,0.8f,0.8f]}}",
				x + fx * 3.0 + rx * side, ey - 0.15, z + fz * 3.0 + rz * side, "mdl_" + modes[i], modes[i], face));
			Quick.cmd("item replace entity @e[tag=mdl_" + modes[i] + ",limit=1] contents with " + spec);
		}
		Quick.cmd("item replace entity @e[tag=mdl_m,limit=1] weapon.mainhand with " + spec);
		var m = Quick.world().getEntities((net.minecraft.world.entity.Entity) null, p.getBoundingBox().inflate(8), e -> e.entityTags().contains("mdl_m"));
		if (!m.isEmpty() && m.get(0) instanceof net.minecraft.world.entity.LivingEntity le) {
			var eq = le.getMainHandItem().get(DataComponents.EQUIPPABLE);
			if (eq != null && eq.slot() == EquipmentSlot.HEAD) { le.setItemSlot(EquipmentSlot.HEAD, le.getMainHandItem().copy()); le.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY); }
		}
		if (hand) {
			slot = p.getInventory().getSelectedSlot();
			saved = p.getInventory().getItem(slot).copy();
			Quick.cmd("item replace entity @s weapon.mainhand with " + spec);
		}
		shownAt = System.currentTimeMillis();
		Quick.after(220, ModelPreview::timeout);
		return "preview of " + spec + " shown";
	}

	static void timeout() { if (shownAt != 0 && System.currentTimeMillis() - shownAt > 9000) clear(); }

	public static String clear() {
		Quick.cmd("kill @e[tag=mdl_preview]");
		ServerPlayer p = Quick.me();
		if (p != null && saved != null && slot >= 0) p.getInventory().setItem(slot, saved);
		saved = null;
		slot = -1;
		shownAt = 0;
		return "preview cleared";
	}

	static String f(String s, Object... a) { return String.format(Locale.ROOT, s, a); }
}
