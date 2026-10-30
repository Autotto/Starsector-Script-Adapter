package org.codehaus.janino.util.resource;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A {@link org.codehaus.janino.util.resource.FileResourceFinder} that finds file resources in
 * a directory. The name of the file is constructed by concatenating a directory name
 * with the resource name such that slashes in the resource name map to file separators.
 */
public class DirectoryResourceFinder extends FileResourceFinder {
    private final File directory;
    private final Map<String, Set<File>> subdirectoryNameToFiles = new HashMap<>();

    /**
     * @param directory the directory to use as the search base
     */
    public DirectoryResourceFinder(File directory) {
        this.directory = directory;
    }

    @Override
    public final String toString() {
        return "dir:" + this.directory;
    }

    // Implement FileResourceFinder.
    @Override
    protected final File findResourceAsFile(String resourceName) {
        // Determine the subdirectory name (null for no subdirectory).
        int idx = resourceName.lastIndexOf('/');
        String subdirectoryName = idx == -1
                ? null
                : resourceName.substring(0, idx).replace('/', File.separatorChar);

        // Determine files existing in this subdirectory.
        Set<File> files = this.subdirectoryNameToFiles.get(subdirectoryName);
        if (files == null) {
            File subDirectory = subdirectoryName == null
                    ? this.directory
                    : new File(this.directory, subdirectoryName);
            File[] fa = subDirectory.listFiles();
            files = fa == null
                    ? Collections.emptySet()
                    : new HashSet<>(Arrays.asList(fa));
            this.subdirectoryNameToFiles.put(subdirectoryName, files);
        }

        // Notice that "File.equals()" performs all the file-system dependent
        // magic like case conversion.
        File file = new File(this.directory, resourceName.replace('/', File.separatorChar));
        return files.contains(file) ? file : null;
    }
}