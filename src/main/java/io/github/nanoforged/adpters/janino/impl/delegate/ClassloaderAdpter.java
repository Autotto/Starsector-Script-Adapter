package io.github.nanoforged.adpters.janino.impl.delegate;

import io.github.nanoforged.adpters.janino.api.ClassDefinitionDelegate;

public abstract class ClassloaderAdpter implements ClassDefinitionDelegate {
    protected final ClassLoader classLoader;

    public ClassloaderAdpter(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }
}
