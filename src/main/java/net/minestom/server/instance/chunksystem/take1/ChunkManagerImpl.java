package net.minestom.server.instance.chunksystem.take1;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import net.minestom.server.exception.ExceptionManager;
import net.minestom.server.instance.*;
import net.minestom.server.instance.chunksystem.*;
import net.minestom.server.instance.generator.Generator;
import net.minestom.server.utils.chunk.ChunkSupplier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnknownNullability;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static net.minestom.server.utils.chunk.ChunkUtils.getChunkIndex;

class ChunkManagerImpl implements ChunkManager {
    /**
     * Initial state of a chunk after construction, changed right after adding a claim
     */
    private static final int STATE_INITIALIZED = 0;
    /**
     * State indicating the chunk has been submitted to the generation queue.
     * The chunk might also be in the process of being generated (generation task execution has started).
     */
    private static final int STATE_GENERATING = STATE_INITIALIZED + 1;
    /**
     * State indicating the chunk has been fully generated and is usable
     */
    private static final int STATE_GENERATED = STATE_GENERATING + 1;
    /**
     * State indicating the chunk has been fully generated and is now loaded and in use.
     */
    private static final int STATE_LOADED = STATE_GENERATED + 1;
    /**
     * State indicating a chunk has been fully generated, but was not required anymore.
     * A chunk with this state will not be forced to stay in memory, but it may.
     * This is useful to reuse the chunk, we do not need to generate it twice, since world generation should be deterministic and not care.
     */
    private static final int STATE_UNLOADED = STATE_LOADED + 1;
    /**
     * Unsafe state. Nothing will interfere with a state while it is unsafe. Chunks should never be unsafe for long.
     */
    private static final int STATE_UNSAFE = STATE_UNLOADED + 1;
    private static final VarHandle ENTRY_COUNT;
    private static final VarHandle ENTRY_STATE;
    /**
     * reusable future to reduce memory allocations
     */
    private static final CompletableFuture<Void> VOID_FUTURE = CompletableFuture.completedFuture(null);
    private final Map<Long, Entry> chunks = new ConcurrentHashMap<>();
    private final LoadingCache<Long, Entry> loader = Caffeine.newBuilder().weakValues().build(Entry::new);
    private final GenerationSystem generationSystem;
    private final @NotNull Supplier<@NotNull IChunkLoader> chunkLoader;
    private final @NotNull Supplier<@NotNull ChunkSupplier> chunkSupplier;
    private final @NotNull Supplier<@Nullable Generator> chunkGenerator;

    public ChunkManagerImpl(@NotNull InstanceContainer instance, @NotNull ExceptionManager exceptionManager) {
        this(instance::getChunkLoader, instance::getChunkSupplier, instance::generator, exceptionManager);
    }

    public ChunkManagerImpl(@NotNull Supplier<@NotNull IChunkLoader> chunkLoader, @NotNull Supplier<@NotNull ChunkSupplier> chunkSupplier, @NotNull Supplier<@Nullable Generator> chunkGenerator, @NotNull ExceptionManager exceptionManager) {
        this.generationSystem = new GenerationSystem(this, exceptionManager);
        this.chunkLoader = chunkLoader;
        this.chunkSupplier = chunkSupplier;
        this.chunkGenerator = chunkGenerator;
    }

    void chunkLoaded(Entry entry) {
        assert entry.future.isDone();
        System.out.println("Loaded chunk");
    }

    void chunkUnloaded(Entry entry) {
        System.out.println("Unloaded chunk");
    }

    void chunkGenerated(Entry entry) {
        System.out.println("Generated chunk");
    }

    public @NotNull IChunkLoader chunkLoader() {
        return chunkLoader.get();
    }

    public @Nullable Generator chunkGenerator() {
        return chunkGenerator.get();
    }

    public @NotNull ChunkSupplier chunkSupplier() {
        return chunkSupplier.get();
    }

    @Override
    public @NotNull ChunkAndClaim addClaim(int chunkX, int chunkZ, int radius, int priority) {
        final long key = getChunkIndex(chunkX, chunkZ);
        var entry = chunks.get(key);
        if (entry == null) {
            // Obtaining from loader is more expensive - first try to find already loaded entry
            entry = loader.get(key);
        }
        var claim = new ChunkClaimImpl(priority, radius);
        var count = entry.addClaim(claim);
        if (count == 0) {
            // we are the first to add a claim. We are responsible for loading the chunk

            // first, make sure it is in the loaded map. After adding the first claim, it will always be loaded.
            chunks.put(key, entry);
            while (true) {
                // busy wait here, we might have added a claim right after unloading the chunk from another thread.
                // busy wait seems best, should only be nanoseconds before the state is something we can work with.
                // state can be something other than STATE_INITIALIZED if a fully loaded chunk is being claimed right after being unclaimed.
                if (entry.state(STATE_INITIALIZED, STATE_UNSAFE)) {
                    entry.priorityTask = generationSystem.publishUpdate(entry, entry.highestPriorityClaim());
                    entry.radiusTask = generationSystem.publishUpdate(entry,entry.highestRadiusClaim());
                    entry.state(STATE_GENERATING);
                    return new ChunkAndClaim(entry.future, claim);
                } else if (entry.state(STATE_UNLOADED, STATE_GENERATED)) {
                    // we can safely call the callback outside of any lock - no need to use STATE_UNSAFE as intermediate step
                    // because we claimed this chunk and the claim cannot be removed before this method returns,
                    // the chunk cannot be unloaded until this method returns.
                    chunkLoaded(entry);
                    return new ChunkAndClaim(entry.future, claim);
                }
                Thread.onSpinWait();
            }
        }
        tryUpdateTask(entry);
        return new ChunkAndClaim(entry.future, claim);
    }

    /**
     * Updates the {@link GeneratorTask} associated with the given {@link Entry}.
     * Done by first checking if the task even has to be updated.
     * If not, this method returns without blocking any other Thread accessing the {@link Entry}.
     */
    private void tryUpdateTask(Entry entry) {
        while (true) {
            final var state = entry.state;
            if (state == STATE_GENERATED) {
                // Might have to update the radius
                break;
            }

            final var highestPriorityClaim = entry.highestPriorityClaim();
            final var task = entry.priorityTask;

            if (task != null) {
                if (highestPriorityClaim.priority() == task.priority()) {
                    // task already has the required priority. No need to up-/downgrade
                    break;
                }
                if (entry.state(STATE_GENERATING, STATE_UNSAFE)) {
                    entry.priorityTask.cancel();
                    entry.radiusTask.cancel();
                    entry.priorityTask = generationSystem.publishUpdate(entry, entry.highestPriorityClaim());
                    entry.radiusTask = generationSystem.publishUpdate(entry, entry.highestRadiusClaim());
                    entry.state(STATE_GENERATING);
                    return;
                }
            }
            Thread.onSpinWait();
        }
        // If we do not have to update the priority, we must update the radius.
        while (true) {
            final var highestRadiusClaim = entry.highestRadiusClaim();
            final var task = entry.radiusTask;

            if (task != null) {
                if (highestRadiusClaim.radius() == task.radius() && highestRadiusClaim.priority() == task.radius()) {
                    // task already has the required radius and priority. No need to up-/downgrade
                    break;
                }
            }
        }
    }

    @Override
    public @NotNull CompletableFuture<Void> removeClaim(int chunkX, int chunkZ, ChunkClaim claim) {
        if (!(claim instanceof ChunkClaimImpl claimImpl)) throw new IllegalArgumentException("Invalid claim");
        final var key = getChunkIndex(chunkX, chunkZ);
        var entry = chunks.get(key);
        if (entry == null) {
            throw new IllegalArgumentException("Chunk(" + chunkX + "," + chunkZ + ") is not loaded");
        }
        final var allClaimsRemoved = entry.removeClaim(claimImpl);
        if (allClaimsRemoved) {
            while (true) {
                if (entry.state(STATE_GENERATING, STATE_UNSAFE)) {
                    final var entryTask = entry.priorityTask;
                    if (entryTask == null) throw new Error("GeneratorTask mustn't be null but is");
                    entryTask.cancel();
                    entry.priorityTask = null;
                    entry.state(STATE_INITIALIZED);
                    return VOID_FUTURE;
                } else if (entry.state(STATE_GENERATED, STATE_UNSAFE)) {
                    // unloads have to be synchronized (during STATE_UNSAFE).
                    // Otherwise another addClaim could call chunkLoaded again before chunkUnloaded,
                    // resulting in chunkLoaded, chunkLoaded, chunkUnloaded
                    chunkUnloaded(entry);
                    entry.state(STATE_UNLOADED);
                    return VOID_FUTURE;
                }
                Thread.onSpinWait();
            }
        } else {
            tryUpdateTask(entry);
            return VOID_FUTURE;
        }
    }

    /**
     * Class containing all data and state information for a chunk.
     * <p>
     * There is only ever a single {@link Entry Entry} for a pair of chunk coordinates in the {@link ChunkManager}.<br>
     * This allows reuse if the garbage collector has not yet collected the entry.<br>
     * This also helps with concurrency issues possibly creating multiple Entries for a single chunk in case of a race condition.
     * Basically, the approach makes our life easier.<br>
     * Performance impact <b>SHOULD</b> be negligible.
     */
    @SuppressWarnings("FieldMayBeFinal")
    static class Entry {
        private final ConcurrentSkipListMap<ChunkClaimImpl, Boolean> byRadius = new ConcurrentSkipListMap<>(ChunkClaimImpl.BY_RADIUS);
        private final ConcurrentSkipListMap<ChunkClaimImpl, Boolean> byPriority = new ConcurrentSkipListMap<>(ChunkClaimImpl.BY_PRIORITY);

        private final CompletableFuture<Chunk> future = new CompletableFuture<>();

        @SuppressWarnings("unused") // we use VarHandle to speed things up compared to AtomicInteger
        private volatile int count;
        private volatile @Nullable GeneratorTask priorityTask;
        private volatile @Nullable GeneratorTask radiusTask;
        private volatile int state = STATE_INITIALIZED;

        public Entry(long key) {
        }

        final @UnknownNullability ChunkClaimImpl highestRadiusClaim() {
            return byRadius.firstKey();
        }

        final @UnknownNullability ChunkClaimImpl highestPriorityClaim() {
            return byPriority.firstKey();
        }

        final @NotNull CompletableFuture<Chunk> future() {
            return future;
        }

        private boolean state(int expected, int newState) {
            return ENTRY_STATE.compareAndSet(this, expected, newState);
        }

        private void state(int newState) {
            assert state == STATE_UNSAFE;
            state = newState;
        }

        private int addClaim(ChunkClaimImpl claim) {
            if (byRadius.put(claim, Boolean.TRUE) == null) {
                byPriority.put(claim, Boolean.TRUE);
                return require();
            }
            throw new IllegalStateException("Claim already added: " + claim);
        }

        private boolean removeClaim(ChunkClaimImpl claim) {
            if (byRadius.remove(claim, Boolean.TRUE)) {
                byPriority.remove(claim, Boolean.TRUE);
                return release();
            }
            throw new IllegalStateException("Claim not found: " + claim);
        }

        /**
         * Adds 1 to the use count.
         *
         * @return the old use count
         */
        private int require() {
            return (int) ENTRY_COUNT.getAndAdd(this, 1);
        }

        /**
         * @return true if the entry is not used anymore
         */
        private boolean release() {
            var n = (int) ENTRY_COUNT.getAndAdd(this, -1) - 1;
            if (n < 0) throw new IllegalStateException();
            return n == 0;
        }
    }

    static {
        try {
            var lookup = MethodHandles.lookup();
            ENTRY_COUNT = lookup.findVarHandle(Entry.class, "count", int.class);
            ENTRY_STATE = lookup.findVarHandle(Entry.class, "state", int.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new Error(e);
        }
    }
}
