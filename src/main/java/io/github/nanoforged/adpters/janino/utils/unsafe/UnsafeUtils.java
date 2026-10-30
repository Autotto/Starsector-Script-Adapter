package io.github.nanoforged.adpters.janino.utils.unsafe;

import sun.misc.Unsafe;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.security.ProtectionDomain;

/**
 * Defines classes through a cached internal Unsafe method handle without module exports.
 * Requires Java 17 with {@code jdk.unsupported}; no internal Unsafe type appears in the public API.
 */
public final class UnsafeUtils {
    private static final Object INTERNAL_UNSAFE;
    private static final MethodHandle DEFINE_CLASS;

    static {
        try {
            Field sunUnsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            sunUnsafeField.setAccessible(true);
            Unsafe sunUnsafe = (Unsafe) sunUnsafeField.get(null);

            Field lookupField = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
            MethodHandles.Lookup trustedLookup = (MethodHandles.Lookup) sunUnsafe.getObject(sunUnsafe.staticFieldBase(lookupField), sunUnsafe.staticFieldOffset(lookupField));
            Class<?> internalUnsafeClass = Class.forName("jdk.internal.misc.Unsafe");
            INTERNAL_UNSAFE = trustedLookup.findStaticGetter(internalUnsafeClass, "theUnsafe", internalUnsafeClass).invoke();
            DEFINE_CLASS = trustedLookup.findVirtual(internalUnsafeClass, "defineClass", MethodType.methodType(Class.class, String.class, byte[].class, int.class, int.class, ClassLoader.class, ProtectionDomain.class)).bindTo(INTERNAL_UNSAFE);
        } catch (Throwable ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private UnsafeUtils() {
    }

    /**
     * Defines, but does not initialize, a class in the supplied loader.
     * This bypasses loader transformers; it does not bypass JVM verification or duplicate-definition
     * checks. A null loader selects the bootstrap loader, and a null name lets the JVM infer the name.
     *
     * @param name             the dotted binary name, or null to infer it from the bytecode
     * @param bytes            the class file containing the requested byte range
     * @param offset           the first byte of the class file
     * @param length           the number of class-file bytes
     * @param loader           the defining loader, or null for the bootstrap loader
     * @param protectionDomain the protection domain, or null for the VM default
     * @return the newly defined, uninitialized class
     * @throws LinkageError              if definition or verification fails, including duplicate definitions
     * @throws NullPointerException      if bytes is null
     * @throws IndexOutOfBoundsException if the byte range is invalid
     * @throws IllegalStateException     if the method handle unexpectedly throws a checked exception
     */
    public static Class<?> defineClass(String name, byte[] bytes, int offset, int length, ClassLoader loader, ProtectionDomain protectionDomain) {
        try {
            return (Class<?>) DEFINE_CLASS.invokeExact(name, bytes, offset, length, loader, protectionDomain);
        } catch (RuntimeException | Error ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new IllegalStateException("Unsafe.defineClass failed", ex);
        }
    }
}
