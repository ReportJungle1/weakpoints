package ru.weakpoints.mixin;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.weakpoints.PlacedBlocks;
import ru.weakpoints.WeakPointsMod;

/** Every block set down from an item, by a player or a dispenser, goes through placeBlock. */
@Mixin(BlockItem.class)
abstract class BlockItemMixin {
	@Inject(method = "placeBlock", at = @At("RETURN"))
	private void weakpoints$markPlaced(BlockPlaceContext context, BlockState state, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() && WeakPointsMod.ACTIVE && !context.getLevel().isClientSide && PlacedBlocks.tracked(state)) {
			PlacedBlocks.mark(context.getLevel(), context.getClickedPos());
		}
	}
}
