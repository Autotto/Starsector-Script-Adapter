package io.github.nanoforged.adpters.janino.api;

import io.github.nanoforged.adpters.janino.JaninoAdpter;
import io.github.nanoforged.adpters.janino.api.comp.BatchCompilationRequest;
import io.github.nanoforged.adpters.janino.api.comp.BatchCompilerBackEnd;
import io.github.nanoforged.adpters.janino.api.comp.CompilationException;
import io.github.nanoforged.adpters.janino.api.comp.CompilationOutput;
import io.github.nanoforged.adpters.janino.api.comp.CompilerBackEnd;
import io.github.nanoforged.adpters.janino.api.comp.TypeResolver;
import io.github.nanoforged.adpters.janino.utils.ClassNameUtil;
import org.codehaus.janino.util.resource.Resource;
import org.codehaus.janino.util.resource.ResourceFinder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Concurrent, loader-private compilation and retention of successful class files.
 *
 * <p>Each outer defining loader owns one session and remains responsible for parent delegation,
 * class-definition synchronization and runtime class identity. This class never defines classes.
 * Source resources, parent binaries, encoding and the {@link JaninoAdpter} provider configuration
 * must remain stable for the session's lifetime. This is not a hot-reload API; create a new loader
 * and session for changed inputs. Finders and providers must support concurrent calls, and providers
 * must supply independent backend/resolver instances for different threads.
 *
 * <p>Compiler and resolver instances are private to each calling thread. Each compilation observes
 * one immutable successful-output snapshot and has its own resolver overlay. Source reads and
 * compilation run without the publication monitor; duplicate concurrent compilations are allowed,
 * with no dependency-future waits. A complete batch is validated against the latest snapshot before
 * atomic publication. Retained output is never evicted and there are no global caches.
 */
public final class SharedCompilationSession {
    private final CompilerContext context;
    private final ThreadLocal<Worker> workers;
    private volatile Snapshot snapshot = new Snapshot(Map.of(), Map.of());

    /**
     * Creates a session and checks batch capability without reading or compiling any source.
     *
     * <p>The caller can catch {@link UnsupportedOperationException} once when selecting its loader
     * route and keep the existing compatibility path for an incapable backend.
     *
     * @param parent   parent supplying compiler-visible binaries; null uses the resolver's default
     * @param finder   explicit root source finder; null means no source resources
     * @param encoding source charset name; null selects the platform default charset
     * @throws UnsupportedOperationException if the configured backend lacks batch capability
     */
    public SharedCompilationSession(ClassLoader parent, ResourceFinder finder, String encoding) {
        context = new CompilerContext(parent, finder, encoding);
        // Check only the backend here: construction must not compile or resolve sources.
        BatchCompilerBackEnd backend = requireBatch(JaninoAdpter.getCompilerBackEnd(context));
        workers = ThreadLocal.withInitial(() -> new Worker(requireBatch(JaninoAdpter.getCompilerBackEnd(context)), Objects.requireNonNull(JaninoAdpter.getTypeResolver(context), "resolver")));
        workers.set(new Worker(backend, Objects.requireNonNull(JaninoAdpter.getTypeResolver(context), "resolver")));
    }

    private static BatchCompilerBackEnd requireBatch(CompilerBackEnd backend) {
        Objects.requireNonNull(backend, "backend provider returned null");
        if (!(backend instanceof BatchCompilerBackEnd batch)) {
            throw new UnsupportedBackendException();
        }
        return batch;
    }

    private static TypeResolver overlay(TypeResolver delegate, Snapshot observed, String rootResource) {
        return new TypeResolver() {
            private boolean rootFamily(String name) {
                String conventionalResource = ClassNameUtil.getSourceResourceName(name);
                String topLevel = conventionalResource.substring(0, conventionalResource.length() - 5).replace('/', '.');
                return rootResource.equals(conventionalResource) || rootResource.equals(observed.origins.get(name)) || rootResource.equals(observed.origins.get(topLevel));
            }

            @Override
            public byte[] findBinary(String name) {
                // Explicit source families must not resolve to old binaries, including secondaries.
                if (rootFamily(name)) return null;
                byte[] binary = delegate.findBinary(name);
                if (binary != null) return binary;
                byte[] retained = observed.classes.get(name);
                return retained == null ? null : retained.clone();
            }

            @Override
            public String findSource(String name) {
                // Backends normally ask for binaries first; preserve precedence even if they do not.
                if (!rootFamily(name) && findBinary(name) != null) return null;
                return delegate.findSource(name);
            }
        };
    }

    private static void validateProvenance(Map<String, byte[]> classes, Map<String, String> origins) {
        if (!classes.keySet().equals(origins.keySet())) {
            throw new IllegalStateException("Complete source provenance is required for every output");
        }
        origins.forEach((name, origin) -> {
            if (origin == null || origin.isEmpty()) {
                throw new IllegalStateException("Missing source provenance for " + name);
            }
        });
    }

    /**
     * Returns a defensive copy of retained or newly compiled bytecode for a binary name.
     *
     * <p>On a cache miss, the normalized top-level root is always read directly from the configured
     * finder, not from the resolver or a parent binary. All outputs require explicit source
     * provenance. Missing requested output, compilation failure, changed bytes, changed origins or
     * removal of a previously retained source-family member leave the snapshot unchanged.
     * Runtime failures outside backend compilation propagate unchanged; backends may wrap callback
     * failures in CompilationException, which this API reports as ClassNotFoundException.
     * Neither case requests compatibility fallback.
     *
     * @param binaryName dotted binary name, including any member-type dollar suffix
     * @return an independently mutable copy of the requested class file
     * @throws ClassNotFoundException   if the source or requested output is absent, or compilation fails
     * @throws IllegalStateException    if output provenance is incomplete or conflicts with retained output
     * @throws NullPointerException     if binaryName is null
     * @throws IllegalArgumentException if binaryName is empty
     */
    public byte[] getBytecode(String binaryName) throws ClassNotFoundException {
        Objects.requireNonNull(binaryName, "binaryName");
        if (binaryName.isEmpty()) throw new IllegalArgumentException("binaryName must not be empty");
        Snapshot observed = snapshot;
        byte[] cached = observed.classes.get(binaryName);
        if (cached != null) return cached.clone();
        String rootResource = ClassNameUtil.getSourceResourceName(binaryName);
        String rootName = rootResource.substring(0, rootResource.length() - 5).replace('/', '.');
        Worker worker = workers.get();
        try {
            String source = readRoot(rootResource, binaryName);
            TypeResolver resolver = overlay(worker.resolver, observed, rootResource);
            CompilationOutput output = worker.backend.compileBatch(new BatchCompilationRequest(Map.of(rootName, source), resolver));
            Map<String, byte[]> classes = output.classFiles();
            // A successful batch that does not answer this call is not a successful session update.
            if (!classes.containsKey(binaryName)) throw new ClassNotFoundException(binaryName);
            Map<String, String> origins = output.sourceResources();
            validateProvenance(classes, origins);
            publish(classes, origins, rootResource);
            return classes.get(binaryName).clone();
        } catch (CompilationException failure) {
            throw new ClassNotFoundException(binaryName, failure);
        }
    }

    private String readRoot(String resourceName, String binaryName) throws ClassNotFoundException {
        Resource resource = context.finder() == null ? null : context.finder().findResource(resourceName);
        if (resource == null) throw new ClassNotFoundException(binaryName);
        Charset charset = context.encode() == null ? Charset.defaultCharset() : Charset.forName(context.encode());
        try (InputStream input = resource.open()) {
            if (input == null) throw new ClassNotFoundException(binaryName);
            return new String(input.readAllBytes(), charset);
        } catch (IOException failure) {
            throw new CompilationException("Cannot read source " + resourceName, failure);
        }
    }

    /**
     * Validates the entire batch against the latest snapshot before publishing any member.
     */
    private synchronized void publish(Map<String, byte[]> classes, Map<String, String> origins, String rootResource) {
        Snapshot current = snapshot;
        Set<String> compiledSources = new HashSet<>(origins.values());
        compiledSources.add(rootResource);
        current.origins.forEach((name, origin) -> {
            if (compiledSources.contains(origin) && !origin.equals(origins.get(name))) {
                throw new IllegalStateException("Source family removed or moved output " + name);
            }
        });
        classes.forEach((name, bytes) -> {
            byte[] previous = current.classes.get(name);
            if (previous != null && (!Arrays.equals(previous, bytes) || !Objects.equals(current.origins.get(name), origins.get(name)))) {
                throw new IllegalStateException("Conflicting compiled output " + name);
            }
        });
        Map<String, byte[]> mergedClasses = new HashMap<>(current.classes);
        classes.forEach((name, bytes) -> mergedClasses.put(name, bytes.clone()));
        Map<String, String> mergedOrigins = new HashMap<>(current.origins);
        mergedOrigins.putAll(origins);
        snapshot = new Snapshot(Map.copyOf(mergedClasses), Map.copyOf(mergedOrigins));
    }

    /**
     * Signals only an absent backend capability, not a provider initialization failure.
     */
    public static final class UnsupportedBackendException extends UnsupportedOperationException {
        /**
         * Creates the capability-selection failure.
         */
        public UnsupportedBackendException() {
            super("Shared compilation requires BatchCompilerBackEnd");
        }
    }

    private record Worker(BatchCompilerBackEnd backend, TypeResolver resolver) {
    }

    private record Snapshot(Map<String, byte[]> classes, Map<String, String> origins) {
    }
}
