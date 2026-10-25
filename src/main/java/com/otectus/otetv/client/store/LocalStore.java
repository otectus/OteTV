package com.otectus.otetv.client.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.otectus.otetv.OteTV;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Optional;

/**
 * The client's private OteTV directory ({@code <game dir>/otetv}). Trust grants, LAN grants, local-file mappings and
 * opt-in history live here: never in world saves, block NBT, item NBT or Minecraft packets (section 9.1). Files are
 * written atomically with owner-only permissions where the platform supports POSIX permissions.
 */
public final class LocalStore {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final long MAX_FILE = 4L * 1024 * 1024;

    private LocalStore() {
    }

    public static Path root() {
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("otetv");
        ensureDirectory(dir);
        return dir;
    }

    public static Path dir(String name) {
        Path d = root().resolve(name);
        ensureDirectory(d);
        return d;
    }

    public static void ensureDirectory(Path dir) {
        try {
            Files.createDirectories(dir);
            try {
                Files.setPosixFilePermissions(dir, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
            } catch (UnsupportedOperationException ignored) {
                // Windows: the profile directory ACL already restricts access to the user.
            }
        } catch (IOException e) {
            OteTV.LOGGER.warn("Could not create OteTV directory {}: {}", dir.getFileName(), e.getMessage());
        }
    }

    public static Optional<JsonElement> read(String fileName) {
        Path file = root().resolve(fileName);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            if (Files.size(file) > MAX_FILE) {
                OteTV.LOGGER.warn("Ignoring oversized local file {}", fileName);
                return Optional.empty();
            }
            return Optional.of(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException e) {
            OteTV.LOGGER.warn("Could not read {}: {}", fileName, e.getMessage());
            return Optional.empty();
        }
    }

    public static void write(String fileName, JsonElement json) {
        Path file = root().resolve(fileName);
        Path temp = root().resolve(fileName + ".tmp");
        try {
            Files.writeString(temp, GSON.toJson(json), StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(temp, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
                // not a POSIX file system
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            OteTV.LOGGER.warn("Could not write {}: {}", fileName, e.getMessage());
        }
    }

    public static void delete(String fileName) {
        try {
            Files.deleteIfExists(root().resolve(fileName));
        } catch (IOException e) {
            OteTV.LOGGER.warn("Could not delete {}: {}", fileName, e.getMessage());
        }
    }
}
