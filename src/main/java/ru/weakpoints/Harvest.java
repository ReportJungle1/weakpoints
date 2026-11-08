package ru.weakpoints;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Takes a whole ore vein or tree once the weak points are hit, the way vein-mining and tree-felling mods do. */
public final class Harvest {
	private static final int LOGS_PER_TICK = 3;
	private static final int LEAVES_PER_TICK = 4;
	/** Leaves learn their trunk is gone one block per tick; this lets the farthest of them find out first. */
	private static final int LEAF_WAIT_TICKS = 20;
	private static final int LEAF_LIMIT = 1024;
	private static final List<Felling> FELLINGS = new ArrayList<>();
	/** True while a vein or tree is being taken, so the ore lock lets those breaks through. */
	static boolean taking;

	private Harvest() {
	}

	/** {@code bare}: felled by hand, so no tool to wear out or break along the way. */
	private static final class Felling {
		final ServerLevel level;
		final ServerPlayer player;
		final ItemStack tool;
		final boolean bare;
		final BlockPos dropAt;
		final Deque<BlockPos> logs;
		final Deque<BlockPos> leaves;
		int wait = LEAF_WAIT_TICKS;

		Felling(ServerLevel level, ServerPlayer player, ItemStack tool, BlockPos dropAt, Deque<BlockPos> logs, Deque<BlockPos> leaves) {
			this.level = level;
			this.player = player;
			this.tool = tool;
			this.bare = tool.isEmpty();
			this.dropAt = dropAt;
			this.logs = logs;
			this.leaves = leaves;
		}
	}

	/** Natural blocks of the kind touching each other, corners included, nearest to the start first. Placed blocks are never taken. */
	public static List<BlockPos> connected(ServerLevel level, BlockPos start, Predicate<BlockState> kind, int limit) {
		List<BlockPos> found = new ArrayList<>();
		LongOpenHashSet seen = new LongOpenHashSet();
		Deque<BlockPos> queue = new ArrayDeque<>();
		queue.add(start.immutable());
		seen.add(start.asLong());
		while (!queue.isEmpty() && found.size() < limit) {
			BlockPos pos = queue.poll();
			if (!kind.test(level.getBlockState(pos)) || PlacedBlocks.isPlaced(level, pos)) continue;
			found.add(pos);
			for (BlockPos next : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 1, 1))) {
				if (seen.add(next.asLong())) queue.add(next.immutable());
			}
		}
		return found;
	}

	public static List<BlockPos> treeLogs(ServerLevel level, BlockPos hit) {
		return connected(level, hit, state -> state.is(BlockTags.LOGS), level.getGameRules().getInt(WeakPointsMod.TREE_LIMIT));
	}

	/**
	 * Iron and deepslate iron ore are one vein where stone turns to deepslate: ore of the same {@code c:ores/...} tag
	 * goes together, any other ore by its own block.
	 */
	public static List<BlockPos> veinOre(ServerLevel level, BlockPos hit, BlockState state) {
		TagKey<Block> family = state.getTags()
			.filter(tag -> tag.location().getNamespace().equals("c") && tag.location().getPath().startsWith("ores/"))
			.findFirst().orElse(null);
		Predicate<BlockState> kind = family != null ? s -> s.is(family) : s -> s.is(state.getBlock());
		return connected(level, hit, kind, level.getGameRules().getInt(WeakPointsMod.VEIN_LIMIT));
	}

	/** Leaves that grew there, or the wart blocks and shroomlights of a nether fungus. */
	private static boolean canopy(BlockState state) {
		if (state.is(BlockTags.LEAVES)) return !(state.hasProperty(LeavesBlock.PERSISTENT) && state.getValue(LeavesBlock.PERSISTENT));
		return state.is(BlockTags.WART_BLOCKS) || state.is(Blocks.SHROOMLIGHT);
	}

	/** Logs count as a tree only next to a canopy that grew there; village houses are natural logs without any. */
	public static boolean isTree(ServerLevel level, BlockPos log) {
		for (BlockPos pos : treeLogs(level, log)) {
			for (Direction direction : Direction.values()) {
				if (canopy(level.getBlockState(pos.relative(direction)))) return true;
			}
		}
		return false;
	}

	public static void vein(ServerLevel level, ServerPlayer player, BlockPos hit, BlockState state) {
		ItemStack tool = player.getMainHandItem();
		for (BlockPos pos : veinOre(level, hit, state)) {
			if (!breakInto(level, player, pos, tool, false, hit)) break;
		}
	}

	/** The trunk goes from the bottom up over a few ticks, so the tree crumbles instead of vanishing at once; then its leaves fall. */
	public static void fell(ServerLevel level, ServerPlayer player, BlockPos hit) {
		List<BlockPos> logs = treeLogs(level, hit);
		if (logs.isEmpty()) return;
		List<BlockPos> leaves = leavesOn(level, logs, level.random);
		logs.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingDouble(pos -> pos.distSqr(hit)));
		level.playSound(null, hit, SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR, SoundSource.BLOCKS, 0.7F, 0.55F);
		ItemStack tool = player.getMainHandItem();
		FELLINGS.add(new Felling(level, player, tool, logs.get(0), new ArrayDeque<>(logs), new ArrayDeque<>(leaves)));
	}

	/** Grown leaves touching the trunk and the leaves joined to them, shuffled so they drop all over the crown. */
	private static List<BlockPos> leavesOn(ServerLevel level, List<BlockPos> logs, RandomSource random) {
		List<BlockPos> found = new ArrayList<>();
		LongOpenHashSet seen = new LongOpenHashSet();
		Deque<BlockPos> queue = new ArrayDeque<>();
		for (BlockPos log : logs) {
			seen.add(log.asLong());
			for (Direction direction : Direction.values()) queue.add(log.relative(direction));
		}
		while (!queue.isEmpty() && found.size() < LEAF_LIMIT) {
			BlockPos pos = queue.poll();
			if (!seen.add(pos.asLong())) continue;
			BlockState state = level.getBlockState(pos);
			if (!(state.getBlock() instanceof LeavesBlock) || state.getValue(LeavesBlock.PERSISTENT)) continue;
			found.add(pos);
			for (Direction direction : Direction.values()) queue.add(pos.relative(direction));
		}
		Collections.shuffle(found, new java.util.Random(random.nextLong()));
		return found;
	}

	public static void tick(MinecraftServer server) {
		for (Iterator<Felling> it = FELLINGS.iterator(); it.hasNext(); ) {
			Felling felling = it.next();
			if (!felling.logs.isEmpty()) {
				ServerPlayer player = felling.player;
				// a player who logged out, died or left stops felling; the rest of the trunk stays standing, and its leaves with it
				boolean going = !player.isRemoved() && player.level() == felling.level;
				for (int i = 0; going && i < LOGS_PER_TICK && !felling.logs.isEmpty(); i++) {
					going = breakInto(felling.level, player, felling.logs.poll(), felling.tool, felling.bare, felling.dropAt);
				}
				if (!going) it.remove();
			} else if (felling.wait > 0) {
				felling.wait--;
			} else {
				for (int i = 0; i < LEAVES_PER_TICK && !felling.leaves.isEmpty(); i++) dropLeaf(felling.level, felling.leaves.poll());
				if (felling.leaves.isEmpty()) it.remove();
			}
		}
	}

	/** Falls as it would decay, saplings and apples included; a leaf another trunk still holds stays. */
	private static void dropLeaf(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.getBlock() instanceof LeavesBlock && !state.getValue(LeavesBlock.PERSISTENT) && state.getValue(LeavesBlock.DISTANCE) == LeavesBlock.DECAY_DISTANCE) {
			level.destroyBlock(pos, true);
		}
	}

	/**
	 * Breaks one block as the player would, with Fortune, Silk Touch, experience, hunger and tool wear, but the drops
	 * land where the player struck. Returns false once the tool has broken, so the rest is not taken with a bare hand;
	 * a tree begun by hand ({@code bare}) goes on to the top.
	 */
	static boolean breakInto(ServerLevel level, ServerPlayer player, BlockPos pos, ItemStack tool, boolean bare, BlockPos dropAt) {
		if (tool.isEmpty() && !bare) return false;
		BlockState state = level.getBlockState(pos);
		if (state.isAir() || player.blockActionRestricted(level, pos, player.gameMode.getGameModeForPlayer())) return true;
		BlockEntity blockEntity = level.getBlockEntity(pos);
		taking = true;
		boolean allowed;
		try {
			allowed = PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(level, player, pos, state, blockEntity);
		} finally {
			taking = false;
		}
		if (!allowed) return true;
		List<ItemStack> drops = Block.getDrops(state, level, pos, blockEntity, player, tool);
		level.destroyBlock(pos, false, player);
		for (ItemStack drop : drops) Block.popResource(level, dropAt, drop);
		state.spawnAfterBreak(level, dropAt, tool, true);
		tool.mineBlock(level, state, pos, player);
		player.awardStat(Stats.BLOCK_MINED.get(state.getBlock()));
		player.causeFoodExhaustion(0.005F);
		PlayerBlockBreakEvents.AFTER.invoker().afterBlockBreak(level, player, pos, state, blockEntity);
		return bare || !tool.isEmpty();
	}
}
