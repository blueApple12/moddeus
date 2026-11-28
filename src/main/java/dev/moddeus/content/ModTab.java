package dev.moddeus.content;

import dev.moddeus.Moddeus;
import dev.moddeus.live.LiveRegistry;
import dev.moddeus.live.OnReload;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTabOutput;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Creative tabs, all live:
 * - No "Moddeus" catalog tab: every mod's items live in its OWN tab (mc.mjs fills "tab" with the mod's name); core
 *   leftovers without a "tab" get none.
 * - A mod's OWN tab: content JSON "tab": "<Mod Name>" (mc item ... --tab "Storm Staff"); datapack mods list stacks in
 *   their pack's moddeus.json (client/PackExtras -> {@link #packStacks}). One tab per name, icon = first item.
 * - A vanilla tab for single small things: "tab": "combat" | tools | food | building | colored | natural | functional |
 *   redstone | ingredients | spawn_eggs (an emerald sword goes to combat, not its own tab).
 */
public final class ModTab {
	public static Map<Identifier, String> tabs;                 // item id -> tab (vanilla key or mod tab name)
	public static Map<String, List<ItemStack>> packStacks;      // tab -> stacks from datapack mods (PackExtras)
	public static Map<String, String> packIcons;                // tab -> icon item string from a datapack mod
	static Map<String, Identifier> registered;                  // mod tab name -> its registry id

	public static Map<Identifier, String> tabs() { if (tabs == null) tabs = new ConcurrentHashMap<>(); return tabs; }
	public static Map<String, List<ItemStack>> packStacks() { if (packStacks == null) packStacks = new ConcurrentHashMap<>(); return packStacks; }
	public static Map<String, String> packIcons() { if (packIcons == null) packIcons = new ConcurrentHashMap<>(); return packIcons; }
	static java.util.Set<String> hidden; // switched-off mods: "tab:<name>" + "id:<snake_id>" + "ns:<namespace>" (lower case)
	static java.util.Set<String> hidden() { if (hidden == null) hidden = ConcurrentHashMap.newKeySet(); return hidden; }

	/** G screen Off = looks uninstalled: the mod's items leave every creative tab (its own tab hides when empty). */
	public static void setHidden(String modName, String projectId, boolean hide) {
		String snake = projectId.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
		String ns = modName.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", ""); // the mod's own namespace
		for (String k : List.of("tab:" + modName.toLowerCase(), "id:" + snake, "ns:" + ns)) if (hide) hidden().add(k); else hidden().remove(k);
		refresh();
	}

	/** Item of a switched-off mod: in its tab, or named after it (<snake_id>_*). */
	static boolean hiddenItem(Identifier item, String tab) {
		if (hidden == null || hidden.isEmpty()) return false;
		if (tab != null && hidden.contains("tab:" + tab.toLowerCase())) return true;
		if (hidden.contains("ns:" + item.getNamespace())) return true;
		String p = item.getPath();
		for (String h : hidden) if (h.startsWith("id:") && (p.equals(h.substring(3)) || p.startsWith(h.substring(3) + "_"))) return true;
		return false;
	}

	static Map<String, Identifier> registered() { if (registered == null) registered = new ConcurrentHashMap<>(); return registered; }

	static Map<String, ResourceKey<CreativeModeTab>> VANILLA; // lazy: a static added by a swap starts null
	static void vanilla() {
		if (VANILLA != null && !VANILLA.isEmpty()) return;
		VANILLA = new LinkedHashMap<>();
		VANILLA.put("combat", CreativeModeTabs.COMBAT);
		VANILLA.put("tools", CreativeModeTabs.TOOLS_AND_UTILITIES);
		VANILLA.put("food", CreativeModeTabs.FOOD_AND_DRINKS);
		VANILLA.put("building", CreativeModeTabs.BUILDING_BLOCKS);
		VANILLA.put("colored", CreativeModeTabs.COLORED_BLOCKS);
		VANILLA.put("natural", CreativeModeTabs.NATURAL_BLOCKS);
		VANILLA.put("functional", CreativeModeTabs.FUNCTIONAL_BLOCKS);
		VANILLA.put("redstone", CreativeModeTabs.REDSTONE_BLOCKS);
		VANILLA.put("ingredients", CreativeModeTabs.INGREDIENTS);
		VANILLA.put("spawn_eggs", CreativeModeTabs.SPAWN_EGGS);
	}

	/** "Tools & Utilities" / "tools_and_utilities" / "tools" -> vanilla key, else null (a mod tab name). */
	static String vanillaKey(String tab) {
		vanilla();
		String t = tab.toLowerCase().replaceAll("[^a-z]+", "_").replaceAll("^_|_$", "");
		if (VANILLA.containsKey(t)) return t;
		for (var e : VANILLA.entrySet()) if (e.getValue().identifier().getPath().equals(t) || e.getValue().identifier().getPath().startsWith(t + "_")) return e.getKey();
		return switch (t) { case "weapons", "weapon", "armor" -> "combat"; case "drinks", "drink" -> "food"; case "blocks" -> "building"; case "eggs" -> "spawn_eggs"; case "materials" -> "ingredients"; default -> null; };
	}

	/** The old "Moddeus" catalog tab is gone. Its code stays compiled (never runs) so lambda numbers, and with them live tabs' callbacks, survive swaps. */
	static boolean catalog() { return false; }

	@OnReload
	public static void register() {
		Identifier catalog = Identifier.fromNamespaceAndPath(Moddeus.MOD_ID, "moddeus");
		if (!catalog() && LiveRegistry.exists(BuiltInRegistries.CREATIVE_MODE_TAB, catalog)) { resetCreativePage(); LiveRegistry.unregister(BuiltInRegistries.CREATIVE_MODE_TAB, catalog); }
		boolean fresh = !LiveRegistry.exists(BuiltInRegistries.CREATIVE_MODE_TAB, LiveRegistry.id("moddeus"));
		if (catalog()) LiveRegistry.register(BuiltInRegistries.CREATIVE_MODE_TAB, LiveRegistry.id("moddeus"), k -> FabricCreativeModeTab.builder()
			.title(Component.literal("Moddeus"))
			.icon(ModTab::icon)
			.displayItems((params, out) -> BuiltInRegistries.ITEM.entrySet().stream()
				.filter(e -> e.getKey().identifier().getNamespace().equals(Moddeus.MOD_ID))
				.sorted(java.util.Comparator.comparing(e -> e.getKey().identifier().getPath()))
				.forEach(e -> out.accept(e.getValue())))
			.build());
		if (catalog() && fresh && LiveRegistry.server != null) page(LiveRegistry.id("moddeus"));
		if (System.getProperties().putIfAbsent("moddeus.tabs.vanilla", Boolean.TRUE) == null)
			CreativeModeTabEvents.MODIFY_OUTPUT_ALL.register(ModTab::modifyAll);
		sync();
		refresh();
	}

	/** Create the mod tabs that are named somewhere (content JSON or datapack mods). Empty tabs hide themselves. */
	public static void sync() {
		java.util.Set<String> names = new java.util.TreeSet<>();
		for (String t : tabs().values()) if (vanillaKey(t) == null) names.add(t);
		for (String t : packStacks().keySet()) if (vanillaKey(t) == null) names.add(t);
		for (String name : names) {
			if (registered().containsKey(name)) continue;
			Identifier id = LiveRegistry.id("tab_" + name.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", ""));
			registered().put(name, id);
			boolean fresh = !LiveRegistry.exists(BuiltInRegistries.CREATIVE_MODE_TAB, id);
			try {
				LiveRegistry.register(BuiltInRegistries.CREATIVE_MODE_TAB, id, k -> FabricCreativeModeTab.builder()
					.title(Component.literal(name))
					.icon(() -> tabIcon(name))
					.displayItems((params, out) -> { for (ItemStack s : stacks(name)) out.accept(s); })
					.build());
				if (fresh && LiveRegistry.server != null) page(id);
			} catch (Throwable t) { Moddeus.LOG.warn("creative tab {} failed", name, t); }
		}
	}

	/** Everything that belongs in a tab (mod tab name or vanilla key), content items first. */
	static List<ItemStack> stacks(String tab) {
		List<ItemStack> l = new ArrayList<>();
		tabs().entrySet().stream().filter(e -> e.getValue().equalsIgnoreCase(tab) || tab.equals(vanillaKey(e.getValue()))).filter(e -> !hiddenItem(e.getKey(), e.getValue()))
			.sorted(Map.Entry.comparingByKey())
			.forEach(e -> { BuiltInRegistries.ITEM.getOptional(e.getKey()).ifPresent(i -> l.add(new ItemStack(i))); ModTabExtras.add(l, e.getKey()); }); // + eggs, potions, books
		for (var e : packStacks().entrySet()) if ((e.getKey().equalsIgnoreCase(tab) || tab.equals(vanillaKey(e.getKey()))) && !hidden().contains("tab:" + e.getKey().toLowerCase())) for (ItemStack s : e.getValue()) l.add(s.copy());
		return l;
	}

	static ItemStack tabIcon(String name) {
		List<ItemStack> l = stacks(name);
		return l.isEmpty() ? new ItemStack(Items.BOOK) : l.getFirst();
	}

	/** Items placed in vanilla tabs (and search). */
	static void modifyAll(CreativeModeTab tab, FabricCreativeModeTabOutput out) {
		vanilla();
		var key = BuiltInRegistries.CREATIVE_MODE_TAB.getResourceKey(tab).orElse(null);
		if (key == null) return;
		for (var e : VANILLA.entrySet()) if (e.getValue().equals(key)) { for (ItemStack s : stacks(e.getKey())) out.accept(s); return; }
	}

	/** Fabric only assigns page/row/column at startup; a tab registered mid-game gets the next free modded slot here. */
	public static void page() { page(LiveRegistry.id("moddeus")); }

	public static void page(Identifier id) {
		var tab = BuiltInRegistries.CREATIVE_MODE_TAB.getValue(id);
		if (tab == null) return;
		java.util.Set<Integer> used = new java.util.HashSet<>();
		for (var e : BuiltInRegistries.CREATIVE_MODE_TAB.entrySet()) {
			var t = e.getValue();
			if (t == tab || e.getKey().identifier().getNamespace().equals("minecraft") || t.row() == null) continue;
			try {
				int p = ((net.fabricmc.fabric.impl.creativetab.FabricCreativeModeTabImpl) t).fabric_getPage();
				used.add((p - 1) * 10 + (t.row() == CreativeModeTab.Row.TOP ? t.column() : t.column() + 5));
			} catch (IllegalStateException ignored) {}
		}
		int i = 0;
		while (used.contains(i)) i++;
		((net.fabricmc.fabric.impl.creativetab.FabricCreativeModeTabImpl) tab).fabric_setPage(i / 10 + 1);
		var acc = (net.fabricmc.fabric.mixin.creativetab.CreativeModeTabAccessor) tab;
		int slot = i % 10;
		acc.setRow(slot < 5 ? CreativeModeTab.Row.TOP : CreativeModeTab.Row.BOTTOM);
		acc.setColumn(slot % 5);
	}

	static ItemStack icon() {
		var ruby = BuiltInRegistries.ITEM.getOptional(LiveRegistry.id("ruby_gem"));
		return new ItemStack(ruby.isPresent() ? ruby.get() : Items.AMETHYST_SHARD);
	}

	/** Forces the creative inventory to rebuild tab contents next time it opens (new items show up). */
	public static void refresh() {
		try {
			var f = CreativeModeTabs.class.getDeclaredField("CACHED_PARAMETERS");
			f.setAccessible(true);
			f.set(null, null);
		} catch (ReflectiveOperationException e) {
			Moddeus.LOG.warn("tab refresh failed", e);
		}
		boolean empty = BuiltInRegistries.ITEM.keySet().stream().noneMatch(k -> k.getNamespace().equals(Moddeus.MOD_ID));
		if (empty) resetCreativePage();
	}

	/**
	 * An empty tab is hidden. If it was the only tab on its page, the creative menu stayed on that page showing just
	 * hotbars/search/inventory with no arrow back. Reset to the first page (client only, via reflection: common code).
	 */
	static void resetCreativePage() {
		try {
			var screen = Class.forName("net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen");
			var page = screen.getDeclaredField("currentPage");
			page.setAccessible(true);
			page.setInt(null, 0);
			var sel = screen.getDeclaredField("selectedTab");
			sel.setAccessible(true);
			sel.set(null, CreativeModeTabs.getDefaultTab());
		} catch (ClassNotFoundException ignored) { // dedicated server
		} catch (ReflectiveOperationException e) {
			Moddeus.LOG.warn("creative page reset failed", e);
		}
	}
}
