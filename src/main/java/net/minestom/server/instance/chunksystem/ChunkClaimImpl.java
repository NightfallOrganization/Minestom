package net.minestom.server.instance.chunksystem;

import java.util.Comparator;

@SuppressWarnings("ClassCanBeRecord")
class ChunkClaimImpl implements ChunkClaim {
    private final int priority;
    private final int radius;

    public ChunkClaimImpl(int priority, int radius) {
        this.priority = priority;
        this.radius = radius;
    }

    public int priority() {
        return priority;
    }

    public int radius() {
        return radius;
    }

    public static final Comparator<ChunkClaimImpl> BY_RADIUS = (o1, o2) -> {
        var cmp = Integer.compare(o2.radius, o1.radius); // Inverse, larger radius first
        if (cmp != 0) return cmp;
        cmp = Integer.compare(o2.priority, o1.priority);
        if (cmp != 0) return cmp;
        return Integer.compare(o1.hashCode(), o2.hashCode());
    };
    public static final Comparator<ChunkClaimImpl> BY_PRIORITY = (o1, o2) -> {
        var cmp = Integer.compare(o2.priority, o1.priority); // Inverse, higher priority first
        if (cmp != 0) return cmp;
        cmp = Integer.compare(o2.radius, o1.radius);
        if (cmp != 0) return cmp;
        return Integer.compare(o1.hashCode(), o2.hashCode());
    };
}
