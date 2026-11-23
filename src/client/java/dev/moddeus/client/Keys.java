package dev.moddeus.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.moddeus.Moddeus;
import dev.moddeus.live.OnReload;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * Key binds for mods, live (no restart): each mod gets its own section in Options > Controls > Key Binds, named after the
 * mod, so the player can see and rebind them. Saved rebinds come back from options.txt.
 *
 * Java mods (client code):  Keys.bind("Storm Staff", "Snap fingers", "G", player -> ...);   // runs on the server thread
 *                            Keys.bindClient(mod, label, key, () -> ...);  Keys.down(mod, label) = held right now
 * Java mods (common code):   Quick.key(mod, label, key, player -> ...)
 * Datapack mods:             "keys" in the pack's moddeus.json (see PackExtras): runs a function as the player.
 * Key names: letters/digits, F1-F12, LEFT_ALT, TAB, CAPS_LOCK, SPACE, MOUSE4, MOUSE5, NONE... Taken by Moddeus: K (Claude), G (mods screen).
 * Re-binding the same mod+label replaces its action (new code generations), so it never doubles.
 */
public final class Keys {
	record Bind(String mod, String label, KeyMapping mapping, Consumer<ServerPlayer> server, Runnable client) {}

	static Map<String, Bind> binds;
	static Map<String, KeyMapping.Category> categories;
	static Map<String, String> lang;

	static Map<String, Bind> binds() { if (binds == null) binds = new ConcurrentHashMap<>(); return binds; }
	static Map<String, KeyMapping.Category> cats() { if (categories == null) categories = new ConcurrentHashMap<>(); return categories; }
	static Map<String, String> lang() { if (lang == null) lang = new ConcurrentHashMap<>(); return lang; }

	public static KeyMapping bind(String mod, String label, String key, Consumer<ServerPlayer> onServer) { return add(mod, label, key, onServer, null); }

	public static KeyMapping bindClient(String mod, String label, String key, Runnable onClient) { return add(mod, label, key, null, onClient); }

	/** Is that key held right now (for charge-up / while-held abilities)? */
	public static boolean down(String mod, String label) {
		Bind b = binds().get(id(mod, label));
		return b != null && b.mapping.isDown();
	}

	/** Remove a mod's keys completely (deleted mod / test): gone from Controls too. */
	public static void remove(String mod) {
		Minecraft mc = Minecraft.getInstance();
		java.util.Set<KeyMapping> gone = new java.util.HashSet<>();
		binds().values().removeIf(b -> { if (b.mod.equals(mod)) { gone.add(b.mapping); lang().remove(b.mapping.getName()); return true; } return false; });
		lang().remove("key.category.moddeus." + slug(mod));
		if (mc.options != null && !gone.isEmpty()) try {
			var f = net.minecraft.client.Options.class.getDeclaredField("keyMappings");
			f.setAccessible(true);
			f.set(mc.options, Arrays.stream((KeyMapping[]) f.get(mc.options)).filter(k -> !gone.contains(k)).toArray(KeyMapping[]::new));
			KeyMapping.resetMapping();
		} catch (Throwable t) { Moddeus.LOG.warn("key remove {}", mod, t); }
		writeLang();
	}

	/** A mod switched off / deleted: its keys stop doing anything (they stay listed until restart). */
	public static void clear(String mod) {
		for (var e : binds().entrySet()) if (e.getValue().mod.equals(mod)) binds().put(e.getKey(), new Bind(mod, e.getValue().label, e.getValue().mapping, null, null));
	}

	static String slug(String s) { return s.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", ""); }

	static String id(String mod, String label) { return "key.moddeus." + slug(mod) + "." + slug(label); }

	static synchronized KeyMapping add(String mod, String label, String key, Consumer<ServerPlayer> server, Runnable client) {
		String name = id(mod, label);
		Bind old = binds().get(name);
		if (old != null) { binds().put(name, new Bind(mod, label, old.mapping, server, client)); return old.mapping; }
		KeyMapping.Category cat = cats().computeIfAbsent(mod, m -> {
			Identifier cid = Identifier.fromNamespaceAndPath("moddeus", slug(m));
			try { return KeyMapping.Category.register(cid); } catch (Throwable t) { return new KeyMapping.Category(cid); }
		});
		InputConstants.Key k = parse(key);
		KeyMapping km = new KeyMapping(name, k.getType(), k.getValue(), cat);
		lang().put(name, label);
		lang().put("key.category.moddeus." + slug(mod), mod);
		Minecraft mc = Minecraft.getInstance();
		if (mc.options == null) {
			net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.registerKeyMapping(km); // startup: options load it (saved rebinds too)
		} else {
			try {
				var f = net.minecraft.client.Options.class.getDeclaredField("keyMappings");
				f.setAccessible(true);
				KeyMapping[] arr = (KeyMapping[]) f.get(mc.options);
				KeyMapping[] more = Arrays.copyOf(arr, arr.length + 1);
				more[arr.length] = km;
				f.set(mc.options, more);
				String saved = savedKey(name);
				if (saved != null) km.setKey(InputConstants.getKey(saved));
				KeyMapping.resetMapping();
			} catch (Throwable t) { Moddeus.LOG.warn("key bind {} failed", name, t); }
		}
		binds().put(name, new Bind(mod, label, km, server, client));
		writeLang();
		return km;
	}

	/** "G", "f6", "LEFT_ALT", "mouse4", "key.keyboard.g", "none". */
	static InputConstants.Key parse(String key) {
		String k = key == null ? "none" : key.trim();
		if (k.startsWith("key.")) return InputConstants.getKey(k);
		String l = k.toLowerCase().replace(' ', '_');
		if (l.equals("none") || l.isEmpty()) return InputConstants.UNKNOWN;
		if (l.matches("mouse\\d")) return InputConstants.getKey("key.mouse." + (Integer.parseInt(l.substring(5))));
		return switch (l) {
			case "alt", "left_alt", "lalt" -> InputConstants.getKey("key.keyboard.left.alt");
			case "right_alt", "ralt" -> InputConstants.getKey("key.keyboard.right.alt");
			case "ctrl", "control", "left_ctrl" -> InputConstants.getKey("key.keyboard.left.control");
			case "shift", "left_shift" -> InputConstants.getKey("key.keyboard.left.shift");
			case "caps", "caps_lock", "capslock" -> InputConstants.getKey("key.keyboard.caps.lock");
			case "space" -> InputConstants.getKey("key.keyboard.space");
			case "tab" -> InputConstants.getKey("key.keyboard.tab");
			case "enter" -> InputConstants.getKey("key.keyboard.enter");
			case "backspace" -> InputConstants.getKey("key.keyboard.backspace");
			case "up", "down", "left", "right" -> InputConstants.getKey("key.keyboard." + l);
			default -> InputConstants.getKey("key.keyboard." + l.replace('_', '.'));
		};
	}

	/** A rebind the player saved: options.txt line key_<name>:<key>. */
	static String savedKey(String name) {
		try {
			for (String line : Files.readAllLines(Minecraft.getInstance().gameDirectory.toPath().resolve("options.txt")))
				if (line.startsWith("key_" + name + ":")) return line.substring(line.indexOf(':') + 1).trim();
		} catch (Exception ignored) {}
		return null;
	}

	/** Labels for Controls: own lang file (namespace moddeus_keys), picked up by the light reload in ~20 ms. */
	static void writeLang() {
		String proj = System.getProperty("moddeus.project");
		if (proj == null) return;
		var o = new com.google.gson.JsonObject();
		new java.util.TreeMap<>(lang()).forEach(o::addProperty);
		String json = new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(o);
		for (String root : List.of("src/main/resources", "build/resources/main")) {
			Path p = Path.of(proj, root, "assets/moddeus_keys/lang/en_us.json");
			try {
				if (Files.exists(p) && Files.readString(p).equals(json)) continue;
				Files.createDirectories(p.getParent());
				Files.writeString(p, json);
			} catch (Exception e) { Moddeus.LOG.warn("key lang", e); }
		}
	}

	/** Every client tick: run the action of each pressed key (server actions on the server thread, as the player). */
	static void tick(Minecraft mc) {
		if (binds == null || mc.player == null) return;
		for (Bind b : binds.values()) {
			while (b.mapping.consumeClick()) {
				if (mc.gui.screen() != null) continue;
				try {
					if (b.client != null) b.client.run();
					if (b.server != null) {
						var sp = mc.getSingleplayerServer();
						if (sp != null) sp.execute(() -> {
							ServerPlayer p = sp.getPlayerList().getPlayer(mc.player.getUUID());
							if (p != null) try { b.server.accept(p); } catch (Throwable t) { Moddeus.LOG.error("key {} failed", b.label, t); }
						});
					}
				} catch (Throwable t) { Moddeus.LOG.error("key {} failed", b.label, t); }
			}
		}
	}

	/** For the lag/G screen and chats: every mod key, "Mod: Label = Key". */
	public static List<String> list() {
		return binds().values().stream().sorted(java.util.Comparator.comparing((Bind b) -> b.mod).thenComparing(b -> b.label))
			.map(b -> b.mod + ": " + b.label + " = " + b.mapping.getTranslatedKeyMessage().getString() + (b.server == null && b.client == null ? " (off)" : "")).toList();
	}

	@OnReload
	public static void hook() {
		if (System.getProperties().putIfAbsent("moddeus.keys", Boolean.TRUE) != null) return;
		ClientTickEvents.END_CLIENT_TICK.register(Keys::tick);
	}
}
