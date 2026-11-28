package dev.moddeus.client;

import dev.moddeus.live.LiveRegistry;
import dev.moddeus.live.OnReload;
import dev.moddeus.live.OnUnload;
import dev.moddeus.live.WorldFeel;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BannerPattern;

/** Client half of live/WorldFeel: ambience on the client's biomes/levels (re-applied on join and dimension change), toasts, live banner patterns. */
public final class WorldFeelClient {
	static ClientLevel last;
	static String colorSig = "";

	@OnReload
	public static void hook() {
		WorldFeel.client = WorldFeelClient::fromServer;
		if (System.getProperties().putIfAbsent("moddeus.worldfeelclient", Boolean.TRUE) == null)
			net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(WorldFeelClient::tick);
		Minecraft.getInstance().execute(WorldFeelClient::apply);
	}

	@OnUnload
	public static void unhook() { Minecraft.getInstance().execute(WorldFeelClient::apply); WorldFeel.client = null; }

	static void tick(Minecraft mc) { if (mc.level != null && mc.level != last) { last = mc.level; apply(); } }

	static void fromServer(String what, Object arg) {
		Minecraft mc = Minecraft.getInstance();
		switch (what) {
			case "ambience" -> mc.execute(WorldFeelClient::apply);
			case "toast" -> { Object[] a = (Object[]) arg; mc.execute(() -> toast(a)); }
			case "banner" -> { Object[] a = (Object[]) arg; mc.execute(() -> banner(a)); }
			default -> {}
		}
	}

	static void apply() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.getConnection() == null) return;
		boolean colors = WorldFeel.applyAmbience(mc.getConnection().registryAccess(), List.<Level>of(mc.level));
		String sig = WorldFeel.colorSignature(); // client + integrated server may share biome objects: the server pass already changed them
		if (colors || !sig.equals(colorSig)) { colorSig = sig; mc.level.clearTintCaches(); mc.levelExtractor.allChanged(); }
	}

	static void banner(Object[] a) {
		var c = Minecraft.getInstance().getConnection();
		if (c == null) return;
		var reg = c.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN);
		BannerPattern bp = (BannerPattern) a[1];
		if (!reg.containsKey((Identifier) a[0])) LiveRegistry.register(reg, (Identifier) a[0], k -> bp);
	}

	static void toast(Object[] a) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !mc.player.getUUID().equals(a[0])) return;
		var item = BuiltInRegistries.ITEM.getOptional(Identifier.parse((String) a[1]));
		mc.gui.toastManager().addToast(new FeelToast(new ItemStack(item.orElse(net.minecraft.world.item.Items.PAPER)), (String) a[2], (String) a[3], (Integer) a[4]));
	}

	/** Advancement-sized toast: dark panel, accent bar + border in the mod's color, item icon, title + one line. */
	public static final class FeelToast implements Toast {
		final ItemStack icon; final String title, text; final int color;
		Visibility vis = Visibility.SHOW;
		public FeelToast(ItemStack icon, String title, String text, int color) { this.icon = icon; this.title = title; this.text = text; this.color = color; }
		@Override public Visibility getWantedVisibility() { return vis; }
		@Override public void update(ToastManager m, long t) { vis = t >= 5000 * m.getNotificationDisplayTimeMultiplier() ? Visibility.HIDE : Visibility.SHOW; }
		@Override public void extractRenderState(GuiGraphicsExtractor g, Font font, long t) {
			int w = width(), h = height();
			g.fill(0, 0, w, h, 0xE0101018);
			g.fill(0, 0, 3, h, color);
			g.outline(0, 0, w, h, (color & 0x00FFFFFF) | 0x90000000);
			g.item(icon, 8, 8);
			g.text(font, title, 30, text.isEmpty() ? 12 : 7, color | 0xFF000000, false);
			if (!text.isEmpty()) g.text(font, text, 30, 18, 0xFFE8E8F0, false);
		}
	}
}
