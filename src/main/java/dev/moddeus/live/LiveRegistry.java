package dev.moddeus.live;

import dev.moddeus.Moddeus;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Registers into built-in registries at any time, including mid-game after they are frozen.
 * At startup (registry not frozen yet) it is a plain {@link Registry#register}. In a running
 * singleplayer world it briefly thaws the registry, registers, refreezes, then does the work
 * vanilla normally does at boot/world-load: empty tag binding, block-state ids, BlockItem
 * mapping and data-component baking. Idempotent: an existing id is returned as-is.
 */
public final class LiveRegistry {
	/** Set by Moddeus on server start, and by HotReloader before running hooks. */
	public static volatile MinecraftServer server;

	private static final Set<Registry<?>> INTRUSIVE = Set.of(
		BuiltInRegistries.BLOCK, BuiltInRegistries.ITEM, BuiltInRegistries.ENTITY_TYPE,
		BuiltInRegistries.FLUID, BuiltInRegistries.GAME_EVENT);

	private LiveRegistry() {}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(Moddeus.MOD_ID, path);
	}

	public static boolean exists(Registry<?> registry, Identifier id) {
		return registry.containsKey(id);
	}

	@SuppressWarnings("unchecked")
	public static synchronized <T> T register(Registry<T> registry, Identifier id, Function<ResourceKey<T>, T> factory) {
		ResourceKey<T> key = ResourceKey.create((ResourceKey<? extends Registry<T>>) registry.key(), id);
		if (registry.containsKey(key)) return registry.getValue(key);

		MappedRegistry<T> mapped = (MappedRegistry<T>) registry;
		if (!(boolean) get(mapped, "frozen")) return Registry.register(registry, key, factory.apply(key));

		boolean intrusive = INTRUSIVE.contains(registry);
		set(mapped, "frozen", false);
		if (intrusive) set(mapped, "unregisteredIntrusiveHolders", new IdentityHashMap<>());
		T value;
		try {
			value = Registry.register(registry, key, factory.apply(key));
		} finally {
			if (intrusive) set(mapped, "unregisteredIntrusiveHolders", null);
			set(mapped, "frozen", true);
		}

		Holder.Reference<T> holder = mapped.get(key).orElseThrow();
		try {
			if (!holder.isBound()) invoke(holder, "bindValue", new Class<?>[] {Object.class}, value);
			invoke(holder, "bindTags", new Class<?>[] {Collection.class}, Set.of());
		} catch (Exception e) {
			throw new IllegalStateException("Failed to bind holder " + key, e);
		}

		if (value instanceof Block block) {
			for (BlockState state : block.getStateDefinition().getPossibleStates()) {
				if (Block.BLOCK_STATE_REGISTRY.getId(state) == -1) Block.BLOCK_STATE_REGISTRY.add(state);
				state.initCache();
			}
		}
		if (value instanceof BlockItem item && !Item.BY_BLOCK.containsKey(item.getBlock())) {
			item.registerBlocks(Item.BY_BLOCK, item);
		}
		bindPendingComponents();
		Moddeus.LOG.info("Live-registered {} into {}", id, registry.key().identifier());
		return value;
	}

	/**
	 * Live removal: takes an entry fully out of a running registry and the vanilla side tables
	 * (block-state ids, BlockItem map, component initializers), compacting ids. Safe in
	 * singleplayer (client and server share these tables). Callers must first remove the
	 * thing from the world (see ContentLoader.purgeRemoved).
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	/** Runs work with a frozen registry briefly writable (e.g. Item.Properties.sword() resolves block tags through the bootstrap lookup). */
	public static synchronized <T> T thawed(Registry<?> registry, java.util.function.Supplier<T> work) {
		boolean was = (boolean) get(registry, "frozen");
		if (was) set(registry, "frozen", false);
		try {
			return work.get();
		} finally {
			if (was) set(registry, "frozen", true);
		}
	}

	public static synchronized <T> boolean unregister(Registry<T> registry, Identifier id) {
		MappedRegistry<T> m = (MappedRegistry<T>) registry;
		ResourceKey<T> key = ResourceKey.create((ResourceKey<? extends Registry<T>>) registry.key(), id);
		Map<ResourceKey<T>, Holder.Reference<T>> byKey = (Map) get(m, "byKey");
		Holder.Reference<T> holder = byKey.remove(key);
		if (holder == null) return false;
		T value = holder.value();
		((Map) get(m, "byLocation")).remove(id);
		((Map) get(m, "byValue")).remove(value);
		((Map) get(m, "registrationInfos")).remove(key);
		List<Holder.Reference<T>> byId = (List) get(m, "byId");
		byId.remove(holder);
		Reference2IntMap<T> toId = (Reference2IntMap) get(m, "toId");
		toId.clear();
		for (int i = 0; i < byId.size(); i++) toId.put(byId.get(i).value(), i);

		if (value instanceof Block block) removeStates(block);
		if (value instanceof Item item) {
			Item.BY_BLOCK.values().removeIf(v -> v == item);
			removeComponentInitializers(key);
		}
		Moddeus.LOG.info("Live-unregistered {} from {}", id, registry.key().identifier());
		return true;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void removeStates(Block block) {
		try {
			Class<?> c = net.minecraft.core.IdMapper.class;
			Field idToT = c.getDeclaredField("idToT"), tToId = c.getDeclaredField("tToId"), nextId = c.getDeclaredField("nextId");
			idToT.setAccessible(true); tToId.setAccessible(true); nextId.setAccessible(true);
			List<Object> list = (List) idToT.get(Block.BLOCK_STATE_REGISTRY);
			Reference2IntMap<Object> map = (Reference2IntMap) tToId.get(Block.BLOCK_STATE_REGISTRY);
			list.removeIf(s -> s instanceof BlockState bs && bs.getBlock() == block);
			map.clear();
			for (int i = 0; i < list.size(); i++) if (list.get(i) != null) map.put(list.get(i), i);
			nextId.setInt(Block.BLOCK_STATE_REGISTRY, list.size());
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void removeComponentInitializers(ResourceKey<?> key) {
		try {
			Field f = DataComponentInitializers.class.getDeclaredField("initializers");
			f.setAccessible(true);
			List<Object> entries = (List) f.get(BuiltInRegistries.DATA_COMPONENT_INITIALIZERS);
			entries.removeIf(e -> {
				try {
					Method k = e.getClass().getRecordComponents()[0].getAccessor();
					k.setAccessible(true);
					return key.equals(k.invoke(e));
				} catch (ReflectiveOperationException ex) {
					return false;
				}
			});
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Bakes data components (item stack size, name, model, ...) for entries that don't have them yet. */
	public static void bindPendingComponents() {
		MinecraftServer s = server;
		if (s == null) {
			Moddeus.LOG.warn("No server yet: components for live entries bind on next world load");
			return;
		}
		for (DataComponentInitializers.PendingComponents<?> pending : BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(s.registryAccess())) {
			bindUnbound(pending);
		}
	}

	private static <T> void bindUnbound(DataComponentInitializers.PendingComponents<T> pending) {
		pending.forEach((ref, components) -> {
			if (!ref.areComponentsBound()) ref.bindComponents(components);
		});
	}

	private static Object get(Object target, String name) {
		try {
			Field f = MappedRegistry.class.getDeclaredField(name);
			f.setAccessible(true);
			return f.get(target);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void set(Object target, String name, Object value) {
		try {
			Field f = MappedRegistry.class.getDeclaredField(name);
			f.setAccessible(true);
			f.set(target, value);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void invoke(Object target, String name, Class<?>[] types, Object... args) throws ReflectiveOperationException {
		Method m = Holder.Reference.class.getDeclaredMethod(name, types);
		m.setAccessible(true);
		m.invoke(target, args);
	}
}
