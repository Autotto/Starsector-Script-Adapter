package io.github.nanoforged.adpters.janino.api.comp;

/** Optional compiler capability for compiling explicit roots together in one source snapshot. */
public interface BatchCompilerBackEnd extends CompilerBackEnd {
    /**
     * Compiles all explicit roots and their transitive source dependencies.
     *
     * <p>Explicit roots take precedence over resolver binaries; non-root dependencies retain the
     * resolver's binary-first semantics. Callers must filter already loaded or parent-owned roots.
     * Empty roots produce empty output without querying the resolver. No partial output is returned
     * if any source fails. This method does not define classes.
     *
     * @param request explicit root sources and their shared resolver
     * @return successful class files and known source resource provenance
     * @throws CompilationException if compilation fails
     */
    CompilationOutput compileBatch(BatchCompilationRequest request);
}
