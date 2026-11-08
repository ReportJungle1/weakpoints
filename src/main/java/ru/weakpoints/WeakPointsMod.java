package ru.weakpoints;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleFactory;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleRegistry;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.weakpoints.net.HitPayload;
import ru.weakpoints.net.PointPayload;

public final class WeakPointsMod implements ModInitializer {
	public static final String ID = "weakpoints";
	public static final Logger LOG = LoggerFactory.getLogger(ID);
	/** Rubezh carries the same gathering inside it; with both installed this one stands aside. */
	public static final boolean ACTIVE = !FabricLoader.getInstance().isModLoaded("rubezh");

	public static final GameRules.Key<GameRules.BooleanValue> ENABLED =
		GameRuleRegistry.register("weakPoints", GameRules.Category.PLAYER, GameRuleFactory.createBooleanRule(true));
	public static final GameRules.Key<GameRules.IntegerValue> VEIN_LIMIT =
		GameRuleRegistry.register("weakPointsVeinLimit", GameRules.Category.PLAYER, GameRuleFactory.createIntRule(32, 1, 512));
	public static final GameRules.Key<GameRules.IntegerValue> TREE_LIMIT =
		GameRuleRegistry.register("weakPointsTreeLimit", GameRules.Category.PLAYER, GameRuleFactory.createIntRule(128, 1, 1024));

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(ID, path);
	}

	@Override
	public void onInitialize() {
		if (!ACTIVE) {
			LOG.info("Rubezh is installed and has weak points of its own; Weak Points stays off");
			return;
		}
		PlacedBlocks.init();
		PayloadTypeRegistry.playS2C().register(PointPayload.TYPE, PointPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(HitPayload.TYPE, HitPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(HitPayload.TYPE, (payload, context) -> WeakPoints.hit(context.player(), payload.pos(), payload.at()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> WeakPoints.forget(handler.getPlayer()));
		ServerTickEvents.END_SERVER_TICK.register(WeakPoints::tick);
		// a player without the mod sees no points, so their ore still breaks the vanilla way
		PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> !(player instanceof ServerPlayer serverPlayer
			&& ServerPlayNetworking.canSend(serverPlayer, PointPayload.TYPE) && WeakPoints.locked(serverPlayer, pos, state)));
		ServerTickEvents.END_SERVER_TICK.register(Harvest::tick);
	}
}
