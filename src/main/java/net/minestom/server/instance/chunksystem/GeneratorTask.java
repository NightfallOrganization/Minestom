package net.minestom.server.instance.chunksystem;

public interface GeneratorTask {
    void cancel();

    int priority();
}
