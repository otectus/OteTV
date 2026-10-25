package com.otectus.otetv.core.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Collection;
import java.util.Enumeration;
import java.util.EnumSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Extracts a runtime bundle into a staging directory (section 17.2) while defending against path traversal,
 * absolute names, drive letters, entry counts, decompression bombs (actual bytes are counted, declared sizes are not
 * trusted) and symlink tricks (ZIP entries are always written as regular files).
 */
public final class SafeZipExtractor {
    public static final int MAX_ENTRIES = 20_000;
    public static final long MAX_RATIO = 400;

    private SafeZipExtractor() {
    }

    public static void validateEntryName(String name) {
        if (name == null || name.isEmpty() || name.length() > 512) {
            throw new IllegalArgumentException("bad entry name");
        }
        if (name.startsWith("/") || name.startsWith("\\") || name.contains("\\") || name.indexOf('\0') >= 0
                || (name.length() > 1 && name.charAt(1) == ':')) {
            throw new IllegalArgumentException("absolute or platform-specific entry name: " + name);
        }
        for (String part : name.split("/")) {
            if (part.equals("..")) {
                throw new IllegalArgumentException("parent reference in entry name: " + name);
            }
        }
    }

    /**
     * Extracts {@code zip} into {@code destination}, which must not exist yet. Returns the number of bytes written.
     */
    public static long extract(Path zip, Path destination, long maxTotalBytes, Collection<String> executables) throws IOException {
        if (Files.exists(destination)) {
            throw new IOException("staging directory already exists");
        }
        Files.createDirectories(destination);
        Path root = destination.toRealPath();
        long total = 0;
        int count = 0;
        byte[] buffer = new byte[64 * 1024];
        try (ZipFile file = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (++count > MAX_ENTRIES) {
                    throw new IOException("too many entries");
                }
                String name = entry.getName();
                try {
                    validateEntryName(name);
                } catch (IllegalArgumentException e) {
                    throw new IOException(e.getMessage());
                }
                Path target = root.resolve(name).normalize();
                if (!target.startsWith(root)) {
                    throw new IOException("entry escapes destination: " + name);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                if (!target.getParent().toRealPath().startsWith(root)) {
                    throw new IOException("entry parent escapes destination: " + name);
                }
                long written = 0;
                long compressed = Math.max(1, entry.getCompressedSize());
                try (InputStream in = file.getInputStream(entry);
                     OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        written += n;
                        total += n;
                        if (total > maxTotalBytes) {
                            throw new IOException("bundle expands beyond declared size");
                        }
                        if (written > 1024 * 1024 && written / compressed > MAX_RATIO) {
                            throw new IOException("suspicious compression ratio in " + name);
                        }
                        out.write(buffer, 0, n);
                    }
                }
            }
        }
        for (String exe : executables) {
            Path p = root.resolve(exe).normalize();
            if (p.startsWith(root) && Files.isRegularFile(p)) {
                makeExecutable(p);
            }
        }
        return total;
    }

    private static void makeExecutable(Path p) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(p, perms);
        } catch (UnsupportedOperationException | IOException e) {
            p.toFile().setExecutable(true, true);
        }
    }
}
