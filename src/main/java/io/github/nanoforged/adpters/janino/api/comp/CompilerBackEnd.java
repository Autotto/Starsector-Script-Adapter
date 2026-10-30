package io.github.nanoforged.adpters.janino.api.comp;

import java.util.Map;

@FunctionalInterface
public interface CompilerBackEnd {
    Map<String, byte[]> compile(CompilationRequest request);
}
