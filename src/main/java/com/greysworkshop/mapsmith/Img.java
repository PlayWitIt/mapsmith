package com.greysworkshop.mapsmith;

/** A picture as ARGB pixels, with the scaling and adjusting the converter needs. */
final class Img {
	final int width, height;
	final int[] argb;

	Img(int width, int height) {
		this(width, height, new int[width * height]);
	}

	Img(int width, int height, int[] argb) {
		if (width <= 0 || height <= 0 || argb.length != width * height) throw new IllegalArgumentException("bad image size " + width + "x" + height);
		this.width = width;
		this.height = height;
		this.argb = argb;
	}

	int get(int x, int y) {
		return argb[y * width + x];
	}

	enum Fit {
		/** Fills the art, cutting off what sticks out. */
		FILL,
		/** All of the picture, with the background colour around it. */
		FIT,
		/** All of the picture, squashed or stretched to the art's shape. */
		STRETCH
	}

	enum Resample {
		/** Averages the pixels each one covers: best for photos and drawings. */
		SMOOTH,
		/** Takes the nearest pixel: keeps pixel art crisp. */
		SHARP
	}

	/**
	 * The part of this picture the art shows, at {@code w}×{@code h}. {@code zoom} ≥ 1 crops in further; {@code panX}
	 * and {@code panY} (-1..1) pick which part when cropping. Transparent parts are laid on {@code background}.
	 */
	Img frame(int w, int h, Fit fit, Resample resample, double zoom, double panX, double panY, int background) {
		double sw = width, sh = height;
		// The source rectangle (in source pixels) that maps onto the destination's [0,w)×[0,h).
		double srcW, srcH;
		double dstX0 = 0, dstY0 = 0, dstW = w, dstH = h;
		switch (fit) {
			case STRETCH -> {
				srcW = sw;
				srcH = sh;
			}
			case FIT -> {
				double s = Math.min(w / sw, h / sh);
				dstW = sw * s;
				dstH = sh * s;
				dstX0 = (w - dstW) / 2;
				dstY0 = (h - dstH) / 2;
				srcW = sw;
				srcH = sh;
			}
			default -> {
				double s = Math.max(w / sw, h / sh);
				srcW = w / s;
				srcH = h / s;
			}
		}
		zoom = Math.max(1, zoom);
		srcW /= zoom;
		srcH /= zoom;
		double srcX0 = (sw - srcW) / 2 * (1 + clamp(panX)), srcY0 = (sh - srcH) / 2 * (1 + clamp(panY));

		// In linear light, premultiplied by alpha.
		float[] lin = new float[width * height * 4];
		for (int i = 0; i < argb.length; i++) {
			int c = argb[i];
			float a = (c >>> 24) / 255f;
			lin[i * 4] = ColorMath.linear(c >> 16) * a;
			lin[i * 4 + 1] = ColorMath.linear(c >> 8) * a;
			lin[i * 4 + 2] = ColorMath.linear(c) * a;
			lin[i * 4 + 3] = a;
		}
		float bgR = ColorMath.linear(background >> 16), bgG = ColorMath.linear(background >> 8), bgB = ColorMath.linear(background);
		int[] out = new int[w * h];
		double[] px = new double[4];
		double kx = srcW / dstW, ky = srcH / dstH;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				double dx = x + 0.5 - dstX0, dy = y + 0.5 - dstY0;
				if (dx < 0 || dy < 0 || dx > dstW || dy > dstH) {
					out[y * w + x] = background & 0xFFFFFF;
					continue;
				}
				double cx = srcX0 + dx * kx, cy = srcY0 + dy * ky;
				if (resample == Resample.SHARP) {
					int ix = (int) Math.min(width - 1, Math.max(0, Math.floor(cx))), iy = (int) Math.min(height - 1, Math.max(0, Math.floor(cy)));
					int i = (iy * width + ix) * 4;
					for (int k = 0; k < 4; k++) px[k] = lin[i + k];
				} else if (kx > 1 || ky > 1) {
					area(lin, cx - Math.max(kx, 1) / 2, cy - Math.max(ky, 1) / 2, cx + Math.max(kx, 1) / 2, cy + Math.max(ky, 1) / 2, px);
				} else {
					bilinear(lin, cx - 0.5, cy - 0.5, px);
				}
				double a = px[3];
				double r = px[0] + bgR * (1 - a), g = px[1] + bgG * (1 - a), b = px[2] + bgB * (1 - a);
				out[y * w + x] = ColorMath.srgb(r) << 16 | ColorMath.srgb(g) << 8 | ColorMath.srgb(b);
			}
		}
		return new Img(w, h, out);
	}

	private static double clamp(double v) {
		return Math.max(-1, Math.min(1, v));
	}

	/** The average over the source rectangle [x0,x1)×[y0,y1), weighting partly covered pixels by how much is covered. */
	private void area(float[] lin, double x0, double y0, double x1, double y1, double[] out) {
		x0 = Math.max(0, x0);
		y0 = Math.max(0, y0);
		x1 = Math.min(width, x1);
		y1 = Math.min(height, y1);
		out[0] = out[1] = out[2] = out[3] = 0;
		double total = 0;
		for (int y = (int) Math.floor(y0); y < Math.ceil(y1); y++) {
			double wy = Math.min(y + 1, y1) - Math.max(y, y0);
			if (wy <= 0) continue;
			for (int x = (int) Math.floor(x0); x < Math.ceil(x1); x++) {
				double wx = Math.min(x + 1, x1) - Math.max(x, x0);
				if (wx <= 0) continue;
				double wgt = wx * wy;
				int i = (y * width + x) * 4;
				for (int k = 0; k < 4; k++) out[k] += lin[i + k] * wgt;
				total += wgt;
			}
		}
		if (total > 0) for (int k = 0; k < 4; k++) out[k] /= total;
	}

	private void bilinear(float[] lin, double x, double y, double[] out) {
		int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
		double fx = x - x0, fy = y - y0;
		out[0] = out[1] = out[2] = out[3] = 0;
		for (int j = 0; j < 2; j++) {
			for (int i = 0; i < 2; i++) {
				int sx = Math.min(width - 1, Math.max(0, x0 + i)), sy = Math.min(height - 1, Math.max(0, y0 + j));
				double wgt = (i == 0 ? 1 - fx : fx) * (j == 0 ? 1 - fy : fy);
				int at = (sy * width + sx) * 4;
				for (int k = 0; k < 4; k++) out[k] += lin[at + k] * wgt;
			}
		}
	}

	/**
	 * Brightness and contrast (-1..1, 0 unchanged) and saturation (0 grey, 1 unchanged, 2 double), done in OKLab so
	 * colours keep their hue.
	 */
	Img adjusted(double brightness, double contrast, double saturation) {
		if (brightness == 0 && contrast == 0 && saturation == 1) return this;
		int[] out = new int[argb.length];
		float[] lab = new float[3];
		double k = contrast >= 0 ? 1 + contrast * 2 : 1 + contrast;
		for (int i = 0; i < argb.length; i++) {
			ColorMath.rgbToLab(argb[i], lab, 0);
			double L = (lab[0] - 0.5) * k + 0.5 + brightness * 0.5;
			out[i] = (argb[i] & 0xFF000000) | ColorMath.labToRgb(L, lab[1] * saturation, lab[2] * saturation);
		}
		return new Img(width, height, out);
	}
}
