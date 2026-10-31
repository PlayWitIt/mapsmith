package com.greysworkshop.mapsmith;

/**
 * Colour conversions. Colours are matched in OKLab, where equal distances look equally different, so the nearest map
 * colour is the one that looks nearest; images are scaled in linear light, so shrinking one doesn't darken it.
 */
final class ColorMath {
	private ColorMath() {}

	private static final float[] TO_LINEAR = new float[256];

	static {
		for (int i = 0; i < 256; i++) {
			double c = i / 255.0;
			TO_LINEAR[i] = (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
		}
	}

	static float linear(int channel) {
		return TO_LINEAR[channel & 255];
	}

	/** Linear 0..1 to an sRGB channel 0..255. */
	static int srgb(double linear) {
		double c = linear <= 0 ? 0 : linear >= 1 ? 1 : linear <= 0.0031308 ? linear * 12.92 : 1.055 * Math.pow(linear, 1 / 2.4) - 0.055;
		return (int) Math.round(c * 255);
	}

	/** Linear RGB to OKLab, into {@code out} (L, a, b). */
	static void linearToLab(double r, double g, double b, float[] out, int at) {
		double l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
		double m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
		double s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
		out[at] = (float) (0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s);
		out[at + 1] = (float) (1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s);
		out[at + 2] = (float) (0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s);
	}

	static void rgbToLab(int rgb, float[] out, int at) {
		linearToLab(linear(rgb >> 16), linear(rgb >> 8), linear(rgb), out, at);
	}

	static float[] lab(int rgb) {
		float[] out = new float[3];
		rgbToLab(rgb, out, 0);
		return out;
	}

	/** OKLab back to sRGB (clamped). */
	static int labToRgb(double L, double a, double b) {
		double l = L + 0.3963377774 * a + 0.2158037573 * b;
		double m = L - 0.1055613458 * a - 0.0638541728 * b;
		double s = L - 0.0894841775 * a - 1.2914855480 * b;
		l = l * l * l;
		m = m * m * m;
		s = s * s * s;
		double r = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s;
		double g = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s;
		double bl = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s;
		return srgb(r) << 16 | srgb(g) << 8 | srgb(bl);
	}

	/** How different two colours look, in OKLab units ×100 (about 1 is just noticeable, 10 is clearly different). */
	static double difference(int rgb1, int rgb2) {
		float[] a = lab(rgb1), b = lab(rgb2);
		double dl = a[0] - b[0], da = a[1] - b[1], db = a[2] - b[2];
		return Math.sqrt(dl * dl + da * da + db * db) * 100;
	}
}
