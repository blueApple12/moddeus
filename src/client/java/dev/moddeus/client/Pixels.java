package dev.moddeus.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.moddeus.Moddeus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.Identifier;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hot-swap fast path for a texture REDRAW: when only the pixels of existing, same-size, non-animated atlas sprites changed
 * (same alpha mask, so item extrusion geometry and render layers stay valid), the new pixels and mips are written straight
 * into the atlas texture: ~1 ms instead of a light reload that re-stitches every atlas (~90-180 ms frame hitch).
 * Anything else returns false and the caller does the normal light reload.
 */
public final class Pixels {
	/** Uploaded contents stay alive until replaced: the GPU frontend may read the image after this frame. */
	private static Map<String, SpriteContents> live;

	public static boolean redraw(Minecraft mc, List<String> changed) {
		if (changed.isEmpty()) return false;
		List<Object[]> todo = new ArrayList<>(); // {atlas, sprite, contents, path}
		try {
			for (String p : changed) {
				String[] parts = p.split("/", 3); // assets/<ns>/textures/...png
				if (parts.length < 3 || !parts[0].equals("assets") || !parts[2].startsWith("textures/") || !p.endsWith(".png")) return fail(todo);
				Identifier file = Identifier.fromNamespaceAndPath(parts[1], parts[2]);
				if (mc.getResourceManager().getResource(file.withSuffix(".mcmeta")).isPresent()) return fail(todo); // animated / custom mip or blur settings
				Identifier id = Identifier.fromNamespaceAndPath(parts[1], parts[2].substring("textures/".length(), parts[2].length() - 4));
				TextureAtlas[] at = new TextureAtlas[1];
				TextureAtlasSprite[] sp = new TextureAtlasSprite[1];
				mc.getAtlasManager().forEach((loc, atlas) -> {
					TextureAtlasSprite s = atlas.getSprite(id);
					if (s != atlas.missingSprite() && sp[0] == null) { at[0] = atlas; sp[0] = s; }
				});
				if (sp[0] == null) return fail(todo); // new texture, or not an atlas sprite (entity/gui: own listener)
				SpriteContents old = sp[0].contents();
				if (old.isAnimated()) return fail(todo);
				var res = mc.getResourceManager().getResource(file);
				if (res.isEmpty()) return fail(todo);
				NativeImage img;
				try (InputStream in = res.get().open()) { img = NativeImage.read(in); }
				if (img.getWidth() != old.width() || img.getHeight() != old.height()) { img.close(); return fail(todo); }
				for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++)
					if (((img.getPixel(x, y) >>> 24) == 0) != old.isTransparent(0, x, y)) { img.close(); return fail(todo); } // shape changed: models must rebake
				SpriteContents fresh = new SpriteContents(id, new FrameSize(img.getWidth(), img.getHeight()), img);
				todo.add(new Object[]{at[0], sp[0], fresh, p});
				if (fresh.transparency() != old.transparency()) return fail(todo); // render layer would change
			}
			Field maxMip = TextureAtlas.class.getDeclaredField("maxMipLevel");
			maxMip.setAccessible(true);
			Field mips = SpriteContents.class.getDeclaredField("byMipLevel");
			mips.setAccessible(true);
			var enc = RenderSystem.getDevice().createCommandEncoder();
			if (live == null) live = new HashMap<>();
			for (Object[] t : todo) {
				TextureAtlas atlas = (TextureAtlas) t[0];
				TextureAtlasSprite s = (TextureAtlasSprite) t[1];
				SpriteContents c = (SpriteContents) t[2];
				int levels = Math.min(maxMip.getInt(atlas), atlas.getTexture().getMipLevels() - 1);
				c.increaseMipLevel(levels);
				NativeImage[] byMip = (NativeImage[]) mips.get(c);
				for (int l = 0; l <= levels && l < byMip.length; l++) enc.writeToTexture(atlas.getTexture(), byMip[l], l, 0, s.getX() >> l, s.getY() >> l);
				SpriteContents prev = live.put((String) t[3], c);
				if (prev != null) prev.close();
			}
			invalidateGuiItems(mc);
			Moddeus.LOG.info("Pixel redraw (no reload) for {}", changed);
			return true;
		} catch (Throwable e) {
			Moddeus.LOG.info("Pixel redraw not possible ({}), light reload instead", e.toString());
			return fail(todo);
		}
	}

	private static boolean fail(List<Object[]> todo) {
		for (Object[] t : todo) if (live == null || !live.containsValue(t[2])) ((SpriteContents) t[2]).close();
		todo.clear();
		return false;
	}

	/** Inventory icons are cached in the GUI item atlas: drop it so they re-render with the new pixels. */
	private static void invalidateGuiItems(Minecraft mc) {
		try {
			Field f = mc.gameRenderer.getClass().getDeclaredField("guiRenderer");
			f.setAccessible(true);
			Object gr = f.get(mc.gameRenderer);
			var m = gr.getClass().getDeclaredMethod("invalidateItemAtlas");
			m.setAccessible(true);
			m.invoke(gr);
		} catch (Throwable ignored) {}
	}
}
