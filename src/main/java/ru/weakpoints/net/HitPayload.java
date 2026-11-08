package ru.weakpoints.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;
import ru.weakpoints.WeakPointsMod;

/** A fresh press of the attack key on a block, with the exact spot the crosshair was on. */
public record HitPayload(BlockPos pos, Vec3 at) implements CustomPacketPayload {
	public static final Type<HitPayload> TYPE = new Type<>(WeakPointsMod.id("hit"));
	public static final StreamCodec<RegistryFriendlyByteBuf, HitPayload> CODEC = StreamCodec.ofMember(HitPayload::write, HitPayload::read);

	private static HitPayload read(RegistryFriendlyByteBuf buf) {
		return new HitPayload(buf.readBlockPos(), new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()));
	}

	private void write(RegistryFriendlyByteBuf buf) {
		buf.writeBlockPos(pos);
		buf.writeDouble(at.x);
		buf.writeDouble(at.y);
		buf.writeDouble(at.z);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
