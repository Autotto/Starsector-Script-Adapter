package io.github.nanoforged.adpters.janino.api.comp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Successful compilation output with explicit source provenance, independent of class definition.
 *
 * <p>Unknown provenance is omitted, never inferred from the emitted name: a secondary top-level
 * type can originate in another type's source file. ECJ supplies provenance for every output.
 *
 * @param classFiles dotted binary class name to class file bytes
 * @param sourceResources emitted binary class name to slash-separated originating source resource
 */
public record CompilationOutput(Map<String, byte[]> classFiles, Map<String, String> sourceResources) {
    /**
     * Takes a defensive snapshot of bytes and provenance.
     *
     * @param classFiles generated class files
     * @param sourceResources known provenance for generated classes only
     * @throws NullPointerException if a map, key or value is null
     * @throws IllegalArgumentException if provenance names a class absent from the output
     */
    public CompilationOutput {
        classFiles = copyClassFiles(classFiles);
        Map<String, String> origins = new LinkedHashMap<>();
        Objects.requireNonNull(sourceResources, "sourceResources").forEach((name, resource) -> {
            Objects.requireNonNull(name, "provenance class name");
            Objects.requireNonNull(resource, "source resource");
            origins.put(name, resource);
        });
        if (!classFiles.keySet().containsAll(origins.keySet())) {
            throw new IllegalArgumentException("provenance must refer to generated classes");
        }
        sourceResources = Collections.unmodifiableMap(origins);
    }

    /**
     * Returns a deep copy, so callers cannot mutate the successful output snapshot.
     *
     * @return immutable map containing fresh class file byte arrays
     */
    @Override
    public Map<String, byte[]> classFiles() {
        return copyClassFiles(this.classFiles);
    }

    /** Copies a class file map and all its byte arrays. */
    private static Map<String, byte[]> copyClassFiles(Map<String, byte[]> files) {
        Map<String, byte[]> copy = new LinkedHashMap<>();
        Objects.requireNonNull(files, "classFiles").forEach((name, bytes) -> copy.put(
                Objects.requireNonNull(name, "class name"),
                Objects.requireNonNull(bytes, "class bytes").clone()));
        return Collections.unmodifiableMap(copy);
    }
}
