package com.greysworkshop.mapsmith;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Mapsmith's settings, in config/mapsmith.json. */
final class MapsmithConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	static MapsmithConfig INSTANCE = new MapsmithConfig();
	private static Path file = FabricLoader.getInstance().getConfigDir().resolve("mapsmith.json");

	/** Blocks placed per second, in survival and in creative. */
	int speed = 8, creativeSpeed = 20;
	/** Turn to look at each block placed (as you would by hand; some servers check). */
	boolean rotate = true;
	/** Break blocks that are the wrong colour, or in the way, and put the right ones in. */
	boolean fixMistakes = true;
	/** Walk (or in creative, fly) from one part of the art to the next. */
	boolean autoWalk = true;
	/** In creative, fly instead of walking. */
	boolean fly = true;
	/** Show the blocks still to place, in the world. */
	boolean ghosts = true;
	int ghostRange = 12;
	/** Show map and chunk borders near you. */
	boolean grid = true;
	boolean hud = true;
	/** The project open last, to open again. */
	String lastProject = "";

	static void load() {
		try {
			if (Files.exists(file)) {
				MapsmithConfig c = GSON.fromJson(Files.readString(file), MapsmithConfig.class);
				if (c != null) INSTANCE = c;
			}
		} catch (IOException | RuntimeException e) {
			LoggerFactory.getLogger("mapsmith").warn("Could not read {}", file, e);
		}
		MapsmithConfig c = INSTANCE;
		c.speed = Math.max(1, Math.min(20, c.speed));
		c.creativeSpeed = Math.max(1, Math.min(40, c.creativeSpeed));
		c.ghostRange = Math.max(4, Math.min(32, c.ghostRange));
		if (c.lastProject == null) c.lastProject = "";
	}

	static void save() {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(INSTANCE));
		} catch (IOException e) {
			LoggerFactory.getLogger("mapsmith").warn("Could not save {}", file, e);
		}
	}

	/** For the self-test: settings in a file of its own. */
	static void useFile(Path f) {
		file = f;
		INSTANCE = new MapsmithConfig();
	}
}
