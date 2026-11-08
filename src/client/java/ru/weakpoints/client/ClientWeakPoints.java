package ru.weakpoints.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import ru.weakpoints.HitRules;
import ru.weakpoints.WeakPointKind;
import ru.weakpoints.WeakPointsMod;
import ru.weakpoints.net.HitPayload;
import ru.weakpoints.net.PointPayload;

/**
 * The client half of the weak points: draws the glowing marker and the outline of what will come out; reports fresh clicks with the exact spot, and plays the blow at once on a local hit instead
 * of waiting for the server, which then answers with the next point.
 */
public final class ClientWeakPoints {
	private static final RenderType MARKER = RenderType.eyes(WeakPointsMod.id("textures/misc/weak_point.png"));
	private static final float HALF_SIZE = 0.13F;
	private static final int KICK_TICKS = 4;
	private static final float OUTLINE_ALPHA = 0.3F;
	/** The outline stands this far off the blocks, so it never fights their faces for depth and shimmers. */
	private static final float OUTLINE_LIFT = 0.006F;
	/**
	 * Vanilla mining waits this long after a click on a gatherable block, so a fast pickaxe does not break the
	 * marked block in a single click; holding the key still mines it the ordinary way.
	 */
	private static final int HOLD_TICKS = 8;
	@Nullable
	private static PointPayload current;
	/** Edges of the outline as x1, y1, z1, x2, y2, z2. */
	private static List<float[]> outline = List.of();
	private static List<BlockPos> outlined = List.of();
	private static long lastHit = Long.MIN_VALUE / 2;
	private static int kick;
	@Nullable
	private static BlockPos holdPos;
	private static long holdUntil;
	private static boolean toldOnce;

	private ClientWeakPoints() {
	}

	public static void update(PointPayload payload, Minecraft minecraft) {
		if (payload.needed() == 0) {
			reset();
			return;
		}
		// a new point starts the server's swing timer
		if (payload.hits() == 0 && minecraft.level != null) lastHit = minecraft.level.getGameTime();
		if (!payload.take().equals(outlined)) {
			outlined = payload.take();
			outline = edges(outlined);
		}
		if (current == null && !toldOnce) {
			toldOnce = true;
			String key = payload.kind() == WeakPointKind.TREE.ordinal() ? "weakpoints.toast.tree" : "weakpoints.toast.ore";
			minecraft.getToasts().addToast(SystemToast.multiline(minecraft, SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
				Component.translatable("weakpoints.toast.title"), Component.translatable(key, payload.needed())));
		}
		current = payload;
	}

	public static void reset() {
		current = null;
		outline = List.of();
		outlined = List.of();
		kick = 0;
		holdPos = null;
	}

	/** What the server would light a point on; the server decides, this only spares packets and vanilla mining. */
	private static boolean gatherable(LocalPlayer player, BlockState state) {
		ItemStack tool = player.getMainHandItem();
		return state.is(ConventionalBlockTags.ORES) && tool.is(ItemTags.PICKAXES)
			|| state.is(BlockTags.LOGS) && (tool.is(ItemTags.AXES) || tool.isEmpty());
	}

	/** From Minecraft.startAttack, which runs on a fresh press only; holding keeps mining the vanilla way. */
	public static void onAttackPress(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null || player.isCreative() || player.isSpectator() || !ClientPlayNetworking.canSend(HitPayload.TYPE)) return;
		if (!(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
		BlockPos pos = hit.getBlockPos();
		BlockState state = level.getBlockState(pos);
		boolean onPoint = current != null && current.pos().equals(pos);
		if (!onPoint && !gatherable(player, state)) return;
		holdPos = pos.immutable();
		holdUntil = level.getGameTime() + HOLD_TICKS;
		if (onPoint) strike(level, hit.getLocation(), state, HitRules.swingTicks(player.getAttributeValue(Attributes.ATTACK_SPEED)));
		ClientPlayNetworking.send(new HitPayload(pos, hit.getLocation()));
	}

	/**
	 * From MultiPlayerGameMode.continueDestroyBlock: true while vanilla mining of this block should wait. Ore with a
	 * point on it never mines; the server refuses it anyway, this only spares the block flickering back.
	 */
	public static boolean holdsOff(BlockPos pos) {
		Minecraft minecraft = Minecraft.getInstance();
		if (current != null && current.kind() == WeakPointKind.ORE.ordinal() && current.pos().equals(pos)) return true;
		return holdPos != null && minecraft.level != null && minecraft.level.getGameTime() < holdUntil && holdPos.equals(pos);
	}

	private static void strike(ClientLevel level, Vec3 at, BlockState state, int swingTicks) {
		if (current == null) return;
		long now = level.getGameTime();
		if (now - lastHit < swingTicks) return;
		Vec3 point = current.point();
		if (at.distanceToSqr(point) > HitRules.TOLERANCE * HitRules.TOLERANCE) {
			level.playLocalSound(at.x, at.y, at.z, state.getSoundType().getHitSound(), SoundSource.BLOCKS, 0.4F, 0.5F, false);
			return;
		}
		lastHit = now;
		kick = KICK_TICKS;
		RandomSource random = level.random;
		Vec3 normal = Vec3.atLowerCornerOf(current.face().getNormal());
		for (int i = 0; i < 12; i++) {
			Vec3 speed = normal.scale(0.08 + random.nextDouble() * 0.12).add((random.nextDouble() - 0.5) * 0.2, random.nextDouble() * 0.12, (random.nextDouble() - 0.5) * 0.2);
			level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, state), point.x, point.y, point.z, speed.x, speed.y, speed.z);
		}
		for (int i = 0; i < 5; i++) {
			level.addParticle(ParticleTypes.CRIT, point.x, point.y, point.z, normal.x * 0.4 + (random.nextDouble() - 0.5) * 0.3, normal.y * 0.4 + 0.1, normal.z * 0.4 + (random.nextDouble() - 0.5) * 0.3);
		}
		level.playLocalSound(point.x, point.y, point.z, state.getSoundType().getHitSound(), SoundSource.BLOCKS, 1.0F, 0.75F, false);
		// the last blow rings higher
		float pitch = current.hits() + 1 >= current.needed() ? 1.8F : 1.3F;
		level.playLocalSound(point.x, point.y, point.z, SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 0.5F, pitch, false);
	}

	public static void tick(Minecraft minecraft) {
		if (kick > 0) kick--;
		// broken by plain holding, or by someone else
		if (current != null && minecraft.level != null && minecraft.level.getBlockState(current.pos()).isAir()) reset();
	}

	/** A short camera jolt on a hit, hooked into the same place the hurt tilt is applied. */
	public static void applyKick(PoseStack pose, float partialTick) {
		float strength = (kick - partialTick) / KICK_TICKS;
		if (strength <= 0) return;
		pose.mulPose(Axis.XP.rotationDegrees(-1.2F * strength));
		pose.mulPose(Axis.ZP.rotationDegrees(0.8F * strength));
	}

	private static boolean ready(Minecraft minecraft) {
		return minecraft.level != null && minecraft.player != null
			&& minecraft.level.getGameTime() - lastHit >= HitRules.swingTicks(minecraft.player.getAttributeValue(Attributes.ATTACK_SPEED));
	}

	/** The star on the block face with a soft glow round it: bright and pulsing when a blow is ready, dim while the tool swings back. */
	public static void render(WorldRenderContext context) {
		Minecraft minecraft = Minecraft.getInstance();
		if (current == null || minecraft.player == null || minecraft.level == null || !(context.consumers() instanceof MultiBufferSource.BufferSource buffers)) return;
		boolean ready = ready(minecraft);
		float time = minecraft.level.getGameTime() + context.tickCounter().getGameTimeDeltaPartialTick(false);
		float pulse = Mth.sin(time * 0.6F);
		Vec3 camera = context.camera().getPosition();
		PoseStack pose = context.matrixStack();

		if (!outline.isEmpty()) {
			pose.pushPose();
			pose.translate(-camera.x, -camera.y, -camera.z);
			drawOutline(pose.last(), buffers.getBuffer(RenderType.lines()), current.kind() == WeakPointKind.TREE.ordinal());
			pose.popPose();
			buffers.endBatch(RenderType.lines());
		}

		Vec3 point = current.point();
		pose.pushPose();
		pose.translate(point.x - camera.x, point.y - camera.y, point.z - camera.z);
		pose.mulPose(current.face().getRotation());
		pose.translate(0, 0.004, 0);
		Matrix4f matrix = pose.last().pose();
		VertexConsumer buffer = buffers.getBuffer(MARKER);
		float size = HALF_SIZE * (ready ? 1 + 0.15F * pulse : 0.7F);
		quad(buffer, matrix, size * 2.6F, ready ? (int) (55 + 20 * pulse) : 25);
		quad(buffer, matrix, size, ready ? 255 : 100);
		pose.popPose();
		buffers.endBatch(MARKER);
	}

	/** Both windings, so face culling can never hide it. */
	private static void quad(VertexConsumer buffer, Matrix4f matrix, float size, int shade) {
		vertex(buffer, matrix, -size, -size, 0, 0, shade);
		vertex(buffer, matrix, -size, size, 0, 1, shade);
		vertex(buffer, matrix, size, size, 1, 1, shade);
		vertex(buffer, matrix, size, -size, 1, 0, shade);
		vertex(buffer, matrix, size, -size, 1, 0, shade);
		vertex(buffer, matrix, size, size, 1, 1, shade);
		vertex(buffer, matrix, -size, size, 0, 1, shade);
		vertex(buffer, matrix, -size, -size, 0, 0, shade);
	}

	private static void vertex(VertexConsumer buffer, Matrix4f matrix, float x, float z, float u, float v, int shade) {
		buffer.addVertex(matrix, x, 0, z).setColor(shade, shade, shade, 255).setUv(u, v)
			.setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(0, 1, 0);
	}

	/** Steady and faint: a pulsing hairline on the block edges read as flicker. */
	private static void drawOutline(PoseStack.Pose pose, VertexConsumer lines, boolean tree) {
		float r = tree ? 0.6F : 0.95F, g = tree ? 0.85F : 0.75F, b = tree ? 0.45F : 0.35F, alpha = OUTLINE_ALPHA;
		for (float[] e : outline) {
			float nx = e[3] - e[0], ny = e[4] - e[1], nz = e[5] - e[2];
			lines.addVertex(pose, e[0], e[1], e[2]).setColor(r, g, b, alpha).setNormal(pose, nx, ny, nz);
			lines.addVertex(pose, e[3], e[4], e[5]).setColor(r, g, b, alpha).setNormal(pose, nx, ny, nz);
		}
	}

	/**
	 * The outer edges of a group of blocks, drawn as one shape rather than a grid of boxes: an edge shows where its
	 * four blocks are not a flat wall (one or three of them taken, or two across a diagonal). Each edge is lifted
	 * a hair towards the empty blocks round it.
	 */
	static List<float[]> edges(List<BlockPos> blocks) {
		LongOpenHashSet set = new LongOpenHashSet();
		for (BlockPos pos : blocks) set.add(pos.asLong());
		LongOpenHashSet[] done = {new LongOpenHashSet(), new LongOpenHashSet(), new LongOpenHashSet()};
		List<float[]> edges = new ArrayList<>();
		Direction.Axis[] axes = Direction.Axis.values();
		for (BlockPos pos : blocks) {
			for (int a = 0; a < 3; a++) {
				BlockPos along = BlockPos.ZERO.relative(axes[a], 1);
				BlockPos u = BlockPos.ZERO.relative(axes[(a + 1) % 3], 1);
				BlockPos v = BlockPos.ZERO.relative(axes[(a + 2) % 3], 1);
				for (int du = 0; du < 2; du++) {
					for (int dv = 0; dv < 2; dv++) {
						BlockPos base = pos.offset(u.multiply(du)).offset(v.multiply(dv));
						if (!done[a].add(base.asLong())) continue;
						boolean c00 = set.contains(base.asLong()), c10 = set.contains(base.subtract(u).asLong());
						boolean c01 = set.contains(base.subtract(v).asLong()), c11 = set.contains(base.subtract(u).subtract(v).asLong());
						int n = (c00 ? 1 : 0) + (c10 ? 1 : 0) + (c01 ? 1 : 0) + (c11 ? 1 : 0);
						if (n == 1 || n == 3 || n == 2 && c00 == c11) {
							// the empty blocks' centres, summed: c00 lies at +u+v from the edge, c10 at -u+v, c01 at +u-v, c11 at -u-v
							int su = (c00 ? 0 : 1) - (c10 ? 0 : 1) + (c01 ? 0 : 1) - (c11 ? 0 : 1);
							int sv = (c00 ? 0 : 1) + (c10 ? 0 : 1) - (c01 ? 0 : 1) - (c11 ? 0 : 1);
							float lift = su == 0 && sv == 0 ? 0 : OUTLINE_LIFT / Mth.sqrt(su * su + sv * sv);
							float ox = (u.getX() * su + v.getX() * sv) * lift, oy = (u.getY() * su + v.getY() * sv) * lift, oz = (u.getZ() * su + v.getZ() * sv) * lift;
							BlockPos end = base.offset(along);
							edges.add(new float[] {base.getX() + ox, base.getY() + oy, base.getZ() + oz, end.getX() + ox, end.getY() + oy, end.getZ() + oz});
						}
					}
				}
			}
		}
		return edges;
	}
}
