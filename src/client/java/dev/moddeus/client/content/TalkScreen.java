package dev.moddeus.client.content;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** Right-click Claude: a one-line box to talk to it (sent to the Voice chat, answered in its bubble and out loud). */
public class TalkScreen extends Screen {
	EditBox box;

	public TalkScreen() { super(Component.literal("Talk to Moddeus")); }

	@Override
	protected void init() {
		int w = Math.min(320, width - 40);
		box = new EditBox(font, (width - w) / 2, height / 2 + 30, w, 20, Component.literal("Message"));
		box.setMaxLength(1000);
		box.setHint(Component.literal("Ask Moddeus or tell it what to do... (Enter)").withColor(0x8C84AE));
		addRenderableWidget(box);
		setInitialFocus(box);
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		// 26.3 key codes are SDL scancodes (Enter = 40), not GLFW (257): always use InputConstants
		if ((e.key() == com.mojang.blaze3d.platform.InputConstants.KEY_RETURN || e.key() == com.mojang.blaze3d.platform.InputConstants.KEY_NUMPADENTER) && !box.getValue().isBlank()) {
			AvatarFeed.talk(box.getValue().strip());
			onClose();
			return true;
		}
		return super.keyPressed(e);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
		g.centeredText(font, Component.literal("Talk to Moddeus").withColor(0xA58BF0), width / 2, height / 2 + 16, 0xFFA58BF0);
		super.extractRenderState(g, mx, my, pt);
	}

	@Override
	public boolean isPauseScreen() { return false; }
}
