package ru.weakpoints.gametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import ru.weakpoints.PlacedBlocks;
import ru.weakpoints.WeakPoints;
import ru.weakpoints.WeakPoints.Result;
import ru.weakpoints.WeakPointsMod;

/** Blows are spaced 25 ticks apart, longer than any tool's swing, so the tests never race the cooldown. */
public final class GatherGameTests implements FabricGameTest {
	private static final int SWING = 25;

	@GameTest(template = EMPTY_STRUCTURE, batch = "wp_vein", timeoutTicks = 400)
	public void oreVeinComesOutWhole(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos first = natural(helper, new BlockPos(2, 2, 2), Blocks.IRON_ORE);
		BlockPos side = natural(helper, new BlockPos(3, 2, 2), Blocks.IRON_ORE);
		BlockPos corner = natural(helper, new BlockPos(4, 3, 3), Blocks.IRON_ORE);
		// where stone turns to deepslate the vein goes on
		BlockPos deep = natural(helper, new BlockPos(4, 1, 2), Blocks.DEEPSLATE_IRON_ORE);
		BlockPos apart = natural(helper, new BlockPos(7, 2, 2), Blocks.IRON_ORE);
		ServerPlayer player = player(helper, new BlockPos(2, 2, 0), Items.STONE_PICKAXE);
		BlockPos display = placeByPlayer(helper, player, first, Direction.SOUTH, Items.IRON_ORE);

		helper.assertTrue(PlacedBlocks.isPlaced(level, display) && !PlacedBlocks.isPlaced(level, first), "the ore the player set down must count as placed, and only it");
		helper.assertTrue(WeakPoints.hit(player, display, northFace(display)) == Result.NOT_ELIGIBLE, "placed ore gives no weak points");
		helper.assertTrue(WeakPoints.hit(player, first, northFace(first)) == Result.STARTED, "the first click must light a point");
		WeakPoints.Session session = WeakPoints.session(player);
		helper.assertTrue(session != null && session.needed() == 5, "a stone pickaxe needs 5 blows");
		helper.assertTrue(session.take().size() == 4, "the outline must hold the four ore blocks of the vein, holds " + session.take().size());
		helper.assertTrue(WeakPoints.hit(player, first, session.point()) == Result.TOO_EARLY, "no blow before the tool swings back");

		helper.startSequence()
			.thenExecuteAfter(SWING, () -> helper.assertTrue(WeakPoints.hit(player, first, Vec3.atLowerCornerOf(first)) == Result.MISSED, "a blow beside the point must miss"))
			.thenExecuteAfter(SWING, () -> blow(helper, player, first, Result.HIT))
			.thenExecuteAfter(SWING, () -> blow(helper, player, first, Result.HIT))
			.thenExecuteAfter(SWING, () -> blow(helper, player, first, Result.HIT))
			.thenExecuteAfter(SWING, () -> blow(helper, player, first, Result.HIT))
			.thenExecuteAfter(SWING, () -> blow(helper, player, first, Result.DONE))
			.thenExecute(() -> {
				for (BlockPos pos : List.of(first, side, corner, deep)) helper.assertTrue(level.getBlockState(pos).isAir(), "the vein must come out at " + pos);
				helper.assertTrue(level.getBlockState(display).is(Blocks.IRON_ORE), "placed ore is not part of a vein");
				helper.assertTrue(level.getBlockState(apart).is(Blocks.IRON_ORE), "ore that does not touch the vein stays");
				helper.assertTrue(dropped(level, first, Items.RAW_IRON) == 4, "four raw iron must land where the blows fell, found " + dropped(level, first, Items.RAW_IRON));
				helper.assertTrue(player.getMainHandItem().getDamageValue() == 4, "the pickaxe wears one point per block");
				helper.assertTrue(player.getFoodData().getExhaustionLevel() > 0, "taking a vein makes the player hungry as mining it would");
				remove(player);
			})
			.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = "wp_vein_tool", timeoutTicks = 300)
	public void brokenToolStopsTheVein(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos first = natural(helper, new BlockPos(2, 2, 2), Blocks.COAL_ORE);
		for (int x = 3; x <= 6; x++) natural(helper, new BlockPos(x, 2, 2), Blocks.COAL_ORE);
		ServerPlayer player = player(helper, new BlockPos(2, 2, 0), Items.STONE_PICKAXE);
		ItemStack pickaxe = player.getMainHandItem();
		pickaxe.setDamageValue(pickaxe.getMaxDamage() - 2);

		WeakPoints.hit(player, first, northFace(first));
		helper.startSequence()
			.thenExecuteAfter(SWING, () -> blow(helper, player, first, Result.HIT))
			.thenExecuteAfter(SWING, () -> blow(helper, player, first, Result.HIT))
			.thenExecuteAfter(SWING, () -> blow(helper, player, first, Result.HIT))
			.thenExecuteAfter(SWING, () -> blow(helper, player, first, Result.HIT))
			.thenExecuteAfter(SWING, () -> blow(helper, player, first, Result.DONE))
			.thenExecute(() -> {
				int left = 0;
				for (int x = 2; x <= 6; x++) if (level.getBlockState(helper.absolutePos(new BlockPos(x, 2, 2))).is(Blocks.COAL_ORE)) left++;
				helper.assertTrue(player.getMainHandItem().isEmpty(), "the pickaxe had two uses left and must have broken");
				helper.assertTrue(left == 3, "a broken pickaxe must stop the vein after two blocks, " + left + " left");
				remove(player);
			})
			.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = "wp_tree", timeoutTicks = 400)
	public void treeFallsAndItsLeavesWithIt(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		for (int y = 1; y <= 4; y++) natural(helper, new BlockPos(2, y, 2), Blocks.OAK_LOG);
		for (int x = 1; x <= 3; x++) {
			for (int z = 1; z <= 3; z++) {
				if (x != 2 || z != 2) leaf(helper, new BlockPos(x, 4, z));
			}
		}
		leaf(helper, new BlockPos(2, 5, 2));
		// a tree beside it that nobody fells keeps its crown
		for (int y = 1; y <= 3; y++) natural(helper, new BlockPos(6, y, 6), Blocks.OAK_LOG);
		BlockPos otherLeaf = leaf(helper, new BlockPos(6, 4, 6));
		// a village wall: natural logs, but no leaves grew on them
		for (int y = 1; y <= 3; y++) natural(helper, new BlockPos(6, y, 2), Blocks.OAK_LOG);
		ServerPlayer player = player(helper, new BlockPos(3, 1, 0), Items.IRON_AXE);
		BlockPos trunk = helper.absolutePos(new BlockPos(2, 2, 2));
		BlockPos house = helper.absolutePos(new BlockPos(6, 2, 2));

		helper.assertTrue(WeakPoints.hit(player, house, northFace(house)) == Result.NOT_ELIGIBLE, "village logs are not a tree");
		helper.assertTrue(WeakPoints.hit(player, trunk, northFace(trunk)) == Result.STARTED, "the trunk must light a point");
		helper.assertTrue(WeakPoints.session(player).needed() == 4, "an iron axe needs 4 blows");
		helper.assertTrue(WeakPoints.session(player).take().size() == 4, "the outline must hold the four logs");
		helper.startSequence()
			.thenExecuteAfter(SWING, () -> blow(helper, player, trunk, Result.HIT))
			.thenExecuteAfter(SWING, () -> blow(helper, player, trunk, Result.HIT))
			.thenExecuteAfter(SWING, () -> blow(helper, player, trunk, Result.HIT))
			.thenExecuteAfter(SWING, () -> blow(helper, player, trunk, Result.DONE))
			// the trunk crumbles over a few ticks
			.thenExecuteAfter(10, () -> {
				for (int y = 1; y <= 4; y++) helper.assertTrue(level.getBlockState(helper.absolutePos(new BlockPos(2, y, 2))).isAir(), "the whole trunk must fall, log " + y + " stands");
				for (int y = 1; y <= 3; y++) helper.assertTrue(level.getBlockState(helper.absolutePos(new BlockPos(6, y, 2))).is(Blocks.OAK_LOG), "the house must stand");
				BlockPos stump = helper.absolutePos(new BlockPos(2, 1, 2));
				helper.assertTrue(dropped(level, stump, Items.OAK_LOG) == 4, "four logs must land at the stump, found " + dropped(level, stump, Items.OAK_LOG));
			})
			// then the crown comes down, not the neighbour's
			.thenExecuteAfter(40, () -> {
				for (int x = 1; x <= 3; x++) {
					for (int z = 1; z <= 3; z++) {
						BlockPos pos = helper.absolutePos(new BlockPos(x, 4, z));
						helper.assertTrue(level.getBlockState(pos).isAir(), "the leaves of the felled tree must fall, " + pos + " is " + level.getBlockState(pos));
					}
				}
				helper.assertTrue(level.getBlockState(otherLeaf).is(Blocks.OAK_LEAVES), "the standing tree keeps its leaves");
				remove(player);
			})
			.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = "wp_tree_hand", timeoutTicks = 500)
	public void aTreeByHandTakesTwelveBlows(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		for (int y = 1; y <= 3; y++) natural(helper, new BlockPos(4, y, 3), Blocks.OAK_LOG);
		leaf(helper, new BlockPos(4, 4, 3));
		ServerPlayer player = player(helper, new BlockPos(5, 1, 1), Items.AIR);
		BlockPos trunk = helper.absolutePos(new BlockPos(4, 2, 3));
		helper.assertTrue(WeakPoints.hit(player, trunk, northFace(trunk)) == Result.STARTED, "bare hands must light a point on a tree too");
		helper.assertTrue(WeakPoints.session(player).needed() == 12, "and need 12 blows, needed " + WeakPoints.session(player).needed());
		// all twelve: the tree must come down, the hand being no tool that broke halfway
		var sequence = helper.startSequence();
		for (int i = 1; i < 12; i++) sequence.thenExecuteAfter(SWING, () -> blow(helper, player, trunk, Result.HIT));
		sequence.thenExecuteAfter(SWING, () -> blow(helper, player, trunk, Result.DONE))
			.thenExecuteAfter(10, () -> {
				for (int y = 1; y <= 3; y++) helper.assertTrue(level.getBlockState(helper.absolutePos(new BlockPos(4, y, 3))).isAir(), "felled by hand, log " + y + " still stands");
				remove(player);
			})
			.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = "wp_grown", timeoutTicks = 100)
	public void grownAndNetherTreesAreTreesPlacedLogsAreNot(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = player(helper, new BlockPos(3, 1, 0), Items.IRON_AXE);

		helper.setBlock(new BlockPos(1, 1, 3), Blocks.DIRT);
		BlockPos sapling = helper.absolutePos(new BlockPos(1, 2, 3));
		level.setBlockAndUpdate(sapling, Blocks.OAK_SAPLING.defaultBlockState());
		RandomSource random = RandomSource.create(1);
		for (int i = 0; i < 2 && level.getBlockState(sapling).is(Blocks.OAK_SAPLING); i++) {
			((SaplingBlock) Blocks.OAK_SAPLING).advanceTree(level, sapling, level.getBlockState(sapling), random);
		}
		helper.assertTrue(level.getBlockState(sapling).is(Blocks.OAK_LOG), "the sapling must have grown, it is " + level.getBlockState(sapling));
		helper.assertTrue(!PlacedBlocks.isPlaced(level, sapling.above()), "a grown trunk is no build");
		helper.assertTrue(WeakPoints.hit(player, sapling, northFace(sapling)) == Result.STARTED, "a tree grown from a sapling must light a point");
		WeakPoints.forget(player);

		// a crimson fungus: a stem under wart blocks
		BlockPos stem = natural(helper, new BlockPos(5, 1, 3), Blocks.CRIMSON_STEM);
		natural(helper, new BlockPos(5, 2, 3), Blocks.CRIMSON_STEM);
		helper.setBlock(new BlockPos(5, 3, 3), Blocks.NETHER_WART_BLOCK);
		helper.assertTrue(WeakPoints.hit(player, stem, northFace(stem)) == Result.STARTED, "a nether fungus must light a point");
		WeakPoints.forget(player);

		// a log the player set beside a standing trunk is the player's, and stays out of the tree
		BlockPos stump = natural(helper, new BlockPos(3, 1, 5), Blocks.OAK_LOG);
		natural(helper, new BlockPos(3, 2, 5), Blocks.OAK_LOG);
		leaf(helper, new BlockPos(3, 3, 5));
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
		BlockPos placed = placeByPlayer(helper, player, stump, Direction.EAST, Items.OAK_LOG);
		helper.assertTrue(WeakPoints.hit(player, placed, northFace(placed)) == Result.NOT_ELIGIBLE, "a placed log gives no weak points");
		helper.assertTrue(WeakPoints.hit(player, stump, northFace(stump)) == Result.STARTED, "the trunk next to it still does");
		helper.assertTrue(WeakPoints.session(player).take().size() == 2, "without the placed log, take " + WeakPoints.session(player).take().size());
		remove(player);
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = "wp_rule", timeoutTicks = 100)
	public void theGameRuleSwitchesItOff(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		for (int y = 1; y <= 3; y++) natural(helper, new BlockPos(4, y, 3), Blocks.OAK_LOG);
		leaf(helper, new BlockPos(4, 4, 3));
		ServerPlayer player = player(helper, new BlockPos(5, 1, 1), Items.IRON_AXE);
		BlockPos trunk = helper.absolutePos(new BlockPos(4, 2, 3));
		// the rule is shared by the tests alongside: turned and turned back within this tick
		level.getGameRules().getRule(WeakPointsMod.ENABLED).set(false, level.getServer());
		Result off = WeakPoints.hit(player, trunk, northFace(trunk));
		level.getGameRules().getRule(WeakPointsMod.ENABLED).set(true, level.getServer());
		helper.assertTrue(off == Result.NOT_ELIGIBLE, "with the rule off a tree lights no mark, got " + off);
		remove(player);
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = "wp_lock", timeoutTicks = 100)
	public void oreBreaksOnlyThroughItsPoints(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos ore = natural(helper, new BlockPos(2, 2, 2), Blocks.IRON_ORE);
		ServerPlayer player = player(helper, new BlockPos(2, 2, 0), Items.IRON_PICKAXE);
		BlockPos display = placeByPlayer(helper, player, ore, Direction.SOUTH, Items.IRON_ORE);
		helper.assertTrue(WeakPoints.locked(player, ore, level.getBlockState(ore)), "natural ore under a fitting pickaxe must not break block by block");
		helper.assertTrue(!WeakPoints.locked(player, display, level.getBlockState(display)), "placed ore breaks the ordinary way");
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WOODEN_PICKAXE));
		helper.assertTrue(!WeakPoints.locked(player, ore, level.getBlockState(ore)), "a pickaxe that gets nothing from it may still dig through");
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
		level.getGameRules().getRule(WeakPointsMod.ENABLED).set(false, level.getServer());
		boolean off = WeakPoints.locked(player, ore, level.getBlockState(ore));
		level.getGameRules().getRule(WeakPointsMod.ENABLED).set(true, level.getServer());
		helper.assertTrue(!off, "with the rule off ore breaks the ordinary way");
		remove(player);
		helper.succeed();
	}

	private static void blow(GameTestHelper helper, ServerPlayer player, BlockPos pos, Result expected) {
		WeakPoints.Session session = WeakPoints.session(player);
		helper.assertTrue(session != null, "the point vanished before the blow on " + pos);
		Result result = WeakPoints.hit(player, pos, session.point());
		helper.assertTrue(result == expected, "expected " + expected + ", got " + result);
	}

	/** The vanilla mock player is always in creative, where points never appear; this one plays survival and never joins. */
	private static ServerPlayer player(GameTestHelper helper, BlockPos rel, Item held) {
		ServerLevel level = helper.getLevel();
		CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "weakpoints-gatherer"), false);
		ServerPlayer player = new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
		Connection connection = new Connection(PacketFlow.SERVERBOUND);
		new EmbeddedChannel(connection);
		player.connection = new ServerGamePacketListenerImpl(level.getServer(), connection, player, cookie);
		player.setGameMode(GameType.SURVIVAL);
		BlockPos at = helper.absolutePos(rel);
		player.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(held));
		return player;
	}

	private static void remove(ServerPlayer player) {
		WeakPoints.forget(player);
	}

	private static Vec3 northFace(BlockPos pos) {
		return Vec3.atCenterOf(pos).add(0, 0, -0.5);
	}

	private static int dropped(ServerLevel level, BlockPos pos, Item item) {
		return level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(1.5)).stream()
			.filter(entity -> entity.getItem().is(item)).mapToInt(entity -> entity.getItem().getCount()).sum();
	}

	private static BlockPos natural(GameTestHelper helper, BlockPos rel, Block block) {
		BlockPos pos = helper.absolutePos(rel);
		helper.getLevel().setBlockAndUpdate(pos, block.defaultBlockState());
		return pos;
	}

	/** Grown leaves, which then work out their distance to a trunk as they would in the wild. */
	private static BlockPos leaf(GameTestHelper helper, BlockPos rel) {
		BlockPos pos = helper.absolutePos(rel);
		helper.getLevel().setBlockAndUpdate(pos, Blocks.OAK_LEAVES.defaultBlockState());
		helper.getLevel().scheduleTick(pos, Blocks.OAK_LEAVES, 1);
		return pos;
	}

	/** Through the item, as a real player would, against a face of {@code against}; gives the tool back after. */
	private static BlockPos placeByPlayer(GameTestHelper helper, ServerPlayer player, BlockPos against, Direction face, Item item) {
		ItemStack tool = player.getMainHandItem();
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
		Vec3 at = Vec3.atCenterOf(against).add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5));
		player.getMainHandItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, new BlockHitResult(at, face, against, false)));
		player.setItemInHand(InteractionHand.MAIN_HAND, tool);
		BlockPos placed = against.relative(face);
		helper.assertTrue(helper.getLevel().getBlockState(placed).is(Block.byItem(item)), "the player must have placed " + item + " at " + placed);
		return placed;
	}
}
