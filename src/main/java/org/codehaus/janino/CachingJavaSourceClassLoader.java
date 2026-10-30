
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

import io.github.nanoforged.adpters.janino.utils.ClassNameUtil;
import org.codehaus.janino.util.resource.*;

import java.io.*;
import java.util.Map;

/**
 * A {@link org.codehaus.janino.JavaSourceClassLoader} that uses a resource storage provided by the application to cache
 * compiled classes and thus saving unnecessary recompilations.
 * <p>
 * The application provides access to the resource storeage through a pair of a {@link
 * org.codehaus.janino.util.resource.ResourceFinder} and a {@link org.codehaus.janino.util.resource.ResourceCreator}
 * (see {@link #CachingJavaSourceClassLoader(ClassLoader, ResourceFinder, String, ResourceFinder, ResourceCreator)}.
 * <p>
 * See {@link org.codehaus.janino.JavaSourceClassLoader#main(String[])} for an example how to use this class.
 * <p>
 * <b>Notice:</b> You must NOT rely on that this class stores some particular data in some particular resources through
 * the given {@code classFileCacheResourceFinder/Creator}! These serve only as a means for the {@link
 * CachingJavaSourceClassLoader} to persistently cache some data between invocations. In other words: If you want to
 * compile {@code .java} files into {@code .class} files, then don't use <i>this</i> class but {@link Compiler}
 * instead!
 */
public class CachingJavaSourceClassLoader extends JavaSourceClassLoader {
    private final ResourceFinder classFileCacheResourceFinder;
    private final ResourceCreator classFileCacheResourceCreator;
    private final ResourceFinder sourceFinder;

    /**
     * See {@link #CachingJavaSourceClassLoader(ClassLoader, ResourceFinder, String, ResourceFinder, ResourceCreator)}.
     *
     * @param optionalSourcePath Directories to scan for source files
     * @param cacheDirectory     Directory to use for caching generated class files (see class description)
     */
    public CachingJavaSourceClassLoader(ClassLoader parentClassLoader, File[] optionalSourcePath, String optionalCharacterEncoding, File cacheDirectory) {
        this(parentClassLoader, (optionalSourcePath == null ? (ResourceFinder) new DirectoryResourceFinder(new File(".")) : (ResourceFinder) new PathResourceFinder(optionalSourcePath)), optionalCharacterEncoding,                   // optionalCharacterEncoding
                new DirectoryResourceFinder(cacheDirectory), // classFileCacheResourceFinder
                new DirectoryResourceCreator(cacheDirectory) // classFileCacheResourceCreator
        );
    }

    /**
     * Notice that this class is thread-safe if and only if the {@code classFileCacheResourceCreator} stores its data
     * atomically, i.e. the {@code classFileCacheResourceFinder} sees the resource written by the {@code
     * classFileCacheResourceCreator} only after the {@link OutputStream} is closed.
     * <p>
     * In order to make the caching scheme work, both the {@code classFileCacheResourceFinder} and the {@code
     * sourceFinder} must support the {@link org.codehaus.janino.util.resource.Resource#lastModified()} method, so that
     * the modification time of the source and the class files can be compared.
     *
     * @param parentClassLoader             Attempt to load classes through this one before looking for source files
     * @param sourceFinder                  Finds Java&trade; source for class {@code pkg.Cls} in resource {@code
     *                                      pkg/Cls.java}
     * @param optionalCharacterEncoding     Encoding of Java&trade; source or {@code null} for platform default
     *                                      encoding
     * @param classFileCacheResourceFinder  Finds precompiled class {@code pkg.Cls} in resource {@code pkg/Cls.class}
     *                                      (see class description)
     * @param classFileCacheResourceCreator Stores compiled class {@code pkg.Cls} in resource {@code pkg/Cls.class} (see
     *                                      class description)
     */
    public CachingJavaSourceClassLoader(ClassLoader parentClassLoader, ResourceFinder sourceFinder, String optionalCharacterEncoding, ResourceFinder classFileCacheResourceFinder, ResourceCreator classFileCacheResourceCreator) {
        super(parentClassLoader, sourceFinder, optionalCharacterEncoding);
        this.classFileCacheResourceFinder = classFileCacheResourceFinder;
        this.classFileCacheResourceCreator = classFileCacheResourceCreator;
        this.sourceFinder = sourceFinder;
    }

    /**
     * Reads all bytes from the given resource.
     */
    private static byte[] readResource(Resource r) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];

        try (InputStream is = r.open()) {
            for (; ; ) {
                int cnt = is.read(buffer);
                if (cnt == -1) break;
                baos.write(buffer, 0, cnt);
            }
        } catch (IOException ignored) {
        }

        return baos.toByteArray();
    }

    /**
     * Create a resource with the given name and store the data in it.
     */
    private static void writeResource(ResourceCreator resourceCreator, String resourceName, byte[] data) throws IOException {
        try (OutputStream os = resourceCreator.createResource(resourceName)) {
            os.write(data);
        } catch (IOException ignored) {
        }
    }

    /**
     * Override {@link JavaSourceClassLoader#generateBytecodes(String)} to implement class file caching.
     *
     * @return String name => byte[] bytecode, or {@code null} if no source code could be found
     * @throws ClassNotFoundException Compilation problems or class file cache I/O problems
     */
    @Override
    protected synchronized Map<String, byte[]> generateBytecodes(String className) throws ClassNotFoundException {
        Map<String, byte[]> cached = this.findCachedBytecodes(className);
        if (cached != null) return cached;
        Map<String, byte[]> generated = super.generateBytecodes(className);
        if (generated != null) this.storeGeneratedBytecodes(generated);
        return generated;
    }

    /**
     * Reads a single timestamp-valid cached class; its source provenance remains unknown.
     * Cached files are not a source closure or a hot-reload protocol. Timestamp equality is a miss.
     *
     * @param className requested binary name
     * @return singleton bytecode map or {@code null} on a miss
     * @throws ClassNotFoundException if cache reading fails
     */
    @Override
    protected Map<String, byte[]> findCachedBytecodes(String className) throws ClassNotFoundException {
        Resource cached = this.classFileCacheResourceFinder.findResource(ClassNameUtil.getClassFileResourceName(className));
        if (cached == null) return null;
        Resource source = this.sourceFinder.findResource(ClassNameUtil.getSourceResourceName(className));
        if (source == null || source.lastModified() >= cached.lastModified()) return null;
        try {
            return Map.of(className, readResource(cached));
        } catch (IOException ex) {
            throw new ClassNotFoundException("Reading class file for " + className, ex);
        }
    }

    /**
     * Stores all fresh output, preserving the existing best-effort cache I/O behavior.
     *
     * @param bytecodes freshly generated classes, including dependencies and nested classes
     * @throws ClassNotFoundException if a cache operation reports an I/O failure
     */
    @Override
    protected void storeGeneratedBytecodes(Map<String, byte[]> bytecodes) throws ClassNotFoundException {
        for (Map.Entry<String, byte[]> entry : bytecodes.entrySet()) {
            try {
                writeResource(this.classFileCacheResourceCreator, ClassNameUtil.getClassFileResourceName(entry.getKey()), entry.getValue());
            } catch (IOException ex) {
                throw new ClassNotFoundException("Writing class file for " + entry.getKey(), ex);
            }
        }
    }

}
