package ru.weakpoints.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import ru.weakpoints.WeakPointsMod;
import ru.weakpoints.net.PointPayload;

public final class WeakPointsClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		if (!WeakPointsMod.ACTIVE) return;
		ClientPlayNetworking.registerGlobalReceiver(PointPayload.TYPE, (payload, context) -> ClientWeakPoints.update(payload, context.client()));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientWeakPoints.reset());
		ClientTickEvents.END_CLIENT_TICK.register(ClientWeakPoints::tick);
		WorldRenderEvents.AFTER_TRANSLUCENT.register(ClientWeakPoints::render);
	}
}
