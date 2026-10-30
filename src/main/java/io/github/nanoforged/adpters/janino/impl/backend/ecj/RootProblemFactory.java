package io.github.nanoforged.adpters.janino.impl.backend.ecj;

import org.eclipse.jdt.core.compiler.CategorizedProblem;
import org.eclipse.jdt.internal.compiler.IProblemFactory;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory;

import java.util.Locale;

/**
 * Read-only diagnostic factory shared across runs without retaining compilation state.
 * The mutable ECJ delegate is private and never reconfigured after safe static publication.
 * Message formatting uses per-call state, and each problem remains a separate object.
 */
final class RootProblemFactory implements IProblemFactory {
    static final IProblemFactory INSTANCE = new RootProblemFactory();
    private final DefaultProblemFactory delegate = new DefaultProblemFactory(Locale.ROOT);

    private RootProblemFactory() { }

    @Override
    public CategorizedProblem createProblem(char[] file, int id, String[] arguments,
            String[] messages, int severity, int start, int end, int line, int column) {
        return delegate.createProblem(file, id, arguments, messages, severity, start, end, line, column);
    }

    @Override
    public CategorizedProblem createProblem(char[] file, int id, String[] arguments, int elaboration,
            String[] messages, int severity, int start, int end, int line, int column) {
        return delegate.createProblem(file, id, arguments, elaboration, messages, severity, start, end, line, column);
    }

    @Override
    public Locale getLocale() { return Locale.ROOT; }

    @Override
    public String getLocalizedMessage(int id, String[] arguments) {
        return delegate.getLocalizedMessage(id, arguments);
    }

    @Override
    public String getLocalizedMessage(int id, int elaboration, String[] arguments) {
        return delegate.getLocalizedMessage(id, elaboration, arguments);
    }
}
