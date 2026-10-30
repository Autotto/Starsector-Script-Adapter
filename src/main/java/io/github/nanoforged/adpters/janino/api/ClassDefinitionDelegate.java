package io.github.nanoforged.adpters.janino.api;


import java.security.ProtectionDomain;

@FunctionalInterface
public interface ClassDefinitionDelegate {
    Class<?> defineClasses(String name, byte[] b, int off, int len, ProtectionDomain protectionDomain) throws ClassNotFoundException;
}
