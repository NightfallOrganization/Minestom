package net.minestom.server.instance.chunksystem;

import net.minestom.server.instance.Chunk;

import java.util.concurrent.CompletableFuture;

/**
 * Represents a chunk and a claim.
 * The chunk may not have been generated yet, requiring the use of a future.
 * Removing the claim before the chunk has been generated <b>CAN but DOES NOT HAVE TO</b> cause the chunk to never generate.
 *
 * @param chunkFuture the future for when the chunk has been generated
 * @param claim the {@link ChunkClaim}
 */
public record ChunkAndClaim(CompletableFuture<Chunk> chunkFuture, ChunkClaim claim) {
}
