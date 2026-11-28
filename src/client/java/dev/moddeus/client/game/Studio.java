package dev.moddeus.client.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4fStack;
import org.joml.Quaternionf;
import org.joml.Vector4f;

/**
 * Offscreen studio: renders an entity / mob (with an animation pose), an item (GUI, first person, in a mannequin's hand),
 * armor on a mannequin (stand / crouch / swim) or a block from several angles into its own GPU texture, then PNGs in
 * run/moddeus/shots/studio/ (one per view + a contact sheet). Never touches the player's camera, screen or world: the
 * models are client-side dummies that are never added to the level. One view per client tick (~1-3 ms), PNG encode
 * off-thread. Shell: node tools/mc.mjs game studio entity|item|armor|block <id> [--angles 4] [--anim walk] ...
 */
public final class Studio {
	/** box > 0: centred model (displays) in a fixed box; box < 0: feet on the origin, fixed box |box|; 0: feet, box from the hitbox. */
	record View(String name, Entity e, float yaw, float pitch, float box, Pose pose) {}

	static final class Job {
		JsonObject spec;
		String name;
		int size, next, pending;
		boolean dead; // failed while a readback was pending: the last callback frees the textures
		long started;
		List<View> views = new ArrayList<>();
		int[][] px;
		GpuTexture tex, depth;
		GpuTextureView texView, depthView;
	}

	static ConcurrentLinkedQueue<JsonObject> queue;
	static Job job;
	static Projection proj;
	static ProjectionMatrixBuffer pmb;
	static final Vector4f BG = new Vector4f(0.17f, 0.18f, 0.21f, 1f);

	static Path dir() { return Path.of("moddeus", "shots", "studio"); }

	/** Queues a shoot (spec JSON: kind, id, angles, anim, t, pose, size, nbt, pitch, view). Returns the job name; result = <dir>/<name>.json. */
	public static String queue(String specJson) {
		hook();
		JsonObject o = JsonParser.parseString(specJson).getAsJsonObject();
		String name = s(o, "kind", "entity") + "_" + s(o, "id", "x").replaceAll("[^A-Za-z0-9_]", "_") + "_" + Long.toString(System.currentTimeMillis(), 36);
		o.addProperty("name", name);
		if (queue == null) queue = new ConcurrentLinkedQueue<>();
		queue.add(o);
		return name;
	}

	@dev.moddeus.live.OnReload
	public static void hook() {
		if (System.getProperties().putIfAbsent("moddeus.studio", Boolean.TRUE) == null) ClientTickEvents.END_CLIENT_TICK.register(Studio::tick);
	}

	static void tick(Minecraft mc) {
		if (job == null) {
			JsonObject o = queue == null ? null : queue.poll();
			if (o == null) return;
			job = new Job();
			job.spec = o;
			job.name = s(o, "name", "shot");
			job.started = System.currentTimeMillis();
			try { setup(mc, job); } catch (Throwable t) { fail(job, t); return; }
		}
		Job j = job;
		try {
			if (j.next < j.views.size()) render(mc, j, j.next++);
			else if (j.pending == 0) { close(j); write(j); job = null; }
			else if (System.currentTimeMillis() - j.started > 20000) fail(j, new IllegalStateException("GPU readback timed out"));
		} catch (Throwable t) { fail(j, t); }
	}

	// ---- the scene -------------------------------------------------------------------------------------------------
	static void setup(Minecraft mc, Job j) throws Exception {
		if (mc.level == null) throw new IllegalStateException("no world loaded");
		JsonObject o = j.spec;
		String kind = s(o, "kind", "entity"), id = s(o, "id", "");
		j.size = Math.max(64, Math.min(1024, Math.round(f(o, "size", 384) / 64f) * 64)); // multiple of 64: aligned readback rows
		int n = Math.max(1, Math.min(8, (int) f(o, "angles", 4)));
		String nbt = s(o, "nbt", "{}");
		switch (kind) {
			case "entity" -> {
				Identifier rid = id(id, BuiltInRegistries.ENTITY_TYPE);
				Entity e = make(mc, nbt, rid.toString());
				for (int i = 0; i < n; i++) j.views.add(new View("a" + i * 360 / n, e, i * 360f / n, f(o, "pitch", 15), -f(o, "box", 0), null)); // --box <blocks>: frame height for big cube-model mobs (state bbox = the vanilla template's)
			}
			case "armor" -> {
				LivingEntity m = (LivingEntity) make(mc, "{hide_description:1b}", "minecraft:mannequin");
				for (ItemStack st : armor(id)) {
					var eq = st.get(DataComponents.EQUIPPABLE);
					m.setItemSlot(eq != null ? eq.slot() : EquipmentSlot.HEAD, st);
				}
				for (String p : s(o, "pose", "stand").replaceAll("[^A-Za-z0-9_,]", "").split(",")) {
					Pose pose = switch (p.trim()) { case "crouch", "sneak" -> Pose.CROUCHING; case "swim", "crawl" -> Pose.SWIMMING; default -> Pose.STANDING; };
					for (int i = 0; i < n; i++) j.views.add(new View(p.trim() + "_a" + i * 360 / n, m, i * 360f / n, f(o, "pitch", 15), -2.1f, pose)); // fixed frame: crouch/swim keep the standing scale
				}
			}
			case "item" -> {
				Identifier rid = id(id, BuiltInRegistries.ITEM);
				String item = "{id:\"" + rid + "\",count:1" + (nbt.isBlank() || nbt.equals("{}") ? "" : ",components:" + nbt) + "}"; // --nbt = item components, e.g. {"minecraft:item_model":"moddeus:x_pull2"}
				String views = s(o, "view", "gui,fp,hand");
				if (views.contains("gui")) j.views.add(new View("gui", make(mc, itemDisplay(item, "gui"), null), 0, 0, 1.1f, null));
				if (views.contains("fp")) j.views.add(new View("first_person", make(mc, itemDisplay(item, "firstpersonrighthand"), null), 0, 0, 1.4f, null));
				if (views.contains("ground")) j.views.add(new View("ground", make(mc, itemDisplay(item, "ground"), null), 0, 20, 0.6f, null));
				if (views.contains("hand")) {
					Entity m = make(mc, "{hide_description:1b,equipment:{mainhand:" + item + "}}", "minecraft:mannequin");
					for (int i = 0; i < n; i++) j.views.add(new View("hand_a" + (35 + i * 360 / n), m, 35 + i * 360f / n, f(o, "pitch", 15), 0, null)); // 35 deg off-axis: flat items face the camera
				}
			}
			case "block" -> {
				Identifier rid = id(id, BuiltInRegistries.BLOCK);
				Entity b = make(mc, "{block_state:{Name:\"" + rid + "\"},brightness:{sky:15,block:15},transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[-0.5f,-0.5f,-0.5f],scale:[1f,1f,1f]}}", "minecraft:block_display");
				for (int i = 0; i < n; i++) j.views.add(new View("a" + (45 + i * 360 / n), b, 45 + i * 360f / n, f(o, "pitch", 25), 1.9f, null));
				Item it = BuiltInRegistries.BLOCK.getValue(rid).asItem();
				if (it != net.minecraft.world.item.Items.AIR) j.views.add(new View("gui", make(mc, itemDisplay("{id:\"" + BuiltInRegistries.ITEM.getKey(it) + "\",count:1}", "gui"), null), 0, 0, 1.1f, null));
			}
			default -> throw new IllegalArgumentException("kind must be entity|item|armor|block");
		}
		j.px = new int[j.views.size()][];
	}

	static String itemDisplay(String item, String mode) {
		return "{id:\"minecraft:item_display\",item:" + item + ",item_display:\"" + mode + "\",brightness:{sky:15,block:15}}";
	}

	/** A client-side entity that is never added to the world. */
	static Entity make(Minecraft mc, String nbt, String type) throws Exception {
		CompoundTag tag = TagParser.parseCompoundFully(nbt == null || nbt.isBlank() ? "{}" : nbt);
		if (type != null) tag.putString("id", type);
		Entity e = EntityType.loadEntityRecursive(tag, mc.level, new EntitySpawnRequest(EntitySpawnReason.LOAD, false), x -> x);
		if (e == null) throw new IllegalArgumentException("can't create entity " + tag);
		e.setPos(0, 2000, 0); // above the build limit: no neighbour blocks cull faces, no world light
		e.setId(-2_000_000 - (int) (System.nanoTime() & 0xFFFFF)); // never a real entity id
		if (e instanceof Display) e.tick(); // builds its render state (the world would tick it)
		return e;
	}

	/** "frost" = moddeus:/minecraft: frost_helmet..boots; or item ids, comma separated. */
	static List<ItemStack> armor(String spec) {
		List<ItemStack> out = new ArrayList<>();
		for (String part : spec.split(",")) {
			String p = part.trim();
			int before = out.size();
			for (String piece : new String[] {"helmet", "chestplate", "leggings", "boots"}) { // set name first ("diamond" is also an item)
				Optional<Item> i = find(p + "_" + piece, BuiltInRegistries.ITEM);
				if (i.isPresent()) out.add(new ItemStack(i.get()));
			}
			if (out.size() == before) find(p, BuiltInRegistries.ITEM).ifPresent(i -> out.add(new ItemStack(i)));
		}
		if (out.isEmpty()) throw new IllegalArgumentException("no armor items for " + spec + " (give item ids or a set name like frost)");
		return out;
	}

	static <T> Optional<T> find(String id, Registry<T> reg) {
		if (id.contains(":")) return reg.getOptional(Identifier.parse(id));
		Optional<T> m = reg.getOptional(Identifier.fromNamespaceAndPath("minecraft", id));
		return m.isPresent() ? m : reg.getOptional(Identifier.fromNamespaceAndPath("moddeus", id));
	}

	static <T> Identifier id(String id, Registry<T> reg) {
		return find(id, reg).map(reg::getKey).orElseThrow(() -> new IllegalArgumentException("unknown " + reg.key().identifier().getPath() + " " + id));
	}

	// ---- one view = one offscreen render (mirrors vanilla's PictureInPictureRenderer) ------------------------------------
	static void render(Minecraft mc, Job j, int idx) {
		View v = j.views.get(idx);
		int w = j.size, h = j.size;
		GpuDevice gpu = RenderSystem.getDevice();
		if (j.tex == null) {
			j.tex = gpu.createTexture(() -> "moddeus studio", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, w, h, 1, 1);
			j.texView = gpu.createTextureView(j.tex);
			j.depth = gpu.createTexture(() -> "moddeus studio depth", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST, GpuFormat.D32_FLOAT, w, h, 1, 1);
			j.depthView = gpu.createTextureView(j.depth);
		}
		if (proj == null) { proj = new Projection(); pmb = new ProjectionMatrixBuffer("moddeus studio"); }
		var erd = mc.getEntityRenderDispatcher();
		EntityRenderState st = state(erd, v, j.spec); // before any GPU command: a broken renderer fails cleanly
		CommandEncoder enc = gpu.createCommandEncoder();
		enc.clearColorAndDepthTextures(j.tex, BG, j.depth, 0.0);
		proj.setupOrtho(-1000, 1000, w, h, true);
		RenderSystem.backupProjectionMatrix();
		RenderSystem.setProjectionMatrix(pmb.getBuffer(proj), ProjectionType.ORTHOGRAPHIC);
		Matrix4fStack mv = RenderSystem.getModelViewStack();
		mv.pushMatrix().identity();
		try {
			boolean feet = v.box <= 0; // living entities stand on their origin; displays are centred
			float box = v.box != 0 ? Math.abs(v.box) : Math.max(st.boundingBoxHeight, st.boundingBoxWidth * 1.3f);
			float s = w * 0.8f / Math.max(0.25f, box);
			float pitch = v.pitch * Mth.DEG_TO_RAD;
			PoseStack ps = new PoseStack();
			ps.translate(w / 2f, h / 2f, 0);
			ps.scale(s, s, -s);
			if (feet) ps.translate(0, st.boundingBoxHeight / 2, 0);
			ps.rotate(new Quaternionf().rotateZ(Mth.PI).rotateX(pitch).rotateY(v.yaw * Mth.DEG_TO_RAD));
			CameraRenderState cam = new CameraRenderState();
			cam.orientation = new Quaternionf().rotateX(pitch).conjugate(new Quaternionf()).rotateY(Mth.PI);
			SubmitNodeStorage nodes = new SubmitNodeStorage();
			mc.gameRenderer.lighting().setupFor(Lighting.Entry.ENTITY_IN_UI);
			erd.submit(st, cam, 0, 0, 0, ps, nodes);
			FeatureRenderDispatcher.PreparedFrame frame = mc.gameRenderer.featureRenderDispatcher().prepareFrame(nodes);
			try {
				RenderPass pass = enc.createRenderPass(() -> "moddeus studio", j.texView, Optional.empty(), j.depthView, OptionalDouble.empty());
				try {
					RenderSystem.bindDefaultUniforms(pass);
					FeatureRenderDispatcher.renderAllFeatures(pass, frame);
				} finally { pass.close(); }
			} finally { frame.close(); }
		} finally {
			mv.popMatrix();
			RenderSystem.restoreProjectionMatrix();
			mc.gameRenderer.lighting().setupFor(Lighting.Entry.LEVEL);
		}
		GpuBuffer buf = gpu.createBuffer(() -> "moddeus studio readback", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) w * h * 4);
		j.pending++;
		try {
		enc.copyTextureToBuffer(j.tex, buf, 0, () -> {
			try (var view = buf.map(true, false)) {
				int[] a = new int[w * h];
				view.data().asIntBuffer().get(a);
				j.px[idx] = a;
			} catch (Throwable t) {
				dev.moddeus.Moddeus.LOG.warn("studio readback", t);
			} finally {
				buf.close();
				if (--j.pending == 0 && j.dead) close(j);
			}
		}, 0);
		} catch (RuntimeException e) { buf.close(); j.pending--; throw e; }
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	static EntityRenderState state(net.minecraft.client.renderer.entity.EntityRenderDispatcher erd, View v, JsonObject spec) {
		Entity e = v.e;
		if (v.pose != null) { e.setPose(v.pose); e.setShiftKeyDown(v.pose == Pose.CROUCHING); }
		EntityRenderState st = ((EntityRenderer) erd.getRenderer(e)).createRenderState(e, 1f);
		st.shadowPieces.clear();
		st.outlineColor = 0;
		st.lightCoords = 0xF000F0;
		st.nameTag = null;
		st.leashStates = null;
		if (st instanceof LivingEntityRenderState l) {
			l.bodyRot = 180;
			l.yRot = 0;
			l.xRot = 0;
			String anim = s(spec, "anim", "idle");
			float t = f(spec, "t", 0.5f);
			l.ageInTicks = t * 40;
			switch (anim) {
				case "idle" -> {}
				case "walk" -> { l.walkAnimationSpeed = 1; l.walkAnimationPos = t * Mth.TWO_PI / 0.6662f; }
				case "attack" -> { set(st, "swing", t); set(st, "swingAnimation", t); set(st, "attackTime", t); }
				case "hurt" -> { set(st, "hurt", 1 - t); l.hasRedOverlay = true; }
				case "death" -> l.deathTime = t * 20;
				default -> { // cube-model clip: pose1.. / sit
					if (anim.startsWith("pose")) {
						int i = Integer.parseInt(anim.substring(4)) - 1;
						float[] w = new float[i + 1];
						w[i] = 1;
						set(st, "pose", w);
					} else set(st, anim, 1f);
				}
			}
			if (st instanceof HumanoidRenderState hs) {
				if (v.pose == Pose.CROUCHING) hs.isCrouching = true;
				if (v.pose == Pose.SWIMMING) { hs.swimAmount = 1; hs.isVisuallySwimming = true; }
			}
		}
		return st;
	}

	/** Sets a render-state field by name if that state class has it (mod states add their own fields). */
	static void set(Object o, String field, Object val) {
		for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
			try { var f = c.getDeclaredField(field); f.setAccessible(true); f.set(o, val); return; }
			catch (NoSuchFieldException ignored) {}
			catch (Exception e) { return; }
		}
	}

	// ---- output ----------------------------------------------------------------------------------------------------
	static void close(Job j) {
		if (j.texView != null) j.texView.close();
		if (j.tex != null) j.tex.close();
		if (j.depthView != null) j.depthView.close();
		if (j.depth != null) j.depth.close();
		j.tex = j.depth = null;
		j.texView = j.depthView = null;
	}

	static void fail(Job j, Throwable t) {
		dev.moddeus.Moddeus.LOG.warn("studio {} failed", j == null ? "?" : j.name, t);
		if (j != null) {
			if (j.pending == 0) close(j);
			else j.dead = true; // the last readback callback closes the textures
			JsonObject r = new JsonObject();
			r.addProperty("error", String.valueOf(t));
			done(j.name, r);
		}
		job = null;
	}

	static void write(Job j) {
		int n = j.views.size(), size = j.size;
		List<String> names = j.views.stream().map(View::name).toList();
		Thread.startVirtualThread(() -> {
			JsonObject r = new JsonObject();
			try {
				Files.createDirectories(dir());
				try (var old = Files.list(dir())) { // keep the folder small
					long cut = System.currentTimeMillis() - 2 * 3600_000L;
					old.filter(p -> { try { return Files.getLastModifiedTime(p).toMillis() < cut; } catch (Exception e) { return false; } }).forEach(p -> p.toFile().delete());
				}
				int cols = Math.min(n, 4), rows = (n + cols - 1) / cols;
				JsonArray files = new JsonArray();
				try (NativeImage sheet = new NativeImage(cols * size, rows * size, false)) {
					sheet.fillRect(0, 0, cols * size, rows * size, 0xFF362E2B); // = BG
					for (int i = 0; i < n; i++) {
						int[] a = j.px[i];
						if (a == null) continue;
						int ox = (i % cols) * size, oy = (i / cols) * size;
						try (NativeImage img = new NativeImage(size, size, false)) {
							for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
								int c = a[(size - 1 - y) * size + x] | 0xFF000000; // GPU rows are bottom-up
								img.setPixelABGR(x, y, c);
								sheet.setPixelABGR(ox + x, oy + y, c);
							}
							Path f = dir().resolve(j.name + "_" + names.get(i) + ".png");
							img.writeToFile(f);
							files.add(f.toAbsolutePath().toString());
						}
					}
					Path sp = dir().resolve(j.name + "_sheet.png");
					sheet.writeToFile(sp);
					r.addProperty("sheet", sp.toAbsolutePath().toString());
				}
				r.add("files", files);
				JsonArray vs = new JsonArray();
				names.forEach(vs::add);
				r.add("views", vs);
				r.addProperty("cols", cols);
			} catch (Throwable t) { r.addProperty("error", String.valueOf(t)); }
			done(j.name, r);
		});
	}

	static void done(String name, JsonObject r) {
		try {
			Files.createDirectories(dir());
			Path tmp = dir().resolve(name + ".json.tmp");
			Files.writeString(tmp, r.toString());
			Files.move(tmp, dir().resolve(name + ".json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) { dev.moddeus.Moddeus.LOG.warn("studio result: {}", e.toString()); }
	}

	static String s(JsonObject o, String k, String d) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : d; }
	static float f(JsonObject o, String k, float d) { try { return o.has(k) ? o.get(k).getAsFloat() : d; } catch (Exception e) { return d; } }
}
