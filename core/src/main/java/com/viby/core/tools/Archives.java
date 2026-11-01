package com.viby.core.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Extracts selected executables from downloaded tool archives. */
final class Archives {

    private Archives() {
    }

    /**
     * Copies archive members whose file name (without directories) is a key of
     * {@code entries} into {@code targetDir} under the mapped name.
     */
    static void extract(Path archive, ToolPackage.Kind kind, Map<String, String> entries,
                        Path targetDir) throws IOException, InterruptedException {
        switch (kind) {
            case RAW -> Files.copy(archive, targetDir.resolve(entries.values().iterator().next()),
                    StandardCopyOption.REPLACE_EXISTING);
            case ZIP -> unzip(archive, entries, targetDir);
            case TAR_XZ -> untar(archive, entries, targetDir);
        }
        for (String name : entries.values()) {
            Path file = targetDir.resolve(name);
            if (!Files.isRegularFile(file)) {
                throw new IOException(name + " not found in " + archive.getFileName());
            }
            file.toFile().setExecutable(true, false);
        }
    }

    private static void unzip(Path archive, Map<String, String> entries, Path targetDir)
            throws IOException {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String target = entries.get(fileName(entry.getName()));
                if (target != null) {
                    Files.copy(zip, targetDir.resolve(target), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** The JDK has no xz codec; every Linux distribution ships tar with xz support. */
    private static void untar(Path archive, Map<String, String> entries, Path targetDir)
            throws IOException, InterruptedException {
        Path scratch = Files.createTempDirectory(targetDir, "untar");
        try {
            Process process = new ProcessBuilder("tar", "-xJf", archive.toString(),
                    "-C", scratch.toString()).inheritIO().start();
            if (process.waitFor() != 0) {
                throw new IOException("tar failed for " + archive.getFileName());
            }
            List<Path> found;
            try (Stream<Path> files = Files.walk(scratch)) {
                found = files.filter(Files::isRegularFile)
                        .filter(file -> entries.containsKey(file.getFileName().toString()))
                        .toList();
            }
            for (Path file : found) {
                Files.move(file, targetDir.resolve(entries.get(file.getFileName().toString())),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            deleteRecursively(scratch);
        }
    }

    static String fileName(String entryName) {
        int slash = Math.max(entryName.lastIndexOf('/'), entryName.lastIndexOf('\\'));
        return entryName.substring(slash + 1);
    }

    static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }
}
