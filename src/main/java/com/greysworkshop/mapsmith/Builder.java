package com.greysworkshop.mapsmith;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The builder: places the art's blocks within reach (lowest first, so supports go in before what they hold up),
 * breaks wrong blocks and anything in the way, swaps the right block or tool into the hotbar, and walks or flies on to
 * the next unfinished part, a strip at a time from west to east, north to south. It stops when it's out of a block,
 * and says which.
 */
final class Builder {
	private Builder() {}

	enum State { OFF, RUNNING, PAUSED, DONE }

	/** What a position in the art needs. */
	enum Need { NOTHING, PLACE, BREAK }

	static State state = State.OFF;
	/** Why it paused, or what it's doing, for the HUD. */
	static Text status = Text.empty();
	static long placed, broken;
	private static long startedAt;
	private static final Walker walker = new Walker();
	private static double budget;
	private static BlockPos breaking;
	private static final Map<BlockPos, Integer> recent = new HashMap<>();
	private static final Map<Long, Integer> skipNode = new HashMap<>();
	/** Blocks it's out of: how many of each are wanted nearby. */
	static final Map<Block, Integer> missing = new LinkedHashMap<>();
	private static int tick, idleTicks, failures, goalAge;
	private static boolean skippedAny;
	/** Ticks to wait before looking for a path again, after one wasn't found. */
	private static int planWait;
	/** Only build this part: pixel columns and rows {minX, minZ, maxX, maxZ}, inclusive; null for all of it. */
	static int[] region;

	static boolean inRegion(Plan plan, int x, int z) {
		if (!plan.inColumns(x, z)) return false;
		if (region == null) return true;
		int px = x - plan.x0, pz = z - plan.z0;
		return px >= region[0] && px <= region[2] && pz >= region[1] - 1 && pz <= region[3];
	}
	private static BlockPos goal;
	private static int cursorLane, cursorPx, cursorNode;

	private static final boolean DEBUG = Boolean.getBoolean("mapsmith.debug");

	static void debug(String what, Object... args) {
		if (DEBUG) Mapsmith.LOGGER.info("[builder] " + what, args);
	}

	/** Debug: what the last 100 ticks went on, and why placing failed. */
	private static final int[] spent = new int[4], fails = new int[Result.values().length];

	static boolean running() {
		return state == State.RUNNING;
	}

	static void start(MinecraftClient client) {
		Plan plan = Mapsmith.plan();
		if (plan == null) {
			say(client, Text.translatable("mapsmith.builder.no_plan").formatted(Formatting.RED));
			return;
		}
		// Carrying on after a pause keeps the counts.
		if (state != State.PAUSED) {
			startedAt = System.currentTimeMillis();
			placed = broken = 0;
		}
		state = State.RUNNING;
		missing.clear();
		skipNode.clear();
		Restock.reset();
		failures = 0;
		goal = null;
		cursorLane = cursorPx = cursorNode = 0;
		status = Text.translatable("mapsmith.builder.started");
		say(client, Text.translatable("mapsmith.builder.started_chat"));
	}

	static void stop(MinecraftClient client, State to, Text why) {
		if (state == State.OFF && to == State.OFF) return;
		state = to;
		status = why;
		walker.clear(client);
		if (breaking != null && client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
		breaking = null;
		if (Restock.active()) Restock.forget();
		Gather.forget(client);
		Survive.cancel(client);
		if (to != State.OFF) say(client, why);
	}

	static void toggle(MinecraftClient client) {
		if (state == State.RUNNING) stop(client, State.PAUSED, Text.translatable("mapsmith.builder.paused"));
		else start(client);
	}

	private static void say(MinecraftClient client, Text text) {
		if (client.player != null) client.player.sendMessage(Text.literal("[Mapsmith] ").formatted(Formatting.GOLD).append(text), false);
	}

	static double reach(ClientPlayerEntity player) {
		return player.getBlockInteractionRange() - 0.4;
	}

	/** What the art needs at a position, given what's there. */
	static Need need(Plan plan, ClientWorld world, BlockPos pos, BlockState state) {
		Plan.Kind kind = plan.kind(pos.getX(), pos.getY(), pos.getZ());
		if (kind == Plan.Kind.NONE || plan.satisfied(world, pos, state)) return Need.NOTHING;
		return switch (kind) {
			// Water in the way is filled in first, then broken.
			case CLEAR -> state.getFluidState().isEmpty() ? Need.BREAK : Need.PLACE;
			case ART -> state.isReplaceable() ? Need.PLACE : Need.BREAK;
			default -> state.isReplaceable() ? Need.PLACE : Need.BREAK;
		};
	}

	static Block blockFor(Plan plan, BlockPos pos) {
		Block b = plan.blockToPlace(pos.getX(), pos.getY(), pos.getZ());
		return b != null ? b : plan.filler;
	}

	static void tick(MinecraftClient client) {
		tick++;
		recent.values().removeIf(t -> tick - t > 12);
		if (state != State.RUNNING) return;
		ClientPlayerEntity player = client.player;
		ClientWorld world = client.world;
		Plan plan = Mapsmith.plan();
		if (player == null || world == null || client.interactionManager == null) return;
		if (plan == null) {
			stop(client, State.OFF, Text.translatable("mapsmith.builder.no_plan"));
			return;
		}
		if (Restock.active()) {
			if (player.getHealth() < 6 && !player.getAbilities().invulnerable) {
				Restock.forget();
				if (player.currentScreenHandler != player.playerScreenHandler) player.closeHandledScreen();
				stop(client, State.PAUSED, Text.translatable("mapsmith.builder.low_health").formatted(Formatting.RED));
				return;
			}
			status = Restock.status();
			Restock.tick(client, plan);
			if (!Restock.active() && Restock.failure != null) {
				// Nothing to fetch: mine it, if it's something that can be mined.
				if (!(MapsmithConfig.INSTANCE.gather && Gather.begin(client, plan, missing.keySet()))) {
					stop(client, State.PAUSED, Restock.failure.copy().formatted(Formatting.YELLOW));
				}
			}
			return;
		}
		if (Gather.active()) {
			if (player.getHealth() < 6 && !player.getAbilities().invulnerable) {
				Gather.forget(client);
				stop(client, State.PAUSED, Text.translatable("mapsmith.builder.low_health").formatted(Formatting.RED));
				return;
			}
			if (Survive.tick(client)) return;
			status = Gather.status();
			Gather.tick(client, plan);
			if (!Gather.active()) {
				if (Gather.failure != null) stop(client, State.PAUSED, Gather.failure.copy().formatted(Formatting.YELLOW));
				else missing.clear();
			}
			return;
		}
		if (Survive.tick(client)) {
			walker.stop(client);
			return;
		}
		if (client.currentScreen != null) {
			walker.stop(client);
			return;
		}
		if (player.getHealth() < 6 && !player.getAbilities().invulnerable) {
			stop(client, State.PAUSED, Text.translatable("mapsmith.builder.low_health").formatted(Formatting.RED));
			return;
		}
		if (!plan.inColumns(player.getBlockX(), player.getBlockZ()) && distanceToArt(plan, player) > 24) {
			stop(client, State.PAUSED, Text.translatable("mapsmith.builder.too_far", plan.x0 + plan.width() / 2, plan.z0 + plan.height() / 2));
			return;
		}
		boolean creative = player.getAbilities().creativeMode;
		double speed = creative ? MapsmithConfig.INSTANCE.creativeSpeed : MapsmithConfig.INSTANCE.speed;
		budget = Math.min(budget + speed / 20.0, Math.max(4, speed / 10));

		if (DEBUG && tick % 100 == 0) {
			debug("last 100 ticks: placing {}, breaking {}, moving {}, idle {}; place fails {}; at {}", spent[0], spent[1], spent[2], spent[3],
					java.util.Arrays.toString(fails), player.getBlockPos().toShortString());
			java.util.Arrays.fill(spent, 0);
			java.util.Arrays.fill(fails, 0);
		}
		if (breaking != null && continueBreaking(client, player, world, plan)) {
			spent[1]++;
			return;
		}

		List<BlockPos> place = new ArrayList<>(), brk = new ArrayList<>();
		gather(plan, world, player, place, brk);
		boolean didSomething = false;
		BlockPos blockedBySelf = null;

		// Wrong blocks where something must go come first; clearing the space above the art (including pillars it went
		// up on) waits until there's nothing to place.
		List<BlockPos> urgent = brk.stream().filter(p -> plan.kind(p.getX(), p.getY(), p.getZ()) != Plan.Kind.CLEAR).toList();
		// Nothing gets broken while pillaring up.
		boolean fix = MapsmithConfig.INSTANCE.fixMistakes && !walker.towering();
		if (fix && !urgent.isEmpty()) {
			startBreaking(client, player, world, nearest(player, urgent));
			didSomething = true;
		} else if (!place.isEmpty()) {
			Vec3d eye = player.getEyePos();
			place.sort(Comparator.comparingInt(BlockPos::getY).thenComparingDouble(p -> eye.squaredDistanceTo(Vec3d.ofCenter(p))));
			missing.clear();
			for (BlockPos pos : place) {
				if (budget < 1) {
					didSomething = true;
					break;
				}
				if (recent.containsKey(pos)) {
					didSomething = true;
					continue;
				}
				Result r = tryPlace(client, player, world, plan, pos);
				fails[r.ordinal()]++;
				if (r == Result.PLACED) {
					budget--;
					placed++;
					recent.put(pos.toImmutable(), tick);
					didSomething = true;
				} else if (r == Result.IN_THE_WAY && blockedBySelf == null) {
					blockedBySelf = pos;
				}
			}
		}
		if (!didSomething && fix && !brk.isEmpty()) {
			startBreaking(client, player, world, nearest(player, brk));
			didSomething = true;
		}
		if (didSomething && walker.towering()) {
			// Halfway up a pillar: keep jumping and placing under, whatever else gets placed meanwhile.
			walker.tick(client);
			return;
		}
		if (didSomething) {
			spent[brk.isEmpty() ? 0 : 1]++;
			failures = 0;
			walker.stop(client);
			idleTicks = 0;
			goal = null;
			return;
		}
		idleTicks++;
		spent[walker.arrived() ? 3 : 2]++;
		if (!MapsmithConfig.INSTANCE.autoWalk) {
			status = Text.translatable("mapsmith.builder.waiting_walk");
			return;
		}
		move(client, player, world, plan, blockedBySelf);
	}

	private static double distanceToArt(Plan plan, ClientPlayerEntity player) {
		double dx = Math.max(0, Math.max(plan.x0 - player.getX(), player.getX() - (plan.x0 + plan.width())));
		double dz = Math.max(0, Math.max(plan.z0 - 1 - player.getZ(), player.getZ() - (plan.z0 + plan.height())));
		return Math.sqrt(dx * dx + dz * dz);
	}

	/** The positions within reach that need a block placed, or broken. */
	private static void gather(Plan plan, ClientWorld world, ClientPlayerEntity player, List<BlockPos> place, List<BlockPos> brk) {
		Vec3d eye = player.getEyePos();
		double r = reach(player), r2 = r * r;
		int minY = Math.max(plan.y0 - 1, MathHelper.floor(eye.y - r)), maxY = Math.min(plan.topY(), MathHelper.ceil(eye.y + r));
		BlockPos.Mutable pos = new BlockPos.Mutable();
		for (int x = MathHelper.floor(eye.x - r); x <= MathHelper.ceil(eye.x + r); x++) {
			for (int z = MathHelper.floor(eye.z - r); z <= MathHelper.ceil(eye.z + r); z++) {
				if (!inRegion(plan, x, z)) continue;
				for (int y = minY; y <= maxY; y++) {
					pos.set(x, y, z);
					if (eye.squaredDistanceTo(x + 0.5, y + 0.5, z + 0.5) > r2) continue;
					BlockState s = world.getBlockState(pos);
					Need n = need(plan, world, pos, s);
					if (n == Need.PLACE) place.add(pos.toImmutable());
					// Never the block it's standing on (say, the top of a pillar it went up on): that goes once it steps off.
					else if (n == Need.BREAK && !holdsUp(player, pos)) brk.add(pos.toImmutable());
				}
			}
		}
	}

	/**
	 * Whether a pillar can go up through {@code cell}: not where a pixel's block goes, and with everything the column
	 * needs below it already there (a pillar would cut it off).
	 */
	private static boolean pillarable(Plan plan, ClientWorld world, BlockPos cell) {
		if (plan.kind(cell.getX(), cell.getY(), cell.getZ()) == Plan.Kind.ART) return false;
		BlockPos.Mutable p = new BlockPos.Mutable();
		for (int y = plan.y0 - 1; y < cell.getY(); y++) {
			p.set(cell.getX(), y, cell.getZ());
			if (need(plan, world, p, world.getBlockState(p)) == Need.PLACE) return false;
		}
		return true;
	}

	/** The highest of the blocks, nearest first among equals: break from the top down. */
	private static BlockPos nearest(ClientPlayerEntity player, List<BlockPos> list) {
		Vec3d eye = player.getEyePos();
		return list.stream().min(Comparator.comparingDouble(p -> -p.getY() * 100 + eye.squaredDistanceTo(Vec3d.ofCenter(p)))).orElseThrow();
	}

	/** Whether the block at {@code pos} is under the player's feet. */
	private static boolean holdsUp(ClientPlayerEntity player, BlockPos pos) {
		net.minecraft.util.math.Box box = player.getBoundingBox();
		// Up to a jump's height below: mid-jump it's still what it lands on.
		return pos.getY() + 1 <= box.minY + 0.1 && pos.getY() + 1 >= box.minY - 1.5
				&& pos.getX() + 1 > box.minX && pos.getX() < box.maxX && pos.getZ() + 1 > box.minZ && pos.getZ() < box.maxZ;
	}

	enum Result { PLACED, NO_SUPPORT, NO_ITEM, IN_THE_WAY }

	private static Result tryPlace(MinecraftClient client, ClientPlayerEntity player, ClientWorld world, Plan plan, BlockPos pos) {
		Block block = blockFor(plan, pos);
		Item item = block.asItem();
		PlayerInventory inv = player.getInventory();
		int slot = find(inv, item);
		if (slot < 0 && player.getAbilities().creativeMode) slot = conjure(client, player, item);
		if (slot < 0) {
			missing.merge(block, 1, Integer::sum);
			return Result.NO_ITEM;
		}
		ItemStack stack = inv.getStack(slot);
		boolean art = plan.kind(pos.getX(), pos.getY(), pos.getZ()) == Plan.Kind.ART;
		int wantColor = art ? plan.colorAt(pos.getX(), pos.getZ()) : -1;
		Vec3d eye = player.getEyePos();
		double range = player.getBlockInteractionRange();
		boolean inTheWay = false;
		// Against the block below first: that's how logs come out upright, and carpets need it anyway.
		for (Direction d : new Direction[] {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP}) {
			BlockPos against = pos.offset(d);
			BlockState there = world.getBlockState(against);
			if (!BlockPalette.solid(there) || there.getOutlineShape(world, against).isEmpty()) continue;
			Direction side = d.getOpposite();
			Vec3d hit = Vec3d.ofCenter(against).add(Vec3d.of(side.getVector()).multiply(0.5));
			if (eye.squaredDistanceTo(hit) > range * range) continue;
			BlockHitResult result = new BlockHitResult(hit, side, against, false);
			ItemPlacementContext ctx = new ItemPlacementContext(player, Hand.MAIN_HAND, stack, result);
			if (!ctx.getBlockPos().equals(pos)) continue;
			BlockState predicted = block.getPlacementState(ctx);
			if (predicted == null || !predicted.canPlaceAt(world, pos)) continue;
			if (art && predicted.getMapColor(world, pos).id != wantColor) continue;
			if (!world.canPlace(predicted, pos, ShapeContext.absent())) {
				inTheWay = true;
				continue;
			}
			select(client, player, slot);
			if (MapsmithConfig.INSTANCE.rotate) look(player, hit);
			client.interactionManager.interactBlock(player, Hand.MAIN_HAND, result);
			player.swingHand(Hand.MAIN_HAND);
			return Result.PLACED;
		}
		if (DEBUG && !inTheWay && fails[Result.NO_SUPPORT.ordinal()] % 50 == 0) {
			StringBuilder why = new StringBuilder();
			for (Direction d : Direction.values()) {
				BlockState there = world.getBlockState(pos.offset(d));
				if (BlockPalette.solid(there)) why.append(d).append(' ');
			}
			debug("can't place {} at {} ({}): solid sides {} eye {}", block, pos, plan.kind(pos.getX(), pos.getY(), pos.getZ()), why,
					player.getEyePos());
		}
		return inTheWay ? Result.IN_THE_WAY : Result.NO_SUPPORT;
	}

	/** The inventory slot holding an item, preferring the hotbar; -1 if none. */
	static int find(PlayerInventory inv, Item item) {
		int selected = inv.getSelectedSlot();
		if (inv.getStack(selected).isOf(item)) return selected;
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) if (inv.getStack(i).isOf(item)) return i;
		return -1;
	}

	/** Pillaring up: the filler block into {@code cell}, against the block under it. */
	private static boolean placeFiller(MinecraftClient client, ClientPlayerEntity player, ClientWorld world, Plan plan, BlockPos cell) {
		int slot = find(player.getInventory(), plan.filler.asItem());
		BlockPos below = cell.down();
		if (slot < 0 || !BlockPalette.solid(world.getBlockState(below))) {
			debug("can't pillar up at {}: {}", cell, slot < 0 ? "no " + plan.filler : "nothing under it (" + world.getBlockState(below) + ")");
			return false;
		}
		select(client, player, slot);
		BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(below).add(0, 0.5, 0), Direction.UP, below, false);
		client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
		player.swingHand(Hand.MAIN_HAND);
		debug("pillared up at {}", cell);
		return true;
	}

	/** In creative, takes a stack of the item out of nowhere, into the hotbar. */
	private static int conjure(MinecraftClient client, ClientPlayerEntity player, Item item) {
		PlayerInventory inv = player.getInventory();
		int hot = inv.getSwappableHotbarSlot();
		ItemStack stack = new ItemStack(item, item.getMaxCount());
		inv.setStack(hot, stack);
		client.interactionManager.clickCreativeStack(stack, PlayerInventory.MAIN_SIZE + hot);
		return hot;
	}

	/** Puts the stack in {@code slot} in hand, swapping it into the hotbar if need be. */
	static void select(MinecraftClient client, ClientPlayerEntity player, int slot) {
		PlayerInventory inv = player.getInventory();
		if (slot < PlayerInventory.getHotbarSize()) {
			inv.setSelectedSlot(slot);
			return;
		}
		int hotbar = inv.getSwappableHotbarSlot();
		client.interactionManager.clickSlot(player.playerScreenHandler.syncId, slot, hotbar, SlotActionType.SWAP, player);
		inv.setSelectedSlot(hotbar);
	}

	static void look(ClientPlayerEntity player, Vec3d at) {
		Vec3d d = at.subtract(player.getEyePos());
		double flat = Math.sqrt(d.x * d.x + d.z * d.z);
		player.setYaw((float) (MathHelper.atan2(d.z, d.x) * 180 / Math.PI) - 90);
		player.setPitch((float) -(MathHelper.atan2(d.y, flat) * 180 / Math.PI));
	}

	private static void startBreaking(MinecraftClient client, ClientPlayerEntity player, ClientWorld world, BlockPos pos) {
		BlockState s = world.getBlockState(pos);
		bestTool(client, player, s);
		// The face towards the player.
		Direction side = Direction.getFacing(player.getEyePos().subtract(Vec3d.ofCenter(pos)));
		if (MapsmithConfig.INSTANCE.rotate) look(player, Vec3d.ofCenter(pos));
		client.interactionManager.attackBlock(pos, side);
		player.swingHand(Hand.MAIN_HAND);
		breaking = pos;
		goalAge = 0;
		debug("breaking {} ({})", pos, s);
		if (world.getBlockState(pos).isAir()) {
			broken++;
			breaking = null;
		}
	}

	/** True while still breaking (this tick is spent on it). */
	private static boolean continueBreaking(MinecraftClient client, ClientPlayerEntity player, ClientWorld world, Plan plan) {
		BlockState s = world.getBlockState(breaking);
		if (need(plan, world, breaking, s) != Need.BREAK || player.getEyePos().distanceTo(Vec3d.ofCenter(breaking)) > reach(player) + 0.5) {
			if (s.isAir() || s.isReplaceable()) broken++;
			client.interactionManager.cancelBlockBreaking();
			breaking = null;
			return false;
		}
		if (++goalAge > 600) {
			client.interactionManager.cancelBlockBreaking();
			breaking = null;
			return false;
		}
		Direction side = Direction.getFacing(player.getEyePos().subtract(Vec3d.ofCenter(breaking)));
		if (MapsmithConfig.INSTANCE.rotate) look(player, Vec3d.ofCenter(breaking));
		client.interactionManager.updateBlockBreakingProgress(breaking, side);
		player.swingHand(Hand.MAIN_HAND);
		return true;
	}

	static void bestTool(MinecraftClient client, ClientPlayerEntity player, BlockState state) {
		PlayerInventory inv = player.getInventory();
		int best = -1;
		float bestSpeed = 1;
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
			ItemStack st = inv.getStack(i);
			if (st.isEmpty() || st.getItem() instanceof BlockItem) continue;
			float sp = st.getMiningSpeedMultiplier(state);
			// Never wear a tool down to nothing.
			if (st.isDamageable() && st.getMaxDamage() - st.getDamage() < 10) continue;
			if (sp > bestSpeed) {
				bestSpeed = sp;
				best = i;
			}
		}
		if (best >= 0) select(client, player, best);
	}

	// ---- Moving on ----

	/** Strips of columns the builder works through, west to east. */
	private static int laneWidth(ClientPlayerEntity player) {
		return flying(player) ? 7 : 5;
	}

	private static boolean flying(ClientPlayerEntity player) {
		return MapsmithConfig.INSTANCE.fly && player.getAbilities().allowFlying;
	}

	private static void move(MinecraftClient client, ClientPlayerEntity player, ClientWorld world, Plan plan, BlockPos blockedBySelf) {
		if (goal != null && !walker.arrived()) {
			if (walker.tick(client)) {
				status = Text.translatable("mapsmith.builder.walking", goal.getX(), goal.getZ());
				return;
			}
			// Stuck: try that spot again later, and another now.
			skipNode.put(nodeKey(plan, goal), tick);
			debug("stuck walking to {}", goal);
			goal = null;
			if (++failures > 6) {
				stop(client, State.PAUSED, Text.translatable("mapsmith.builder.stuck").formatted(Formatting.RED));
				return;
			}
		}
		if (goal != null) {
			// There, and nothing could be done: give the placing a moment, then leave that spot for later.
			if (idleTicks < 8) return;
			debug("nothing doable at {}, leaving it for later", goal);
			skipNode.put(nodeKey(plan, goal), tick);
			goal = null;
		}
		if (planWait > 0) {
			planWait--;
			return;
		}
		skipNode.values().removeIf(t -> tick - t > 400);
		BlockPos target = blockedBySelf != null ? blockedBySelf : nextTarget(plan, world, player);
		// Out of something a carried shulker box holds: fetch it now, it's right here.
		if (!player.getAbilities().creativeMode && !missing.isEmpty() && Restock.carriedHas(player, missing.keySet())) {
			walker.clear(client);
			goal = null;
			if (Restock.begin(client, plan, missing.keySet())) return;
		}
		if (target == null && !skippedAny) {
			// Before calling it done, look over all of it again from the start: the server can undo a block after the
			// strip it's in was passed.
			cursorLane = 0;
			target = nextTarget(plan, world, player);
		}
		if (target == null && skippedAny && missing.isEmpty()) {
			// Everything left was put off: try it all again, but not forever.
			skipNode.clear();
			if (++failures > 12) stop(client, State.PAUSED, Text.translatable("mapsmith.builder.stuck").formatted(Formatting.RED));
			return;
		}
		if (target == null) {
			if (!missing.isEmpty()) {
				// Everything else is done: get more from the chests near the art before giving up.
				if (!player.getAbilities().creativeMode && (Restock.begin(client, plan, missing.keySet())
						|| MapsmithConfig.INSTANCE.gather && Gather.begin(client, plan, missing.keySet()))) {
					walker.clear(client);
					goal = null;
					return;
				}
				stop(client, State.PAUSED, (Restock.failure != null ? Restock.failure.copy()
						: Text.translatable("mapsmith.builder.out_of", missingText())).formatted(Formatting.YELLOW));
			} else if (allLoaded(plan, world)) {
				stop(client, State.DONE, Text.translatable("mapsmith.builder.done", placed, broken).formatted(Formatting.GREEN));
			} else {
				stop(client, State.PAUSED, Text.translatable("mapsmith.builder.unloaded"));
			}
			return;
		}
		goal = target;
		double r = reach(player);
		if (flying(player)) {
			BlockPos stand = flyStand(plan, world, player, target);
			walker.flyTo(world, player, Vec3d.ofBottomCenter(stand), plan.topY() + 2);
			walker.tick(client);
			return;
		}
		int[] bounds = {plan.x0 - 6, plan.y0 - 3, plan.z0 - 7, plan.x0 + plan.width() + 5, plan.topY() + 3, plan.z0 + plan.height() + 5};
		BlockPos t = target;
		boolean canTower = find(player.getInventory(), plan.filler.asItem()) >= 0;
		walker.placeUnder = cell -> placeFiller(client, player, world, plan, cell);
		walker.breakUnder = cell -> {
			if (breaking == null) startBreaking(client, player, world, cell);
			return true;
		};
		boolean ok = walker.plan(world, player, feet -> {
			// Not in the way of it, nor standing on it.
			if (feet.equals(t) || feet.up().equals(t) || feet.down().equals(t)) return false;
			Vec3d eye = new Vec3d(feet.getX() + 0.5, feet.getY() + 1.62, feet.getZ() + 0.5);
			return eye.squaredDistanceTo(Vec3d.ofCenter(t)) <= (r - 0.6) * (r - 0.6);
		}, bounds, canTower ? cell -> cell.getY() < t.getY() && !(cell.getX() == t.getX() && cell.getZ() == t.getZ())
				// Only inside the art: there a pillar is either hidden under it or cleared away as the builder goes.
				&& inRegion(plan, cell.getX(), cell.getZ()) && pillarable(plan, world, cell) : null,
				// Down through anything that has to go anyway: a pillar it went up on, something over the art.
				cell -> plan.kind(cell.getX(), cell.getY(), cell.getZ()) == Plan.Kind.CLEAR && MapsmithConfig.INSTANCE.fixMistakes);
		if (!ok) {
			planWait = 5;
			debug("no path to {}", target);
			skipNode.put(nodeKey(plan, target), tick);
			goal = null;
			if (++failures > 12) stop(client, State.PAUSED, Text.translatable("mapsmith.builder.stuck").formatted(Formatting.RED));
			return;
		}
		failures = Math.max(0, failures - 1);
		walker.tick(client);
	}

	/**
	 * Where to hover to work on a block: over the middle of its strip, two above it, so the whole width of the strip is
	 * in reach; straight over the block instead if something's in the way there.
	 */
	private static BlockPos flyStand(Plan plan, ClientWorld world, ClientPlayerEntity player, BlockPos target) {
		int lane = laneWidth(player);
		int px = target.getX() - plan.x0;
		int mid = Math.min(plan.width() - 1, px / lane * lane + lane / 2);
		double r = reach(player);
		BlockPos best = null;
		for (int x : new int[] {plan.x0 + mid, target.getX()}) {
			for (int dz : new int[] {0, 1, -1}) {
				BlockPos p = openSky(world, x, target.getZ() + dz, target.getY() + 2, plan.topY() + 3);
				if (best == null) best = p;
				Vec3d eye = new Vec3d(p.getX() + 0.5, p.getY() + 1.62, p.getZ() + 0.5);
				if (eye.squaredDistanceTo(Vec3d.ofCenter(target)) <= r * r && !p.equals(target) && !p.up().equals(target)) return p;
			}
		}
		return best;
	}

	/** The lowest spot at or above {@code fromY} in a column with nothing above it up to {@code top}: flight comes down to it from above. */
	private static BlockPos openSky(ClientWorld world, int x, int z, int fromY, int top) {
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int y = top; y >= fromY; y--) {
			if (!Walker.passable(world, m.set(x, y, z))) return new BlockPos(x, y + 1, z);
		}
		return new BlockPos(x, fromY, z);
	}

	private static long nodeKey(Plan plan, BlockPos p) {
		return (long) (p.getX() - plan.x0) << 32 | (p.getZ() - plan.z0 + 1) & 0xFFFFFFFFL;
	}

	/**
	 * The first part of the art, in building order, that still needs something the builder can do (and has the block
	 * for): its lowest such position. Null if there's none among the loaded chunks.
	 */
	private static BlockPos nextTarget(Plan plan, ClientWorld world, ClientPlayerEntity player) {
		int lane = laneWidth(player);
		int lanes = (plan.width() + lane - 1) / lane;
		BlockPos.Mutable pos = new BlockPos.Mutable();
		PlayerInventory inv = player.getInventory();
		boolean creative = player.getAbilities().creativeMode;
		// Start over from the beginning now and then, in case something got broken behind us.
		if (tick % 600 == 0) cursorLane = 0;
		missing.clear();
		skippedAny = false;
		for (int l = cursorLane; l < lanes; l++) {
			boolean laneDone = true;
			BlockPos fallback = null;
			for (int node = 0; node <= plan.height(); node++) {
				for (int px = l * lane; px < Math.min(plan.width(), (l + 1) * lane); px++) {
					int x = plan.x0 + px, z = plan.z0 + node - 1;
					if (!inRegion(plan, x, z)) continue;
					if (!world.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) {
						laneDone = false;
						continue;
					}
					for (int y = plan.y0 - 1; y <= plan.topY(); y++) {
						pos.set(x, y, z);
						Need n = need(plan, world, pos, world.getBlockState(pos));
						if (n == Need.NOTHING) continue;
						laneDone = false;
						if (n == Need.BREAK && !MapsmithConfig.INSTANCE.fixMistakes) continue;
						if (n == Need.PLACE && !creative && find(inv, blockFor(plan, pos).asItem()) < 0) {
							missing.merge(blockFor(plan, pos), 1, Integer::sum);
							continue;
						}
						if (skipNode.containsKey(nodeKey(plan, pos))) {
							skippedAny = true;
							continue;
						}
						// Something already there to place it against comes first; the rest waits on it.
						if (n == Need.PLACE && !touchesSolid(world, pos)) {
							if (fallback == null) fallback = pos.toImmutable();
							continue;
						}
						cursorLane = l;
						return pos.toImmutable();
					}
				}
			}
			if (fallback != null) {
				cursorLane = l;
				return fallback;
			}
			if (laneDone && l == cursorLane) cursorLane = l + 1;
		}
		cursorLane = 0;
		return null;
	}

	private static boolean touchesSolid(ClientWorld world, BlockPos pos) {
		for (Direction d : Direction.values()) if (BlockPalette.solid(world.getBlockState(pos.offset(d)))) return true;
		return false;
	}

	private static boolean allLoaded(Plan plan, ClientWorld world) {
		for (int x = plan.x0 >> 4; x <= plan.x0 + plan.width() - 1 >> 4; x++) {
			for (int z = plan.z0 - 1 >> 4; z <= plan.z0 + plan.height() - 1 >> 4; z++) {
				if (!world.getChunkManager().isChunkLoaded(x, z)) return false;
			}
		}
		return true;
	}

	/** How many of each block the art (or the region being built) still needs placed, in the loaded chunks. */
	static Map<Block, Integer> remaining(Plan plan, ClientWorld world) {
		Map<Block, Integer> out = new LinkedHashMap<>();
		BlockPos.Mutable pos = new BlockPos.Mutable();
		for (int px = 0; px < plan.width(); px++) {
			for (int node = 0; node <= plan.height(); node++) {
				int x = plan.x0 + px, z = plan.z0 + node - 1;
				if (!inRegion(plan, x, z) || !world.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) continue;
				for (int y = plan.y0 - 1; y <= plan.topY(); y++) {
					pos.set(x, y, z);
					if (need(plan, world, pos, world.getBlockState(pos)) == Need.PLACE) out.merge(blockFor(plan, pos), 1, Integer::sum);
				}
			}
		}
		return out;
	}

	static Text missingText() {
		List<Map.Entry<Block, Integer>> list = new ArrayList<>(missing.entrySet());
		list.sort(Map.Entry.<Block, Integer>comparingByValue().reversed());
		net.minecraft.text.MutableText t = Text.empty();
		int n = 0;
		for (Map.Entry<Block, Integer> e : list) {
			if (n++ == 4) {
				t.append(Text.literal(", …"));
				break;
			}
			if (n > 1) t.append(", ");
			t.append(e.getKey().getName()).append(" ×" + e.getValue());
		}
		return t;
	}

	/** Blocks per second since it started. */
	static double rate() {
		double s = (System.currentTimeMillis() - startedAt) / 1000.0;
		return s < 1 ? 0 : placed / s;
	}
}
