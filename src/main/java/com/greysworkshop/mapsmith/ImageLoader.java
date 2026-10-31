package com.greysworkshop.mapsmith;

import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads PNG, JPEG, GIF, BMP, TGA and PSD pictures (stb_image, which the game ships), or anything Java can read. */
final class ImageLoader {
	private ImageLoader() {}

	/** The largest picture read, so a huge photo can't run the game out of memory. */
	static final int MAX_PIXELS = 40_000_000;

	static boolean looksLikePicture(Path p) {
		String n = p.getFileName().toString().toLowerCase();
		return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".gif") || n.endsWith(".bmp")
				|| n.endsWith(".tga") || n.endsWith(".psd") || n.endsWith(".webp");
	}

	static Img read(Path file) throws IOException {
		byte[] bytes = Files.readAllBytes(file);
		Img img = readStb(bytes);
		if (img != null) return img;
		BufferedImage b = ImageIO.read(file.toFile());
		if (b == null) throw new IOException("Not a picture Mapsmith can read (try PNG or JPEG)");
		if ((long) b.getWidth() * b.getHeight() > MAX_PIXELS) throw new IOException("That picture is too big");
		int[] px = b.getRGB(0, 0, b.getWidth(), b.getHeight(), null, 0, b.getWidth());
		return new Img(b.getWidth(), b.getHeight(), px);
	}

	private static Img readStb(byte[] bytes) throws IOException {
		ByteBuffer in = MemoryUtil.memAlloc(bytes.length);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			in.put(bytes).flip();
			IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1), comp = stack.mallocInt(1);
			if (!STBImage.stbi_info_from_memory(in, w, h, comp)) return null;
			if ((long) w.get(0) * h.get(0) > MAX_PIXELS) throw new IOException("That picture is too big");
			ByteBuffer data = STBImage.stbi_load_from_memory(in, w, h, comp, 4);
			if (data == null) return null;
			try {
				int width = w.get(0), height = h.get(0);
				int[] px = new int[width * height];
				for (int i = 0; i < px.length; i++) {
					int r = data.get(i * 4) & 255, g = data.get(i * 4 + 1) & 255, b = data.get(i * 4 + 2) & 255, a = data.get(i * 4 + 3) & 255;
					px[i] = a << 24 | r << 16 | g << 8 | b;
				}
				return new Img(width, height, px);
			} finally {
				STBImage.stbi_image_free(data);
			}
		} finally {
			MemoryUtil.memFree(in);
		}
	}
}
