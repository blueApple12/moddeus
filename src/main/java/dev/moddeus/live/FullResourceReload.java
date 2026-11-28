package dev.moddeus.live;

/** Minecraft.reloadResourcePacks() (F3+T) on the client thread; reflection, as this is common code. Used by PackNamespaces. */
public final class FullResourceReload implements Runnable {
	final Object mc;

	public FullResourceReload(Object mc) { this.mc = mc; }

	public void run() {
		try { mc.getClass().getMethod("reloadResourcePacks").invoke(mc); }
		catch (Throwable t) { dev.moddeus.Moddeus.LOG.warn("resource reload failed: {}", t.toString()); }
	}
}
