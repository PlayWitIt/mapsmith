package com.greysworkshop.mapsmith;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

/** A texture of the art as the maps will show it, or of the framed picture, remade when the art changes. */
final class Preview {
	private final Identifier id;
	private NativeImageBackedTexture texture;
	private Object shownFor;
	private int width, height;

	Preview(String name) {
		id = Identifier.of(Mapsmith.MOD_ID, "preview/" + name);
	}

	/** The texture for {@code result} ({@code picture}: the framed picture instead of the map colours). */
	Identifier texture(Converter.Result result, boolean picture) {
		Object key = picture ? result.source : result;
		if (key == shownFor && texture != null) return id;
		int w = result.width, h = result.height;
		if (texture == null || w != width || h != height) {
			if (texture != null) MinecraftClient.getInstance().getTextureManager().destroyTexture(id);
			texture = new NativeImageBackedTexture(() -> "mapsmith preview", new NativeImage(w, h, false));
			MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
			width = w;
			height = h;
		}
		NativeImage img = texture.getImage();
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int rgb = picture ? result.source.get(x, y) : result.rgb(x, y);
				img.setColorArgb(x, y, 0xFF000000 | rgb);
			}
		}
		texture.upload();
		shownFor = key;
		return id;
	}

	int width() {
		return width;
	}

	int height() {
		return height;
	}

	void close() {
		if (texture != null) MinecraftClient.getInstance().getTextureManager().destroyTexture(id);
		texture = null;
		shownFor = null;
	}
}
