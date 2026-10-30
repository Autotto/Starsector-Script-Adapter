package io.github.nanoforged.adpters.janino.impl.backend.ecj;

import io.github.nanoforged.adpters.janino.utils.ClassNameUtil;
import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.internal.compiler.env.ICompilationUnit;
import org.eclipse.jdt.internal.compiler.lookup.LookupEnvironment;
import org.eclipse.jdt.internal.compiler.lookup.ModuleBinding;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable ECJ compilation unit backed by a source string that was resolved through the request's
 * {@link io.github.nanoforged.adpters.janino.api.comp.TypeResolver}.
 *
 * <p>The unit is associated with the unnamed module: the adapter compiles game scripts that live on
 * an ordinary runtime class path, not in a named module. All package and module queries that ECJ
 * may make are answered from the class name of the unit, so the compiler never has to touch the
 * file system.
 *
 * <p>The unit also answers the set of package paths that would collide with a declared type (for
 * example {@code sample.Simple} for a {@code Simple} declared in package {@code sample}). That set
 * lets the name environment answer ECJ's "does a sub-package of this name exist?" question
 * correctly without being able to enumerate packages. See
 * {@link ECJNameEnvironment#isPackage(char[][], char[])}.
 *
 * @see ECJNameEnvironment
 */
final class ECJCompilationUnit implements ICompilationUnit {

    /**
     * Matches type declarations so that the name environment can recognize package/type collisions.
     */
    private static final Pattern TYPE_DECLARATION = Pattern.compile("\\b(?:class|interface|enum|record)\\s+([\\p{L}_$][\\p{L}\\p{N}_$]*)");

    /**
     * Slash separated source file name, for example {@code a/b/Outer.java}.
     */
    private final char[] fileName;

    /**
     * Package of the outermost type, or an empty array for the default package.
     */
    private final char[][] packageName;

    /**
     * Simple name of the outermost type, which is the type ECJ expects the file to name.
     */
    private final char[] mainTypeName;

    /**
     * Source characters; requested twice by ECJ, therefore cached here.
     */
    private final char[] contents;

    /**
     * Package paths of the types declared by this unit, for example {@code sample.Simple}.
     */
    private final Set<String> declaredTypePackagePaths;

    /**
     * Creates a compilation unit.
     *
     * @param fileName                 slash separated source file name
     * @param packageName              package of the outermost type, never {@code null}
     * @param mainTypeName             simple name of the outermost type
     * @param contents                 source characters, never {@code null}
     * @param declaredTypePackagePaths package paths of the declared types
     */
    private ECJCompilationUnit(char[] fileName, char[][] packageName, char[] mainTypeName, char[] contents, Set<String> declaredTypePackagePaths) {
        this.fileName = fileName;
        this.packageName = packageName;
        this.mainTypeName = mainTypeName;
        this.contents = contents;
        this.declaredTypePackagePaths = declaredTypePackagePaths;
    }

    /**
     * Creates the compilation unit for a class name.
     *
     * <p>Nested class names are reduced to their outermost declaring class, mirroring
     * {@link ClassNameUtil#getSourceResourceName(String)}: the source of {@code a.b.Outer$Inner} is
     * the file {@code a/b/Outer.java} and its main type is {@code Outer}.
     *
     * @param className binary name of the class whose source was resolved, for example
     *                  {@code a.b.Outer$Inner}
     * @param source    source text of the compilation unit
     * @return the unit for that source
     */
    static ECJCompilationUnit forClassName(String className, String source) {
        String outermostTypeName = topLevelClassName(className);
        String sourceFileName = ClassNameUtil.getSourceResourceName(className);

        int lastDot = outermostTypeName.lastIndexOf('.');
        String packagePart = lastDot < 0 ? "" : outermostTypeName.substring(0, lastDot);
        String mainTypePart = outermostTypeName.substring(lastDot + 1);

        char[][] packageSegments = packagePart.isEmpty() ? CharOperation.NO_CHAR_CHAR : CharOperation.splitOn('.', packagePart.toCharArray());

        char[] contents = source.toCharArray();
        Set<String> declaredTypePackagePaths = new LinkedHashSet<>();
        String packagePrefix = packagePart.isEmpty() ? "" : packagePart + '.';
        for (String declaredTypeName : declaredTypeNames(contents)) {
            declaredTypePackagePaths.add(packagePrefix + declaredTypeName);
        }

        return new ECJCompilationUnit(sourceFileName.toCharArray(), packageSegments, mainTypePart.toCharArray(), contents, declaredTypePackagePaths);
    }

    /**
     * Strips the member type suffix from a binary class name.
     *
     * <p>For example {@code a.b.Outer$Inner$Deeper} becomes {@code a.b.Outer}. Names without a
     * {@code $} in their last segment are returned unchanged.
     *
     * @param className binary class name
     * @return binary name of the outermost declaring class
     */
    static String topLevelClassName(String className) {
        int lastDot = className.lastIndexOf('.');
        int memberStart = className.indexOf('$', lastDot + 1);
        return memberStart < 0 ? className : className.substring(0, memberStart);
    }

    /**
     * Collects the simple names of the types declared by a source text.
     *
     * <p>The scan is deliberately lexical and over-inclusive: comments, string and character
     * literals and text blocks are blanked out first, and every {@code class}, {@code interface},
     * {@code enum} and {@code record} declaration is recorded regardless of nesting. Over-inclusion
     * is harmless because the result is only used to veto a package name that is already known to be
     * a type name in the same package.
     *
     * @param source source characters
     * @return declared type simple names, in source order
     */
    private static Set<String> declaredTypeNames(char[] source) {
        String code = blankOutCommentsAndLiterals(source);
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = TYPE_DECLARATION.matcher(code);
        while (matcher.find()) names.add(matcher.group(1));
        return names;
    }

    /**
     * Replaces comments, string literals, character literals and text blocks by a single space.
     *
     * <p>Line separators of line comments are kept so that the result stays line-oriented and the
     * scanner cannot accidentally join tokens. The method does not need to be exact; it only has to
     * remove text that could contain a type declaration keyword.
     *
     * @param source source characters
     * @return a copy with comments and literals blanked out
     */
    private static String blankOutCommentsAndLiterals(char[] source) {
        StringBuilder code = new StringBuilder(source.length);
        int index = 0;
        while (index < source.length) {
            char character = source[index];
            if (character == '/' && index + 1 < source.length && source[index + 1] == '/') {
                index += 2;
                while (index < source.length && source[index] != '\n' && source[index] != '\r') index++;
                code.append(' ');
            } else if (character == '/' && index + 1 < source.length && source[index + 1] == '*') {
                index += 2;
                while (index + 1 < source.length && !(source[index] == '*' && source[index + 1] == '/')) {
                    index++;
                }
                index = Math.min(index + 2, source.length);
                code.append(' ');
            } else if (character == '"' && index + 2 < source.length && source[index + 1] == '"' && source[index + 2] == '"') {
                index += 3;
                while (index + 2 < source.length && !(source[index] == '"' && source[index + 1] == '"' && source[index + 2] == '"')) {
                    if (source[index] == '\\') index++;
                    index++;
                }
                index = Math.min(index + 3, source.length);
                code.append(' ');
            } else if (character == '"') {
                index++;
                while (index < source.length && source[index] != '"') {
                    if (source[index] == '\\') index++;
                    index++;
                }
                index = Math.min(index + 1, source.length);
                code.append(' ');
            } else if (character == '\'') {
                index++;
                while (index < source.length && source[index] != '\'') {
                    if (source[index] == '\\') index++;
                    index++;
                }
                index = Math.min(index + 1, source.length);
                code.append(' ');
            } else {
                code.append(character);
                index++;
            }
        }
        return code.toString();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The characters are stored once and returned as-is; ECJ treats them as read-only.
     */
    @Override
    public char[] getContents() {
        return this.contents;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public char[] getMainTypeName() {
        return this.mainTypeName;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The package is derived from the requested class name. Returning it (instead of {@code null},
     * which disables the check) lets ECJ reject a source resource whose declared package does not
     * match the package implied by its resource name.
     */
    @Override
    public char[][] getPackageName() {
        return this.packageName;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public char[] getFileName() {
        return this.fileName;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Answers {@code null}: the adapter always compiles into the unnamed module.
     */
    @Override
    public char[] getModuleName() {
        return null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Answers the unnamed module of the compiler that asks. The interface default implementation
     * only works for module aware name environments and would return {@code null} here, which this
     * ECJ version reports as a missing module (and formats with a {@code null} module name). The
     * binding is not cached because one unit can be compiled by several ECJ compiler rounds, each
     * with its own environment.
     */
    @Override
    public ModuleBinding module(LookupEnvironment environment) {
        return environment.getModule(getModuleName());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Optional problems stay visible, matching a plain {@code javac} invocation without
     * {@code -Xlint} configuration.
     */
    @Override
    public boolean ignoreOptionalProblems() {
        return false;
    }

    /**
     * Answers the source file name as a string, suitable as a map key.
     *
     * @return the slash separated source file name
     */
    String fileNameAsString() {
        return new String(this.fileName);
    }

    /**
     * Answers the package paths of the types declared by this unit.
     *
     * <p>The set is used to answer ECJ's package/type collision check: a package path that would be
     * formed by a declared type name cannot also be a real sub-package of the declaring package.
     *
     * @return package paths such as {@code sample.Simple}; empty for types of the default package
     */
    Set<String> declaredTypePackagePaths() {
        return this.declaredTypePackagePaths;
    }

    @Override
    public String toString() {
        return "ECJCompilationUnit[" + new String(this.fileName) + "]";
    }
}
