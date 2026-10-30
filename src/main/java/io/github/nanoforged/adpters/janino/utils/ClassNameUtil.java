package io.github.nanoforged.adpters.janino.utils;


public final class ClassNameUtil {
    private ClassNameUtil() {}


    /**
     * Construct the name of a resource that could contain the source code of
     * the class with the given name.
     * <p>
     * Notice that member types are declared inside a different type, so the relevant source file
     * is that of the outermost declaring class.
     *
     * @param className Fully qualified class name, e.g. "pkg1.pkg2.Outer$Inner"
     * @return the name of the resource, e.g. "pkg1/pkg2/Outer.java"
     */
    public static String getSourceResourceName(String className) {

        // Strip nested type suffixes.
        {
            int idx = className.lastIndexOf('.') + 1;
            idx = className.indexOf('$', idx);
            if (idx != -1) className = className.substring(0, idx);
        }

        return className.replace('.', '/') + ".java";
    }

    /**
     * Construct the name of a resource that could contain the class file of the
     * class with the given name.
     *
     * @param className Fully qualified class name, e.g. "pkg1.pkg2.Outer$Inner"
     * @return the name of the resource, e.g. "pkg1/pkg2/Outer$Inner.class"
     */
    public static String getClassFileResourceName(String className) { return className.replace('.', '/') + ".class"; }

    /**
     * @return the binary name of the top-level type that declares the given class
     */
    public static String toTopLevelBinaryName(String binaryName) {
        int nested = binaryName.indexOf('$');
        return nested < 0 ? binaryName : binaryName.substring(0, nested);
    }
}
