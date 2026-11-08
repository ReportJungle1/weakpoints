package ru.weakpoints.client.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.weakpoints.WeakPointsMod;
import ru.weakpoints.client.ClientWeakPoints;

/**
 * Right after a click on an ore or a trunk the block takes no mining progress for a moment, and ore with a point on
 * it takes none at all: it comes out only through its points. Returning true keeps the arm swinging.
 */
@Mixin(MultiPlayerGameMode.class)
abstract class MultiPlayerGameModeMixin {
	@Inject(method = "continueDestroyBlock", at = @At("HEAD"), cancellable = true)
	private void weakpoints$holdOff(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> cir) {
		if (WeakPointsMod.ACTIVE && ClientWeakPoints.holdsOff(pos)) cir.setReturnValue(true);
	}
}
