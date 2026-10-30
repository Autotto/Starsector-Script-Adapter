package io.github.nanoforged.adpters.janino;


import io.github.nanoforged.adpters.janino.api.ClassDefinitionDelegate;
import io.github.nanoforged.adpters.janino.api.CompilerContext;
import io.github.nanoforged.adpters.janino.api.comp.CompilerBackEnd;
import io.github.nanoforged.adpters.janino.api.comp.TypeResolver;
import io.github.nanoforged.adpters.janino.impl.backend.ecj.ECJCompilerBackEnd;
import io.github.nanoforged.adpters.janino.impl.delegate.LCLDelegate;
import io.github.nanoforged.adpters.janino.impl.delegate.UnsafeDelegate;
import io.github.nanoforged.adpters.janino.impl.resolver.DefaultTypeResolver;
import io.github.nanoforged.adpters.janino.impl.resolver.LRUTypeResolver;

import java.util.function.Function;

public final class JaninoAdpter {
    /**
     * The compatibility layer version; not the emulated Janino ABI version.
     */
    public static final String VERSION = "0.1.0";
    /**
     * Enables source-text LRU decoration of the configured resolver provider.
     */
    public static final String SOURCE_LRU_PROPERTY = "starsector.scriptAdapter.sourceLru";
    /**
     * Enables successful-output reuse for newly constructed source loaders.
     */
    public static final String COMPILED_REUSE_PROPERTY = "starsector.scriptAdapter.compiledReuse";

    private static Function<CompilerContext, TypeResolver> typeResolverFactory = DefaultTypeResolver::new;

    private static Function<CompilerContext, CompilerBackEnd> compilerBackEndFactory = ctx -> new ECJCompilerBackEnd();

    private static ClassDefinitionDelegate classDefinitionDelegate;

    private JaninoAdpter() {
    }

    public static void setTypeResolverProvider(Function<CompilerContext, TypeResolver> typeResolverProvider) {
        typeResolverFactory = typeResolverProvider;
    }

    public static void setCompilerBackEndProvider(Function<CompilerContext, CompilerBackEnd> compilerBackEndProvider) {
        compilerBackEndFactory = compilerBackEndProvider;
    }

    public static TypeResolver getTypeResolver(CompilerContext context) {
        TypeResolver resolver = typeResolverFactory.apply(context);
        return Boolean.getBoolean(SOURCE_LRU_PROPERTY) && !(resolver instanceof LRUTypeResolver) ? new LRUTypeResolver(resolver) : resolver;
    }

    public static CompilerBackEnd getCompilerBackEnd(CompilerContext context) {
        return compilerBackEndFactory.apply(context);
    }

    public static boolean hasDelegated() {
        return classDefinitionDelegate != null;
    }

    public static ClassDefinitionDelegate getClassDefinitionDelegate() {
        if (classDefinitionDelegate == null)
            throw new IllegalStateException("classDefinitionDelegate has not been initialized");
        return classDefinitionDelegate;
    }

    public static void setClassDefinitionDelegate(ClassLoader cl) {
        var name = cl.getClass().getName();
        switch (name) {
            case "net.minecraft.launchwrapper.LaunchClassLoader" -> {
                if (cl instanceof net.minecraft.launchwrapper.LaunchClassLoader lcl) {
                    classDefinitionDelegate = new LCLDelegate(lcl);
                }
            }
            default -> classDefinitionDelegate = new UnsafeDelegate(cl);
        }
    }
}
