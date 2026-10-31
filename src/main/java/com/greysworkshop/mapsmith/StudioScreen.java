package com.greysworkshop.mapsmith;

import com.greysworkshop.mapsmith.MapPalette.Shade;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleFunction;
import java.util.stream.Stream;

/**
 * The studio: pick a picture, see it as map art straight away, and tune it (size in maps, framing, colours, flat or
 * staircase, dithering, blocks), then place it on the map grid, build it, or save it as a schematic.
 */
final class StudioScreen extends Screen {
	private static final int PANEL = 168, ROW = 22;
	private final Screen parent;
	private final Preview preview = new Preview("studio");
	private int scroll;
	private int contentHeight;
	private boolean showPicture;
	private Text message = Text.empty();
	private int messageColor = 0xFFFFFFFF;
	/** Where the preview is drawn, and at what scale (screen pixels per block). */
	private int pvX, pvY, pvW, pvH;
	private double pvScale;
	private double dragStartX, dragStartY, panStartX, panStartY;
	private boolean dragging;
	private int bottomRows = 1;
	private long lastDragConvert;
	private String confirmDelete;

	StudioScreen(Screen parent) {
		super(Text.translatable("mapsmith.studio"));
		this.parent = parent;
	}

	private static Text t(String key, Object... args) {
		return Text.translatable("mapsmith.studio." + key, args);
	}

	@Override
	protected void init() {
		Art art = Mapsmith.art();
		if (art == null) initProjects();
		else initEditor(art);
	}

	// ---- No project open: the list ----

	private void initProjects() {
		int x = width / 2 - 150, y = 34;
		addDrawableChild(ButtonWidget.builder(t("open_picture"), b -> pickPicture()).dimensions(x, y, 148, 20)
				.tooltip(Tooltip.of(t("open_picture.tip"))).build());
		addDrawableChild(ButtonWidget.builder(t("pictures_folder"), b -> openFolder(Project.pictures())).dimensions(x + 152, y, 148, 20)
				.tooltip(Tooltip.of(t("pictures_folder.tip"))).build());
		y += 30;
		List<Project> projects = Project.all();
		for (Project p : projects) {
			if (y > height - 50) break;
			String folder = Project.folderName(p.name);
			Text label = Text.literal(p.name).append(Text.literal("  " + p.settings.mapsWide + "×" + p.settings.mapsTall + " · "
					+ (p.flat() ? "flat" : "staircase")).formatted(Formatting.GRAY));
			addDrawableChild(ButtonWidget.builder(label, b -> {
				try {
					Mapsmith.open(p);
					clearAndInit();
				} catch (IOException e) {
					say(t("cant_open", e.getMessage()), 0xFFFF5555);
				}
			}).dimensions(x, y, 276, 20).build());
			boolean confirming = folder.equals(confirmDelete);
			addDrawableChild(ButtonWidget.builder(Text.literal(confirming ? "✔" : "✖").formatted(confirming ? Formatting.RED : Formatting.GRAY), b -> {
				if (!confirming) {
					confirmDelete = folder;
					say(t("delete_confirm", p.name), 0xFFFFAA00);
				} else {
					try {
						p.delete();
						say(t("deleted", p.name), 0xFFAAAAAA);
					} catch (IOException e) {
						say(Text.literal(e.getMessage()), 0xFFFF5555);
					}
					confirmDelete = null;
				}
				clearAndInit();
			}).dimensions(x + 280, y, 20, 20).tooltip(Tooltip.of(t("delete.tip"))).build());
			y += ROW;
		}
		// Pictures put in the pictures folder by hand.
		for (Path pic : loosePictures()) {
			if (y > height - 50) break;
			addDrawableChild(ButtonWidget.builder(t("new_from", pic.getFileName().toString()), b -> newProject(pic)).dimensions(x, y, 300, 20).build());
			y += ROW;
		}
		addDrawableChild(ButtonWidget.builder(t("settings"), b -> client.setScreen(new SettingsScreen(this))).dimensions(width / 2 - 152, height - 26, 148, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), b -> close()).dimensions(width / 2 + 4, height - 26, 148, 20).build());
	}

	private static List<Path> loosePictures() {
		List<Path> out = new ArrayList<>();
		try {
			Files.createDirectories(Project.pictures());
			try (Stream<Path> files = Files.list(Project.pictures())) {
				files.filter(ImageLoader::looksLikePicture).sorted().forEach(out::add);
			}
		} catch (IOException ignored) {
			// Nothing to list.
		}
		return out;
	}

	private void pickPicture() {
		String picked;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			String[] patterns = {"*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.tga", "*.webp"};
			PointerBuffer filters = stack.mallocPointer(patterns.length);
			for (String p : patterns) filters.put(stack.UTF8(p));
			filters.flip();
			picked = TinyFileDialogs.tinyfd_openFileDialog("Pick a picture for map art", System.getProperty("user.home") + java.io.File.separator,
					filters, "Pictures", false);
		}
		if (picked != null) newProject(Path.of(picked));
	}

	@Override
	public void onFilesDropped(List<Path> paths) {
		for (Path p : paths) {
			if (ImageLoader.looksLikePicture(p)) {
				newProject(p);
				return;
			}
		}
		say(t("not_picture"), 0xFFFF5555);
	}

	private void newProject(Path picture) {
		try {
			ImageLoader.read(picture);
			Project p = Project.create(picture);
			Mapsmith.open(p);
			say(t("made", p.name), 0xFF55FF55);
		} catch (IOException | RuntimeException e) {
			say(t("cant_open", String.valueOf(e.getMessage())), 0xFFFF5555);
		}
		clearAndInit();
	}

	// ---- A project open: the editor ----

	private void initEditor(Art art) {
		Project p = art.project;
		ArtSettings s = p.settings;
		int x = 6, w = PANEL - 12;
		int y = 24 - scroll;
		int half = (w - 4) / 2;

		addDrawableChild(ButtonWidget.builder(t("maps_wide", s.mapsWide), b -> {
			s.mapsWide = s.mapsWide % 8 + 1;
			changed();
		}).dimensions(x, y, half, 20).tooltip(Tooltip.of(t("maps.tip"))).build());
		addDrawableChild(ButtonWidget.builder(t("maps_tall", s.mapsTall), b -> {
			s.mapsTall = s.mapsTall % 8 + 1;
			changed();
		}).dimensions(x + half + 4, y, half, 20).tooltip(Tooltip.of(t("maps.tip"))).build());
		y += ROW;
		addDrawableChild(ButtonWidget.builder(t("mode." + s.mode.name().toLowerCase()), b -> {
			s.mode = s.mode == ArtSettings.Mode.FLAT ? ArtSettings.Mode.STAIRCASE : ArtSettings.Mode.FLAT;
			changed();
		}).dimensions(x, y, w, 20).tooltip(Tooltip.of(t("mode." + s.mode.name().toLowerCase() + ".tip"))).build());
		y += ROW;
		if (s.mode == ArtSettings.Mode.STAIRCASE) {
			addDrawableChild(slider(x, y, w, s.maxHeight / 64.0, v -> s.maxHeight = (int) Math.round(v * 64) == 1 ? 2 : (int) Math.round(v * 64),
					v -> (int) Math.round(v * 64) == 0 ? t("max_height.off") : t("max_height", (int) Math.round(v * 64)), t("max_height.tip")));
			y += ROW;
		}
		addDrawableChild(ButtonWidget.builder(t("dither", s.dither.label), b -> {
			s.dither = Dither.values()[(s.dither.ordinal() + 1) % Dither.values().length];
			changed();
		}).dimensions(x, y, w, 20).tooltip(Tooltip.of(t("dither.tip"))).build());
		y += ROW;
		if (s.dither != Dither.NONE) {
			addDrawableChild(slider(x, y, w, s.ditherStrength, v -> s.ditherStrength = v, v -> t("strength", Math.round(v * 100)), t("strength.tip")));
			y += ROW;
		}
		addDrawableChild(ButtonWidget.builder(t("fit." + s.fit.name().toLowerCase()), b -> {
			s.fit = Img.Fit.values()[(s.fit.ordinal() + 1) % Img.Fit.values().length];
			changed();
		}).dimensions(x, y, half, 20).tooltip(Tooltip.of(t("fit.tip"))).build());
		addDrawableChild(ButtonWidget.builder(t("resample." + s.resample.name().toLowerCase()), b -> {
			s.resample = s.resample == Img.Resample.SMOOTH ? Img.Resample.SHARP : Img.Resample.SMOOTH;
			changed();
		}).dimensions(x + half + 4, y, half, 20).tooltip(Tooltip.of(t("resample.tip"))).build());
		y += ROW;
		addDrawableChild(slider(x, y, w, (s.zoom - 1) / 7, v -> s.zoom = 1 + v * 7, v -> t("zoom", String.format("%.1f", 1 + v * 7)), t("zoom.tip")));
		y += ROW;
		addDrawableChild(slider(x, y, w, (s.brightness + 1) / 2, v -> s.brightness = v * 2 - 1, v -> t("brightness", Math.round((v * 2 - 1) * 100)), null));
		y += ROW;
		addDrawableChild(slider(x, y, w, (s.contrast + 1) / 2, v -> s.contrast = v * 2 - 1, v -> t("contrast", Math.round((v * 2 - 1) * 100)), null));
		y += ROW;
		addDrawableChild(slider(x, y, w, s.saturation / 2, v -> s.saturation = v * 2, v -> t("saturation", Math.round(v * 200)), null));
		y += ROW;
		addDrawableChild(ButtonWidget.builder(t("blocks"), b -> client.setScreen(new PaletteScreen(this, art))).dimensions(x, y, w, 20)
				.tooltip(Tooltip.of(t("blocks.tip"))).build());
		y += ROW;
		addDrawableChild(ButtonWidget.builder(t("materials"), b -> client.setScreen(new MaterialsScreen(this, art))).dimensions(x, y, w, 20).build());
		y += ROW;
		addDrawableChild(ButtonWidget.builder(t("settings"), b -> client.setScreen(new SettingsScreen(this))).dimensions(x, y, w, 20).build());
		y += ROW;
		addDrawableChild(ButtonWidget.builder(t("reset"), b -> {
			ArtSettings fresh = new ArtSettings();
			fresh.mapsWide = s.mapsWide;
			fresh.mapsTall = s.mapsTall;
			p.settings = fresh;
			changed();
		}).dimensions(x, y, w, 20).tooltip(Tooltip.of(t("reset.tip"))).build());
		y += ROW;
		contentHeight = y + scroll - 24;

		// Along the bottom: one row of six buttons, or two of three when narrow.
		int avail = width - PANEL - 12;
		int perRow = avail >= 6 * 74 ? 6 : 3;
		bottomRows = 6 / perRow;
		int bw = (avail - (perRow - 1) * 4) / perRow;
		int[] slot = {0};
		java.util.function.IntUnaryOperator bxOf = i -> PANEL + 6 + (i % perRow) * (bw + 4);
		java.util.function.IntUnaryOperator byOf = i -> height - 24 * (bottomRows - i / perRow);
		int by = byOf.applyAsInt(0);
		int bx = bxOf.applyAsInt(0);
		addDrawableChild(ButtonWidget.builder(t(showPicture ? "show_map" : "show_picture"), b -> {
			showPicture = !showPicture;
			clearAndInit();
		}).dimensions(bx, by, bw, 20).tooltip(Tooltip.of(t("show.tip"))).build());
		bx = bxOf.applyAsInt(++slot[0]);
		by = byOf.applyAsInt(slot[0]);
		addDrawableChild(ButtonWidget.builder(t("place"), b -> place()).dimensions(bx, by, bw, 20).tooltip(Tooltip.of(t("place.tip"))).build());
		bx = bxOf.applyAsInt(++slot[0]);
		by = byOf.applyAsInt(slot[0]);
		boolean placedHere = Mapsmith.plan() != null;
		ButtonWidget build = ButtonWidget.builder(t(Builder.running() ? "stop" : "build"), b -> {
			if (Builder.running()) Builder.stop(client, Builder.State.PAUSED, Text.translatable("mapsmith.builder.paused"));
			else {
				close();
				Builder.start(client);
			}
		}).dimensions(bx, by, bw, 20).tooltip(Tooltip.of(t("build.tip"))).build();
		build.active = placedHere;
		addDrawableChild(build);
		bx = bxOf.applyAsInt(++slot[0]);
		by = byOf.applyAsInt(slot[0]);
		addDrawableChild(ButtonWidget.builder(t("export"), b -> export()).dimensions(bx, by, bw, 20).tooltip(Tooltip.of(t("export.tip"))).build());
		bx = bxOf.applyAsInt(++slot[0]);
		by = byOf.applyAsInt(slot[0]);
		addDrawableChild(ButtonWidget.builder(t("projects"), b -> {
			preview.close();
			Builder.stop(client, Builder.State.OFF, Text.empty());
			Mapsmith.close();
			scroll = 0;
			clearAndInit();
		}).dimensions(bx, by, bw, 20).tooltip(Tooltip.of(t("projects.tip"))).build());
		bx = bxOf.applyAsInt(++slot[0]);
		by = byOf.applyAsInt(slot[0]);
		addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), b -> close()).dimensions(bx, by, bw, 20).build());
	}

	private SliderWidget slider(int x, int y, int w, double value, java.util.function.DoubleConsumer apply, DoubleFunction<Text> label, Text tip) {
		SliderWidget s = new SliderWidget(x, y, w, 20, label.apply(value), value) {
			@Override
			protected void updateMessage() {
				setMessage(label.apply(this.value));
			}

			@Override
			protected void applyValue() {
				apply.accept(this.value);
				convertSoon();
			}

			@Override
			public void onRelease(Click click) {
				super.onRelease(click);
				changed(false);
			}
		};
		if (tip != null) s.setTooltip(Tooltip.of(tip));
		return s;
	}

	private long convertAt;

	/** Converts again shortly, so dragging a slider doesn't start a conversion every frame. */
	private void convertSoon() {
		convertAt = System.currentTimeMillis() + 120;
	}

	private void changed() {
		changed(true);
	}

	private void changed(boolean rebuild) {
		Art art = Mapsmith.art();
		if (art == null) return;
		art.project.settings.sanitize();
		art.project.save();
		art.convert(null);
		convertAt = 0;
		if (rebuild) clearAndInit();
	}

	private void place() {
		if (client.player == null) {
			say(t("place.in_world"), 0xFFFF5555);
			return;
		}
		Mapsmith.placeHere(client, 0, 0);
		Project.Placement pl = Mapsmith.art().project.placement;
		say(t("placed", pl.x, pl.y, pl.z), 0xFF55FF55);
		clearAndInit();
	}

	private void export() {
		Art art = Mapsmith.art();
		Art.Done d = art.done();
		if (d == null) return;
		try {
			Plan plan = Mapsmith.plan();
			if (plan == null) plan = new Plan(d.result(), d.layout(), d.blocks(), d.filler(), 0, 0, 0);
			Path dir = client.runDirectory.toPath().resolve("schematics");
			Schematics.litematic(plan, art.project.name, dir);
			Schematics.sponge(plan, art.project.name, dir);
			Schematics.png(d.result(), art.project.dir().resolve("map preview.png"));
			say(t("exported"), 0xFF55FF55);
			openFolder(dir);
		} catch (IOException e) {
			say(Text.literal(e.getMessage()), 0xFFFF5555);
		}
	}

	private static void openFolder(Path dir) {
		try {
			Files.createDirectories(dir);
		} catch (IOException ignored) {
			// Opening it will say.
		}
		Util.getOperatingSystem().open(dir.toFile());
	}

	private void say(Text text, int color) {
		message = text;
		messageColor = color;
	}

	@Override
	public void tick() {
		if (convertAt != 0 && System.currentTimeMillis() >= convertAt) {
			convertAt = 0;
			Art art = Mapsmith.art();
			if (art != null) {
				art.project.settings.sanitize();
				art.convert(null);
			}
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		Art art = Mapsmith.art();
		if (art == null) {
			context.drawCenteredTextWithShadow(textRenderer, Text.translatable("mapsmith.studio"), width / 2, 10, 0xFFFFD040);
			context.drawCenteredTextWithShadow(textRenderer, t("drop_hint"), width / 2, 22, 0xFFAAAAAA);
			if (Project.all().isEmpty() && loosePictures().isEmpty()) {
				context.drawCenteredTextWithShadow(textRenderer, t("empty"), width / 2, 74, 0xFFCCCCCC);
			}
			context.drawCenteredTextWithShadow(textRenderer, message, width / 2, height - 40, messageColor);
			return;
		}
		context.drawTextWithShadow(textRenderer, Text.literal(art.project.name).formatted(Formatting.GOLD), 6, 8, 0xFFFFFFFF);
		renderPreview(context, art, mouseX, mouseY);
		context.drawTextWithShadow(textRenderer, message, PANEL + 6, height - 24 * bottomRows - 12, messageColor);
	}

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
		super.renderBackground(context, mouseX, mouseY, delta);
		if (Mapsmith.art() != null) context.fill(0, 0, PANEL, height, 0x80000000);
	}

	private void renderPreview(DrawContext context, Art art, int mouseX, int mouseY) {
		Art.Done d = art.done();
		int bottom = height - 24 * bottomRows - 26;
		int areaX = PANEL + 6, areaY = 20, areaW = width - PANEL - 12, areaH = bottom - 2 - areaY;
		String status;
		if (art.error() != null) status = "§c" + art.error();
		else if (art.working()) status = "§7" + Text.translatable("mapsmith.studio.working").getString();
		else status = "";
		if (d == null) {
			context.drawCenteredTextWithShadow(textRenderer, Text.literal(status.isEmpty() ? "…" : status), areaX + areaW / 2, areaY + areaH / 2, 0xFFFFFFFF);
			return;
		}
		Converter.Result r = d.result();
		double scale = Math.min(areaW / (double) r.width, areaH / (double) r.height);
		// Whole screen pixels per block when big enough, so the pixels stay square and sharp.
		if (scale >= 1) scale = Math.floor(scale);
		int w = (int) (r.width * scale), h = (int) (r.height * scale);
		pvX = areaX + (areaW - w) / 2;
		pvY = areaY + (areaH - h) / 2;
		pvW = w;
		pvH = h;
		pvScale = scale;
		boolean picture = showPicture || (client != null && GLFW.glfwGetKey(client.getWindow().getHandle(), GLFW.GLFW_KEY_SPACE) == GLFW.GLFW_PRESS);
		Identifier tex = preview.texture(r, picture);
		context.fill(pvX - 1, pvY - 1, pvX + w + 1, pvY + h + 1, 0xFF000000);
		var m = context.getMatrices();
		m.pushMatrix();
		m.translate(pvX, pvY);
		m.scale((float) scale, (float) scale);
		context.drawTexture(RenderPipelines.GUI_TEXTURED, tex, 0, 0, 0, 0, r.width, r.height, r.width, r.height);
		m.popMatrix();
		// The map borders.
		for (int i = 1; i < r.width / 128; i++) context.fill(pvX + (int) (i * 128 * scale), pvY, pvX + (int) (i * 128 * scale) + 1, pvY + h, 0x80FFC030);
		for (int i = 1; i < r.height / 128; i++) context.fill(pvX, pvY + (int) (i * 128 * scale), pvX + w, pvY + (int) (i * 128 * scale) + 1, 0x80FFC030);

		Layout layout = d.layout();
		long blocks = (long) r.width * r.height;
		String info = Text.translatable("mapsmith.studio.stats", r.colorsUsed, String.format("%.1f", r.meanDifference),
				layout.maxLevel + 1, String.format("%,d", blocks + r.width + layout.supportCount())).getString();
		int statusW = status.isEmpty() ? 0 : textRenderer.getWidth(status) + 6;
		context.drawTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(info, areaW - statusW)), areaX, 8, 0xFFCCCCCC);
		if (!status.isEmpty()) context.drawTextWithShadow(textRenderer, Text.literal(status), areaX + areaW - textRenderer.getWidth(status), 8, 0xFFFFFFFF);
		Plan plan = Mapsmith.plan();
		Project.Placement pl = art.project.placement;
		long total = blocks + r.width + layout.supportCount();
		boolean creative = client.player != null && client.player.getAbilities().creativeMode;
		String eta = Text.translatable("mapsmith.studio.eta", minutes(total, creative ? MapsmithConfig.INSTANCE.creativeSpeed : MapsmithConfig.INSTANCE.speed)).getString();
		String where = (pl == null ? Text.translatable("mapsmith.studio.not_placed").getString()
				: Text.translatable(plan != null ? "mapsmith.studio.placed_at" : "mapsmith.studio.placed_elsewhere", pl.x, pl.y, pl.z).getString()) + " · " + eta;
		context.drawTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(where, areaW)), PANEL + 6, bottom + 2, 0xFFAAAAAA);

		if (mouseX >= pvX && mouseY >= pvY && mouseX < pvX + w && mouseY < pvY + h) {
			int px = (int) ((mouseX - pvX) / scale), py = (int) ((mouseY - pvY) / scale);
			px = Math.min(r.width - 1, px);
			py = Math.min(r.height - 1, py);
			int id = r.colorAt(px, py);
			Shade shade = r.shadeAt(px, py);
			Block b = d.blocks()[id];
			List<Text> tip = new ArrayList<>();
			tip.add(b == null ? Text.literal("?") : b.getName().copy().formatted(Formatting.WHITE));
			tip.add(Text.literal(MapPalette.displayName(id) + " · " + Text.translatable("mapsmith.shade." + shade.name().toLowerCase()).getString()).formatted(Formatting.GRAY));
			tip.add(Text.translatable("mapsmith.studio.pixel", px, py, px / 128 + 1, py / 128 + 1, layout.level[px][py + 1]).formatted(Formatting.DARK_GRAY));
			if (pl != null) tip.add(Text.translatable("mapsmith.studio.pixel_world", pl.x + px, pl.y + layout.level[px][py + 1], pl.z + py).formatted(Formatting.DARK_GRAY));
			context.drawTooltip(textRenderer, tip, mouseX, mouseY);
		}
	}

	/** About how long placing {@code blocks} takes at {@code perSecond}: "45 min", "3 h 10 min". */
	private static String minutes(long blocks, int perSecond) {
		long m = Math.max(1, Math.round(blocks / (double) Math.max(1, perSecond) / 60 * 1.15));
		return m < 60 ? m + " min" : m / 60 + " h " + m % 60 + " min";
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (super.mouseClicked(click, doubled)) return true;
		if (Mapsmith.art() != null && click.x() >= pvX && click.y() >= pvY && click.x() < pvX + pvW && click.y() < pvY + pvH) {
			dragging = true;
			dragStartX = click.x();
			dragStartY = click.y();
			panStartX = Mapsmith.art().project.settings.panX;
			panStartY = Mapsmith.art().project.settings.panY;
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(Click click, double dx, double dy) {
		Art art = Mapsmith.art();
		if (dragging && art != null && pvW > 0) {
			// Dragging moves the picture under the frame: right shows more of the left.
			ArtSettings s = art.project.settings;
			s.panX = Math.max(-1, Math.min(1, panStartX - (click.x() - dragStartX) / pvW * 2 * s.zoom));
			s.panY = Math.max(-1, Math.min(1, panStartY - (click.y() - dragStartY) / pvH * 2 * s.zoom));
			if (System.currentTimeMillis() - lastDragConvert > 150) {
				lastDragConvert = System.currentTimeMillis();
				art.convert(null);
			}
			return true;
		}
		return super.mouseDragged(click, dx, dy);
	}

	@Override
	public boolean mouseReleased(Click click) {
		if (dragging) {
			dragging = false;
			changed(false);
			return true;
		}
		return super.mouseReleased(click);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		Art art = Mapsmith.art();
		if (art != null && mouseX < PANEL) {
			int max = Math.max(0, contentHeight - (height - 30));
			int was = scroll;
			scroll = (int) Math.max(0, Math.min(max, scroll - vertical * 20));
			if (scroll != was) clearAndInit();
			return true;
		}
		if (art != null && mouseX >= pvX && mouseY >= pvY && mouseX < pvX + pvW && mouseY < pvY + pvH) {
			ArtSettings s = art.project.settings;
			s.zoom = Math.max(1, Math.min(8, s.zoom * (vertical > 0 ? 1.15 : 1 / 1.15)));
			convertSoon();
			art.project.save();
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		if (Mapsmith.studioKey.matchesKey(input) && getFocused() == null) {
			close();
			return true;
		}
		return super.keyPressed(input);
	}

	@Override
	public void close() {
		Art art = Mapsmith.art();
		if (art != null) art.project.save();
		preview.close();
		client.setScreen(parent);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	/** Test hooks. */
	void testPlace() {
		place();
	}

	void testExport() {
		export();
	}

	Text testMessage() {
		return message;
	}
}
