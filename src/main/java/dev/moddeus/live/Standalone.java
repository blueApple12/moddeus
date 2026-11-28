package dev.moddeus.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.moddeus.Moddeus;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.server.MinecraftServer;

/**
 * Entry point of the Moddeus RUNTIME jar (build/runtime/moddeus-runtime.jar, mod id "moddeus") that every exported mod
 * embeds (Fabric jar-in-jar: one copy loads, the newest; a full Moddeus install wins). It is Moddeus without AI, chat,
 * bridge, game API or hot reload: content JSON, creative tabs, VFX, physics, Quick helpers, keys, GUIs, cutscenes, and
 * the @OnReload hooks of every installed exported mod (listed in its moddeus-mod.json).
 */
public final class Standalone implements ModInitializer {
	@Override
	public void onInitialize() {
		ContentLoader.loadAll();
		dev.moddeus.content.ModTab.register();
		dev.moddeus.content.Mobs.hook();
		dev.moddeus.content.Vfx.hook();
		dev.moddeus.content.Physics.hook();
		Quick.hook();
		Combat.hook();
		Dimensions.hook();
		Structures.hook();
		dev.moddeus.content.Gameplay.hook();
		Bosses.hook();
		Life.hook();
		Systems.hook();
		Effects.hook();
		Weapons.hook();
		WorldFeel.hook();
		ServerLifecycleEvents.SERVER_STARTED.register(Standalone::started);
		ServerLifecycleEvents.SERVER_STOPPED.register(Standalone::stopped);
		if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) runHooks(false); // client: StandaloneClient
		Moddeus.LOG.info("Moddeus runtime loaded ({} exported mods)", mods().size());
	}

	static void started(MinecraftServer s) { LiveRegistry.server = s; }
	static void stopped(MinecraftServer s) { LiveRegistry.server = null; }

	/** moddeus-mod.json of every installed exported mod. */
	public static List<JsonObject> mods() {
		List<JsonObject> out = new ArrayList<>();
		for (ModContainer m : FabricLoader.getInstance().getAllMods()) {
			if (m.getMetadata().getId().equals(Moddeus.MOD_ID)) continue;
			m.findPath("moddeus-mod.json").ifPresent(p -> {
				try { out.add(JsonParser.parseString(Files.readString(p)).getAsJsonObject()); }
				catch (Exception e) { Moddeus.LOG.warn("{}: bad moddeus-mod.json", m.getMetadata().getId(), e); }
			});
		}
		return out;
	}

	/** Loads each exported mod's classes and runs their @OnReload hooks (what Gens.startAll does in Moddeus). */
	public static void runHooks(boolean client) {
		for (JsonObject mod : mods()) {
			List<String> names = new ArrayList<>();
			for (String k : client ? new String[]{"classes", "clientClasses"} : new String[]{"classes"}) {
				JsonArray a = mod.has(k) ? mod.getAsJsonArray(k) : new JsonArray();
				a.forEach(e -> names.add(e.getAsString()));
			}
			int hooks = 0;
			for (String n : names) {
				if (n.contains("$")) continue;
				try {
					Class<?> c = Class.forName(n, true, Standalone.class.getClassLoader());
					for (Method m : c.getDeclaredMethods()) {
						if (!m.isAnnotationPresent(OnReload.class) || !Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0) continue;
						m.setAccessible(true);
						m.invoke(null);
						hooks++;
					}
				} catch (Throwable t) {
					Moddeus.LOG.error("Mod {}: {} failed to start", mod.has("name") ? mod.get("name").getAsString() : "?", n, t);
				}
			}
			Moddeus.LOG.info("Mod {} started ({} hooks)", mod.has("name") ? mod.get("name").getAsString() : "?", hooks);
		}
	}
}
