package com.greysworkshop.mapsmith;

import com.greysworkshop.mapsmith.MapPalette.Shade;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.item.Items;
import net.minecraft.item.map.MapState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Hand;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.rule.GameRules;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Development-only scripted test, run with {@code ./gradlew runSelfTest}. Not in the release jar. Builds staircase art
 * in creative (flying) and checks a real map of it matches the art pixel for pixel; then flat carpet art in survival,
 * walking, with a wrong block to fix, a hole to fill and a block it's run out of.
 */
final class SelfTest {
	private static final String WORLD = "mapsmith-selftest";
	private static boolean started;
	private static int tick = -1, phaseStart, passed, failed;
	private static String phase = "setup";
	private static Path picture;
	private static Block withheld;
	private static double startX, startZ;
	private static int mapWait;

	private SelfTest() {}

	static void start() {
		log("Self-test armed");
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
		ClientTickEvents.END_CLIENT_TICK.register(SelfTest::tick);
	}

	private static void openWorld(MinecraftClient client) {
		client.options.pauseOnLostFocus = false;
		Path saves = client.runDirectory.toPath().resolve("saves").resolve(WORLD);
		deleteTree(saves);
		deleteTree(Project.projects());
		try {
			MapsmithConfig.useFile(Files.createTempDirectory("mapsmith").resolve("mapsmith.json"));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		Mapsmith.close();
		LevelInfo info = new LevelInfo(WORLD, GameMode.CREATIVE, false, Difficulty.PEACEFUL, true,
				new GameRules(FeatureFlags.DEFAULT_ENABLED_FEATURES), DataConfiguration.SAFE_MODE);
		client.createIntegratedServerLoader().createAndStart(WORLD, info, GeneratorOptions.createTestWorld(),
				WorldPresets::createTestOptions, new TitleScreen());
	}

	private static void deleteTree(Path dir) {
		try (Stream<Path> files = Files.exists(dir) ? Files.walk(dir) : Stream.empty()) {
			files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		} catch (IOException e) {
			log("Could not clear " + dir + ": " + e);
		}
	}

	private static void tick(MinecraftClient client) {
		if (client.player == null || client.world == null || client.getServer() == null) return;
		tick++;
		int since = tick - phaseStart;
		switch (phase) {
			case "setup" -> {
				if (tick == 20) {
					checkPalette();
					command(client, "time set 1000");
					command(client, "weather clear");
					command(client, "tp @p 0.5 -60 0.5 0 90");
				}
				if (tick == 40) {
					picture = writePicture(client);
					try {
						Project p = Project.create(picture);
						staircase = p;
						p.settings.mode = ArtSettings.Mode.STAIRCASE;
						p.settings.maxHeight = 20;
						p.save();
						Mapsmith.open(p);
					} catch (IOException e) {
						check("project made from a PNG (" + e + ")", false);
					}
				}
				if (tick > 40 && Mapsmith.art() != null && Mapsmith.art().done() != null && !Mapsmith.art().working()) {
					Art.Done d = Mapsmith.art().done();
					check("the picture converts (" + d.result().colorsUsed + " colours, difference " + String.format("%.2f", d.result().meanDifference)
							+ ", tallest " + (d.layout().maxLevel + 1) + ")", d.result().width == 128 && d.result().colorsUsed > 40);
					check("the height limit is kept (" + (d.layout().maxLevel + 1) + " high, limit 20)", d.layout().maxLevel <= 20);
					Mapsmith.placeHere(client, 0, 0);
					Plan plan = Mapsmith.plan();
					check("placed on the map grid: corner -64,-64 at y -60 (" + (plan == null ? "none" : plan.x0 + "," + plan.y0 + "," + plan.z0) + ")",
							plan != null && plan.x0 == -64 && plan.z0 == -64 && plan.y0 == -60);
					if (Boolean.getBoolean("mapsmith.selftest.stairs")) {
						command(client, "gamemode survival @p");
						next("stairs_setup");
						return;
					}
					client.setScreen(new StudioScreen(null));
					next("studio_shot");
				}
				if (tick > 1200) fail(client, "conversion never finished");
			}
			case "studio_shot" -> {
				if (since == 20) screenshot(client, "1_studio");
				if (since == 22) client.setScreen(new PaletteScreen(null, Mapsmith.art()));
				if (since == 40) screenshot(client, "2_palette");
				if (since == 42) client.setScreen(new MaterialsScreen(null, Mapsmith.art()));
				if (since == 60) screenshot(client, "3_materials");
				if (since == 62) client.setScreen(new SettingsScreen(null));
				if (since == 75) screenshot(client, "3b_settings");
				if (since == 77) {
					client.setScreen(null);
					exports(client);
					command(client, "tp @p -60.5 -59 -70.5 -45 40");
				}
				if (since == 105) screenshot(client, "4_ghosts_before");
				if (since == 107) {
					MapsmithConfig.INSTANCE.creativeSpeed = 400;
					Builder.region = CREATIVE;
					Builder.start(client);
					check("the builder starts", Builder.running());
					next("build_creative");
				}
			}
			case "build_creative" -> {
				MapCheck c = Mapsmith.check();
				if (since % 400 == 0) log("building: state " + Builder.state + ", placed " + Builder.placed + ", map check " + (c == null ? "-" : c.right + " right"));
				if (since == 300) screenshot(client, "5_building");
				if (Builder.state == Builder.State.DONE || Builder.state == Builder.State.PAUSED) {
					log("builder stopped: " + Builder.status.getString());
					check("staircase art built in creative (" + Builder.placed + " placed): " + Builder.status.getString(), Builder.state == Builder.State.DONE);
					next("verify_creative");
				}
				if (since > 9000) {
					check("staircase build finished in time", false);
					Builder.stop(client, Builder.State.OFF, net.minecraft.text.Text.empty());
					next("verify_creative");
				}
			}
			case "verify_creative" -> {
				MapCheck c = Mapsmith.check();
				if (since == 1) mapWait = 0;
				if (since > 60 && c != null && c.complete) {
					MapCheck fresh = new MapCheck(Mapsmith.plan());
					fresh.step(client.world, 128);
					int[] score = regionScore(Mapsmith.plan(), fresh.seen, CREATIVE);
					check("the simulated map matches every pixel built (" + score[0] + " right, " + score[1] + " wrong)", score[1] == 0);
					// A real map: stand in the middle and use an empty map.
					command(client, "tp @p 0.5 " + (Mapsmith.plan().topY() + 1) + " 0.5 0 90");
					command(client, "clear @p");
					command(client, "item replace entity @p weapon.mainhand with minecraft:map");
					next("make_map");
				}
				if (since > 2000) {
					check("map check completes", false);
					next("make_map");
				}
			}
			case "make_map" -> {
				if (since == 20) client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
				// In creative the empty map stays in hand and the new one goes elsewhere: hold that.
				if (since > 20 && !client.player.getMainHandStack().isOf(Items.FILLED_MAP)) {
					var inv = client.player.getInventory();
					for (int i = 0; i < 9; i++) if (inv.getStack(i).isOf(Items.FILLED_MAP)) inv.setSelectedSlot(i);
				}
				if (since > 20 && client.player.getMainHandStack().isOf(Items.FILLED_MAP)) {
					MapIdComponent id = client.player.getMainHandStack().get(DataComponentTypes.MAP_ID);
					MapState state = id == null ? null : client.world.getMapState(id);
					int[] cmp = state == null ? null : MapCheck.compare(Mapsmith.plan(), state);
					if (cmp != null && cmp[2] == 0 && since > 140 || since > 600) {
						int[] score = state == null ? new int[] {0, 1} : regionScore(Mapsmith.plan(), state.colors, CREATIVE);
						check("a real map of the art matches what's built, pixel for pixel (" + score[0] + " right, " + score[1] + " wrong)",
								score[1] == 0 && score[0] == 32 * 48);
						if (score[1] > 0) logWrong(state);
						screenshot(client, "6_real_map");
						next("survival_setup");
					}
				}
				if (since > 700) {
					check("an empty map turned into a map of the art", false);
					next("survival_setup");
				}
			}
			case "survival_setup" -> survivalSetup(client, since);
			case "survival_build" -> survivalBuild(client, since);
			case "stairs_setup" -> stairsSetup(client, since);
			case "stairs_build" -> stairsBuild(client, since);
			case "finish" -> {
				if (since == 40) finish(client);
			}
			default -> {
			}
		}
	}

	/** What the creative test builds: a corner of the map, 32 wide and 48 tall (a whole map takes half an hour). */
	private static final int[] CREATIVE = {0, 0, 31, 47};

	/** How many pixels in a region a map (or the simulated map) gets right, and how many wrong or not drawn. */
	private static int[] regionScore(Plan plan, byte[] colors, int[] r) {
		int right = 0, wrong = 0;
		for (int z = r[1]; z <= r[3]; z++) {
			for (int x = r[0]; x <= r[2]; x++) {
				int i = z * 128 + x;
				if (colors[i] == MapPalette.colorByte(plan.art.color[i], Shade.of(plan.art.shade[i]))) right++;
				else wrong++;
			}
		}
		return new int[] {right, wrong};
	}

	private static Project staircase;
	/** Survival on staircase art: a strip of the first art the creative test didn't build. */
	private static final int[] STAIRS = {40, 0, 51, 19};

	private static void stairsSetup(MinecraftClient client, int since) {
		if (since == 1) {
			Builder.stop(client, Builder.State.OFF, net.minecraft.text.Text.empty());
			command(client, "clear @p");
			command(client, "tp @p -18.5 -60 -67.5 180 30");
			try {
				Mapsmith.open(Project.load(staircase.dir()));
			} catch (IOException e) {
				check("staircase project reopens (" + e + ")", false);
			}
		}
		if (since == 40) {
			Plan plan = Mapsmith.plan();
			check("the staircase art reopens where it was placed", plan != null && plan.x0 == -64 && plan.layout.maxLevel > 3);
			if (plan == null) {
				next("finish");
				return;
			}
			Builder.region = STAIRS;
			Map<Block, Integer> need = new java.util.LinkedHashMap<>();
			for (int x = STAIRS[0]; x <= STAIRS[2]; x++) {
				for (int node = 0; node <= STAIRS[3] + 1; node++) {
					for (int lv = plan.layout.supportFrom[x][node]; lv <= plan.level(x, node); lv++) {
						Block b = plan.blockToPlace(plan.x0 + x, plan.y0 + lv, plan.z0 + node - 1);
						if (b != null) need.merge(b, 1, Integer::sum);
					}
				}
			}
			command(client, "give @p minecraft:diamond_pickaxe");
			// Spare filler, to pillar up with.
			need.merge(plan.filler, 64, Integer::sum);
			int total = 0;
			for (Map.Entry<Block, Integer> e : need.entrySet()) {
				command(client, "give @p " + Registries.ITEM.getId(e.getKey().asItem()) + " " + (e.getValue() + 2));
				total += e.getValue();
			}
			log("staircase region needs " + total + " blocks of " + need.size() + " kinds, " + (plan.layout.maxLevel + 1) + " high at most");
		}
		if (since == 60) {
			startX = client.player.getX();
			startZ = client.player.getZ();
			Builder.start(client);
			next("stairs_build");
		}
	}

	private static void stairsBuild(MinecraftClient client, int since) {
		if (since % 200 == 0) log("stairs: " + Builder.state + " placed " + Builder.placed + " at " + client.player.getBlockPos().toShortString()
				+ " · " + Builder.status.getString());
		if (since == 300) screenshot(client, "9_stairs_building");
		if (since > 20 && Builder.state != Builder.State.RUNNING || since > 14000) {
			check("survival staircase: finishes (" + Builder.status.getString() + ")", Builder.state == Builder.State.DONE);
			MapCheck c = new MapCheck(Mapsmith.plan());
			c.step(client.world, 128);
			int[] score = regionScore(Mapsmith.plan(), c.seen, STAIRS);
			check("survival staircase: every pixel maps right (" + score[0] + " right, " + score[1] + " wrong)", score[1] == 0);
			screenshot(client, "10_stairs_done");
			Builder.stop(client, Builder.State.OFF, net.minecraft.text.Text.empty());
			next("finish");
		}
	}

	/** Region of the survival test: pixels 0..15 both ways (with the reference row). */
	private static final int[] REGION = {0, 0, 15, 15};

	private static void survivalSetup(MinecraftClient client, int since) {
		if (since == 1) {
			Builder.stop(client, Builder.State.OFF, net.minecraft.text.Text.empty());
			command(client, "clear @p");
			command(client, "gamemode survival @p");
			command(client, "tp @p 70.5 -60 -62.5 0 30");
		}
		if (since == 20) {
			try {
				Project p = Project.create(picture);
				p.settings.mode = ArtSettings.Mode.FLAT;
				p.carpets = true;
				p.save();
				Mapsmith.open(p);
			} catch (IOException e) {
				check("second project (" + e + ")", false);
			}
		}
		if (since == 60 && Mapsmith.art() != null && Mapsmith.art().done() != null) {
			Mapsmith.placeHere(client, 0, 0);
			Plan plan = Mapsmith.plan();
			check("flat art placed one map east: corner 64,-64 (" + (plan == null ? "none" : plan.x0 + "," + plan.z0) + ")",
					plan != null && plan.x0 == 64 && plan.z0 == -64);
			check("flat art is one level", plan != null && plan.layout.maxLevel == 0);
			Builder.region = REGION;
			// What the region takes, all given but one kind: the builder must say it's out of that.
			Map<Block, Integer> need = new java.util.LinkedHashMap<>();
			for (int x = 0; x <= 15; x++) {
				for (int z = -1; z <= 15; z++) {
					Block b = plan.blockToPlace(64 + x, -60, -64 + z);
					if (b != null) need.merge(b, 1, Integer::sum);
				}
			}
			need.merge(plan.filler, 8, Integer::sum);
			withheld = need.entrySet().stream().filter(e -> e.getKey() != plan.filler).max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
			for (Map.Entry<Block, Integer> e : need.entrySet()) {
				if (e.getKey() == withheld) continue;
				command(client, "give @p " + Registries.ITEM.getId(e.getKey().asItem()) + " " + (e.getValue() + 4));
			}
			withheldCount = need.getOrDefault(withheld, 0) + 4;
			command(client, "give @p minecraft:diamond_pickaxe");
			command(client, "give @p minecraft:diamond_shovel");
			// A wrong block to fix (pixel 2,2), a hole in the ground under pixel 6,4, grass in the way at pixel 4,6.
			command(client, "setblock 66 -60 -62 minecraft:stone");
			command(client, "setblock 70 -61 -60 minecraft:air");
			command(client, "setblock 68 -60 -58 minecraft:short_grass");
			log("withheld " + withheld + "; needs " + need.size() + " kinds");
		}
		if (since == 80) {
			startX = client.player.getX();
			startZ = client.player.getZ();
			Builder.start(client);
			next("survival_build");
		}
	}

	private static boolean refilled;
	private static int withheldCount;

	private static void survivalBuild(MinecraftClient client, int since) {
		if (since % 200 == 0) log("survival: " + Builder.state + " placed " + Builder.placed + " broken " + Builder.broken + " at "
				+ client.player.getBlockPos().toShortString() + " · " + Builder.status.getString());
		if (since == 160) screenshot(client, "7_survival_building");
		if (!refilled && Builder.state == Builder.State.PAUSED) {
			String s = Builder.status.getString();
			check("survival: pauses saying it's out of " + withheld.getName().getString() + " (\"" + s + "\")",
					withheld != null && s.contains(withheld.getName().getString()));
			command(client, "give @p " + Registries.ITEM.getId(withheld.asItem()) + " " + withheldCount);
			refilled = true;
			phaseStart = tick - 1;
			return;
		}
		if (refilled && since == 20) Builder.start(client);
		if (refilled && since > 20 && Builder.state != Builder.State.RUNNING) {
			check("survival: finishes the region (" + Builder.status.getString() + ")", Builder.state == Builder.State.DONE);
			checkRegion(client);
			log("block at the wrong-block spot: " + client.world.getBlockState(new net.minecraft.util.math.BlockPos(66, -60, -62)));
			double walked = Math.hypot(client.player.getX() - startX, client.player.getZ() - startZ);
			check("survival: walked to do it (" + String.format("%.1f", walked) + " blocks from the start)", walked > 3);
			check("survival: broke the wrong block (" + Builder.broken + " broken)", Builder.broken >= 1);
			screenshot(client, "8_survival_done");
			next("stairs_setup");
		}
		if (since > 12000) {
			check("survival build finished in time (" + Builder.status.getString() + ")", false);
			Builder.stop(client, Builder.State.OFF, net.minecraft.text.Text.empty());
			checkRegion(client);
			next("finish");
		}
	}

	private static void checkRegion(MinecraftClient client) {
		MapCheck c = new MapCheck(Mapsmith.plan());
		c.step(client.world, Mapsmith.plan().width());
		int right = 0, wrong = 0;
		for (int z = REGION[1]; z <= REGION[3]; z++) {
			for (int x = REGION[0]; x <= REGION[2]; x++) {
				byte s = c.status[z * 128 + x];
				if (s == MapCheck.RIGHT) right++;
				else {
					wrong++;
					if (wrong < 6) log("region pixel " + x + "," + z + " status " + s + " sees " + c.seen[z * 128 + x] + " wants "
							+ MapPalette.colorByte(Mapsmith.plan().art.colorAt(x, z), Shade.NORMAL));
				}
			}
		}
		check("survival: every pixel of the region maps right (" + right + " right, " + wrong + " not)", wrong == 0);
	}

	private static void logWrong(MapState state) {
		Plan plan = Mapsmith.plan();
		int n = 0;
		for (int i = 0; i < 128 * 128 && n < 10; i++) {
			if (i % 128 > CREATIVE[2] || i / 128 > CREATIVE[3]) continue;
			byte want = MapPalette.colorByte(plan.art.color[i], Shade.of(plan.art.shade[i]));
			if (state.colors[i] != want) {
				log("map pixel " + i % 128 + "," + i / 128 + " is " + state.colors[i] + " wants " + want);
				n++;
			}
		}
	}

	/** The colour table and the block lists agree with the game. */
	private static void checkPalette() {
		boolean same = true;
		for (int id = 1; id < MapPalette.COLORS; id++) {
			for (Shade s : Shade.values()) {
				MapColor.Brightness b = MapColor.Brightness.validateAndGet(s.id);
				if ((MapColor.get(id).getRenderColor(b) & 0xFFFFFF) != MapPalette.rgb(id, s)) {
					same = false;
					log("colour " + id + " " + s + " differs");
				}
			}
		}
		check("all 61 map colours in 4 shades match the game's", same);
		int colours = 0;
		StringBuilder missingFav = new StringBuilder();
		for (int id = 1; id < MapPalette.COLORS; id++) {
			if (id == MapPalette.WATER) continue;
			var list = BlockPalette.candidates(id);
			if (!list.isEmpty()) colours++;
			else missingFav.append(" ").append(id);
			Block def = BlockPalette.defaultFor(id, false);
			if (def != null && BlockPalette.colorOf(def.getDefaultState()) != id) missingFav.append(" wrong:").append(id);
		}
		log("colours without a block:" + missingFav);
		check("a block for every map colour but water (" + colours + " of 60)", colours == 60);
	}

	private static Path writePicture(MinecraftClient client) {
		Path dir = Project.pictures();
		Path file = dir.resolve("selftest.png");
		try {
			Files.createDirectories(dir);
			try (NativeImage img = new NativeImage(160, 120, false)) {
				for (int y = 0; y < 120; y++) {
					for (int x = 0; x < 160; x++) {
						float hue = x / 160f, sat = 1 - y / 120f;
						int c = java.awt.Color.HSBtoRGB(hue, sat, 0.35f + 0.65f * (float) Math.abs(Math.sin((x + y) / 25.0)));
						double dx = x - 80, dy = y - 60;
						if (dx * dx + dy * dy < 400) c = 0xFFFFFF;
						img.setColorArgb(x, y, 0xFF000000 | c);
					}
				}
				img.writeTo(file);
			}
		} catch (IOException e) {
			check("test picture written (" + e + ")", false);
		}
		return file;
	}

	private static void exports(MinecraftClient client) {
		Plan plan = Mapsmith.plan();
		try {
			Path dir = Files.createTempDirectory("mapsmith-export");
			Path lit = Schematics.litematic(plan, "selftest", dir);
			Path schem = Schematics.sponge(plan, "selftest", dir);
			Path png = Schematics.png(plan.art, dir.resolve("art.png"));
			NbtCompound root = NbtIo.readCompressed(lit, NbtSizeTracker.ofUnlimitedBytes());
			NbtCompound region = root.getCompoundOrEmpty("Regions").getCompoundOrEmpty("selftest");
			NbtList palette = region.getListOrEmpty("BlockStatePalette");
			long[] states = region.getLongArray("BlockStates").orElse(new long[0]);
			NbtCompound size = region.getCompoundOrEmpty("Size");
			int sx = size.getInt("x", 0), sy = size.getInt("y", 0), sz = size.getInt("z", 0);
			int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
			boolean ok = sx == 128 && sz == 129 && sy == plan.layout.maxLevel + 1;
			int checked = 0;
			for (int x = 0; x < 128 && ok; x += 7) {
				for (int node = 0; node <= 128 && ok; node += 5) {
					int y = plan.level(x, node);
					long i = ((long) y * sz + node) * sx + x;
					long start = i * bits;
					int at = (int) (start >> 6), off = (int) (start & 63);
					long v = states[at] >>> off;
					if (off + bits > 64) v |= states[at + 1] << (64 - off);
					v &= (1L << bits) - 1;
					String name = palette.getCompoundOrEmpty((int) v).getString("Name", "");
					Block want = plan.blockToPlace(plan.x0 + x, plan.y0 + y, plan.z0 + node - 1);
					if (!name.equals(Registries.BLOCK.getId(want).toString())) {
						ok = false;
						log("litematic at " + x + "," + y + "," + node + " has " + name + " wants " + want);
					}
					checked++;
				}
			}
			check("the Litematica file reads back right (" + checked + " blocks checked, " + palette.size() + " block types)", ok);
			NbtCompound sponge = NbtIo.readCompressed(schem, NbtSizeTracker.ofUnlimitedBytes());
			check("the Sponge schematic is the art's size", sponge.getShort("Width", (short) 0) == 128 && sponge.getShort("Length", (short) 0) == 129);
			check("the PNG preview is written", Files.size(png) > 1000);
		} catch (IOException | RuntimeException e) {
			check("exports (" + e + ")", false);
		}
	}

	private static void fail(MinecraftClient client, String why) {
		check(why, false);
		next("finish");
	}

	private static void command(MinecraftClient client, String command) {
		MinecraftServer server = client.getServer();
		server.execute(() -> server.getCommandManager().parseAndExecute(server.getCommandSource(), command));
	}

	private static void next(String name) {
		phase = name;
		phaseStart = tick;
	}

	private static void finish(MinecraftClient client) {
		log("RESULT " + passed + " passed, " + failed + " failed");
		next("done");
		client.scheduleStop();
	}

	private static void screenshot(MinecraftClient client, String name) {
		client.getToastManager().clear();
		ScreenshotRecorder.saveScreenshot(client.runDirectory, "ms_" + name + ".png", client.getFramebuffer(), 1, message -> {});
		log("Screenshot " + name);
	}

	private static void check(String what, boolean ok) {
		if (ok) passed++;
		else failed++;
		log((ok ? "PASS " : "FAIL ") + what);
	}

	private static void log(String message) {
		Mapsmith.LOGGER.info("[SELFTEST] {}", message);
	}
}
