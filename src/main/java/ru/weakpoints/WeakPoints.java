package ru.weakpoints;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ru.weakpoints.net.PointPayload;

/**
 * Rust-style gathering. The first click on a natural ore or trunk lights a point on the block; each blow on the point,
 * one per swing, moves it; after a few blows the whole vein or tree is taken. Such ore comes out no other way; a trunk
 * held at still comes down log by log.
 */
public final class WeakPoints {
	private static final double SPREAD = 0.3;
	private static final double MIN_MOVE = 0.3;
	private static final int IDLE_TICKS = 300;
	private static final double LEAVE_DISTANCE_SQR = 8 * 8;
	// keyed by the player object: a respawn makes a new one, so death drops the point on its own
	private static final Map<ServerPlayer, Session> SESSIONS = new HashMap<>();

	private WeakPoints() {
	}

	public enum Result { STARTED, HIT, DONE, MISSED, TOO_EARLY, NOT_ELIGIBLE }

	public static final class Session {
		private final WeakPointKind kind;
		private final ResourceKey<Level> dimension;
		private final BlockPos pos;
		private final BlockState state;
		private final int needed;
		private final List<BlockPos> take;
		private Vec3 point = Vec3.ZERO;
		private Direction face = Direction.UP;
		private int hits;
		private int misses;
		private long lastHit;

		private Session(WeakPointKind kind, ResourceKey<Level> dimension, BlockPos pos, BlockState state, int needed, List<BlockPos> take, long now) {
			this.kind = kind;
			this.dimension = dimension;
			this.pos = pos;
			this.state = state;
			this.needed = needed;
			this.take = take;
			this.lastHit = now;
		}

		public WeakPointKind kind() {
			return kind;
		}

		public Vec3 point() {
			return point;
		}

		public int hits() {
			return hits;
		}

		public int needed() {
			return needed;
		}

		public List<BlockPos> take() {
			return take;
		}
	}

	@Nullable
	public static Session session(ServerPlayer player) {
		return SESSIONS.get(player);
	}

	/** A fresh press of the attack key at an exact spot on a block. */
	public static Result hit(ServerPlayer player, BlockPos pos, Vec3 at) {
		ServerLevel level = player.serverLevel();
		if (player.isCreative() || player.isSpectator() || !player.canInteractWithBlock(pos, 1.0) || !new AABB(pos).inflate(0.05).contains(at)) {
			return Result.NOT_ELIGIBLE;
		}
		BlockState state = level.getBlockState(pos);
		long now = level.getGameTime();
		Session session = SESSIONS.get(player);
		if (session != null && (!session.pos.equals(pos) || session.state != state)) {
			clear(player);
			session = null;
		}
		if (session == null) return start(player, level, pos, state, at, now);
		if (now - session.lastHit < swingTicks(player)) return Result.TOO_EARLY;
		if (at.distanceToSqr(session.point) > HitRules.TOLERANCE * HitRules.TOLERANCE) {
			level.playSound(player, pos, state.getSoundType().getHitSound(), SoundSource.BLOCKS, 0.4F, 0.5F);
			session.misses++;
			return Result.MISSED;
		}
		session.hits++;
		session.lastHit = now;
		impactForOthers(level, player, session.point, state);
		if (session.hits >= session.needed) {
			clear(player);
			finish(level, player, session);
			return Result.DONE;
		}
		pickPoint(level, player, session, null);
		send(player, session);
		return Result.HIT;
	}

	public static int swingTicks(ServerPlayer player) {
		return HitRules.swingTicks(player.getAttributeValue(Attributes.ATTACK_SPEED));
	}

	private static Result start(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state, Vec3 at, long now) {
		if (!level.getGameRules().getBoolean(WeakPointsMod.ENABLED)) return Result.NOT_ELIGIBLE;
		WeakPointKind kind = gatherKind(player, level, pos, state);
		if (kind == null) return Result.NOT_ELIGIBLE;
		ItemStack tool = player.getMainHandItem();
		int needed = kind == WeakPointKind.TREE && !tool.is(ItemTags.AXES) ? HitRules.HAND_TREE_HITS : HitRules.gatherHits(tool.getDestroySpeed(state));
		List<BlockPos> take = kind == WeakPointKind.TREE ? Harvest.treeLogs(level, pos) : exposed(level, Harvest.veinOre(level, pos, state));
		Session session = new Session(kind, level.dimension(), pos.immutable(), state, needed, take, now);
		pickPoint(level, player, session, nearestFace(pos, at));
		SESSIONS.put(player, session);
		send(player, session);
		return Result.STARTED;
	}

	/** Only ore the player could already see: outlining buried ore would turn every vein into an x-ray. */
	private static List<BlockPos> exposed(ServerLevel level, List<BlockPos> vein) {
		List<BlockPos> open = new ArrayList<>();
		for (BlockPos pos : vein) {
			for (Direction direction : Direction.values()) {
				BlockPos side = pos.relative(direction);
				if (!level.getBlockState(side).isSolidRender(level, side)) {
					open.add(pos);
					break;
				}
			}
		}
		return open;
	}

	/** A natural ore the player's pickaxe could take whole: breaking it block by block is refused. */
	public static boolean locked(ServerPlayer player, BlockPos pos, BlockState state) {
		ServerLevel level = player.serverLevel();
		return !Harvest.taking && !player.isCreative() && state.is(ConventionalBlockTags.ORES)
			&& level.getGameRules().getBoolean(WeakPointsMod.ENABLED) && gatherKind(player, level, pos, state) == WeakPointKind.ORE;
	}

	@Nullable
	private static WeakPointKind gatherKind(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state) {
		ItemStack tool = player.getMainHandItem();
		// your own log walls and ore displays come apart the ordinary way
		if (PlacedBlocks.isPlaced(level, pos)) return null;
		if (state.is(ConventionalBlockTags.ORES) && tool.is(ItemTags.PICKAXES) && player.hasCorrectToolForDrops(state)) return WeakPointKind.ORE;
		// bare hands fell a tree too, on twelve blows instead of an axe's five or fewer
		if (state.is(BlockTags.LOGS) && (tool.is(ItemTags.AXES) || tool.isEmpty()) && Harvest.isTree(level, pos)) return WeakPointKind.TREE;
		return null;
	}

	private static void finish(ServerLevel level, ServerPlayer player, Session session) {
		switch (session.kind) {
			case ORE -> Harvest.vein(level, player, session.pos, session.state);
			case TREE -> Harvest.fell(level, player, session.pos);
		}
	}

	/** On a face turned to the player and open to the air, away from where the last point was. */
	private static void pickPoint(ServerLevel level, ServerPlayer player, Session session, @Nullable Direction preferred) {
		Vec3 eye = player.getEyePosition();
		Vec3 center = Vec3.atCenterOf(session.pos);
		List<Direction> faces = new ArrayList<>();
		for (Direction direction : Direction.values()) {
			BlockPos side = session.pos.relative(direction);
			Vec3 normal = Vec3.atLowerCornerOf(direction.getNormal());
			boolean open = !level.getBlockState(side).isSolidRender(level, side);
			if (open && normal.dot(eye.subtract(center.add(normal.scale(0.5)))) > 0) faces.add(direction);
		}
		Direction face = preferred != null && (faces.contains(preferred) || faces.isEmpty()) ? preferred
			: faces.isEmpty() ? session.face : faces.get(level.random.nextInt(faces.size()));
		Vec3 normal = Vec3.atLowerCornerOf(face.getNormal());
		Vec3 u = face.getAxis() == Direction.Axis.X ? new Vec3(0, 0, 1) : new Vec3(1, 0, 0);
		Vec3 v = face.getAxis() == Direction.Axis.Y ? new Vec3(0, 0, 1) : new Vec3(0, 1, 0);
		Vec3 faceCenter = center.add(normal.scale(0.5));
		Vec3 point = faceCenter;
		for (int attempt = 0; attempt < 8; attempt++) {
			point = faceCenter.add(u.scale((level.random.nextDouble() * 2 - 1) * SPREAD)).add(v.scale((level.random.nextDouble() * 2 - 1) * SPREAD));
			if (session.hits == 0 && preferred != null || point.distanceTo(session.point) >= MIN_MOVE) break;
		}
		session.point = point;
		session.face = face;
	}

	private static Direction nearestFace(BlockPos pos, Vec3 at) {
		Vec3 local = at.subtract(Vec3.atCenterOf(pos));
		return Direction.getNearest(local.x, local.y, local.z);
	}

	/** The hitter already saw and heard the blow on their side; everyone else gets it from here. */
	private static void impactForOthers(ServerLevel level, ServerPlayer player, Vec3 point, BlockState state) {
		level.playSound(player, BlockPos.containing(point), state.getSoundType().getHitSound(), SoundSource.BLOCKS, 1.0F, 0.75F);
		for (ServerPlayer other : level.players()) {
			if (other != player) level.sendParticles(other, new BlockParticleOption(ParticleTypes.BLOCK, state), false, point.x, point.y, point.z, 8, 0.05, 0.05, 0.05, 0.15);
		}
	}

	private static void send(ServerPlayer player, Session session) {
		if (ServerPlayNetworking.canSend(player, PointPayload.TYPE)) {
			ServerPlayNetworking.send(player, new PointPayload(session.pos, session.point, session.face, session.kind.ordinal(), session.hits, session.needed, session.take));
		}
	}

	public static void clear(ServerPlayer player) {
		if (SESSIONS.remove(player) != null && ServerPlayNetworking.canSend(player, PointPayload.TYPE)) {
			ServerPlayNetworking.send(player, PointPayload.clear());
		}
	}

	public static void forget(ServerPlayer player) {
		SESSIONS.remove(player);
	}

	/** Drops points the player walked away from, turned their back on for long, or whose block changed under them. */
	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % 20 != 0 || SESSIONS.isEmpty()) return;
		List<ServerPlayer> stale = new ArrayList<>();
		for (Iterator<Map.Entry<ServerPlayer, Session>> it = SESSIONS.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<ServerPlayer, Session> entry = it.next();
			Session session = entry.getValue();
			ServerPlayer player = entry.getKey();
			if (player.isRemoved()) {
				it.remove();
				continue;
			}
			ServerLevel level = player.serverLevel();
			boolean gone = level.dimension() != session.dimension || player.distanceToSqr(Vec3.atCenterOf(session.pos)) > LEAVE_DISTANCE_SQR
				|| level.getGameTime() - session.lastHit > IDLE_TICKS || level.getBlockState(session.pos) != session.state;
			if (gone) stale.add(player);
		}
		for (ServerPlayer player : stale) clear(player);
	}
}
