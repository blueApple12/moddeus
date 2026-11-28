package dev.moddeus.client;

import dev.moddeus.live.Combat;
import dev.moddeus.live.OnReload;
import dev.moddeus.live.OnUnload;
import java.util.UUID;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Client half of live/Combat: movement perks that need the player's own input (double jump, glide, wall run) and the
 * charge bar + combo counter (no energy/cooldown widget: each mod draws its own). Singleplayer: reads Combat.Stats directly.
 * HUD look: arena HUD · slanted segments + round dials · segments drain/refill with a sweep, low energy pulses · teal energy, gold charge, red low.
 */
public final class CombatClient {
	static final String MOD = "Moddeus Combat";
	static boolean jumpWas, usedDouble, gliding;
	static int wallTicks, t;

	@OnReload
	public static void hook() {
		ModGui.hud(MOD, "hud", CombatClient::draw);
		if (System.getProperties().putIfAbsent("moddeus.combatclient", Boolean.TRUE) == null) ClientTickEvents.END_CLIENT_TICK.register(CombatClient::tick);
	}

	@OnUnload
	public static void unload() { ModGui.removeHud(MOD, "hud"); }

	static void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || !Combat.enabled) return;
		Combat.Stats s = Combat.peek(p.getUUID());
		boolean jump = mc.options.keyJump.isDown();
		t++;
		if (s == null || s.perks.isEmpty()) { jumpWas = jump; return; }
		boolean free = !p.onGround() && !p.getAbilities().flying && !p.isInWater() && !p.onClimbable() && !p.isPassenger() && !p.isFallFlying();
		if (p.onGround() || !free) { usedDouble = false; wallTicks = 0; gliding = false; }
		int air = AirTime.tick(free);
		Vec3 v = p.getDeltaMovement();
		boolean pressed = jump && !jumpWas;
		boolean wallRun = s.perks.contains("wall_run") && free && p.horizontalCollision && mc.options.keyUp.isDown() && wallTicks < 40;
		if (wallRun) {
			wallTicks++;
			Vec3 look = p.getLookAngle().multiply(1, 0, 1).normalize();
			if (pressed) { // wall jump: kick off backwards and up
				p.setDeltaMovement(-look.x * 0.6, 0.6, -look.z * 0.6);
				wallTicks = 40;
				server(p, "combat_double_jump");
			} else {
				p.setDeltaMovement(v.x * 1.05 + look.x * 0.03, Math.max(v.y, wallTicks < 12 ? 0.08 : 0.0), v.z * 1.05 + look.z * 0.03);
				if (wallTicks % 8 == 1) server(p, null);
			}
		} else if (s.perks.contains("double_jump") && pressed && free && air > 2 && !usedDouble) {
			usedDouble = true;
			p.setDeltaMovement(v.x * 1.15, 0.62, v.z * 1.15);
			server(p, "combat_double_jump");
		} else if (s.perks.contains("glide") && jump && free && v.y < -0.08) {
			Vec3 look = p.getLookAngle().multiply(1, 0, 1).normalize();
			double hs = Math.min(0.7, Math.hypot(v.x, v.z) + 0.02);
			Vec3 h = new Vec3(v.x, 0, v.z).scale(0.9).add(look.scale(0.1));
			h = h.lengthSqr() < 1e-6 ? h : h.normalize().scale(hs);
			p.setDeltaMovement(h.x, Math.max(v.y, -0.1), h.z);
			if (!gliding || t % 10 == 0) server(p, t % 20 == 0 || !gliding ? "combat_glide" : null);
			gliding = true;
		}
		jumpWas = jump;
	}

	/** Resets server-side fall distance (and plays a VFX) for the local player. */
	static void server(LocalPlayer p, String fx) {
		var srv = Minecraft.getInstance().getSingleplayerServer();
		if (srv == null) return;
		UUID u = p.getUUID();
		srv.execute(() -> {
			var sp = srv.getPlayerList().getPlayer(u);
			if (sp == null) return;
			sp.resetFallDistance();
			if (fx != null) Combat.fx(fx, sp.position(), sp.getLookAngle(), sp);
		});
	}

	static void draw(ModGui.Ui ui) {
		var mc = Minecraft.getInstance();
		if (mc.player == null || !Combat.enabled) return;
		Combat.Stats s = Combat.peek(mc.player.getUUID());
		if (s == null) return;
		long now = Combat.now();
		boolean combo = s.combo > 1 && now <= s.comboUntil;
		float energyFrac = s.max <= 0 ? 1 : Math.max(0, Math.min(1, s.energy / s.max));
		boolean show = s.charging != null || combo;
		float vis = ui.anim("cmb.vis", show ? 1 : 0, 8);
		if (vis < 0.02f) return;
		int gold = 0xFFFFC24A, red = 0xFFFF4A5A, dark = 0xFF071618;
		// no energy/cooldown widget: mods draw their own (ModGui.hud, GUI.md)
		// charge bar under the crosshair
		float ch = ui.anim("cmb.charge", s.charging != null ? s.charge : 0, 20);
		float chVis = ui.anim("cmb.chvis", s.charging != null ? 1 : 0, 10);
		if (chVis > 0.02f) {
			int bw = 64, bx = ui.w / 2 - bw / 2, by = ui.h / 2 + 14;
			boolean full = ch > 0.99f;
			ui.rect(bx - 1, by - 1, bw + 2, 5, ModGui.Ui.alpha(dark, 0.7f * chVis));
			ui.rect(bx, by, Math.round(bw * ch), 3, ModGui.Ui.alpha(full ? ModGui.Ui.mix(gold, 0xFFFFFFFF, ModGui.Ui.pulse(ui.t, 6)) : gold, chVis));
			if (s.charging != null) ui.center(s.charging, ui.w / 2, by + 6, ModGui.Ui.alpha(0xFFFFE6A8, chVis));
		}
		// combo counter right of the crosshair, pops on each hit
		float cv = ui.anim("cmb.combo", combo ? 1 : 0, 12);
		if (cv > 0.02f) {
			float pop = 1 + 0.6f * Math.max(0, 1 - ui.anim("cmb.pop" + s.combo, 1, 9));
			ui.big("x" + s.combo, ui.w / 2 + 16, ui.h / 2 - 6, 1.4f * pop, ModGui.Ui.alpha(ModGui.Ui.mix(gold, red, Math.min(1, s.combo / 8f)), cv), false);
		}
	}

	static String abbreviation(String name) {
		String[] w = name.trim().split("[\\s_]+");
		if (w.length >= 2) return ("" + w[0].charAt(0) + w[1].charAt(0)).toUpperCase();
		return name.length() <= 2 ? name.toUpperCase() : name.substring(0, 1).toUpperCase() + name.substring(1, 2);
	}
}
