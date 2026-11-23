package dev.moddeus.client.content;

import com.google.gson.JsonObject;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;

/** Data-driven particle: frames animate over its life; settings come from the particle's content JSON. */
public class CustomParticle extends SingleQuadParticle {
	private final SpriteSet sprites;
	private final boolean glow, fade, shrink;
	private final float spin, size0, sizeEnd, wobble, swirl;
	private final float[] c0, c1; // color over life (multiplies the drawn texture; white = as drawn)
	private final double sx, sz; // spawn point (swirl orbits it)

	public CustomParticle(ClientLevel level, double x, double y, double z, double xd, double yd, double zd, SpriteSet sprites, JsonObject o) {
		super(level, x, y, z, 0, 0, 0, sprites.first());
		this.sprites = sprites;
		this.xd = xd; this.yd = yd; this.zd = zd; // exact velocity (vfx shapes rely on it)
		int life = (int) f(o, "lifetime", 20);
		this.lifetime = Math.max(1, life + (life > 4 ? random.nextInt(life / 4 + 1) : 0));
		this.gravity = f(o, "gravity", 0);
		this.friction = f(o, "friction", 0.96f);
		this.hasPhysics = o.has("collide") && o.get("collide").getAsBoolean();
		this.glow = !o.has("glow") || o.get("glow").getAsBoolean();
		this.fade = !o.has("fade") || o.get("fade").getAsBoolean();
		this.shrink = o.has("shrink") && o.get("shrink").getAsBoolean();
		this.spin = f(o, "spin", 0);
		this.quadSize *= f(o, "size", 1);
		this.size0 = quadSize;
		this.sizeEnd = o.has("size_end") ? quadSize / f(o, "size", 1) * f(o, "size_end", 1) : -1;
		this.wobble = f(o, "wobble", 0);
		this.swirl = f(o, "swirl", 0);
		this.c0 = color(o, "color");
		this.c1 = o.has("color_end") ? color(o, "color_end") : c0;
		this.sx = x; this.sz = z;
		setColor(c0[0], c0[1], c0[2]);
		this.roll = this.oRoll = spin != 0 ? random.nextFloat() * 6.28f : 0;
		setSpriteFromAge(sprites);
	}

	private static float[] color(JsonObject o, String k) {
		int v = o.has(k) ? Integer.parseInt(o.get(k).getAsString().replace("#", ""), 16) : 0xFFFFFF;
		return new float[] {(v >> 16 & 255) / 255f, (v >> 8 & 255) / 255f, (v & 255) / 255f};
	}

	private static float f(JsonObject o, String k, float d) { return o.has(k) ? o.get(k).getAsFloat() : d; }

	@Override
	public void tick() {
		super.tick();
		if (removed) return;
		setSpriteFromAge(sprites);
		float life = age / (float) lifetime;
		if (fade) alpha = 1 - life * life;
		if (shrink) quadSize = size0 * (1 - life);
		else if (sizeEnd >= 0) quadSize = size0 + (sizeEnd - size0) * life;
		setColor(c0[0] + (c1[0] - c0[0]) * life, c0[1] + (c1[1] - c0[1]) * life, c0[2] + (c1[2] - c0[2]) * life);
		if (wobble != 0) { xd += (random.nextFloat() - 0.5f) * wobble; zd += (random.nextFloat() - 0.5f) * wobble; }
		if (swirl != 0) { double dx = x - sx, dz = z - sz; xd += -dz * swirl; zd += dx * swirl; }
		oRoll = roll;
		roll += spin;
	}

	@Override
	protected int getLightCoords(float partial) { return glow ? 0xF000F0 : super.getLightCoords(partial); }

	@Override
	protected Layer getLayer() { return Layer.TRANSLUCENT; }
}
