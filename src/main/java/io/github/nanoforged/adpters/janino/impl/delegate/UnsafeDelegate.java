package io.github.nanoforged.adpters.janino.impl.delegate;

import io.github.nanoforged.adpters.janino.utils.unsafe.UnsafeUtils;

import java.security.ProtectionDomain;

public class UnsafeDelegate extends ClassloaderAdpter {

    public UnsafeDelegate(ClassLoader classLoader) {
        super(classLoader);
    }

    @Override
    public Class<?> defineClasses(String name, byte[] b, int off, int len, ProtectionDomain protectionDomain) {
        return UnsafeUtils.defineClass(name, b, off, len, classLoader, protectionDomain);
    }


}
