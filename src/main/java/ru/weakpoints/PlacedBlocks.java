package ru.weakpoints;

import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.stream.LongStream;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Per chunk, server only: logs and ores a player set down from an item, so log walls and ore displays are never
 * taken as a tree or a vein. Only player placement counts: a tree grown from a sapling is a tree.
 * shortcut: a log or ore moved by a piston loses its mark; track piston moves if built things get felled that way.
 */
public final class PlacedBlocks {
	private static final Codec<LongOpenHashSet> CODEC = Codec.LONG_STREAM.xmap(stream -> new LongOpenHashSet(stream.toArray()), set -> LongStream.of(set.toLongArray()));
	private static final AttachmentType<LongOpenHashSet> PLACED = AttachmentRegistry.createPersistent(WeakPointsMod.id("placed"), CODEC);

	private PlacedBlocks() {
	}

	/** Loads the class, which registers the attachment. */
	static void init() {
	}

	/** Only what gathering ever asks about is remembered, so a big build costs nothing. */
	public static boolean tracked(BlockState state) {
		return state.is(BlockTags.LOGS) || state.is(ConventionalBlockTags.ORES);
	}

	public static boolean isPlaced(Level level, BlockPos pos) {
		LongOpenHashSet placed = level.getChunk(pos).getAttached(PLACED);
		return placed != null && placed.contains(pos.asLong());
	}

	public static void mark(Level level, BlockPos pos) {
		ChunkAccess chunk = level.getChunk(pos);
		if (chunk.getAttachedOrCreate(PLACED, LongOpenHashSet::new).add(pos.asLong())) chunk.setUnsaved(true);
	}

	/** From LevelChunk.setBlockState: a block broken or swapped for another kind is no longer the one placed. */
	public static void changed(ChunkAccess chunk, BlockPos pos, BlockState before, BlockState after) {
		if (before.is(after.getBlock())) return;
		LongOpenHashSet placed = chunk.getAttached(PLACED);
		if (placed != null && placed.remove(pos.asLong())) chunk.setUnsaved(true);
	}
}
