package dev.moddeus.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.StringReader;
import dev.moddeus.Moddeus;
import dev.moddeus.content.ModTab;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.item.ItemParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Creative tab + key binds for DATAPACK mods: an optional moddeus.json at the root of <Id>-DP (next to pack.mcmeta):
 * {
 *   "tab": "Storm Staff",            // the mod's own tab, or a vanilla one: combat, tools, food, building, ...
 *   "icon": "minecraft:golden_sword[item_model=\"storm:staff\"]",   // optional (default: first item)
 *   "items": ["minecraft:golden_sword[item_model=\"storm:staff\",item_name=\"Storm Staff\"]", ...],   // /give syntax
 *   "keys": [{"name": "Snap fingers", "key": "G", "function": "storm:key/snap"}]   // runs as + at the player
 * }
 * Re-read on every reload (game cmd reload, instant or full) and when a mod is switched on/off. Disabled packs = nothing.
 */
public final class PackExtras {
	static Set<String> lastMods;

	public static void load() {
		Minecraft mc = Minecraft.getInstance();
		MinecraftServer s = mc.getSingleplayerServer();
		if (s == null) return;
		Set<String> mods = new HashSet<>();
		ModTab.packStacks().clear();
		try (Stream<Path> packs = Files.list(s.getWorldPath(LevelResource.DATAPACK_DIR))) {
			Set<String> enabled = new HashSet<>(s.getPackRepository().getSelectedIds());
			for (Path pack : packs.toList()) {
				Path f = pack.resolve("moddeus.json");
				if (!Files.isRegularFile(f) || !enabled.contains("file/" + pack.getFileName())) continue;
				String mod = pack.getFileName().toString().replaceAll("-(DP|dp)$", "");
				try {
					JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
					String tab = o.has("tab") ? o.get("tab").getAsString() : mod;
					String modName = Projects.name(mod);
					List<ItemStack> stacks = new ArrayList<>();
					ItemParser parser = new ItemParser(s.registryAccess());
					if (o.has("icon")) stacks.add(parse(parser, o.get("icon").getAsString()));
					if (o.has("items")) for (JsonElement e : o.getAsJsonArray("items")) {
						ItemStack st = parse(parser, e.getAsString());
						if (st != null && stacks.stream().noneMatch(x -> x != null && ItemStack.isSameItemSameComponents(x, st))) stacks.add(st);
					}
					stacks.removeIf(java.util.Objects::isNull);
					if (!stacks.isEmpty()) ModTab.packStacks().merge(tab, stacks, (a, b) -> { a.addAll(b); return a; });
					if (o.has("keys")) for (JsonElement e : o.getAsJsonArray("keys")) {
						JsonObject k = e.getAsJsonObject();
						String fn = k.get("function").getAsString();
						Keys.bind(modName, k.get("name").getAsString(), k.has("key") ? k.get("key").getAsString() : "none", p ->
							s.getCommands().performPrefixedCommand(p.createCommandSourceStack().withSuppressedOutput()
								.withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER), "function " + fn));
					}
					mods.add(modName);
				} catch (Exception ex) {
					Moddeus.LOG.warn("{}/moddeus.json: {}", pack.getFileName(), ex.toString());
					HotReloader.say("§c" + pack.getFileName() + "/moddeus.json: " + ex.getMessage());
				}
			}
		} catch (Exception ignored) {}
		if (lastMods != null) for (String m : lastMods) if (!mods.contains(m)) Keys.clear(m); // pack switched off / deleted
		lastMods = mods;
		ModTab.sync();
		ModTab.refresh();
	}

	static ItemStack parse(ItemParser p, String s) {
		try { return p.parse(new StringReader(s)).createItemStack(1); }
		catch (Exception e) { Moddeus.LOG.warn("moddeus.json item {}: {}", s, e.getMessage()); return null; }
	}

	static void reloaded(MinecraftServer s, net.minecraft.server.packs.resources.CloseableResourceManager rm, boolean ok) { s.execute(PackExtras::load); }

	static void started(MinecraftServer s) { s.execute(PackExtras::load); }

	@dev.moddeus.live.OnReload
	public static void hook() {
		if (System.getProperties().putIfAbsent("moddeus.packextras", Boolean.TRUE) == null) {
			net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.END_DATA_PACK_RELOAD.register(PackExtras::reloaded);
			net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(PackExtras::started);
		}
		var s = Minecraft.getInstance().getSingleplayerServer();
		if (s != null) s.execute(PackExtras::load);
	}
}
