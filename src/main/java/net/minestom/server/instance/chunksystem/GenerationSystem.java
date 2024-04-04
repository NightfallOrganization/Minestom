package net.minestom.server.instance.chunksystem;

import org.jetbrains.annotations.NotNull;

interface GenerationSystem {
    @NotNull GeneratorTask publishUpdate(@NotNull ChunkManagerImpl.Entry entry, @NotNull ChunkClaimImpl claim);

    void publishRadius(@NotNull ChunkManagerImpl.Entry entry, @NotNull ChunkClaimImpl claim);

}
