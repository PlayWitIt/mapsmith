package com.greysworkshop.mapsmith;

import com.greysworkshop.mapsmith.MapPalette.Shade;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The colours and the block for each: click a colour for the next block of that colour, shift-click for the one
 * before, right-click to leave the colour out.
 */
final class PaletteScreen extends Screen {
	private static final int CELL_W = 150, CELL_H = 24;
	private static final Block[] FILLERS = {Blocks.COBBLESTONE, Blocks.NETHERRACK, Blocks.STONE, Blocks.DIRT, Blocks.COBBLED_DEEPSLATE,
			Blocks.ANDESITE, Blocks.OAK_PLANKS, Blocks.END_STONE, Blocks.BLACKSTONE};
	private final Screen parent;
	private final Art art;
	private int scroll;
	private int columns, top;
	private List<Integer> ids = new ArrayList<>();
	private Map<Item, Long> carried = Map.of();

	PaletteScreen(Screen parent, Art art) {
		super(Text.translatable("mapsmith.palette"));
		this.parent = parent;
		this.art = art;
	}

	private static Text t(String key, Object... args) {
		return Text.translatable("mapsmith.palette." + key, args);
	}

	@Override
	protected void init() {
		Project p = art.project;
		if (client.player != null) carried = Materials.carried(client.player);
		ids = new ArrayList<>();
		for (int id = 1; id < MapPalette.COLORS; id++) if (id != MapPalette.WATER && !BlockPalette.candidates(id).isEmpty()) ids.add(id);
		// Five buttons in a row, or as many rows as it takes on a narrow screen.
		int gap = 4, perRow = Math.max(1, Math.min(5, (width - 8) / 120)), bw = Math.min(116, (width - 8 - gap * (perRow - 1)) / perRow);
		int left = (width - (bw * perRow + gap * (perRow - 1))) / 2;
		List<ButtonWidget> buttons = new ArrayList<>();
		buttons.add(ButtonWidget.builder(t("defaults"), b -> {
			p.blocks.clear();
			changed();
		}).tooltip(Tooltip.of(t("defaults.tip"))).build());
		buttons.add(ButtonWidget.builder(t("carried"), b -> useCarried()).tooltip(Tooltip.of(t("carried.tip"))).build());
		buttons.add(ButtonWidget.builder(t("gatherable"), b -> useGatherable()).tooltip(Tooltip.of(t("gatherable.tip"))).build());
		buttons.add(ButtonWidget.builder(t("no_falling"), b -> {
			for (int id : ids) {
				Block cur = p.block(id);
				if (cur != null && BlockPalette.needsBelow(cur)) {
					Block other = null;
					for (Block c : BlockPalette.candidates(id)) {
						if (!BlockPalette.needsBelow(c)) {
							other = c;
							break;
						}
					}
					p.blocks.put(id, other == null ? "" : BlockPalette.id(other));
				}
			}
			changed();
		}).tooltip(Tooltip.of(t("no_falling.tip"))).build());
		buttons.add(ButtonWidget.builder(t(p.carpets ? "carpets.on" : "carpets.off"), b -> {
			p.carpets = !p.carpets;
			changed();
		}).tooltip(Tooltip.of(t("carpets.tip"))).build());
		buttons.add(ButtonWidget.builder(t("filler", p.fillerBlock().getName()), b -> {
			int at = 0;
			for (int i = 0; i < FILLERS.length; i++) if (FILLERS[i] == p.fillerBlock()) at = i;
			p.filler = BlockPalette.id(FILLERS[(at + 1) % FILLERS.length]);
			changed();
		}).tooltip(Tooltip.of(t("filler.tip"))).build());
		for (int i = 0; i < buttons.size(); i++) {
			ButtonWidget b = buttons.get(i);
			b.setDimensionsAndPosition(bw, 20, left + (i % perRow) * (bw + gap), 22 + (i / perRow) * 22);
			addDrawableChild(b);
		}
		int rows = (buttons.size() + perRow - 1) / perRow;
		addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), b -> close()).dimensions(width / 2 - 75, height - 26, 150, 20).build());
		columns = Math.max(1, (width - 20) / (CELL_W + 4));
		top = 28 + rows * 22;
	}

	/** Each colour gets a block of it the player is carrying (the most of), or is left out. */
	private void useCarried() {
		Project p = art.project;
		for (int id : ids) {
			Block best = null;
			long most = 0;
			for (Block c : BlockPalette.candidates(id)) {
				if (c instanceof net.minecraft.block.CarpetBlock && !p.flat()) continue;
				long n = Materials.have(carried, c);
				if (n > most) {
					most = n;
					best = c;
				}
			}
			p.blocks.put(id, best == null ? "" : BlockPalette.id(best));
		}
		changed();
	}

	/**
	 * Each colour gets a block the builder can get by itself: one already carried, else one it can mine nearby (with
	 * Silk Touch, if a Silk Touch tool is carried); colours with neither are left out.
	 */
	private void useGatherable() {
		Plan plan = Mapsmith.plan();
		BlockPos at = plan != null ? new BlockPos(plan.x0 + plan.width() / 2, plan.y0, plan.z0 + plan.height() / 2)
				: client.player != null ? client.player.getBlockPos() : BlockPos.ORIGIN;
		gatherable(art.project, carried, client.player != null && Gather.hasSilk(client.player.getInventory()),
				client.world == null ? Map.of() : Gather.available(client.world, at));
		changed();
	}

	/** The palette for {@code p} that the builder can get by itself (see the button's tooltip). */
	static void gatherable(Project p, Map<net.minecraft.item.Item, Long> carried, boolean silk, Map<Block, Integer> available) {
		for (int id = 1; id < MapPalette.COLORS; id++) {
			if (id == MapPalette.WATER || BlockPalette.candidates(id).isEmpty()) continue;
			Block best = null;
			long most = 0;
			for (Block c : BlockPalette.candidates(id)) {
				if (c instanceof net.minecraft.block.CarpetBlock && !p.flat()) continue;
				long n = Materials.have(carried, c);
				if (n > most) {
					most = n;
					best = c;
				}
			}
			if (best == null) {
				// Candidates come best first: the first that can be mined and doesn't fall.
				for (Block c : BlockPalette.candidates(id)) {
					if (!Gather.gatherableHere(c, silk, available)) continue;
					if (best == null || BlockPalette.needsBelow(best) && !BlockPalette.needsBelow(c)) best = c;
					if (!BlockPalette.needsBelow(best)) break;
				}
			}
			p.blocks.put(id, best == null ? "" : BlockPalette.id(best));
		}
	}

	private void changed() {
		art.project.save();
		art.convert(null);
		clearAndInit();
	}

	private int cellX(int i) {
		int total = columns * (CELL_W + 4) - 4;
		return (width - total) / 2 + (i % columns) * (CELL_W + 4);
	}

	private int cellY(int i) {
		return top + (i / columns) * (CELL_H + 2) - scroll;
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		Project p = art.project;
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 8, 0xFFFFD040);
		int bottom = height - 32;
		context.enableScissor(0, top - 2, width, bottom);
		int hovered = -1;
		for (int i = 0; i < ids.size(); i++) {
			int x = cellX(i), y = cellY(i);
			if (y + CELL_H < top || y > bottom) continue;
			int id = ids.get(i);
			Block b = p.block(id);
			boolean on = b != null;
			boolean hover = mouseX >= x && mouseX < x + CELL_W && mouseY >= y && mouseY < y + CELL_H && mouseY >= top && mouseY < bottom;
			if (hover) hovered = id;
			context.fill(x, y, x + CELL_W, y + CELL_H, hover ? 0xA0404040 : 0x80000000);
			// The shades this colour can be built in.
			Shade[] shades = p.flat() ? new Shade[] {Shade.NORMAL} : Shade.BUILDABLE;
			for (int s = 0; s < shades.length; s++) {
				context.fill(x + 2 + s * 7, y + 2, x + 9 + s * 7, y + CELL_H - 2, 0xFF000000 | MapPalette.rgb(id, shades[s]));
			}
			int textX = x + 24;
			if (on) {
				context.drawItem(new ItemStack(b.asItem()), textX, y + 4);
				textX += 18;
				long have = Materials.have(carried, b);
				String name = textRenderer.trimToWidth(b.getName().getString(), CELL_W - (textX - x) - 2);
				context.drawTextWithShadow(textRenderer, Text.literal(name), textX, y + 3, 0xFFFFFFFF);
				context.drawTextWithShadow(textRenderer, Text.literal(have > 0 ? "×" + have : "").formatted(Formatting.GREEN), textX, y + 13, 0xFFFFFFFF);
				if (BlockPalette.needsBelow(b)) context.drawTextWithShadow(textRenderer, Text.literal("↓").formatted(Formatting.YELLOW), x + CELL_W - 8, y + 13, 0xFFFFFFFF);
			} else {
				context.drawTextWithShadow(textRenderer, t("off").copy().formatted(Formatting.DARK_GRAY), textX, y + 8, 0xFFFFFFFF);
				context.fill(x, y + CELL_H / 2, x + CELL_W, y + CELL_H / 2 + 1, 0x80FF4040);
			}
		}
		context.disableScissor();
		if (hovered >= 0) {
			List<Text> tip = new ArrayList<>();
			tip.add(Text.literal(MapPalette.displayName(hovered)).formatted(Formatting.GOLD));
			Block b = p.block(hovered);
			int n = BlockPalette.candidates(hovered).size();
			tip.add(t("cell.tip", n).copy().formatted(Formatting.GRAY));
			if (b != null && BlockPalette.needsBelow(b)) tip.add(t("needs_below").copy().formatted(Formatting.YELLOW));
			context.drawTooltip(textRenderer, tip, mouseX, mouseY);
		}
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (super.mouseClicked(click, doubled)) return true;
		if (click.y() < top || click.y() >= height - 32) return false;
		for (int i = 0; i < ids.size(); i++) {
			int x = cellX(i), y = cellY(i);
			if (click.x() >= x && click.x() < x + CELL_W && click.y() >= y && click.y() < y + CELL_H) {
				cycle(ids.get(i), click.button() == 1 ? 0 : click.hasShift() ? -1 : 1);
				return true;
			}
		}
		return false;
	}

	/** Steps a colour's block on (+1) or back (-1) through its blocks, or (0) turns the colour off or back on. */
	void cycle(int id, int step) {
		Project p = art.project;
		Block cur = p.block(id);
		if (step == 0) {
			if (cur == null) p.blocks.remove(id);
			else p.blocks.put(id, "");
		} else {
			List<Block> list = new ArrayList<>(BlockPalette.candidates(id));
			if (!p.flat()) list.removeIf(b -> b instanceof net.minecraft.block.CarpetBlock);
			if (list.isEmpty()) return;
			int at = cur == null ? -1 : list.indexOf(cur);
			int next = Math.floorMod(at + step, list.size());
			p.blocks.put(id, BlockPalette.id(list.get(next)));
		}
		art.project.save();
		art.convert(null);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		int rows = (ids.size() + columns - 1) / columns;
		int max = Math.max(0, rows * (CELL_H + 2) - (height - 32 - top));
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
