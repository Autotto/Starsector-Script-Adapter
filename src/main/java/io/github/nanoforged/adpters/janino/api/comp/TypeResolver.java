package io.github.nanoforged.adpters.janino.api.comp;

/**
 * Supplies source text and class files by binary class name without exposing resource storage to
 * the compiler. Implementations own resource lookup, decoding and parent/platform visibility.
 *
 * <p>A resource finder may back this interface, but does not replace those policies. A missing
 * answer is {@code null}; lookup failures must not be silently converted into misses. The compiler
 * normally requests binary bytes before source text. Implementations used with loader-local output
 * reuse must keep their underlying source and binary configuration stable for that loader lifetime.
 */
public interface TypeResolver {
    /**
     * Looks up source text for a binary class name.
     *
     * @param className dotted binary name, possibly containing a member-type {@code $} suffix
     * @return decoded source text, or {@code null} if no source is available
     */
    String findSource(String className);

    /**
     * Looks up a class file without defining or initializing its class.
     *
     * @param className dotted binary name, retaining any member-type {@code $} suffix
     * @return class file bytes, or {@code null} if no binary is available; callers must not mutate
     *         the returned bytes unless the implementation explicitly grants ownership
     */
    byte[] findBinary(String className);
}
