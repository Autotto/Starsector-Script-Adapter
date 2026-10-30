package io.github.nanoforged.adpters.janino.impl.resolver;

import io.github.nanoforged.adpters.janino.api.CompilerContext;
import io.github.nanoforged.adpters.janino.api.comp.CompilationException;
import io.github.nanoforged.adpters.janino.api.comp.TypeResolver;
import io.github.nanoforged.adpters.janino.utils.ClassNameUtil;
import org.codehaus.janino.util.resource.Resource;
import org.codehaus.janino.util.resource.ResourceFinder;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.*;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link TypeResolver} of the adapter: it feeds {@code .java} sources and {@code .class}
 * files found by the surrounding game class loaders into the compiler back end.
 *
 * <p><strong>Source resolution.</strong> A binary class name such as {@code a.b.C} or
 * {@code a.b.Outer$Inner} is translated with
 * {@link ClassNameUtil#getSourceResourceName(String)} into a source resource name
 * ({@code a/b/Outer.java} for the nested example, because the source of a nested type lives in the
 * file of its outermost declaring class). The resource is then looked up through the
 * {@link ResourceFinder} of the {@link CompilerContext} and read as a whole. The bytes are decoded
 * with the context encoding; a {@code null} encoding selects the platform default charset.
 *
 * <p><strong>Binary resolution.</strong> A binary class name such as {@code a.b.Outer$Inner} is
 * translated with {@link ClassNameUtil#getClassFileResourceName(String)} into
 * {@code a/b/Outer$Inner.class}; nested type names keep their {@code $} suffix and are
 * <em>not</em> reduced to the outermost type. The class file is first requested from the context's
 * parent class loader ({@link ClassLoader#getResourceAsStream(String)}), so the game's own classes
 * and libraries are visible. Only if that fails is the Java runtime image file system
 * ({@code jrt:/}, JEP 220) consulted; this fallback is required because platform types such as
 * {@link Object} or {@link java.util.List} have no class file on a regular class path since
 * Java 9. If neither source has the class, {@code null} is returned.
 *
 * <p><strong>Runtime image lookup.</strong> Platform classes are located through the package index
 * that JEP 220 defines for a standard Java runtime image: for a packaged class,
 * {@code /packages/<package>} lists the modules that contain the package, so only those modules are
 * checked instead of scanning the whole image. An indexed miss - the package is not in the index,
 * or it is but none of its modules contains the class file - returns {@code null} immediately.
 * This deliberately relies on the JEP 220 contract that a standard runtime image has a complete,
 * immutable package index; the mere presence of a {@code /packages} directory does not prove that
 * an unusual or malformed image has a complete index, so full-scan equivalence is not claimed for
 * such images. To keep those cases from getting worse, a runtime image whose whole
 * {@code /packages} directory is missing falls back to scanning {@code /modules}, and
 * default-package names, which named modules cannot contain, always use that scan.
 *
 * <p><strong>State and threading.</strong> The resolver is immutable except for two caches: the
 * lazily resolved source {@link Charset} and the per-instance binary cache
 * ({@link ConcurrentHashMap}). Both are safe for concurrent use. The binary cache stores byte
 * arrays that no caller can mutate (every read returns a copy) and never caches file systems,
 * streams, paths or class loaders. It is not shared between resolver instances, so resolvers bound
 * to different parent class loaders cannot observe each other's results. A resolver is intended to
 * live as long as the {@link CompilerContext} it was created from.
 *
 * <p><strong>Known limitations.</strong> Classes that only exist at runtime, for example classes
 * that the game's source class loader generated and defined from an earlier compilation, have no
 * {@code .class} resource the parent class loader can return, and they are not part of the Java
 * runtime image either; {@link #findBinary(String)} therefore cannot resolve them. The same holds
 * for classes that were compiled without a class file being written anywhere. Such definitions
 * must be supplied separately, for example by the loader-private successful-output overlay when
 * that mode is enabled. This resolver itself does not retain generated output. The binary cache
 * is unbounded and never invalidated, which is acceptable because
 * a compiler context lives for a single game session.
 *
 * @see CompilerContext
 * @see TypeResolver
 */
public final class DefaultTypeResolver implements TypeResolver {

    /**
     * URI of the Java runtime image file system (JEP 220).
     */
    private static final URI JRT_URI = URI.create("jrt:/");

    /**
     * Cache marker for names without a class file; a class file is never empty.
     */
    private static final byte[] NOT_FOUND = new byte[0];

    /**
     * Class loader consulted first for class files; {@code null} means the system loader.
     */
    private final ClassLoader parent;

    /**
     * Finder consulted for source files; {@code null} means no sources are available.
     */
    private final ResourceFinder finder;

    /**
     * Encoding name from the compiler context; {@code null} means the platform default.
     */
    private final String encoding;

    /**
     * Per-instance cache of resolved class files, including the {@link #NOT_FOUND} marker.
     */
    private final ConcurrentHashMap<String, byte[]> binaryCache = new ConcurrentHashMap<>();

    /**
     * Decoded source charset; assigned on the first source lookup.
     */
    private volatile Charset sourceCharset;

    /**
     * Creates a resolver for the given compiler context.
     *
     * <p>The context's parent class loader, resource finder and source encoding are captured; the
     * context itself is not retained. Both the class loader and the finder may be {@code null}: a
     * {@code null} class loader is replaced by the system class loader and a {@code null} finder
     * makes {@link #findSource(String)} report every source as absent.
     *
     * <p>The source encoding is resolved lazily on the first source lookup, so a context with an
     * unsupported encoding can still be used for binary resolution only.
     *
     * @param context compiler context providing the parent class loader, the source finder and the
     *                source encoding
     * @throws NullPointerException if {@code context} is {@code null}
     */
    public DefaultTypeResolver(CompilerContext context) {
        Objects.requireNonNull(context, "context");
        this.parent = context.parent();
        this.finder = context.finder();
        this.encoding = context.encode();
    }

    /**
     * Locates a class file in the Java runtime image.
     *
     * <p>For a packaged class this uses the JEP 220 package index ({@code /packages/<package>}),
     * whose entries link the modules that contain the package, and returns {@code null} on an
     * indexed miss instead of scanning the whole image. Only two cases fall back to scanning
     * {@code /modules}: a runtime image that has no {@code /packages} directory at all, and
     * default-package names, which named modules cannot contain.
     *
     * <p>Package-private solely so tests can drive the lookup with a synthetic file system; this is
     * not API.
     *
     * @param runtimeImage runtime image file system
     * @param className    binary class name
     * @param resourceName class file resource name
     * @return the class file path, or {@code null} if the runtime image does not contain it
     * @throws IOException if the runtime image cannot be listed
     */
    static Path findPlatformClassFile(FileSystem runtimeImage, String className, String resourceName) throws IOException {
        int lastDot = className.lastIndexOf('.');
        if (lastDot > 0) {
            Path packageIndex = runtimeImage.getPath("/packages");
            if (Files.isDirectory(packageIndex)) {
                Path packageDirectory = packageIndex.resolve(className.substring(0, lastDot));
                return getPath(resourceName, packageDirectory);
            }
        }
        return scanModules(runtimeImage, resourceName);
    }

    /**
     * Scans every module of the runtime image for a class file resource.
     *
     * <p>This is the compatibility path for default-package names and for runtime images without a
     * package index; packaged lookups use the {@code /packages} index instead.
     *
     * @param runtimeImage runtime image file system
     * @param resourceName class file resource name
     * @return the class file path, or {@code null} if no module contains it
     * @throws IOException if the runtime image cannot be listed
     */
    private static Path scanModules(FileSystem runtimeImage, String resourceName) throws IOException {
        Path modulesDirectory = runtimeImage.getPath("/modules");
        return getPath(resourceName, modulesDirectory);
    }

    private static Path getPath(String resourceName, Path modulesDirectory) throws IOException {
        if (!Files.isDirectory(modulesDirectory)) return null;
        try (DirectoryStream<Path> modules = Files.newDirectoryStream(modulesDirectory)) {
            for (Path module : modules) {
                Path candidate = module.resolve(resourceName);
                if (Files.isRegularFile(candidate)) return candidate;
            }
        }
        return null;
    }

    /**
     * Returns the file system of the running Java runtime image.
     *
     * @return the {@code jrt:/} file system, or {@code null} if this runtime image does not provide
     * one, in which case platform classes cannot be resolved
     */
    private static FileSystem jrtFileSystem() {
        try {
            return FileSystems.getFileSystem(JRT_URI);
        } catch (FileSystemNotFoundException e) {
            // The JRT file system is created lazily on some runtimes; open it on demand.
            try {
                return FileSystems.newFileSystem(JRT_URI, Map.of());
            } catch (FileSystemAlreadyExistsException e2) {
                return FileSystems.getFileSystem(JRT_URI);
            } catch (IOException | ProviderNotFoundException | UnsupportedOperationException e2) {
                return null;
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Nested class names are reduced to the source file of their outermost declaring class. A
     * resource that cannot be found or cannot be opened yields {@code null}; a resource that is
     * found but cannot be read is reported as a {@link CompilationException}.
     *
     * @throws IllegalArgumentException if the configured source encoding is not a supported charset
     * @throws CompilationException     if the source resource exists but cannot be read
     * @throws NullPointerException     if {@code className} is {@code null}
     */
    @Override
    public String findSource(String className) {
        Objects.requireNonNull(className, "className");
        if (this.finder == null) return null;

        // Resolve the charset before any lookup so that a misconfigured encoding is always reported,
        // even if the requested class has no source.
        Charset charset = this.sourceCharset();
        String resourceName = ClassNameUtil.getSourceResourceName(className);
        Resource resource = this.finder.findResource(resourceName);
        if (resource == null) return null;

        try (InputStream in = resource.open()) {
            if (in == null) return null;
            return new String(in.readAllBytes(), charset);
        } catch (IOException e) {
            throw new CompilationException("Failed to read source resource '" + resourceName + "' for class '" + className + "'", e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>The class file is requested from the parent class loader first; platform classes that no
     * class loader exposes class files for are read from the Java runtime image. Results, including
     * negative results, are cached per resolver instance and never shared with other resolvers, so
     * a cache cannot mask a class that a different parent class loader does provide.
     *
     * <p>The returned array is a defensive copy: it can be modified by the caller without
     * corrupting the cache.
     *
     * @throws CompilationException if an existing class file resource cannot be read
     * @throws NullPointerException if {@code className} is {@code null}
     */
    @Override
    public byte[] findBinary(String className) {
        Objects.requireNonNull(className, "className");

        byte[] cached = this.binaryCache.get(className);
        if (cached == null) {
            byte[] loaded = this.loadBinary(className);
            byte[] candidate = loaded == null ? NOT_FOUND : loaded;
            // putIfAbsent keeps the first result in case two threads resolve the same name concurrently.
            byte[] existing = this.binaryCache.putIfAbsent(className, candidate);
            cached = existing == null ? candidate : existing;
        }
        return cached == NOT_FOUND ? null : cached.clone();
    }

    /**
     * Resolves the charset that source resources are decoded with.
     *
     * @return the configured charset, or the platform default charset if no encoding is configured
     * @throws IllegalArgumentException if the configured encoding is not a supported charset name
     */
    private Charset sourceCharset() {
        Charset charset = this.sourceCharset;
        if (charset == null) {
            charset = this.resolveSourceCharset();
            this.sourceCharset = charset;
        }
        return charset;
    }

    /**
     * Maps the configured encoding name to a {@link Charset}.
     *
     * @return the charset selected by the context encoding, or the platform default charset
     * @throws IllegalArgumentException if the encoding is neither a legal nor a supported charset
     *                                  name; both {@link IllegalCharsetNameException} and
     *                                  {@link UnsupportedCharsetException} are wrapped so that the
     *                                  origin of the bad configuration is clear
     */
    private Charset resolveSourceCharset() {
        if (this.encoding == null) return Charset.defaultCharset();
        try {
            return Charset.forName(this.encoding);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            throw new IllegalArgumentException("Invalid source encoding '" + this.encoding + "' in the compiler context", e);
        }
    }

    /**
     * Resolves class file bytes without the cache.
     *
     * @param className binary class name
     * @return the class file bytes, or {@code null} if neither the parent class loader nor the Java
     * runtime image has them
     * @throws CompilationException if an existing class file resource cannot be read
     */
    private byte[] loadBinary(String className) {
        String resourceName = ClassNameUtil.getClassFileResourceName(className);

        ClassLoader loader = this.parent != null ? this.parent : ClassLoader.getSystemClassLoader();
        if (loader != null) {
            try (InputStream in = loader.getResourceAsStream(resourceName)) {
                if (in != null) return in.readAllBytes();
            } catch (IOException e) {
                throw new CompilationException("Failed to read class file '" + resourceName + "' for class '" + className + "' from its class loader", e);
            }
        }

        return this.loadPlatformBinary(className, resourceName);
    }

    /**
     * Reads a platform class file from the Java runtime image.
     *
     * @param className    binary class name
     * @param resourceName class file resource name, e.g. {@code java/lang/Object.class}
     * @return the class file bytes, or {@code null} if the runtime image has no such class
     * @throws CompilationException if the runtime image file system exists but the class file
     *                              cannot be read
     */
    private byte[] loadPlatformBinary(String className, String resourceName) {
        FileSystem runtimeImage = jrtFileSystem();
        if (runtimeImage == null) return null;
        try {
            Path classFile = findPlatformClassFile(runtimeImage, className, resourceName);
            return classFile == null ? null : Files.readAllBytes(classFile);
        } catch (IOException e) {
            throw new CompilationException("Failed to read platform class file '" + resourceName + "' for class '" + className + "' from the Java runtime image", e);
        }
    }
}
