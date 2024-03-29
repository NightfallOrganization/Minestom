package net.minestom.server.instance.chunksystem;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import net.minestom.server.exception.ExceptionManager;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.IChunkLoader;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.generator.Generator;
import net.minestom.server.utils.chunk.ChunkSupplier;
import net.minestom.server.utils.chunk.ChunkUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnknownNullability;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.Supplier;

public class ChunkManagerImpl implements ChunkManager {
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

    private final LoadingCache<Long, Entry> loader = Caffeine.newBuilder().weakValues().build(Entry::new);
    private final ConcurrentHashMap<Long, Entry> chunks = new ConcurrentHashMap<>();

    private final @NotNull Supplier<@NotNull IChunkLoader> chunkLoader;
    private final @NotNull Supplier<@NotNull ChunkSupplier> chunkSupplier;
    private final @NotNull Supplier<@Nullable Generator> chunkGenerator;
    private final @NotNull ExceptionManager exceptionManager;
    private final GenerationSystem generationSystem;

    public ChunkManagerImpl(@NotNull InstanceContainer instance, @NotNull ExceptionManager exceptionManager) {
        this(instance::getChunkLoader, instance::getChunkSupplier, instance::generator, exceptionManager);
    }

    public ChunkManagerImpl(@NotNull Supplier<@NotNull IChunkLoader> chunkLoader, @NotNull Supplier<@NotNull ChunkSupplier> chunkSupplier, @NotNull Supplier<@Nullable Generator> chunkGenerator, @NotNull ExceptionManager exceptionManager) {
        this.chunkLoader = chunkLoader;
        this.chunkSupplier = chunkSupplier;
        this.chunkGenerator = chunkGenerator;
        this.exceptionManager = exceptionManager;
        this.generationSystem = new GenerationSystem(this, exceptionManager);
    }

    private static void loopHint() {
        Thread.onSpinWait();
        Thread.yield(); // TODO: Profile this
    }

    void chunkLoaded(ChunkManagerImpl.Entry entry) {
        assert entry.future.isDone();
        System.out.println("Loaded chunk");
    }

    void chunkUnloaded(ChunkManagerImpl.Entry entry) {
        System.out.println("Unloaded chunk");
    }


    @Override
    public @NotNull ChunkAndClaim addClaim(int chunkX, int chunkZ, int radius, int priority) {
        final long key = ChunkUtils.getChunkIndex(chunkX, chunkZ);
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
                if (entry.state(STATE_INITIALIZED, STATE_UNSAFE)) { // try lock entry
                    // use entry#highestPriorityClaim instead of `claim`. Method call is very fast and runs approx in O(1).
                    // if someone else comes along and also adds a claim, they might not have to regenerate the task,
                    // because we already selected their claim
                    entry.task = generationSystem.publishUpdate(entry, entry.highestPriorityClaim());
                    entry.state(STATE_GENERATING); // unlock entry
                    break;
                } else if (entry.state(STATE_UNLOADED, STATE_LOADED)) { // no need to lock
                    // we can safely call the callback outside of any lock - no need to use STATE_UNSAFE as intermediate step
                    // because we claimed this chunk and the claim cannot be removed before this method returns,
                    // the chunk cannot be unloaded until this method returns.
                    chunkLoaded(entry);
                    break;
                }
                loopHint();
            }
        } else {
            // someone else has already claimed the chunk. Now there are 3 options:
            // 1. we have claimed with higher priority. Now we have to update the task to run earlier.
            // 2. we have claimed with same or less priority. We don't have to update the task in this case.
            // 3. we have claimed with a radius. We have to submit the claim to the generationSystem, so it can figure out when to generate adjacent chunks.
            // Claim Syntax for following comments: claim{id}(radius,priority)
            // as a rule, we always have to submit all claims to the generationSystem.
            // If we have a claim1(1,5) and a claim2(4,2), we first have to generate claim1 and then claim2.
            // Even though claim2 had a lower priority, we still had to submit it to make sure everything is generated.
            // as a result the PriorityQueue of the generationSystem might get overloaded. Gonna have to test and find out if it is a problem.

            // first we try to update the task priority if necessary
            while (true) {
                final var state = entry.state;
                if (state == STATE_LOADED || state == STATE_GENERATED) {
                    // the chunk has been generated. We don't care about priority now.
                    // continue with submitting the radius to the generationSystem
                    break;
                }

                final var task = entry.task;
                if (task != null) {

                    // might be wrong priority
                    if (entry.state(STATE_GENERATING, STATE_UNSAFE)) { // try lock entry
                        // TODO try to extract an initial check that is safe to use outside of the locked entry
                        var highestPriorityClaim = entry.highestPriorityClaim(); // recheck for updates
                        if (entry.task.priority() == highestPriorityClaim.priority()) {
                            // everything correct at this point
                            entry.state(STATE_GENERATING); // unlock entry
                            break;
                        }
                        entry.task.cancel();
                        entry.task = generationSystem.publishUpdate(entry, highestPriorityClaim);
                        entry.state(STATE_GENERATING); // unlock entry
                        break;
                    }
                }
                loopHint();
            }
            // task priority is correct now
        }
        // submit radius update
        generationSystem.publishRadius(entry, claim);

        return new ChunkAndClaim(entry.future, claim);
    }

    @Override
    public @NotNull CompletableFuture<Void> removeClaim(int chunkX, int chunkZ, ChunkClaim claim) {
        return null;
    }

    public static class Entry {
        private static final VarHandle COUNT;
        private static final VarHandle STATE;

        private final ConcurrentSkipListMap<ChunkClaimImpl, Boolean> byPriority = new ConcurrentSkipListMap<>(ChunkClaimImpl.BY_PRIORITY);
        private final CompletableFuture<Chunk> future = new CompletableFuture<>();

        /**
         * Initially null.<br>
         * Has a value while the chunk is being generated<br>
         * When generation has finished ({@link #STATE_GENERATED} or {@link #STATE_LOADED}), null again
         */
        private volatile GeneratorTask task;

        @SuppressWarnings("unused") // we use VarHandle to speed things up compared to AtomicInteger
        private volatile int count;
        private volatile int state = STATE_INITIALIZED;

        private Entry(long unused) {
        }

        public CompletableFuture<Chunk> future() {
            return future;
        }

        final @UnknownNullability ChunkClaimImpl highestPriorityClaim() {
            return byPriority.firstKey();
        }

        private boolean state(int expected, int newState) {
            return STATE.compareAndSet(this, expected, newState);
        }

        private void state(int newState) {
            assert state == STATE_UNSAFE;
            state = newState;
        }

        int addClaim(ChunkClaimImpl claim) {
            if (byPriority.put(claim, Boolean.TRUE) == null) {
                return require();
            }
            throw new IllegalStateException("Claim already added: " + claim);
        }

        boolean removeClaim(ChunkClaimImpl claim) {
            if (byPriority.remove(claim, Boolean.TRUE)) {
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
            return (int) COUNT.getAndAdd(this, 1);
        }

        /**
         * @return true if the entry is not used anymore
         */
        private boolean release() {
            var n = (int) COUNT.getAndAdd(this, -1) - 1;
            if (n < 0) throw new IllegalStateException();
            return n == 0;
        }

        static {
            try {
                var lookup = MethodHandles.lookup();
                COUNT = lookup.findVarHandle(Entry.class, "count", int.class);
                STATE = lookup.findVarHandle(Entry.class, "state", int.class);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new Error(e);
            }
        }
    }
}
