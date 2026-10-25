package com.otectus.otetv.core.runtime;

import java.util.Locale;

/** Operating system and architecture of this process, in the vocabulary of the runtime manifest. */
public record PlatformId(Os os, Arch arch) {
    public enum Os { LINUX, WINDOWS, MACOS, OTHER }

    public enum Arch { X86_64, AARCH64, OTHER }

    public static PlatformId current() {
        return of(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    public static PlatformId of(String osName, String osArch) {
        String n = osName.toLowerCase(Locale.ROOT);
        Os os = n.contains("win") ? Os.WINDOWS : n.contains("linux") ? Os.LINUX : (n.contains("mac") || n.contains("darwin")) ? Os.MACOS : Os.OTHER;
        String a = osArch.toLowerCase(Locale.ROOT);
        Arch arch = (a.equals("amd64") || a.equals("x86_64") || a.equals("x64")) ? Arch.X86_64
                : (a.equals("aarch64") || a.equals("arm64")) ? Arch.AARCH64 : Arch.OTHER;
        return new PlatformId(os, arch);
    }

    /** Whether the launch matrix (Windows and Linux on x86-64) covers this platform. */
    public boolean isLaunchPlatform() {
        return arch == Arch.X86_64 && (os == Os.LINUX || os == Os.WINDOWS);
    }

    public String id() {
        return os.name().toLowerCase(Locale.ROOT) + "-" + arch.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return id();
    }
}
