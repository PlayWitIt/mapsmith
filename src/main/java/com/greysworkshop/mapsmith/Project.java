package com.greysworkshop.mapsmith;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.CarpetBlock;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * One piece of map art: the picture, how it's converted, which blocks it uses, and where in the world it goes. Kept
 * in .minecraft/mapsmith/projects/&lt;name&gt;/ as project.json next to a copy of the picture.
 */
final class Project {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	String name = "";
	/** The picture's file name in the project's folder. */
	String image = "";
	ArtSettings settings = new ArtSettings();
	/** The block for each colour id; "" for a colour that's not used. Colours not in here use their default block. */
	Map<Integer, String> blocks = new TreeMap<>();
	/** Flat art uses carpets where a colour has one (cheaper: 2 wool make 3). */
	boolean carpets = true;
	/** What supports and reference blocks are made of. */
	String filler = "minecraft:cobblestone";
	Placement placement;
	long created = System.currentTimeMillis();

	/** Where the art is built: its north-west pixel's block, at the base level. */
	static final class Placement {
		/** The server address or world name, and the dimension. */
		String place = "", dimension = "";
		int x, y, z;

		Placement() {}

		Placement(String place, String dimension, int x, int y, int z) {
			this.place = place;
			this.dimension = dimension;
			this.x = x;
			this.y = y;
			this.z = z;
		}
	}

	static Path root() {
		return FabricLoader.getInstance().getGameDir().resolve("mapsmith");
	}

	static Path projects() {
		return root().resolve("projects");
	}

	/** Where pictures dropped in by hand are picked up from. */
	static Path pictures() {
		return root().resolve("pictures");
	}

	Path dir() {
		return projects().resolve(folderName(name));
	}

	Path imagePath() {
		return dir().resolve(image);
	}

	static String folderName(String name) {
		String s = name.replaceAll("[^A-Za-z0-9 _.-]", "_").strip();
		return s.isEmpty() ? "art" : s;
	}

	boolean flat() {
		return settings.mode == ArtSettings.Mode.FLAT;
	}

	/** The block for a colour, or null if it isn't used. */
	Block block(int colorId) {
		String id = blocks.get(colorId);
		if (id == null) {
			Block b = BlockPalette.defaultFor(colorId, carpets && flat());
			if (b instanceof CarpetBlock && !flat()) b = BlockPalette.defaultFor(colorId, false);
			return b;
		}
		return id.isEmpty() ? null : BlockPalette.byId(id);
	}

	boolean[] usable() {
		boolean[] u = new boolean[MapPalette.COLORS];
		for (int id = 1; id < u.length; id++) u[id] = id != MapPalette.WATER && block(id) != null;
		return u;
	}

	Block fillerBlock() {
		Block b = BlockPalette.byId(filler);
		return b == null ? net.minecraft.block.Blocks.COBBLESTONE : b;
	}

	/** Makes a project for a picture: copies it in, under a name not already taken. */
	static Project create(Path picture) throws IOException {
		String base = picture.getFileName().toString().replaceFirst("\\.[^.]*$", "");
		String name = base;
		for (int i = 2; Files.exists(projects().resolve(folderName(name))); i++) name = base + " " + i;
		Project p = new Project();
		p.name = name;
		String ext = picture.getFileName().toString().replaceFirst("^.*?(\\.[^.]*)?$", "$1");
		p.image = "picture" + (ext.isEmpty() ? ".png" : ext.toLowerCase());
		Files.createDirectories(p.dir());
		Files.copy(picture, p.imagePath(), StandardCopyOption.REPLACE_EXISTING);
		p.save();
		return p;
	}

	void save() {
		try {
			Files.createDirectories(dir());
			Path tmp = dir().resolve("project.json.tmp");
			Files.writeString(tmp, GSON.toJson(this));
			Files.move(tmp, dir().resolve("project.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			LoggerFactory.getLogger("mapsmith").warn("Could not save project {}", name, e);
		}
	}

	static Project load(Path dir) {
		try {
			Project p = GSON.fromJson(Files.readString(dir.resolve("project.json")), Project.class);
			if (p == null) return null;
			if (p.settings == null) p.settings = new ArtSettings();
			p.settings.sanitize();
			if (p.blocks == null) p.blocks = new TreeMap<>();
			if (p.filler == null) p.filler = "minecraft:cobblestone";
			if (p.name == null || p.name.isEmpty()) p.name = dir.getFileName().toString();
			return p;
		} catch (IOException | RuntimeException e) {
			LoggerFactory.getLogger("mapsmith").warn("Could not read project in {}", dir, e);
			return null;
		}
	}

	/** All projects, newest first. */
	static List<Project> all() {
		List<Project> list = new ArrayList<>();
		if (!Files.isDirectory(projects())) return list;
		try (Stream<Path> dirs = Files.list(projects())) {
			for (Path d : (Iterable<Path>) dirs::iterator) {
				if (Files.exists(d.resolve("project.json"))) {
					Project p = load(d);
					if (p != null) list.add(p);
				}
			}
		} catch (IOException e) {
			LoggerFactory.getLogger("mapsmith").warn("Could not list projects", e);
		}
		list.sort(Comparator.comparingLong((Project p) -> p.created).reversed());
		return list;
	}

	void delete() throws IOException {
		try (Stream<Path> files = Files.walk(dir())) {
			for (Path f : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(f);
		}
	}
}
