package io.github.nanoforged.adpters.janino.api;

import org.codehaus.janino.util.resource.ResourceFinder;

public record CompilerContext(ClassLoader parent, ResourceFinder finder, String encode) {
}
