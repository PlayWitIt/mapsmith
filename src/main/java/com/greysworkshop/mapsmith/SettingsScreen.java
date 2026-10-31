package com.greysworkshop.mapsmith;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** The builder's and the display's settings. */
final class SettingsScreen extends Screen {
	private final Screen parent;

	SettingsScreen(Screen parent) {
		super(Text.translatable("mapsmith.settings"));
		this.parent = parent;
	}

	private static Text t(String key, Object... args) {
		return Text.translatable("mapsmith.settings." + key, args);
	}

	@Override
	protected void init() {
		MapsmithConfig c = MapsmithConfig.INSTANCE;
		int w = 150, x1 = width / 2 - w - 2, x2 = width / 2 + 2, y = 30;
		addDrawableChild(ButtonWidget.builder(t("speed", c.speed), b -> {
			c.speed = c.speed >= 20 ? 1 : c.speed < 4 ? c.speed + 1 : c.speed + 2;
			changed();
		}).dimensions(x1, y, w, 20).tooltip(Tooltip.of(t("speed.tip"))).build());
		addDrawableChild(ButtonWidget.builder(t("creative_speed", c.creativeSpeed), b -> {
			c.creativeSpeed = c.creativeSpeed >= 40 ? 5 : c.creativeSpeed + 5;
			changed();
		}).dimensions(x2, y, w, 20).tooltip(Tooltip.of(t("creative_speed.tip"))).build());
		y += 24;
		toggle(x1, y, w, "rotate", () -> c.rotate, v -> c.rotate = v);
		toggle(x2, y, w, "fix", () -> c.fixMistakes, v -> c.fixMistakes = v);
		y += 24;
		toggle(x1, y, w, "walk", () -> c.autoWalk, v -> c.autoWalk = v);
		toggle(x2, y, w, "fly", () -> c.fly, v -> c.fly = v);
		y += 24;
		toggle(x1, y, w, "ghosts", () -> c.ghosts, v -> c.ghosts = v);
		addDrawableChild(ButtonWidget.builder(t("range", c.ghostRange), b -> {
			c.ghostRange = c.ghostRange >= 32 ? 4 : c.ghostRange + 4;
			changed();
		}).dimensions(x2, y, w, 20).tooltip(Tooltip.of(t("range.tip"))).build());
		y += 24;
		toggle(x1, y, w, "grid", () -> c.grid, v -> c.grid = v);
		toggle(x2, y, w, "hud", () -> c.hud, v -> c.hud = v);
		addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, b -> close()).dimensions(width / 2 - 75, height - 28, 150, 20).build());
	}

	private void toggle(int x, int y, int w, String key, Supplier<Boolean> get, Consumer<Boolean> set) {
		Text label = t(key).copy().append(": ").append(get.get() ? ScreenTexts.ON : ScreenTexts.OFF);
		addDrawableChild(ButtonWidget.builder(label, b -> {
			set.accept(!get.get());
			changed();
		}).dimensions(x, y, w, 20).tooltip(Tooltip.of(t(key + ".tip"))).build());
	}

	private void changed() {
		MapsmithConfig.save();
		clearAndInit();
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, 0xFFFFD040);
	}

	@Override
	public void close() {
		client.setScreen(parent);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
