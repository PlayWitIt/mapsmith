package com.greysworkshop.mapsmith;

import net.minecraft.block.Block;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Everything the art takes, most first, against what you're carrying (shulker boxes included). */
final class MaterialsScreen extends Screen {
	private final Screen parent;
	private final Art art;
	private int scroll;
	private List<Map.Entry<Block, Long>> rows = List.of();
	private Map<Item, Long> carried = Map.of();

	MaterialsScreen(Screen parent, Art art) {
		super(Text.translatable("mapsmith.materials"));
		this.parent = parent;
		this.art = art;
	}

	@Override
	protected void init() {
		Art.Done d = art.done();
		if (d != null) {
			Plan plan = new Plan(d.result(), d.layout(), d.blocks(), d.filler(), 0, 0, 0);
			rows = new ArrayList<>(plan.materials().entrySet());
			rows.sort(Map.Entry.<Block, Long>comparingByValue().reversed());
		}
		if (client.player != null) carried = Materials.carried(client.player);
		addDrawableChild(ButtonWidget.builder(Text.translatable("mapsmith.materials.copy"), b -> {
			StringBuilder s = new StringBuilder(art.project.name + "\n");
			for (Map.Entry<Block, Long> e : rows) s.append(e.getKey().getName().getString()).append(": ").append(Materials.amount(e.getValue())).append('\n');
			client.keyboard.setClipboard(s.toString());
		}).dimensions(width / 2 - 154, height - 26, 150, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), b -> close()).dimensions(width / 2 + 4, height - 26, 150, 20).build());
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 8, 0xFFFFD040);
		long total = 0;
		for (Map.Entry<Block, Long> e : rows) total += e.getValue();
		context.drawCenteredTextWithShadow(textRenderer, Text.translatable("mapsmith.materials.total", Materials.amount(total)), width / 2, 20, 0xFFAAAAAA);
		int x = width / 2 - 170, top = 36, bottom = height - 32;
		context.enableScissor(0, top, width, bottom);
		int y = top - scroll;
		for (Map.Entry<Block, Long> e : rows) {
			if (y + 18 >= top && y <= bottom) {
				context.drawItem(new ItemStack(e.getKey().asItem()), x, y);
				context.drawTextWithShadow(textRenderer, e.getKey().getName(), x + 20, y + 4, 0xFFFFFFFF);
				context.drawTextWithShadow(textRenderer, Text.literal(Materials.amount(e.getValue())), x + 150, y + 4, 0xFFCCCCCC);
				long have = Materials.have(carried, e.getKey());
				Text h = have >= e.getValue() ? Text.literal("✔").formatted(Formatting.GREEN)
						: Text.translatable("mapsmith.materials.have", have).formatted(have > 0 ? Formatting.YELLOW : Formatting.DARK_GRAY);
				context.drawTextWithShadow(textRenderer, h, x + 300, y + 4, 0xFFFFFFFF);
			}
			y += 20;
		}
		context.disableScissor();
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		int max = Math.max(0, rows.size() * 20 - (height - 68));
		scroll = (int) Math.max(0, Math.min(max, scroll - vertical * 20));
		return true;
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
