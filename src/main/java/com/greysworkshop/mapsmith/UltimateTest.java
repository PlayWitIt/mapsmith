package com.greysworkshop.mapsmith;

import com.greysworkshop.mapsmith.MapPalette.Shade;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.Heightmap;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.rule.GameRules;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The ultimate test ({@code ./gradlew runUltimateTest}): a real picture built in a real survival world, hands free. A
 * normally generated world on Normal; the player starts with nothing but tools and food; the palette is "Blocks I can
 * gather", so every block has to be mined from the world. Reports how much of the art maps right, how long it took,
 * and every pause and why (it carries on after each, as a player pressing G would, to see what else goes wrong).
 * {@code -PultRegion=x0,z0,x1,z1} (pixels) builds part of the map; {@code full} for all of it.
 */
final class UltimateTest {
	private UltimateTest() {}

	private static final String WORLD = "mapsmith-ultimate";
	private static final long SEED = 20261101L;
	private static boolean started;
	private static String phase = "wait";
	private static int tick, phaseStart, resumes;
	private static long runStart;
	private static final List<String> pauses = new ArrayList<>();
	private static int[] region;
	private static int minutes;

	static void start() {
		log("Ultimate test armed");
		String r = System.getProperty("mapsmith.ultimate.region", "40,16,71,47");
		if (!r.equals("full")) {
			String[] p = r.split(",");
			region = new int[] {Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])};
		}
		minutes = Integer.getInteger("mapsmith.ultimate.minutes", 90);
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (!started && screen instanceof net.minecraft.client.gui.screen.AccessibilityOnboardingScreen) {
				client.options.onboardAccessibility = false;
				client.send(() -> client.setScreen(new TitleScreen()));
				return;
			}
			if (started || !(screen instanceof TitleScreen)) return;
			started = true;
			client.send(() -> openWorld(client));
		});
		ClientTickEvents.END_CLIENT_TICK.register(UltimateTest::tick);
	}

	private static void openWorld(MinecraftClient client) {
		client.options.pauseOnLostFocus = false;
		client.options.getViewDistance().setValue(7);
		client.options.getSimulationDistance().setValue(6);
		Path saves = client.runDirectory.toPath().resolve("saves").resolve(WORLD);
		deleteTree(saves);
		deleteTree(Project.projects());
		try {
			MapsmithConfig.useFile(Files.createTempDirectory("mapsmith").resolve("mapsmith.json"));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		Mapsmith.close();
		LevelInfo info = new LevelInfo(WORLD, GameMode.SURVIVAL, false, Difficulty.NORMAL, true,
				new GameRules(FeatureFlags.DEFAULT_ENABLED_FEATURES), DataConfiguration.SAFE_MODE);
		client.createIntegratedServerLoader().createAndStart(WORLD, info, new GeneratorOptions(SEED, true, false),
				WorldPresets::createDemoOptions, new TitleScreen());
	}

	private static void tick(MinecraftClient client) {
		if (client.player == null || client.world == null || client.getServer() == null) return;
		tick++;
		int since = tick - phaseStart;
		switch (phase) {
			case "wait" -> {
				if (since == 100) setup(client);
				if (since == 140) project(client);
				if (since > 160 && Mapsmith.art() != null && Mapsmith.art().done() != null) place(client);
				if (since > 2000) {
					log("FAIL the picture never converted");
					next("report");
				}
			}
			case "run" -> run(client, since);
			case "report" -> {
				if (since == 20) report(client);
				if (since == 60) client.scheduleStop();
			}
			default -> {}
		}
	}

	private static void setup(MinecraftClient client) {
		command(client, "clear @p");
		command(client, "gamerule keepInventory true");
		// Tools and food, nothing else.
		command(client, "give @p minecraft:netherite_pickaxe[enchantments={efficiency:5,unbreaking:3,mending:1}]");
		command(client, "give @p minecraft:netherite_pickaxe[enchantments={efficiency:5,unbreaking:3,silk_touch:1}]");
		command(client, "give @p minecraft:netherite_shovel[enchantments={efficiency:5,unbreaking:3,mending:1}]");
		command(client, "give @p minecraft:netherite_shovel[enchantments={efficiency:5,unbreaking:3,silk_touch:1}]");
		command(client, "give @p minecraft:netherite_axe[enchantments={efficiency:5,unbreaking:3}]");
		command(client, "give @p minecraft:cooked_beef 64");
		try {
			Path pic = Project.pictures().resolve("rickroll.png");
			Files.createDirectories(pic.getParent());
			Path src = Path.of(System.getProperty("mapsmith.ultimate.picture", "mapsmith/pictures/rickroll.png"));
			if (!src.isAbsolute()) src = client.runDirectory.toPath().resolve(src);
			if (!src.equals(pic)) Files.copy(src, pic, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			log("FAIL no picture: " + e);
		}
	}

	private static void project(MinecraftClient client) {
		try {
			Project p = Project.create(Project.pictures().resolve("rickroll.png"));
			p.settings.mode = ArtSettings.Mode.FLAT;
			p.carpets = false;
			PaletteScreen.gatherable(p, Materials.carried(client.player), Gather.hasSilk(client.player.getInventory()),
					Gather.available(client.world, client.player.getBlockPos()));
			p.save();
			Mapsmith.open(p);
			StringBuilder used = new StringBuilder();
			for (Map.Entry<Integer, String> e : p.blocks.entrySet()) if (!e.getValue().isEmpty()) used.append(e.getValue()).append(' ');
			log("palette (blocks it can gather): " + used);
		} catch (IOException e) {
			log("FAIL project: " + e);
			next("report");
		}
	}

	private static void place(MinecraftClient client) {
		// Into the map square we're in, then stand on the ground in the middle of the part to build, and place there.
		int x0 = Plan.mapCorner(client.player.getBlockX()), z0 = Plan.mapCorner(client.player.getBlockZ());
		int[] r = region != null ? region : new int[] {0, 0, 127, 127};
		int cx = x0 + (r[0] + r[2]) / 2, cz = z0 + (r[1] + r[3]) / 2;
		int y = server(client, s -> s.getOverworld().getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, cx, cz));
		command(client, "tp @p " + cx + ".5 " + y + " " + cz + ".5");
		phase = "placing";
		client.execute(() -> client.execute(() -> {
			Mapsmith.placeHere(client, 0, 0);
			Plan plan = Mapsmith.plan();
			Builder.region = region;
			log("placed at " + (plan == null ? "nowhere" : plan.x0 + "," + plan.y0 + "," + plan.z0) + ", building pixels "
					+ java.util.Arrays.toString(r) + "; needs " + (plan == null ? "?" : Builder.remaining(plan, client.world)));
			runStart = System.currentTimeMillis();
			Builder.start(client);
			next("run");
		}));
	}

	private static void run(MinecraftClient client, int since) {
		if (since % 600 == 0) {
			log(String.format("%d min: %s, placed %d, broken %d, at %s · %s", (System.currentTimeMillis() - runStart) / 60000, Builder.state,
					Builder.placed, Builder.broken, client.player.getBlockPos().toShortString(), Builder.status.getString()));
			int[] s = score(client);
			if (s != null) log("  map so far: " + s[0] + " right, " + s[1] + " not yet");
		}
		if (Builder.state == Builder.State.DONE) {
			log("DONE: " + Builder.status.getString());
			next("report");
			return;
		}
		if (Builder.state == Builder.State.PAUSED || Builder.state == Builder.State.OFF) {
			String why = Builder.status.getString();
			if (pressAfter == 0) {
				pauses.add(elapsed() + " " + why);
				log("PAUSE " + elapsed() + ": " + why);
			}
			// Carry on, as a player would, a few seconds later: unless it's the same pause over and over.
			if (pressAfter == 0) {
				if (++resumes > 40) {
					next("report");
					return;
				}
				pressAfter = tick + 100;
			}
		}
		if (pressAfter > 0 && tick >= pressAfter && Builder.state != Builder.State.RUNNING) {
			pressAfter = 0;
			Builder.start(client);
		}
		long work = Builder.placed + Builder.broken;
		if (work != lastWork) {
			lastWork = work;
			lastWorkAt = tick;
		} else if (tick - lastWorkAt == 6000) {
			log("STALL: nothing placed or broken for 5 minutes · " + Builder.status.getString() + " · screen " + client.currentScreen);
		}
		if (System.currentTimeMillis() - runStart > minutes * 60_000L) {
			log("time is up");
			Builder.stop(client, Builder.State.OFF, net.minecraft.text.Text.empty());
			next("report");
		}
	}

	private static int pressAfter, lastWorkAt;
	private static long lastWork = -1;

	private static int[] score(MinecraftClient client) {
		Plan plan = Mapsmith.plan();
		if (plan == null) return null;
		MapCheck c = new MapCheck(plan);
		c.step(client.world, 128);
		int[] r = region != null ? region : new int[] {0, 0, 127, 127};
		int right = 0, wrong = 0;
		for (int z = r[1]; z <= r[3]; z++) {
			for (int x = r[0]; x <= r[2]; x++) {
				int i = z * 128 + x;
				if (c.seen[i] == MapPalette.colorByte(plan.art.color[i], Shade.of(plan.art.shade[i]))) right++;
				else wrong++;
			}
		}
		return new int[] {right, wrong};
	}

	private static void report(MinecraftClient client) {
		int[] s = score(client);
		log("REPORT ==========================================");
		log("REPORT " + elapsed() + ", placed " + Builder.placed + ", broken " + Builder.broken + ", final state " + Builder.state
				+ " (" + Builder.status.getString() + ")");
		if (s != null) log("REPORT map: " + s[0] + " pixels right, " + s[1] + " wrong or missing (" + String.format("%.1f", 100.0 * s[0] / (s[0] + s[1])) + "%)");
		log("REPORT pauses (" + pauses.size() + "): " + (pauses.isEmpty() ? "none" : String.join(" | ", pauses)));
		log("REPORT health " + client.player.getHealth() + ", hunger " + client.player.getHungerManager().getFoodLevel());
	}

	private static String elapsed() {
		long s = (System.currentTimeMillis() - runStart) / 1000;
		return String.format("%d:%02d", s / 60, s % 60);
	}

	private static void next(String p) {
		phase = p;
		phaseStart = tick;
	}

	private static <T> T server(MinecraftClient client, java.util.function.Function<MinecraftServer, T> job) {
		MinecraftServer server = client.getServer();
		try {
			return server.submit(() -> job.apply(server)).get();
		} catch (Exception e) {
			log("server job failed: " + e);
			return null;
		}
	}

	private static void command(MinecraftClient client, String command) {
		server(client, s -> {
			s.getCommandManager().parseAndExecute(s.getCommandSource(), command);
			return 0;
		});
	}

	private static void deleteTree(Path dir) {
		try (Stream<Path> files = Files.exists(dir) ? Files.walk(dir) : Stream.empty()) {
			files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		} catch (IOException e) {
			log("Could not clear " + dir + ": " + e);
		}
	}

	private static void log(String message) {
		Mapsmith.LOGGER.info("[ULTIMATE] " + message);
	}
}
