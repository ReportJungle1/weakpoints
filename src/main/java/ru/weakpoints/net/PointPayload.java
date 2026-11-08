package ru.weakpoints.net;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;
import ru.weakpoints.WeakPointsMod;

/**
 * Where the glowing point on a block is now, and the blocks that will come out with it ({@code take}: the whole tree,
 * or only the ore of the vein already open to the air, so no buried ore is given away). {@code needed == 0} clears it.
 */
public record PointPayload(BlockPos pos, Vec3 point, Direction face, int kind, int hits, int needed, List<BlockPos> take) implements CustomPacketPayload {
	public static final Type<PointPayload> TYPE = new Type<>(WeakPointsMod.id("point"));
	public static final StreamCodec<RegistryFriendlyByteBuf, PointPayload> CODEC = StreamCodec.ofMember(PointPayload::write, PointPayload::read);
	private static final int MAX_TAKE = 2048;

	public static PointPayload clear() {
		return new PointPayload(BlockPos.ZERO, Vec3.ZERO, Direction.UP, 0, 0, 0, List.of());
	}

	private static PointPayload read(RegistryFriendlyByteBuf buf) {
		BlockPos pos = buf.readBlockPos();
		Vec3 point = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
		Direction face = buf.readEnum(Direction.class);
		int kind = buf.readVarInt(), hits = buf.readVarInt(), needed = buf.readVarInt();
		int count = buf.readVarInt();
		if (count < 0 || count > MAX_TAKE) throw new IllegalArgumentException("weak point outline of " + count + " blocks");
		List<BlockPos> take = new ArrayList<>(count);
		for (int i = 0; i < count; i++) take.add(BlockPos.of(buf.readLong()));
		return new PointPayload(pos, point, face, kind, hits, needed, take);
	}

	private void write(RegistryFriendlyByteBuf buf) {
		buf.writeBlockPos(pos);
		buf.writeDouble(point.x);
		buf.writeDouble(point.y);
		buf.writeDouble(point.z);
		buf.writeEnum(face);
		buf.writeVarInt(kind);
		buf.writeVarInt(hits);
		buf.writeVarInt(needed);
		int count = Math.min(take.size(), MAX_TAKE);
		buf.writeVarInt(count);
		for (int i = 0; i < count; i++) buf.writeLong(take.get(i).asLong());
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
