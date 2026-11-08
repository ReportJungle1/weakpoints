package ru.weakpoints.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.weakpoints.PlacedBlocks;
import ru.weakpoints.WeakPointsMod;

@Mixin(LevelChunk.class)
abstract class LevelChunkMixin {
	@Shadow
	@Final
	Level level;

	@Inject(method = "setBlockState", at = @At("RETURN"))
	private void weakpoints$forgetPlaced(BlockPos pos, BlockState state, boolean moved, CallbackInfoReturnable<BlockState> cir) {
		BlockState before = cir.getReturnValue();
		// null means nothing changed
		if (before != null && WeakPointsMod.ACTIVE && !level.isClientSide) PlacedBlocks.changed((LevelChunk) (Object) this, pos, before, state);
	}
}
