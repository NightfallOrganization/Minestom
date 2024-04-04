package net.minestom.server.instance.chunksystem;

import it.unimi.dsi.fastutil.Pair;
import net.minestom.server.exception.ExceptionManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;

final class GenerationSystemImpl implements GenerationSystem {
    private final ChunkManagerImpl chunkManager;
    private final ChunkPriorityQueue queue = new ChunkPriorityQueue();
    private final ExecutorService service;

    public GenerationSystemImpl(ChunkManagerImpl chunkManager, ExceptionManager exceptionManager) {
        this.chunkManager = chunkManager;
        var threads = Runtime.getRuntime().availableProcessors();
        this.service = Executors.newFixedThreadPool(threads, new GeneratorThreadFactory(chunkManager));
        for (int i = 0; i < threads; i++) {
            service.submit(() -> {
                while (true) {
                    try {
                        queue.poll((priority, request) -> {
                            if (request instanceof GenerationRequest generationRequest) {
                                generate(priority, generationRequest.entry());
                            } else if (request instanceof PropagationRequest propagationRequest) {
                                propagate(propagationRequest.entry(), propagationRequest.direction(), propagationRequest.claim());
                            }
                        });
                    } catch (InterruptedException e) {
                        // service shutdown
                        return;
                    } catch (ExecutionException e) {
                        exceptionManager.handleException(e);
                    }
                }
            });
        }
    }

    public @NotNull GeneratorTask publishUpdate(@NotNull ChunkManagerImpl.Entry entry, @NotNull ChunkClaimImpl claim) {
        var request = new GenerationRequest(entry);
        var priority = claim.priority();
        var node = queue.add(priority, request);
        return new GeneratorTaskImpl(queue, priority, node);
    }

    public void publishRadius(ChunkManagerImpl.@NotNull Entry entry, @NotNull ChunkClaimImpl claim) {
        var request = new PropagationRequest(entry, Direction.ALL, claim);
        queue.add(claim.priority(), request);
    }

    private void generate(int priority, ChunkManagerImpl.Entry entry) {
        final var future = entry.future();
        if (future.isDone()) return;

    }

    private void propagate(ChunkManagerImpl.Entry entry, Direction direction, ChunkClaimImpl claim) {

    }

    private interface Request {
    }

    private record GenerationRequest(ChunkManagerImpl.Entry entry) implements Request {
    }

    private record PropagationRequest(ChunkManagerImpl.Entry entry, Direction direction,
                                      ChunkClaimImpl claim) implements Request {
    }

    private record RemovePropagationRequest(ChunkManagerImpl.Entry entry, Direction direction) implements Request {

    }

    private enum Direction {
        ALL, POSITIVE_X, POSITIVE_Z, NEGATIVE_X, NEGATIVE_Z
    }

    private static final class GeneratorThreadFactory implements ThreadFactory {
        private final ThreadGroup group;
        private final AtomicInteger id = new AtomicInteger();

        GeneratorThreadFactory(ChunkManagerImpl chunkManager) {
            this.group = new ThreadGroup("ChunkGenerators-" + Integer.toHexString(chunkManager.hashCode()));
        }

        @Override
        public Thread newThread(@NotNull Runnable r) {
            var thread = new Thread(group, r, "ChunkGeneratorThread-" + id.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        }
    }

    static final class GeneratorTaskImpl implements GeneratorTask {
        private final ChunkPriorityQueue queue;
        private final int priority;
        private final @Nullable SynchronizedLinkedDeque.Node<?> node;

        public GeneratorTaskImpl(ChunkPriorityQueue queue, int priority, @Nullable SynchronizedLinkedDeque.Node<?> node) {
            this.queue = queue;
            this.priority = priority;
            this.node = node;
        }

        @Override
        public void cancel() {
            if (node != null) {
                node.unlink();
                if (node.deque.unsafeEmptied()) {
                    queue.byPriority.remove(priority, node.deque);
                }
            }
        }

        @Override
        public int priority() {
            return priority;
        }
    }

    static final class ChunkPriorityQueue {

        // we have to use a SkipListMap, because we have unlimited possible priorities.
        // An array would be faster, but that would require limiting priorities to a fixed number.
        final ConcurrentSkipListMap<Integer, SynchronizedLinkedDeque<Request>> byPriority = new ConcurrentSkipListMap<>(Comparator.reverseOrder());
        // polling mechanism
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition notEmpty = lock.newCondition();
        private final AtomicInteger waiting = new AtomicInteger(0);

        SynchronizedLinkedDeque.Node<Request> add(int priority, Request entry) {
            queryQueue:
            while (true) {
                var queue = byPriority.get(priority);
                while (true) {
                    if (queue == null) {
                        var pair = SynchronizedLinkedDeque.create(entry);
                        queue = pair.first();
                        var node = pair.second();
                        var old = byPriority.putIfAbsent(priority, queue);
                        if (old == null) {
                            wakeup(1);
                            return node;
                        }
                        continue queryQueue;
                    } else {
                        var node = queue.offerLastUnlessEmpty(entry);
                        if (node == null) { // queue was empty, is going to be removed
                            // we can safely do this here. Now we don't have to wait for another thread to remove the queue
                            // once a queue is empty it can NEVER be filled again, so this is completely safe to do
                            byPriority.remove(priority, queue);
                            // reduce calls to byPriority map. Faster overall
                            queue = null;
                        } else {
                            // successfully added to queue
                            wakeup(1);
                            return node;
                        }
                    }
                }
            }
        }

        void poll(BiConsumer<Integer, Request> task) throws InterruptedException, ExecutionException {
            //noinspection InfiniteLoopStatement
            while (true) {
                var mapEntry = byPriority.firstEntry();
                check:
                if (mapEntry == null) {
                    // start waiting
                    lock.lockInterruptibly(); // we do not unlock in case of InterruptedException
                    waiting.incrementAndGet();
                    mapEntry = byPriority.firstEntry();
                    if (mapEntry != null) {
                        // someone just added an entry to the queue. Manually unlock.
                        // we do not use finally because of the awkward waiting counter
                        waiting.decrementAndGet();
                        lock.unlock();
                        break check;
                    }
                    try {
                        notEmpty.await();
                    } finally {
                        lock.unlock();
                        waiting.decrementAndGet();
                    }
                    continue;
                }

                var queue = mapEntry.getValue();
                var entry = queue.pollFirst();
                var empty = queue.emptied();
                if (empty) { // once a queue is empty it can never be used again. Always try to remove it from the deque
                    byPriority.remove(mapEntry.getKey(), queue);
                }
                if (entry == null) {
                    continue;
                }
                try {
                    task.accept(mapEntry.getKey(), entry);
                } catch (Throwable throwable) {
                    throw new ExecutionException(throwable);
                }
            }
        }

        private void wakeup(int signalCount) {
            if (waiting.get() != 0) { // at least 1 thread waiting
                try {
                    lock.lock();
                    if (signalCount == 1) {
                        notEmpty.signal();
                    } else {
                        notEmpty.signalAll();
                    }
                } finally {
                    lock.unlock();
                }
            }
        }
    }

    /**
     * We need a linked deque, because we want to be able to remove a node from anywhere in the queue in O(1) while keeping insertion order.
     * Synchronizing LinkedHashMap is way to slow.
     * Would be nicer to use a modified ConcurrentLinkedDeque, but implementing the required changes to make the queue work
     * is way to difficult (for me, DasBabyPixel), and the synchronized deque is good enough. It is able to handle about a
     * million offers/polls per second with contention (on my system), so for a few thousand chunks a second it will be sufficient.
     */
    static final class SynchronizedLinkedDeque<T> {

        /**
         * Once this is set to true, nothing can enter the queue
         */
        private boolean emptied = false;
        private @Nullable SynchronizedLinkedDeque.Node<T> head;
        private @Nullable SynchronizedLinkedDeque.Node<T> tail;

        public SynchronizedLinkedDeque(T item) {
            head = tail = new SynchronizedLinkedDeque.Node<>(item, this);
        }

        public static <T> Pair<SynchronizedLinkedDeque<T>, SynchronizedLinkedDeque.Node<T>> create(T entry) {
            var queue = new SynchronizedLinkedDeque<>(entry);
            return Pair.of(queue, queue.head);
        }

        public final synchronized @Nullable SynchronizedLinkedDeque.Node<T> offerLastUnlessEmpty(T item) {
            if (emptied) return null;
            var node = new SynchronizedLinkedDeque.Node<>(item, this);
            node.prev = tail;
            assert node.prev != null; // here to suppress DataFlowIssue. tail can never be null, because this queue is fully synchronized
            node.prev.next = node;
            tail = node;
            return node;
        }

        public final synchronized @Nullable T pollFirst() {
            var head = this.head;
            if (head == null) return null;
            final var next = head.next;
            this.head = next;
            if (next != null) {
                next.prev = null;
            } else {
                tail = null;
                emptied = true;
            }
            head.next = null;
            return head.item;
        }

        public final boolean emptied() {
            if (emptied) return true;
            synchronized (this) {
                return emptied;
            }
        }

        public final boolean unsafeEmptied() {
            return emptied; // Note no synchronization here. "unsafe".
        }

        private boolean unlink(SynchronizedLinkedDeque.Node<T> x) {
            var prev = x.prev;
            var next = x.next;
            if (prev == null && next == null && x != head) return false;
            x.prev = null;
            x.next = null;
            if (prev != null) {
                prev.next = next;
            }
            if (next != null) {
                next.prev = prev;
            }
            if (head == x) {
                head = next;
            }
            if (tail == x) {
                tail = prev;
            }
            return true;
        }

        public static final class Node<T> {
            final SynchronizedLinkedDeque<T> deque;
            private final T item;
            private @Nullable SynchronizedLinkedDeque.Node<T> prev;
            private @Nullable SynchronizedLinkedDeque.Node<T> next;

            private Node(T item, SynchronizedLinkedDeque<T> deque) {
                this.deque = deque;
                this.item = item;
            }

            /**
             * @return true if the node was unlinked, false if it could not be unlinked because it isn't valid or already has been unlinked
             */
            public boolean unlink() {
                synchronized (deque) {
                    return deque.unlink(this);
                }
            }

            @Override
            public String toString() {
                return "Node{" + "item=" + item + ",hashCode=" + Integer.toHexString(hashCode()) + '}';
            }
        }
    }
}
