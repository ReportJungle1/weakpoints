package ru.weakpoints.client.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.weakpoints.WeakPointsMod;
import ru.weakpoints.client.ClientWeakPoints;

/** startAttack runs once per press of the attack key, unlike AttackBlockCallback, which also fires while it is held. */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	@Inject(method = "startAttack", at = @At("HEAD"))
	private void weakpoints$click(CallbackInfoReturnable<Boolean> cir) {
		if (WeakPointsMod.ACTIVE) ClientWeakPoints.onAttackPress((Minecraft) (Object) this);
	}
}
