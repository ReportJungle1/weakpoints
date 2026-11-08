package ru.weakpoints.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.weakpoints.WeakPointsMod;
import ru.weakpoints.client.ClientWeakPoints;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
	@Inject(method = "bobHurt", at = @At("HEAD"))
	private void weakpoints$impactKick(PoseStack pose, float partialTick, CallbackInfo ci) {
		if (WeakPointsMod.ACTIVE) ClientWeakPoints.applyKick(pose, partialTick);
	}
}
