package io.github.nanoforged.adpters.janino.impl.resolver;

import io.github.nanoforged.adpters.janino.api.comp.TypeResolver;

import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * Decorates any resolver with a bounded, positive-only source text LRU.
 * Binary lookups always reach the delegate. Keys are exact query names, not inferred resource paths.
 * Sources must remain stable while cached; eviction does not provide snapshot semantics.
 * Map access is synchronized, but delegate work is outside the lock and concurrent misses may repeat.
 */
public final class LRUTypeResolver implements TypeResolver {
    private final TypeResolver delegate;
    private final int maxEntries;
    private final long maxChars;
    private final LinkedHashMap<String, String> sources = new LinkedHashMap<>(16, 0.75f, true);
    private long chars;

    /**
     * Creates a source cache with 128 entries and at most two million UTF-16 code units.
     *
     * @param delegate underlying resolver
     */
    public LRUTypeResolver(TypeResolver delegate) {
        this(delegate, 128, 2L * 1024 * 1024);
    }

    /**
     * Creates an independent source cache.
     *
     * @param delegate   underlying resolver
     * @param maxEntries maximum retained entries, zero disables retention
     * @param maxChars   maximum retained UTF-16 code units, excluding object overhead
     * @throws IllegalArgumentException if a limit is negative
     * @throws NullPointerException     if delegate is null
     */
    public LRUTypeResolver(TypeResolver delegate, int maxEntries, long maxChars) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (maxEntries < 0 || maxChars < 0) throw new IllegalArgumentException("Negative cache limit");
        this.maxEntries = maxEntries;
        this.maxChars = maxChars;
    }

    /**
     * {@inheritDoc} Null answers and exceptions are never cached.
     */
    @Override
    public String findSource(String className) {
        Objects.requireNonNull(className, "className");
        synchronized (sources) {
            String cached = sources.get(className);
            if (cached != null) return cached;
        }
        String loaded = delegate.findSource(className);
        if (loaded == null) return null;
        synchronized (sources) {
            String winner = sources.get(className);
            if (winner != null) return winner;
            if (maxEntries == 0 || maxChars == 0 || loaded.length() > maxChars) return loaded;
            var oldest = sources.values().iterator();
            while (sources.size() >= maxEntries || chars > maxChars - loaded.length()) {
                chars -= oldest.next().length();
                oldest.remove();
            }
            sources.put(className, loaded);
            chars += loaded.length();
            return loaded;
        }
    }

    /**
     * {@inheritDoc} No binary results or misses are cached by this decorator.
     */
    @Override
    public byte[] findBinary(String className) {
        return delegate.findBinary(className);
    }
}
