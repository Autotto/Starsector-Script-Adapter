package io.github.nanoforged.adpters.janino.impl.backend.ecj;

import io.github.nanoforged.adpters.janino.api.comp.CompilationException;
import io.github.nanoforged.adpters.janino.api.comp.TypeResolver;
import io.github.nanoforged.adpters.janino.utils.ClassNameUtil;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileReader;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFormatException;
import org.eclipse.jdt.internal.compiler.env.INameEnvironment;
import org.eclipse.jdt.internal.compiler.env.NameEnvironmentAnswer;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * ECJ name environment that answers every type lookup through a request's {@link TypeResolver}.
 *
 * <p>The lookup order is:
 * <ol>
 *   <li>class files produced by an earlier compilation round of the same request (this lets later
 *       rounds link against the classes that were already generated),</li>
 *   <li>{@link TypeResolver#findBinary(String)} for classes that already exist as byte code,
 *       including platform classes,</li>
 *   <li>{@link TypeResolver#findSource(String)} for sources that the compiler has not seen yet. The
 *       resulting unit is registered in the run's discovered-unit map and compiled by the current
 *       compiler round (or, if it is discovered too late, by a following round).</li>
 * </ol>
 *
 * <p>The environment is intentionally not module aware: ECJ then does not require
 * {@code IModuleAwareNameEnvironment} and queries platform types like any other binary. It is
 * confined to the thread that drives the compiler.
 */
final class ECJNameEnvironment implements INameEnvironment {

    /**
     * Resolver of the compilation request.
     */
    private final TypeResolver typeResolver;

    /**
     * Classes already produced by this compilation request (binary name to class file bytes).
     */
    private final Map<String, byte[]> generatedClasses;

    /**
     * Sources discovered so far (slash separated source file name to unit), shared with the run.
     */
    private final Map<String, ECJCompilationUnit> discoveredUnits;

    /**
     * Package paths that are in fact declared type names, for example {@code sample.Simple}; shared
     * with the run and used by {@link #isPackage(char[][], char[])}.
     */
    private final Set<String> declaredTypePackagePaths;

    /**
     * Creates a name environment.
     *
     * @param typeResolver             resolver of the compilation request
     * @param generatedClasses         class files produced so far by the request; read-only here
     * @param discoveredUnits          source units discovered so far; new units are added to it
     * @param declaredTypePackagePaths package paths of types declared by discovered units; new type
     *                                 paths are added to it
     */
    ECJNameEnvironment(TypeResolver typeResolver, Map<String, byte[]> generatedClasses, Map<String, ECJCompilationUnit> discoveredUnits, Set<String> declaredTypePackagePaths) {
        this.typeResolver = typeResolver;
        this.generatedClasses = generatedClasses;
        this.discoveredUnits = discoveredUnits;
        this.declaredTypePackagePaths = declaredTypePackagePaths;
    }

    /**
     * Builds a binary answer from class file bytes.
     *
     * @param binaryName dotted binary name the bytes belong to
     * @param bytes      class file bytes
     * @return the ECJ answer
     * @throws CompilationException if the bytes are not a valid class file
     */
    private static NameEnvironmentAnswer binaryAnswer(String binaryName, byte[] bytes) {
        String fileName = ClassNameUtil.getClassFileResourceName(binaryName);
        try {
            ClassFileReader reader = ClassFileReader.read(bytes, fileName);
            return new NameEnvironmentAnswer(reader, null);
        } catch (ClassFormatException | IOException e) {
            throw new CompilationException("Cannot read class file '" + fileName + "' for type '" + binaryName + "'", e);
        }
    }

    /**
     * Joins a compound type name with dots.
     *
     * @param compoundTypeName component names as supplied by ECJ
     * @return the dotted binary name
     */
    private static String dottedName(char[][] compoundTypeName) {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < compoundTypeName.length; i++) {
            if (i > 0) name.append('.');
            name.append(compoundTypeName[i]);
        }
        return name.toString();
    }

    /**
     * Joins a package and type name with dots.
     *
     * @param packageName package components, {@code null} or empty for the default package
     * @param typeName    simple type name
     * @return the dotted binary name
     */
    private static String dottedName(char[][] packageName, char[] typeName) {
        StringBuilder name = new StringBuilder();
        if (packageName != null) {
            for (char[] segment : packageName) {
                name.append(segment).append('.');
            }
        }
        return name.append(typeName).toString();
    }

    /**
     * Registers a discovered source unit.
     *
     * <p>Units are kept once per source file: a file can be the answer for several of its types, and
     * reusing the first unit keeps ECJ's file name based bookkeeping consistent across lookups. The
     * declared type names of the unit are recorded so that {@link #isPackage(char[][], char[])} does
     * not mistake a type for a package.
     *
     * @param unit the discovered unit
     * @return the unit already known for that source file, or the given unit if it is new
     */
    ECJCompilationUnit register(ECJCompilationUnit unit) {
        String fileName = unit.fileNameAsString();
        ECJCompilationUnit known = this.discoveredUnits.get(fileName);
        if (known != null) return known;
        this.discoveredUnits.put(fileName, unit);
        this.declaredTypePackagePaths.addAll(unit.declaredTypePackagePaths());
        return unit;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NameEnvironmentAnswer findType(char[][] compoundTypeName) {
        if (compoundTypeName == null || compoundTypeName.length == 0) return null;
        return findType(dottedName(compoundTypeName));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NameEnvironmentAnswer findType(char[] typeName, char[][] packageName) {
        if (typeName == null) return null;
        return findType(dottedName(packageName, typeName));
    }

    /**
     * {@inheritDoc}
     *
     * <p>A {@link TypeResolver} can answer single types but cannot enumerate the packages they
     * belong to, so package questions are answered conservatively:
     * <ul>
     *   <li>a top-level package name is always accepted; answering {@code false} here would make
     *       the compiler reject the implicit {@code import java.lang.*} and every explicit
     *       {@code import com.example.*} before the resolver is asked for a single type,</li>
     *   <li>a sub-package name is accepted unless the resulting package path is already known to be
     *       a declared type name (for example {@code sample.Simple} when {@code Simple} is declared
     *       in package {@code sample}). ECJ asks this question to detect types that collide with
     *       packages, and a type name must not be answered as a package.</li>
     * </ul>
     *
     * <p>The conservative answers only turn some "package cannot be resolved" diagnostics into
     * "type cannot be resolved" messages; they never reject valid code.
     */
    @Override
    public boolean isPackage(char[][] parentPackageName, char[] packageName) {
        if (packageName == null) return false;
        if (parentPackageName == null || parentPackageName.length == 0) return true;
        StringBuilder packagePath = new StringBuilder();
        for (char[] segment : parentPackageName) packagePath.append(segment).append('.');
        packagePath.append(packageName);
        return !this.declaredTypePackagePaths.contains(packagePath.toString());
    }

    /**
     * {@inheritDoc}
     *
     * <p>There are no external resources to release: the resolver and the result maps belong to the
     * compilation run and become garbage together with this environment.
     */
    @Override
    public void cleanup() {
        // Nothing to release.
    }

    /**
     * Resolves one binary class name.
     *
     * @param binaryName dotted binary name, for example {@code java.lang.Object} or
     *                   {@code a.b.Outer$Inner}
     * @return the answer for the type, or {@code null} if the resolver knows neither byte code nor
     * source for it
     * @throws CompilationException if a resolved class file cannot be parsed
     */
    private NameEnvironmentAnswer findType(String binaryName) {
        byte[] generated = this.generatedClasses.get(binaryName);
        if (generated != null) return binaryAnswer(binaryName, generated);

        byte[] resolved = this.typeResolver.findBinary(binaryName);
        if (resolved != null) return binaryAnswer(binaryName, resolved);

        ECJCompilationUnit unit = findSourceUnit(binaryName);
        if (unit == null) return null;
        return new NameEnvironmentAnswer(unit, null);
    }

    /**
     * Resolves the source compilation unit that declares a type.
     *
     * <p>The resolver is asked for the type itself first. If it answers nothing and the name has a
     * member type suffix, the outermost class name is tried as well; the default resolver already
     * reduces nested names, but this keeps the back end compatible with simpler resolvers.
     *
     * <p>A source file is turned into a compilation unit once per request. The file name is derived
     * first, and an already discovered unit is answered again instead of scanning the returned
     * source text a second time; the first unit always wins, even if a later resolver answer for
     * the same file carries different text.
     *
     * @param binaryName dotted binary name
     * @return the source unit, or {@code null} if the resolver has no source for the type
     */
    private ECJCompilationUnit findSourceUnit(String binaryName) {
        String className = binaryName;
        String source = this.typeResolver.findSource(className);
        if (source == null) {
            String outermost = ECJCompilationUnit.topLevelClassName(binaryName);
            if (!outermost.equals(binaryName)) {
                source = this.typeResolver.findSource(outermost);
                if (source != null) className = outermost;
            }
        }
        if (source == null) return null;

        // The resolver may answer the same source for several types of one file. Look the unit up by
        // its source resource name before creating one: creating a unit scans the source to learn the
        // declared type names, and that scan must not be repeated for an already known file.
        ECJCompilationUnit known = this.discoveredUnits.get(ClassNameUtil.getSourceResourceName(className));
        if (known != null) return known;

        return register(ECJCompilationUnit.forClassName(className, source));
    }
}
