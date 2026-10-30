
/*
 * Janino - An embedded Java[TM] compiler
 *
 * Copyright (c) 2001-2010, Arno Unkrig
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * following conditions are met:
 *
 *    1. Redistributions of source code must retain the above copyright notice, this list of conditions and the
 *       following disclaimer.
 *    2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
 *       following disclaimer in the documentation and/or other materials provided with the distribution.
 *    3. The name of the author may not be used to endorse or promote products derived from this software without
 *       specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL
 * THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package org.codehaus.janino;

import io.github.nanoforged.adpters.janino.JaninoAdpter;
import io.github.nanoforged.adpters.janino.api.CompilerContext;
import io.github.nanoforged.adpters.janino.api.anno.Modified;
import io.github.nanoforged.adpters.janino.api.comp.*;
import io.github.nanoforged.adpters.janino.utils.ClassNameUtil;
import org.codehaus.commons.compiler.AbstractJavaSourceClassLoader;
import org.codehaus.commons.compiler.ErrorHandler;
import org.codehaus.commons.compiler.WarningHandler;
import org.codehaus.janino.util.resource.DirectoryResourceFinder;
import org.codehaus.janino.util.resource.PathResourceFinder;
import org.codehaus.janino.util.resource.Resource;
import org.codehaus.janino.util.resource.ResourceFinder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.*;


@Modified
public class JavaSourceClassLoader extends AbstractJavaSourceClassLoader {


    private final Map<String, byte[]> precompiledClasses = new HashMap<>();

    private final CompilerContext compilerContext;
    private final Map<String, byte[]> successfulClasses = new HashMap<>();
    private final Map<String, String> sourceResources = new HashMap<>();
    private final Map<String, Class<?>> delegatedClasses = new HashMap<>();
    private CompilerBackEnd compilerBackEnd;
    private TypeResolver typeResolver;
    private boolean compiledOutputReuseEnabled;
    private boolean compilationStarted;
    private Map<String, String> generatedOrigins = Map.of();
    private Map<String, byte[]> stagedCache = Map.of();
    private Set<String> compilingSources = Set.of();

    @Deprecated
    public JavaSourceClassLoader() {
        this(ClassLoader.getSystemClassLoader());
    }

    @Deprecated
    public JavaSourceClassLoader(ClassLoader parentClassLoader) {
        this(parentClassLoader, (File[]) null,     // optionalSourcePath
                null               // optionalCharacterEncoding
        );
    }

    public JavaSourceClassLoader(ClassLoader parentClassLoader, File[] optionalSourcePath, String optionalCharacterEncoding) {
        this(parentClassLoader,        // parentClassLoader
                (                         // sourceFinder
                        optionalSourcePath == null ? new DirectoryResourceFinder(new File(".")) : new PathResourceFinder(optionalSourcePath)), optionalCharacterEncoding);
    }

    public JavaSourceClassLoader(ClassLoader parentClassLoader, ResourceFinder sourceFinder, String optionalCharacterEncoding) {
        super(parentClassLoader);
        this.compilerContext = new CompilerContext(parentClassLoader, sourceFinder, optionalCharacterEncoding);
        this.compiledOutputReuseEnabled = Boolean.getBoolean(JaninoAdpter.COMPILED_REUSE_PROPERTY);
    }

    @Deprecated
    public JavaSourceClassLoader(ClassLoader parentClassLoader, JavaSourceIClassLoader iClassLoader) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    /**
     * Selects loader-private successful-output reuse before compilation starts.
     *
     * <p>Disabled unless the compiled-reuse system property is true at construction. This setter
     * overrides that initial setting before compilation. Enabled loaders pin successful output for their lifetime, including
     * bytes of classes already defined. Sources, parent binaries and resolver configuration must
     * remain stable; use a new loader to reload changed sources. This captures successful output,
     * not all source text: uncompiled sources are still read when first needed. External definition
     * delegates must provide their own runtime dependency bridge; retained compiler bytes do not
     * make a foreign target loader able to find pending dependencies. The global delegate must not
     * change while a loader is in use. Legacy backends without the batch
     * capability retain sequential compilation and do not reuse binaries across compilations.
     *
     * @param enabled whether successful outputs may answer later binary dependencies
     * @throws IllegalStateException if compilation or nonempty preparation has started
     */
    public synchronized void setCompiledOutputReuseEnabled(boolean enabled) {
        if (this.compilationStarted) throw new IllegalStateException("Compilation has already started");
        this.compiledOutputReuseEnabled = enabled;
    }

    /**
     * Prepares explicit roots without defining or initializing their classes.
     *
     * <p>A nonempty call enables stable output reuse and locks the policy. Parent-visible roots and
     * already prepared/defined roots are skipped. Capable backends compile remaining roots together
     * and publish pending output only after the entire batch succeeds. Legacy backends use the
     * existing sequential generation hook; earlier successful roots remain pending on later failure.
     * An empty collection is a no-op. This is not a force-recompile or hot-reload API.
     *
     * @param classNames binary names to prepare
     * @throws ClassNotFoundException if a root is missing or compilation fails
     * @throws IllegalStateException  if reuse was disabled and compilation already started
     */
    public synchronized void prepareClasses(Collection<String> classNames) throws ClassNotFoundException {
        Objects.requireNonNull(classNames, "classNames");

        if (classNames.isEmpty()) return;

        LinkedHashSet<String> names = new LinkedHashSet<>();

        for (String name : classNames) names.add(Objects.requireNonNull(name, "className"));

        if (this.compilationStarted && !this.compiledOutputReuseEnabled) {
            throw new IllegalStateException("Enable output reuse before compilation starts");
        }

        this.compiledOutputReuseEnabled = true;
        this.compilationStarted = true;

        if (!(this.getCompiler() instanceof BatchCompilerBackEnd)) {

            for (String name : names) {
                if (this.isAvailable(name)) continue;

                this.generatedOrigins = Map.of();

                Map<String, byte[]> output = this.generateBytecodes(name);

                if (output == null || !output.containsKey(name)) throw new ClassNotFoundException(name);

                this.acceptOutput(output, Map.of());
            }

            return;
        }
        Map<String, byte[]> cached = new LinkedHashMap<>();
        Map<String, String> roots = new LinkedHashMap<>();
        for (String name : names) {
            if (this.isAvailable(name)) continue;
            Map<String, byte[]> hit = this.findCachedBytecodes(name);
            if (hit != null) {
                cached.putAll(hit);
                continue;
            }
            String root = ClassNameUtil.toTopLevelBinaryName(name);
            if (!roots.containsKey(root)) roots.put(root, this.requireSource(name));
        }
        Map<String, byte[]> compiled = Map.of();
        Map<String, String> origins = Map.of();
        if (!roots.isEmpty()) {
            this.stagedCache = cached;
            try {
                CompilationOutput output = this.compileBatch(roots);
                compiled = output.classFiles();
                origins = output.sourceResources();
            } finally {
                this.stagedCache = Map.of();
            }
        }
        Map<String, byte[]> combined = new LinkedHashMap<>(cached);
        combined.putAll(compiled);
        for (String name : names) {
            if (!this.isAvailable(name) && !combined.containsKey(name)) throw new ClassNotFoundException(name);
        }
        this.validateOutput(combined, origins);
        this.storeGeneratedBytecodes(compiled);
        this.acceptOutput(combined, origins);
    }

    /**
     * Checks parent visibility without initializing classes or compiling local sources.
     */
    private boolean isAvailable(String name) throws ClassNotFoundException {
        try {
            Class.forName(name, false, this.getParent());
            return true;
        } catch (ClassNotFoundException absent) {
            return this.findLoadedClass(name) != null || this.delegatedClasses.containsKey(name) || this.precompiledClasses.containsKey(name);
        }
    }

    /**
     * Reads an explicit source, reporting absence using the loader exception contract.
     */
    private String requireSource(String name) throws ClassNotFoundException {
        Resource resource = this.compilerContext.finder().findResource(ClassNameUtil.getSourceResourceName(name));
        try {
            String source = resource == null ? null : this.readSource(resource);
            if (source == null) throw new ClassNotFoundException(name);
            return source;
        } catch (IOException ex) {
            throw new ClassNotFoundException("Reading source for " + name, ex);
        }
    }

    /**
     * Compiles against parent-first binary answers and then this loader's stable repository.
     */
    private CompilationOutput compileBatch(Map<String, String> roots) throws ClassNotFoundException {
        Set<String> families = new HashSet<>();
        for (String root : roots.keySet()) families.add(ClassNameUtil.getSourceResourceName(root));
        this.compilingSources = families;
        try {
            return ((BatchCompilerBackEnd) this.getCompiler()).compileBatch(new BatchCompilationRequest(roots, this.reusingResolver()));
        } catch (CompilationException ex) {
            throw new ClassNotFoundException(ex.getMessage(), ex);
        } finally {
            this.compilingSources = Set.of();
        }
    }

    /**
     * Creates an overlay; delegate exceptions and source callbacks are never swallowed.
     */
    private TypeResolver reusingResolver() {
        TypeResolver delegate = this.getTypeResolver();
        return new TypeResolver() {
            public byte[] findBinary(String name) {
                byte[] binary = delegate.findBinary(name);
                if (binary != null) return binary;
                String source = sourceResources.get(name);
                if (source != null && compilingSources.contains(source)) return null;
                byte[] local = stagedCache.get(name);
                if (local == null) local = successfulClasses.get(name);
                return local == null ? null : local.clone();
            }

            public String findSource(String name) {
                return delegate.findSource(name);
            }
        };
    }

    /**
     * Rejects changes before publishing any member of a successful compilation.
     */
    private void validateOutput(Map<String, byte[]> output, Map<String, String> origins) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(origins, "origins");

        for (Map.Entry<String, byte[]> entry : output.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "output name");
            Objects.requireNonNull(entry.getValue(), "output bytes");
        }

        for (Map.Entry<String, String> entry : origins.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "origin name");
            Objects.requireNonNull(entry.getValue(), "source resource");

            if (!output.containsKey(entry.getKey()))
                throw new IllegalStateException("Origin without output: " + entry.getKey());
            String oldOrigin = this.sourceResources.get(entry.getKey());
            if (oldOrigin != null && !oldOrigin.equals(entry.getValue())) {
                throw new IllegalStateException("Conflicting source origins for " + entry.getKey());
            }
        }
        if (!this.compiledOutputReuseEnabled || !(this.getCompiler() instanceof BatchCompilerBackEnd)) return;
        for (Map.Entry<String, byte[]> entry : output.entrySet()) {
            byte[] old = this.successfulClasses.get(entry.getKey());
            if (old != null && !Arrays.equals(old, entry.getValue())) {
                throw new IllegalStateException("Stable output changed: " + entry.getKey() + "; use a new loader");
            }
        }
        Set<String> replacedSources = new HashSet<>(origins.values());
        for (Map.Entry<String, String> entry : this.sourceResources.entrySet()) {
            if (replacedSources.contains(entry.getValue()) && !output.containsKey(entry.getKey())) {
                throw new IllegalStateException("Stable output removed: " + entry.getKey() + "; use a new loader");
            }
        }
    }

    /**
     * Publishes defensive copies; unknown disk-cache provenance is intentionally left unknown.
     */
    private void acceptOutput(Map<String, byte[]> output, Map<String, String> origins) {
        this.validateOutput(output, origins);
        boolean reuse = this.compiledOutputReuseEnabled && this.getCompiler() instanceof BatchCompilerBackEnd;
        for (Map.Entry<String, byte[]> entry : output.entrySet()) {
            String name = entry.getKey();
            boolean pending = this.findLoadedClass(name) == null && !this.delegatedClasses.containsKey(name);
            if (!reuse && !pending) continue;
            // Both maps are private: share one owned snapshot, copying only at external boundaries.
            byte[] owned = entry.getValue().clone();
            if (reuse) this.successfulClasses.put(name, owned);
            if (pending) this.precompiledClasses.put(name, owned);
        }
        if (reuse) this.sourceResources.putAll(origins);
    }

    /**
     * Looks up a persistent cache entry; the base loader has no persistent cache.
     *
     * @param name requested binary name
     * @return cached classes, or {@code null} on a miss; provenance is unknown
     * @throws ClassNotFoundException if cache I/O fails
     */
    protected Map<String, byte[]> findCachedBytecodes(String name) throws ClassNotFoundException {
        return null;
    }

    /**
     * Stores every newly generated class; the base loader does nothing.
     *
     * @param bytecodes freshly generated classes
     * @throws ClassNotFoundException if cache I/O fails
     */
    protected void storeGeneratedBytecodes(Map<String, byte[]> bytecodes) throws ClassNotFoundException {
    }

    @Override
    @Deprecated
    public void setSourcePath(File[] ignored) {
        //NO-OP
    }

    @Override
    @Deprecated
    public void setSourceFileCharacterEncoding(String ignored) {
        //NO-OP
    }

    @Override
    @Deprecated
    public void setDebuggingInfo(boolean debugSource, boolean debugLines, boolean debugVars) {
        //NO-OP

    }

    @Deprecated
    public void setCompileErrorHandler(ErrorHandler ignored) {
        //NO-OP
    }

    @Deprecated
    public void setWarningHandler(WarningHandler ignored) {
        //NO-OP
    }

    /**
     * Implementation of {@link ClassLoader#findClass(String)}.
     *
     * @throws ClassNotFoundException
     */
    @Override
    protected synchronized Class findClass(String name) throws ClassNotFoundException {
        Class<?> external = this.delegatedClasses.get(name);
        if (external != null) return external;
        Class<?> loaded = this.findLoadedClass(name);
        if (loaded != null) return loaded;
        byte[] bytecode = this.precompiledClasses.get(name);
        if (bytecode == null) {
            this.compilationStarted = true;
            this.generatedOrigins = Map.of();
            Map<String, byte[]> output = this.generateBytecodes(name);
            if (output == null || !output.containsKey(name)) throw new ClassNotFoundException(name);
            this.acceptOutput(output, this.generatedOrigins);
            bytecode = this.precompiledClasses.get(name);
        }
        Class<?> defined = this.defineBytecode(name, bytecode);
        this.precompiledClasses.remove(name);
        return defined;
    }

    /**
     * Find, scan, parse the right compilation unit. Compile the parsed compilation unit to
     * bytecode. This may cause more compilation units being scanned and parsed. Continue until
     * all compilation units are compiled.
     *
     * @return String name => byte[] bytecode, or <code>null</code> if no source code could be found
     * @throws ClassNotFoundException on compilation problems
     */
    protected synchronized Map<String, byte[]> generateBytecodes(String name) throws ClassNotFoundException {
        this.compilationStarted = true;
        this.generatedOrigins = Map.of();
        if (this.compiledOutputReuseEnabled && this.getCompiler() instanceof BatchCompilerBackEnd) {
            CompilationOutput output = this.compileBatch(Map.of(ClassNameUtil.toTopLevelBinaryName(name), this.requireSource(name)));
            Map<String, byte[]> classes = output.classFiles();
            this.generatedOrigins = output.sourceResources();
            this.validateOutput(classes, this.generatedOrigins);
            return classes;
        }
        Resource resource = this.compilerContext.finder().findResource(ClassNameUtil.getSourceResourceName(name));
        if (resource == null) return null;
        String source;
        try {
            source = this.readSource(resource);
        } catch (IOException ex) {
            throw new ClassNotFoundException("Reading source for " + name, ex);
        }
        if (source == null) return null;
        try {
            return this.getCompiler().compile(new CompilationRequest(ClassNameUtil.toTopLevelBinaryName(name), source, this.getTypeResolver()));
        } catch (CompilationException ex) {
            throw new ClassNotFoundException(ex.getMessage(), ex);
        }
    }

    /**
     * Reads a source resource with the encoding configured on this loader.
     *
     * @return source text, or <code>null</code> if the resource finder reports an unreadable resource
     */
    private String readSource(Resource resource) throws IOException {
        try (InputStream in = resource.open()) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int read; (read = in.read(buffer)) != -1; ) out.write(buffer, 0, read);
            return out.toString(this.sourceCharset());
        }
    }

    /**
     * @return the character encoding for source resources, defaulting to the platform encoding
     */
    private Charset sourceCharset() {
        String encoding = this.compilerContext.encode();
        return encoding == null ? Charset.defaultCharset() : Charset.forName(encoding);
    }


    private Class<?> defineBytecode(String className, byte[] ba) throws ClassNotFoundException {
        if (!JaninoAdpter.hasDelegated()) return defineClass(className, ba);
        Class<?> result = deleteDefineClass(className, ba.clone());
        if (result == null || !className.equals(result.getName())) {
            throw new IllegalStateException("Class definition delegate returned no matching class for " + className);
        }
        this.delegatedClasses.put(className, result);
        return result;
    }


    // Adpter Addition Start
    private Class<?> defineClass(String className, byte[] ba) {
        return this.defineClass(className, ba, 0, ba.length, (this.optionalProtectionDomainFactory == null ? null : this.optionalProtectionDomainFactory.getProtectionDomain(this.sourceResources.getOrDefault(className, ClassNameUtil.getSourceResourceName(className)))));
    }

    private Class<?> deleteDefineClass(String className, byte[] ba) throws ClassNotFoundException {
        return JaninoAdpter.getClassDefinitionDelegate().defineClasses(className, ba, 0, ba.length, (this.optionalProtectionDomainFactory == null ? null : this.optionalProtectionDomainFactory.getProtectionDomain(this.sourceResources.getOrDefault(className, ClassNameUtil.getSourceResourceName(className)))));
    }

    private CompilerBackEnd getCompiler() {
        if (this.compilerBackEnd == null) {
            this.compilerBackEnd = JaninoAdpter.getCompilerBackEnd(compilerContext);
        }
        return compilerBackEnd;
    }

    private TypeResolver getTypeResolver() {
        if (this.typeResolver == null) {
            this.typeResolver = JaninoAdpter.getTypeResolver(compilerContext);
        }
        return this.typeResolver;
    }
    // Adpter Addition END

}
