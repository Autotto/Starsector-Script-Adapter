package io.github.nanoforged.adpters.janino.impl.backend.ecj;

import io.github.nanoforged.adpters.janino.api.comp.*;

import java.util.Map;
import java.util.Objects;

/**
 * {@link CompilerBackEnd} that translates Janino compilation requests with the Eclipse JDT Core
 * compiler (ECJ) instead of Janino.
 *
 * <p>The back end drives ECJ's low-level batch API
 * ({@code org.eclipse.jdt.internal.compiler.Compiler}) directly: no {@code javax.tools} compiler and
 * no {@code jdk.compiler} module is used, so the adapter also works on a plain Java runtime without
 * a bundled javac. Compilation units, binary types and the transitive source closure are resolved
 * exclusively through the {@link io.github.nanoforged.adpters.janino.api.comp.TypeResolver} of the
 * request; the back end never opens the file system or a class loader itself.
 *
 * <p>Instances are stateless: every {@link #compile(CompilationRequest)} call creates its own
 * compiler, name environment and result buffers, and the returned map is owned by the caller.
 * A single instance can therefore be shared and invoked concurrently from several threads.
 *
 * @see CompilerBackEnd
 */
public final class ECJCompilerBackEnd implements BatchCompilerBackEnd {

    /**
     * Creates a stateless ECJ back end.
     */
    public ECJCompilerBackEnd() {
        // No state: all compilation state lives in a per-request ECJCompilationRun.
    }

    /**
     * {@inheritDoc}
     *
     * <p>The source of the request is compiled as a Java 17 compilation unit whose top-level type is
     * {@link CompilationRequest#className()}. Sources that the compiler discovers while resolving
     * the initial unit are compiled as well, so the returned map contains every class of the
     * transitive source closure (including nested and anonymous classes).
     *
     * @throws io.github.nanoforged.adpters.janino.api.comp.CompilationException if the source has
     *                                                                           compile errors or if the ECJ pipeline fails for a non-diagnostic reason
     */
    @Override
    public Map<String, byte[]> compile(CompilationRequest request) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(request.className(), "request.className()");
        Objects.requireNonNull(request.source(), "request.source()");
        Objects.requireNonNull(request.typeResolver(), "request.typeResolver()");
        BatchCompilationRequest batch = new BatchCompilationRequest(Map.of(request.className(), request.source()), request.typeResolver());
        return new ECJCompilationRun(batch.roots(), batch.typeResolver()).runClassFiles();
    }

    /**
     * {@inheritDoc}
     *
     * <p>All roots are registered before the first compiler pass, including secondary top-level
     * types. Every generated class records the actual originating compilation unit resource.
     *
     * @throws IllegalArgumentException if two roots share a source resource
     */
    @Override
    public CompilationOutput compileBatch(BatchCompilationRequest request) {
        Objects.requireNonNull(request, "request");
        return new ECJCompilationRun(request.roots(), request.typeResolver()).run();
    }
}
