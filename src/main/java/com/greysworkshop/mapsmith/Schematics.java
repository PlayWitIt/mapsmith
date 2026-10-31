package com.greysworkshop.mapsmith;

import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtIntArray;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtLongArray;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Saves the art for other tools: a Litematica schematic (for its printer, or to check a build against), a Sponge
 * schematic (WorldEdit, and Baritone's builder), and a PNG of exactly what the map will show. The schematics hold the
 * art blocks, the reference row and the supports; their corner goes at the reference row's west end.
 */
final class Schematics {
	private Schematics() {}

	/** The schematic's blocks as states, [x][y][z], with the size: x = columns, y = levels, z = reference row + rows. */
	private static BlockState[][][] blocks(Plan plan) {
		int w = plan.width(), h = plan.layout.maxLevel + 1, l = plan.height() + 1;
		BlockState[][][] out = new BlockState[w][h][l];
		for (int x = 0; x < w; x++) {
			for (int node = 0; node < l; node++) {
				int top = plan.level(x, node), from = plan.layout.supportFrom[x][node];
				for (int y = from; y <= top; y++) {
					Block b = plan.blockToPlace(plan.x0 + x, plan.y0 + y, plan.z0 + node - 1);
					out[x][y][node] = b == null ? null : b.getDefaultState();
				}
			}
		}
		return out;
	}

	/** Litematica's format (version 7). */
	static Path litematic(Plan plan, String name, Path dir) throws IOException {
		BlockState[][][] b = blocks(plan);
		int sx = b.length, sy = b[0].length, sz = b[0][0].length;
		List<BlockState> palette = new ArrayList<>();
		Map<BlockState, Integer> index = new LinkedHashMap<>();
		palette.add(Blocks.AIR.getDefaultState());
		index.put(Blocks.AIR.getDefaultState(), 0);
		long volume = (long) sx * sy * sz, count = 0;
		for (BlockState[][] col : b) for (BlockState[] row : col) for (BlockState s : row) {
			if (s != null && !index.containsKey(s)) {
				index.put(s, palette.size());
				palette.add(s);
			}
		}
		int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
		long[] packed = new long[(int) ((volume * bits + 63) / 64)];
		long mask = (1L << bits) - 1;
		for (int y = 0; y < sy; y++) {
			for (int z = 0; z < sz; z++) {
				for (int x = 0; x < sx; x++) {
					BlockState s = b[x][y][z];
					if (s == null) continue;
					count++;
					long i = ((long) y * sz + z) * sx + x;
					long value = index.get(s);
					long start = i * bits;
					int at = (int) (start >> 6), end = (int) ((start + bits - 1) >> 6), off = (int) (start & 63);
					packed[at] = packed[at] & ~(mask << off) | (value & mask) << off;
					if (at != end) {
						int rest = 64 - off;
						packed[end] = packed[end] >>> (bits - rest) << (bits - rest) | (value & mask) >> rest;
					}
				}
			}
		}
		NbtList pal = new NbtList();
		for (BlockState s : palette) pal.add(NbtHelper.fromBlockState(s));
		NbtCompound region = new NbtCompound();
		region.put("Position", vec(0, 0, 0));
		region.put("Size", vec(sx, sy, sz));
		region.put("BlockStatePalette", pal);
		region.put("BlockStates", new NbtLongArray(packed));
		region.put("TileEntities", new NbtList());
		region.put("Entities", new NbtList());
		region.put("PendingBlockTicks", new NbtList());
		region.put("PendingFluidTicks", new NbtList());
		NbtCompound regions = new NbtCompound();
		regions.put(name, region);

		NbtCompound meta = new NbtCompound();
		meta.putString("Name", name);
		meta.putString("Author", "Mapsmith");
		meta.putString("Description", "Map art. Place its corner at " + plan.x0 + " " + plan.y0 + " " + (plan.z0 - 1)
				+ " (the west end of the reference row, just north of the map) so it lines up with the map grid.");
		meta.putInt("RegionCount", 1);
		meta.putLong("TotalVolume", volume);
		meta.putLong("TotalBlocks", count);
		long now = System.currentTimeMillis();
		meta.putLong("TimeCreated", now);
		meta.putLong("TimeModified", now);
		meta.put("EnclosingSize", vec(sx, sy, sz));
		meta.put("PreviewImageData", new NbtIntArray(preview(plan, 140)));

		NbtCompound root = new NbtCompound();
		root.putInt("MinecraftDataVersion", SharedConstants.getGameVersion().dataVersion().id());
		root.putInt("Version", 7);
		root.putInt("SubVersion", 1);
		root.put("Metadata", meta);
		root.put("Regions", regions);
		Path file = dir.resolve(fileName(name, plan) + ".litematic");
		write(root, "", file);
		return file;
	}

	/** The Sponge schematic format, version 2. */
	static Path sponge(Plan plan, String name, Path dir) throws IOException {
		BlockState[][][] b = blocks(plan);
		int w = b.length, h = b[0].length, l = b[0][0].length;
		Map<String, Integer> palette = new LinkedHashMap<>();
		palette.put("minecraft:air", 0);
		ByteArrayOutputStream data = new ByteArrayOutputStream();
		for (int y = 0; y < h; y++) {
			for (int z = 0; z < l; z++) {
				for (int x = 0; x < w; x++) {
					BlockState s = b[x][y][z];
					String key = s == null ? "minecraft:air" : BlockArgumentParser.stringifyBlockState(s);
					Integer id = palette.get(key);
					if (id == null) palette.put(key, id = palette.size());
					int v = id;
					while ((v & ~0x7F) != 0) {
						data.write(v & 0x7F | 0x80);
						v >>>= 7;
					}
					data.write(v);
				}
			}
		}
		NbtCompound pal = new NbtCompound();
		palette.forEach(pal::putInt);
		NbtCompound root = new NbtCompound();
		root.putInt("Version", 2);
		root.putInt("DataVersion", SharedConstants.getGameVersion().dataVersion().id());
		root.putShort("Width", (short) w);
		root.putShort("Height", (short) h);
		root.putShort("Length", (short) l);
		root.putIntArray("Offset", new int[] {0, 0, 0});
		root.putInt("PaletteMax", palette.size());
		root.put("Palette", pal);
		root.putByteArray("BlockData", data.toByteArray());
		root.put("BlockEntities", new NbtList());
		NbtCompound meta = new NbtCompound();
		meta.putString("Name", name);
		meta.putString("Author", "Mapsmith");
		meta.putInt("WEOffsetX", 0);
		meta.putInt("WEOffsetY", 0);
		meta.putInt("WEOffsetZ", 0);
		root.put("Metadata", meta);
		Path file = dir.resolve(fileName(name, plan) + ".schem");
		write(root, "Schematic", file);
		return file;
	}

	/** Exactly what the maps will show, one pixel per block. */
	static Path png(Converter.Result art, Path file) throws IOException {
		BufferedImage img = new BufferedImage(art.width, art.height, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < art.height; y++) for (int x = 0; x < art.width; x++) img.setRGB(x, y, art.rgb(x, y));
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".part");
		ImageIO.write(img, "png", tmp.toFile());
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		return file;
	}

	static String fileName(String name, Plan plan) {
		return Project.folderName(name) + " at " + plan.x0 + " " + plan.y0 + " " + (plan.z0 - 1);
	}

	private static int[] preview(Plan plan, int size) {
		int[] out = new int[size * size];
		Converter.Result art = plan.art;
		double k = Math.max(art.width, art.height) / (double) size;
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				int ax = (int) (x * k), ay = (int) (y * k);
				out[y * size + x] = ax < art.width && ay < art.height ? 0xFF000000 | art.rgb(ax, ay) : 0;
			}
		}
		return out;
	}

	private static NbtCompound vec(int x, int y, int z) {
		NbtCompound c = new NbtCompound();
		c.putInt("x", x);
		c.putInt("y", y);
		c.putInt("z", z);
		return c;
	}

	/** Gzipped NBT with a root tag of the given name. */
	private static void write(NbtCompound root, String rootName, Path file) throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".part");
		try (OutputStream os = Files.newOutputStream(tmp); DataOutputStream out = new DataOutputStream(new GZIPOutputStream(os))) {
			out.writeByte(NbtElement.COMPOUND_TYPE);
			out.writeUTF(rootName);
			root.write(out);
		}
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
	}
}
