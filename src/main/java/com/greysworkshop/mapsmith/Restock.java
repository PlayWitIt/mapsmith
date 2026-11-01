package com.greysworkshop.mapsmith;

import net.minecraft.block.BarrelBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Survival restocking: when the builder runs out of blocks it gets more, hands free. From shulker boxes it carries
 * (it puts one down, takes what it needs, breaks it and picks it back up), then chests, barrels, shulker boxes and an
 * ender chest near the art. It remembers what each container holds once it has looked, and makes room by putting
 * away blocks the art doesn't need any more.
 */
final class Restock {
	private Restock() {}

	enum Step { IDLE, PICK, PLACE_BOX, GO, OPEN, TRANSFER, BREAK_BOX, PICK_UP }

	static Step step = Step.IDLE;
	/** Why the last restock got nothing, for the pause message; null if it got something. */
	static Text failure;

	private static final Walker walker = new Walker();
	/** What each container is known to hold. The ender chest's contents are under {@link #ENDER}. */
	private static final Map<BlockPos, Map<Item, Integer>> known = new HashMap<>();
	private static final BlockPos ENDER = new BlockPos(0, Integer.MIN_VALUE / 2, 0);
	/** Containers that couldn't be reached or opened this time. */
	private static final Set<BlockPos> skip = new HashSet<>();
	private static Map<Item, Integer> want = new HashMap<>();
	private static Set<Item> neededAtAll = new HashSet<>();
	private static Set<Block> missingAtStart = new HashSet<>();
	private static boolean gotAny;
	private static BlockPos target, boxAt;
	private static int boxSlot = -1, ticks, tries, clicks;
	private static Item boxItem;

	static boolean active() {
		return step != Step.IDLE;
	}

	static void forget() {
		known.clear();
		skip.clear();
		step = Step.IDLE;
	}

	/** True if a carried shulker box holds something the builder is out of: worth fetching right away. */
	static boolean carriedHas(ClientPlayerEntity player, Set<Block> missing) {
		Set<Item> items = new HashSet<>();
		for (Block b : missing) items.add(b.asItem());
		return carriedBox(player.getInventory(), items) >= 0;
	}

	/** Starts restocking for the blocks the builder is out of. False if there's nowhere left to look. */
	static boolean begin(MinecraftClient client, Plan plan, Set<Block> missing) {
		ClientPlayerEntity player = client.player;
		missingAtStart = new HashSet<>(missing);
		gotAny = false;
		failure = null;
		skip.clear();
		recount(client, plan);
		if (want.isEmpty()) return false;
		step = Step.PICK;
		Builder.debug("restock: want {}", want);
		return pick(client, player, plan);
	}

	/** What's still wanted: the rest of the art's need for each block, less what's carried. */
	private static void recount(MinecraftClient client, Plan plan) {
		Map<Block, Integer> need = Builder.remaining(plan, client.world);
		PlayerInventory inv = client.player.getInventory();
		want = new HashMap<>();
		neededAtAll = new HashSet<>();
		for (Map.Entry<Block, Integer> e : need.entrySet()) {
			Item item = e.getKey().asItem();
			neededAtAll.add(item);
			int have = count(inv, item);
			// Spare filler, to pillar up with.
			int wanted = e.getValue() + (e.getKey() == plan.filler ? 64 : 0) - have;
			if (wanted > 0) want.put(item, wanted);
		}
		neededAtAll.add(plan.filler.asItem());
	}

	static void tick(MinecraftClient client, Plan plan) {
		ClientPlayerEntity player = client.player;
		ClientWorld world = client.world;
		ticks++;
		switch (step) {
			case PICK -> pick(client, player, plan);
			case PLACE_BOX -> placeBox(client, player, world, plan);
			case GO -> go(client, player, world);
			case OPEN -> open(client, player, world);
			case TRANSFER -> transfer(client, player, plan);
			case BREAK_BOX -> breakBox(client, player, world);
			case PICK_UP -> pickUp(client, player, world);
			default -> {}
		}
	}

	private static void to(Step s) {
		step = s;
		ticks = 0;
		tries = 0;
		clicks = 0;
	}

	private static void finish(MinecraftClient client) {
		walker.clear(client);
		step = Step.IDLE;
		if (!gotAny) {
			failure = Text.translatable("mapsmith.restock.none", Builder.missingText());
		}
		Builder.debug("restock done: got {}", gotAny);
	}

	/** The next place to get things from. */
	private static boolean pick(MinecraftClient client, ClientPlayerEntity player, Plan plan) {
		recount(client, plan);
		if (want.isEmpty() || room(player.getInventory()) == 0 && !canMakeRoom(player.getInventory())) {
			finish(client);
			return true;
		}
		PlayerInventory inv = player.getInventory();
		int box = carriedBox(inv, want.keySet());
		if (box >= 0) {
			boxSlot = box;
			boxItem = inv.getStack(box).getItem();
			boxAt = null;
			to(Step.PLACE_BOX);
			return true;
		}
		// Known to hold something wanted, then not looked in yet; nearest first.
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		boolean bestKnown = false;
		for (BlockPos c : containers(client.world, plan)) {
			if (skip.contains(c)) continue;
			Map<Item, Integer> has = known.get(key(client.world, c));
			boolean useful = has != null && has.keySet().stream().anyMatch(want::containsKey);
			if (has != null && !useful) continue;
			double d = c.getSquaredDistance(player.getBlockPos());
			if (useful && !bestKnown || useful == bestKnown && d < bestD) {
				best = c;
				bestD = d;
				bestKnown = useful;
			}
		}
		if (best == null) {
			finish(client);
			return gotAny;
		}
		target = best;
		boxAt = null;
		to(Step.GO);
		return true;
	}

	// ---- a carried shulker box

	private static void placeBox(MinecraftClient client, ClientPlayerEntity player, ClientWorld world, Plan plan) {
		if (boxAt != null && world.getBlockState(boxAt).getBlock() instanceof ShulkerBoxBlock) {
			target = boxAt;
			to(Step.OPEN);
			return;
		}
		if (ticks % 10 != 1) return;
		if (++tries > 6) {
			Builder.debug("restock: couldn't put a shulker box down");
			skipBox = true;
			finish(client);
			return;
		}
		PlayerInventory inv = player.getInventory();
		if (boxSlot < 0 || boxSlot >= PlayerInventory.MAIN_SIZE || !inv.getStack(boxSlot).isOf(boxItem)) {
			to(Step.PICK);
			return;
		}
		BlockPos spot = boxSpot(player, world, plan);
		if (spot == null) {
			Builder.debug("restock: nowhere to put a shulker box down");
			finish(client);
			return;
		}
		boxAt = spot;
		Builder.select(client, player, boxSlot);
		client.options.sneakKey.setPressed(false);
		BlockPos below = spot.down();
		Vec3d hit = Vec3d.ofCenter(below).add(0, 0.5, 0);
		Builder.look(player, hit);
		client.interactionManager.interactBlock(player, Hand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, below, false));
		player.swingHand(Hand.MAIN_HAND);
	}

	private static boolean skipBox;

	/** Beside us on something solid, with room above to open, and not where the art needs a block. */
	private static BlockPos boxSpot(ClientPlayerEntity player, ClientWorld world, Plan plan) {
		BlockPos feet = Walker.feet(player);
		double r = Builder.reach(player) - 0.5;
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (BlockPos q : BlockPos.iterate(feet.add(-3, -1, -3), feet.add(3, 2, 3))) {
			if (q.equals(feet) || q.equals(feet.up())) continue;
			if (!world.getBlockState(q).isAir() || !world.getBlockState(q.up()).isAir()) continue;
			if (!BlockPalette.solid(world.getBlockState(q.down())) || world.getBlockState(q.down()).hasBlockEntity()) continue;
			Plan.Kind k = plan.kind(q.getX(), q.getY(), q.getZ());
			if (k != Plan.Kind.NONE && k != Plan.Kind.CLEAR) continue;
			Plan.Kind up = plan.kind(q.getX(), q.getY() + 1, q.getZ());
			if (up != Plan.Kind.NONE && up != Plan.Kind.CLEAR) continue;
			if (player.getBoundingBox().intersects(new Box(q))) continue;
			double d = player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(q));
			if (d > r * r || d < 2) continue;
			if (d < bestD) {
				bestD = d;
				best = q.toImmutable();
			}
		}
		return best;
	}

	private static void breakBox(MinecraftClient client, ClientPlayerEntity player, ClientWorld world) {
		BlockState s = world.getBlockState(boxAt);
		if (!(s.getBlock() instanceof ShulkerBoxBlock)) {
			client.interactionManager.cancelBlockBreaking();
			to(Step.PICK_UP);
			return;
		}
		if (ticks > 400) {
			client.interactionManager.cancelBlockBreaking();
			Builder.debug("restock: couldn't break the shulker box at {}", boxAt);
			finish(client);
			return;
		}
		Direction side = Direction.getFacing(player.getEyePos().subtract(Vec3d.ofCenter(boxAt)));
		Builder.look(player, Vec3d.ofCenter(boxAt));
		if (ticks == 1) {
			Builder.bestTool(client, player, s);
			client.interactionManager.attackBlock(boxAt, side);
		} else {
			client.interactionManager.updateBlockBreakingProgress(boxAt, side);
		}
		player.swingHand(Hand.MAIN_HAND);
	}

	private static void pickUp(MinecraftClient client, ClientPlayerEntity player, ClientWorld world) {
		ItemEntity drop = world.getEntitiesByClass(ItemEntity.class, new Box(boxAt).expand(4), e -> e.getStack().isOf(boxItem)).stream()
				.min(java.util.Comparator.comparingDouble(e -> e.squaredDistanceTo(player))).orElse(null);
		if (drop == null) {
			if (ticks > 10 || count(player.getInventory(), boxItem) > 0 && ticks > 4) {
				walker.clear(client);
				to(Step.PICK);
			}
			return;
		}
		if (ticks > 300) {
			Builder.debug("restock: left the shulker box at {}", drop.getBlockPos());
			walker.clear(client);
			finish(client);
			return;
		}
		if (player.getBoundingBox().expand(1, 0.5, 1).intersects(drop.getBoundingBox())) {
			walker.stop(client);
			return;
		}
		if (walker.arrived() || ticks % 40 == 1) {
			BlockPos cell = drop.getBlockPos();
			walker.plan(world, player, f -> Math.abs(f.getX() - cell.getX()) + Math.abs(f.getZ() - cell.getZ()) <= 1
					&& Math.abs(f.getY() - cell.getY()) <= 1, around(player.getBlockPos(), cell, 6));
		}
		walker.tick(client);
	}

	// ---- containers

	private static void go(MinecraftClient client, ClientPlayerEntity player, ClientWorld world) {
		double r = Builder.reach(player) - 0.5;
		if (player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(target)) <= r * r) {
			walker.stop(client);
			to(Step.OPEN);
			return;
		}
		if (walker.arrived() || !walker.moving() && ticks % 20 == 1) {
			if (++tries > 4) {
				skip.add(target);
				walker.clear(client);
				to(Step.PICK);
				return;
			}
			double want = r - 0.6;
			BlockPos t = target;
			if (!walker.plan(world, player, f -> new Vec3d(f.getX() + 0.5, f.getY() + 1.62, f.getZ() + 0.5).squaredDistanceTo(Vec3d.ofCenter(t)) <= want * want,
					around(player.getBlockPos(), t, 8))) {
				Builder.debug("restock: no way to {}", t);
				skip.add(target);
				to(Step.PICK);
				return;
			}
		}
		walker.tick(client);
	}

	private static void open(MinecraftClient client, ClientPlayerEntity player, ClientWorld world) {
		ScreenHandler h = player.currentScreenHandler;
		if (h != player.playerScreenHandler && !containerSlots(h, player).isEmpty()) {
			to(Step.TRANSFER);
			return;
		}
		if (ticks % 20 != 1) return;
		if (++tries > 5) {
			Builder.debug("restock: couldn't open {}", target);
			skip.add(target);
			if (boxAt != null) {
				to(Step.BREAK_BOX);
			} else {
				to(Step.PICK);
			}
			return;
		}
		client.options.sneakKey.setPressed(false);
		Vec3d hit = Vec3d.ofCenter(target).add(0, 0.45, 0);
		Builder.look(player, hit);
		client.interactionManager.interactBlock(player, Hand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, target, false));
		player.swingHand(Hand.MAIN_HAND);
	}

	/** One click a tick: room first (blocks the art doesn't need go in), then what's wanted comes out. */
	private static void transfer(MinecraftClient client, ClientPlayerEntity player, Plan plan) {
		ScreenHandler h = player.currentScreenHandler;
		if (h == player.playerScreenHandler) {
			// Closed on us (the box was broken, or it's out of reach): look again.
			done(client, player);
			return;
		}
		List<Slot> theirs = containerSlots(h, player);
		remember(client.world, theirs);
		PlayerInventory inv = player.getInventory();
		// A carried box needs a free slot to come back to.
		int keep = boxAt != null ? 1 : 0;
		if (clicks++ % 2 == 0) {
			if (room(inv) <= keep) {
				for (Slot s : h.slots) {
					if (s.inventory != inv || s.getIndex() >= PlayerInventory.MAIN_SIZE) continue;
					ItemStack st = s.getStack();
					if (!(st.getItem() instanceof BlockItem bi) || neededAtAll.contains(st.getItem()) || bi.getBlock() instanceof ShulkerBoxBlock) continue;
					if (!hasRoom(theirs, st)) continue;
					Builder.debug("restock: putting away {}", st);
					quickMove(client, player, h, s);
					return;
				}
			}
			for (Slot s : theirs) {
				ItemStack st = s.getStack();
				Integer w = want.get(st.getItem());
				if (w == null || w <= 0) continue;
				if (room(inv) <= keep && !canMerge(inv, st)) break;
				want.put(st.getItem(), w - st.getCount());
				gotAny |= missingAtStart.contains(Block.getBlockFromItem(st.getItem()));
				Builder.debug("restock: taking {}", st);
				quickMove(client, player, h, s);
				return;
			}
			if (clicks < 6) return;
			remember(client.world, theirs);
			player.closeHandledScreen();
			done(client, player);
		}
	}

	private static void done(MinecraftClient client, ClientPlayerEntity player) {
		// A container that had nothing more to give won't be looked in again.
		if (boxAt != null) {
			to(Step.BREAK_BOX);
		} else {
			to(Step.PICK);
		}
	}

	private static void remember(ClientWorld world, List<Slot> slots) {
		Map<Item, Integer> has = new HashMap<>();
		for (Slot s : slots) if (!s.getStack().isEmpty()) has.merge(s.getStack().getItem(), s.getStack().getCount(), Integer::sum);
		if (boxAt == null && target != null) {
			known.put(key(world, target), has);
			BlockPos other = otherHalf(world, target);
			if (other != null) known.put(other, has);
		}
	}

	/** Chests, barrels, placed shulker boxes and ender chests near the art. */
	private static List<BlockPos> containers(ClientWorld world, Plan plan) {
		List<BlockPos> out = new ArrayList<>();
		int margin = 32;
		int minX = plan.x0 - margin, maxX = plan.x0 + plan.width() + margin, minZ = plan.z0 - margin, maxZ = plan.z0 + plan.height() + margin;
		boolean ender = false;
		for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
			for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
				WorldChunk chunk = world.getChunkManager().getWorldChunk(cx, cz);
				if (chunk == null) continue;
				for (BlockPos p : chunk.getBlockEntityPositions()) {
					if (p.getX() < minX || p.getX() > maxX || p.getZ() < minZ || p.getZ() > maxZ) continue;
					BlockState s = world.getBlockState(p);
					Block b = s.getBlock();
					if (b instanceof EnderChestBlock) {
						if (ender) continue;
						ender = true;
					} else if (!(b instanceof ChestBlock || b instanceof BarrelBlock || b instanceof ShulkerBoxBlock)) {
						continue;
					}
					// One half of a double chest is enough.
					BlockPos other = otherHalf(world, p);
					if (other != null && out.contains(other)) continue;
					out.add(p.toImmutable());
				}
			}
		}
		return out;
	}

	private static BlockPos key(ClientWorld world, BlockPos p) {
		return world.getBlockState(p).getBlock() instanceof EnderChestBlock ? ENDER : p;
	}

	private static BlockPos otherHalf(ClientWorld world, BlockPos p) {
		BlockState s = world.getBlockState(p);
		if (!(s.getBlock() instanceof ChestBlock) || s.get(ChestBlock.CHEST_TYPE) == ChestType.SINGLE) return null;
		return p.offset(ChestBlock.getFacing(s));
	}

	// ---- inventory

	/** A carried shulker box holding any of the items, or -1. */
	private static int carriedBox(PlayerInventory inv, Set<Item> items) {
		if (skipBox) return -1;
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
			ItemStack st = inv.getStack(i);
			if (!(st.getItem() instanceof BlockItem bi) || !(bi.getBlock() instanceof ShulkerBoxBlock)) continue;
			ContainerComponent c = st.get(DataComponentTypes.CONTAINER);
			if (c != null && c.streamNonEmpty().anyMatch(x -> items.contains(x.getItem()))) return i;
		}
		return -1;
	}

	static int count(PlayerInventory inv, Item item) {
		int n = 0;
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) if (inv.getStack(i).isOf(item)) n += inv.getStack(i).getCount();
		return n;
	}

	private static int room(PlayerInventory inv) {
		int n = 0;
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) if (inv.getStack(i).isEmpty()) n++;
		return n;
	}

	private static boolean canMerge(PlayerInventory inv, ItemStack st) {
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
			ItemStack mine = inv.getStack(i);
			if (ItemStack.areItemsAndComponentsEqual(mine, st) && mine.getCount() < mine.getMaxCount()) return true;
		}
		return false;
	}

	private static boolean canMakeRoom(PlayerInventory inv) {
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
			ItemStack st = inv.getStack(i);
			if (st.getItem() instanceof BlockItem bi && !neededAtAll.contains(st.getItem()) && !(bi.getBlock() instanceof ShulkerBoxBlock)) return true;
		}
		return false;
	}

	private static boolean hasRoom(List<Slot> slots, ItemStack st) {
		for (Slot s : slots) {
			ItemStack there = s.getStack();
			if (there.isEmpty() || ItemStack.areItemsAndComponentsEqual(there, st) && there.getCount() < there.getMaxCount()) return true;
		}
		return false;
	}

	private static List<Slot> containerSlots(ScreenHandler h, ClientPlayerEntity player) {
		List<Slot> out = new ArrayList<>();
		for (Slot s : h.slots) if (s.inventory != player.getInventory()) out.add(s);
		return out;
	}

	private static void quickMove(MinecraftClient client, ClientPlayerEntity player, ScreenHandler h, Slot s) {
		client.interactionManager.clickSlot(h.syncId, h.slots.indexOf(s), 0, SlotActionType.QUICK_MOVE, player);
	}

	private static int[] around(BlockPos a, BlockPos b, int margin) {
		return new int[] {Math.min(a.getX(), b.getX()) - margin, Math.min(a.getY(), b.getY()) - margin, Math.min(a.getZ(), b.getZ()) - margin,
				Math.max(a.getX(), b.getX()) + margin, Math.max(a.getY(), b.getY()) + margin, Math.max(a.getZ(), b.getZ()) + margin};
	}

	static Text status() {
		return switch (step) {
			case PLACE_BOX, OPEN, TRANSFER -> Text.translatable("mapsmith.restock.box");
			case GO -> Text.translatable("mapsmith.restock.going", target.getX(), target.getY(), target.getZ());
			case BREAK_BOX, PICK_UP -> Text.translatable("mapsmith.restock.box_back");
			default -> Text.translatable("mapsmith.restock.looking");
		};
	}

	static void reset() {
		skipBox = false;
	}
}
