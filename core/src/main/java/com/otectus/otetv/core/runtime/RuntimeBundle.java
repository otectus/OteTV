package com.otectus.otetv.core.runtime;

import com.otectus.otetv.core.net.SafeUrl;

import java.util.List;

/**
 * One installable native runtime bundle from a project-controlled manifest (section 17.1): exact version, platform,
 * HTTPS locations, sizes, SHA-256, protocol compatibility range and license notices. Download locations and hashes
 * come only from the built-in or locally configured manifest, never from a Minecraft server.
 */
public record RuntimeBundle(String id, String version, PlatformId platform, List<SafeUrl> urls, String sha256, long size,
                            long unpackedSize, int protocolMin, int protocolMax, String entrypoint, List<String> executables,
                            List<String> licenses, String minimumSystem, String notice) {
    public boolean supportsProtocol(int protocol) {
        return protocol >= protocolMin && protocol <= protocolMax;
    }

    public String directoryName() {
        return id + "-" + version;
    }
}
