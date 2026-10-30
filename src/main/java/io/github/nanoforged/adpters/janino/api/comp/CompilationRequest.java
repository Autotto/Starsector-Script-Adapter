package io.github.nanoforged.adpters.janino.api.comp;

public record CompilationRequest(String className, String source, TypeResolver typeResolver) {
}
