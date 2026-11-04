package com.greysworkshop.mapsmith;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Locale;

/** Mapsmith: map art from any picture, planned to the block and built for you. */
public final class Mapsmith implements ClientModInitializer {
	public static final String MOD_ID = "mapsmith";
	static final Logger LOGGER = LoggerFactory.getLogger("mapsmith");

	static KeyBinding studioKey, buildKey;
	/** The open project. */
	private static Art art;
	private static Plan plan;
	private static Art.Done planDone;
	private static Project.Placement planPlacement;
	private static String planWorld;
	private static MapCheck check;
	private static int tick;

	@Override
	public void onInitializeClient() {
		MapsmithConfig.load();
		try { UpdateCheck.init(); } catch (RuntimeException e) { LOGGER.debug("Updater init: {}", e.toString()); }
		KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
		studioKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.mapsmith.studio", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_U, category));
		buildKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.mapsmith.build", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, category));
		ClientTickEvents.END_CLIENT_TICK.register(Mapsmith::tick);
		WorldRenderEvents.BEFORE_DEBUG_RENDER.register(Ghosts::render);
		HudElementRegistry.addLast(Identifier.of(MOD_ID, "hud"), Hud::render);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> Commands.register(dispatcher));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> Builder.stop(client, Builder.State.OFF, net.minecraft.text.Text.empty())));
		if (!MapsmithConfig.INSTANCE.lastProject.isEmpty()) {
			Project p = Project.load(Project.projects().resolve(MapsmithConfig.INSTANCE.lastProject));
			if (p != null) {
				try {
					open(p);
				} catch (IOException e) {
					LOGGER.warn("Could not open the last project", e);
				}
			}
		}
		if (Boolean.getBoolean("mapsmith.selftest")) {
			try {
				Class.forName("com.greysworkshop.mapsmith.SelfTest").getDeclaredMethod("start").invoke(null);
			} catch (ReflectiveOperationException e) {
				LOGGER.error("Self-test missing", e);
			}
		}
		if (Boolean.getBoolean("mapsmith.ultimate")) {
			try {
				Class.forName("com.greysworkshop.mapsmith.UltimateTest").getDeclaredMethod("start").invoke(null);
			} catch (ReflectiveOperationException e) {
				LOGGER.error("Ultimate test missing", e);
			}
		}
	}

	private static void tick(MinecraftClient client) {
		tick++;
		UpdateCheck.tick(client);
		while (studioKey.wasPressed()) {
			if (client.currentScreen == null) client.setScreen(new StudioScreen(null));
		}
		while (buildKey.wasPressed()) {
			if (client.currentScreen == null) Builder.toggle(client);
		}
		if (client.world == null) return;
		Builder.tick(client);
		Ghosts.tick(client);
		Plan p = plan();
		if (p != null) {
			if (check == null || check.plan != p) check = new MapCheck(p);
			check.step(client.world, Math.max(1, p.width() / 40));
		} else {
			check = null;
		}
	}

	static Art art() {
		return art;
	}

	static MapCheck check() {
		return check;
	}

	static void open(Project project) throws IOException {
		Art a = Art.open(project);
		art = a;
		plan = null;
		check = null;
		a.convert(null);
		MapsmithConfig.INSTANCE.lastProject = Project.folderName(project.name);
		MapsmithConfig.save();
	}

	static void close() {
		art = null;
		plan = null;
		check = null;
		MapsmithConfig.INSTANCE.lastProject = "";
		MapsmithConfig.save();
	}

	/**
	 * The open project, placed in this world, converted: what the builder, the ghosts and the check work from. Null if
	 * any of that isn't so. The same object until something changes.
	 */
	static Plan plan() {
		Art a = art;
		MinecraftClient client = MinecraftClient.getInstance();
		if (a == null || client.world == null) return null;
		Art.Done d = a.done();
		Project.Placement pl = a.project.placement;
		if (d == null || pl == null) return null;
		String world = worldId(client);
		if (!pl.place.equals(world) || !pl.dimension.equals(client.world.getRegistryKey().getValue().toString())) return null;
		if (plan == null || planDone != d || planPlacement != pl || !world.equals(planWorld)) {
			plan = new Plan(d.result(), d.layout(), d.blocks(), d.filler(), pl.x, pl.y, pl.z);
			planDone = d;
			planPlacement = pl;
			planWorld = world;
		}
		return plan;
	}

	/** Which world this is: the server's address, or the singleplayer world's folder. */
	static String worldId(MinecraftClient client) {
		if (client.getServer() != null) return "local:" + client.getServer().getSaveProperties().getLevelName();
		ServerInfo info = client.getCurrentServerEntry();
		if (info == null) return "unknown";
		String a = info.address.toLowerCase(Locale.ROOT).strip();
		if (a.endsWith(":25565")) a = a.substring(0, a.length() - 6);
		return a;
	}

	/** Puts the open project's north-west corner on the map square the player is in, at their feet. */
	static boolean placeHere(MinecraftClient client, int mapsEast, int mapsSouth) {
		Art a = art;
		if (a == null || client.player == null || client.world == null) return false;
		int x = Plan.mapCorner(client.player.getBlockX()) + mapsEast * 128;
		int z = Plan.mapCorner(client.player.getBlockZ()) + mapsSouth * 128;
		int y = Walker.feet(client.player).getY();
		a.project.placement = new Project.Placement(worldId(client), client.world.getRegistryKey().getValue().toString(), x, y, z);
		a.project.save();
		return true;
	}

	static int tick() {
		return tick;
	}
}
