package io.github.nanoforged.adpters.janino.api.comp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Explicit root compilation units sharing one resolver and source snapshot.
 *
 * <p>Only null and empty names are rejected here; Java name and source validity are checked by the
 * compiler. Backends reject aliases that map two roots to the same source resource.
 *
 * @param roots top-level binary name to source text, in compilation order
 * @param typeResolver resolver of dependencies not supplied as roots
 */
public record BatchCompilationRequest(Map<String, String> roots, TypeResolver typeResolver) {
    /**
     * Copies and validates the roots without querying the resolver.
     *
     * @param roots top-level binary name to source text; names must be nonempty
     * @param typeResolver dependency resolver
     * @throws NullPointerException if any argument, root name or source is null
     * @throws IllegalArgumentException if a root name is empty
     */
    public BatchCompilationRequest {
        Objects.requireNonNull(roots, "roots");
        Objects.requireNonNull(typeResolver, "typeResolver");
        Map<String, String> copy = new LinkedHashMap<>();
        roots.forEach((name, source) -> {
            Objects.requireNonNull(name, "root class name");
            Objects.requireNonNull(source, "source of '" + name + "'");
            if (name.isEmpty()) throw new IllegalArgumentException("root class name must not be empty");
            copy.put(name, source);
        });
        roots = Collections.unmodifiableMap(copy);
    }
}
