package net.minestom.server.instance.chunksystem;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Manager for a claim-based chunk system.
 * Every {@link Instance} has a separate {@link ChunkManager}.
 * <p>
 * <h2>Priority</h2>
 * Claim priorities can be used to tell how urgently a chunk is needed.
 *
 * @see Instance#getChunkManager
 */
public interface ChunkManager {
    /**
     * Safely execute an operation on a chunk.
     * The chunk will be claimed, the operation executed, and the claim removed.
     *
     * @param chunkX    the chunk X, in chunk coordinate space
     * @param chunkZ    the chunk Z, in chunk coordinate space
     * @param operation the chunk operation
     */
    default void operateOnChunk(int chunkX, int chunkZ, @NotNull Consumer<@NotNull Chunk> operation) {
        var chunkAndClaim = addClaim(chunkX, chunkZ, 0);
        chunkAndClaim.chunkFuture().thenAccept(chunk -> {
            operation.accept(chunk);
            var claim = chunkAndClaim.claim();
            removeClaim(chunkX, chunkZ, claim);
        });
    }

    /**
     * Adds a claim to a chunk.
     * Adding a claim can take an undefined period of time, chunk generation might have to happen first.
     * Same as {@link #addClaim(int, int, int, int) addClaim(chunkX, chunkZ, radius, 0)}
     *
     * @param chunkX the chunk X, in chunk coordinate space
     * @param chunkZ the chunk Z, in chunk coordinate space
     * @param radius the radius the claim will spread to other chunks. 0 for a single chunk, 1 for 3x3, 2 for 5x5, etc.
     * @return the {@link ChunkAndClaim#chunkFuture() chunkFuture} and {@link ChunkAndClaim#claim() chunkClaim}
     */
    default @NotNull ChunkAndClaim addClaim(int chunkX, int chunkZ, int radius) {
        return addClaim(chunkX, chunkZ, radius, 0);
    }

    /**
     * Adds a claim to a chunk.
     * Adding a claim can take an undefined period of time, chunk generation might have to happen first.
     *
     * @param chunkX   the chunk X, in chunk coordinate space
     * @param chunkZ   the chunk Z, in chunk coordinate space
     * @param radius   the radius the claim will spread to other chunks. 0 for a single chunk, 1 for 3x3, 2 for 5x5, etc.
     * @param priority the priority for the chunk load. Only used if chunk has to be loaded
     * @return the {@link ChunkAndClaim#chunkFuture() chunkFuture} and {@link ChunkAndClaim#claim() chunkClaim}
     */
    @NotNull ChunkAndClaim addClaim(int chunkX, int chunkZ, int radius, int priority);

    /**
     * Adds a claim to a chunk.
     * Adding a claim can take an undefined period of time, chunk generation might have to happen first.
     *
     * @param chunkX    the chunk x, in chunk coordinate space
     * @param chunkZ    the chunk z, in chunk coordinate space
     * @param claimType the claim type by which to create the claim
     * @return the {@link ChunkAndClaim#chunkFuture() chunkFuture} and {@link ChunkAndClaim#claim() chunkClaim}
     */
    @ApiStatus.Experimental
    default @NotNull ChunkAndClaim addClaim(int chunkX, int chunkZ, @NotNull ClaimType claimType) {
        return addClaim(chunkX, chunkZ, claimType.radius(), claimType.priority());
    }

    /**
     * Removes a claim from a chunk
     *
     * @param chunkX the chunk x, in chunk coordinate space
     * @param chunkZ the chunk z, in chunk coordinate space
     * @param claim  the claim to remove
     * @return a future for when the claim was removed.
     * @implNote ideally, the claim is removed as soon as the method returns (the future is already completed), this is not a requirement though.
     */
    @NotNull CompletableFuture<Void> removeClaim(int chunkX, int chunkZ, @NotNull ChunkClaim claim);
}
