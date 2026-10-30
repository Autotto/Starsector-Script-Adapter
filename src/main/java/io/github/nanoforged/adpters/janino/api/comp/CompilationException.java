package io.github.nanoforged.adpters.janino.api.comp;

import java.util.List;

/**
 * Signals that a {@link CompilerBackEnd} could not translate a compilation request into class files.
 *
 * <p>The message is a human-readable rendering of the underlying compiler diagnostics. The
 * individual diagnostics remain available through {@link #diagnostics()} so that callers can map
 * them onto their own reporting channel (for example Janino's compile error handler).
 */
public final class CompilationException extends RuntimeException {

    private final List<String> diagnostics;

    /**
     * @param diagnostics non-empty list of formatted compiler diagnostics
     */
    public CompilationException(List<String> diagnostics) {
        super(String.join(System.lineSeparator(), diagnostics));
        if (diagnostics.isEmpty()) throw new IllegalArgumentException("diagnostics must not be empty");
        this.diagnostics = List.copyOf(diagnostics);
    }

    /**
     * @param message description of a failure that is not expressed as compiler diagnostics
     * @param cause   underlying failure
     */
    public CompilationException(String message, Throwable cause) {
        super(message, cause);
        this.diagnostics = List.of(message);
    }

    /**
     * @return immutable list of formatted diagnostics, never empty
     */
    public List<String> diagnostics() {
        return this.diagnostics;
    }
}
