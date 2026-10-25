package com.otectus.otetv.session;

import com.otectus.otetv.config.OteTvServerConfig;
import com.otectus.otetv.core.protocol.RejectReason;
import com.otectus.otetv.core.source.MediaSource;
import com.otectus.otetv.core.source.SourceException;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The server's source policy (section 18.1 {@code sourcePolicy}). A server can only narrow what sources are offered;
 * it can never widen a client's local networking, consent or file access. Effective permission is the intersection
 * of provider capability, this policy, the TV's ACL and each client's own consent.
 */
public final class ServerSourcePolicy {
    public enum Mode { BUNDLED_ONLY, APPROVED_HOSTS, ANY_PUBLIC_HTTPS }

    /** Bundled assets shipped inside the mod jar (never resource-pack overridable). */
    public static final Set<String> BUNDLED_ASSETS = Set.of("otetv:welcome");

    private ServerSourcePolicy() {
    }

    public static MediaSource.Policy structuralPolicy() {
        boolean lan = OteTvServerConfig.ALLOW_PRIVATE_NETWORK_SOURCES.get();
        return new MediaSource.Policy(lan, lan);
    }

    public record Verdict(RejectReason reason, String detail) {
        public boolean allowed() {
            return reason == RejectReason.NONE;
        }

        static Verdict ok() {
            return new Verdict(RejectReason.NONE, "");
        }
    }

    public static Verdict check(MediaSource source) {
        Mode mode = OteTvServerConfig.SOURCE_POLICY.get();
        switch (source.type()) {
            case BUNDLED -> {
                return BUNDLED_ASSETS.contains(source.assetId().orElse("")) ? Verdict.ok()
                        : new Verdict(RejectReason.SOURCE_REJECTED, "unknown bundled asset");
            }
            case YOUTUBE -> {
                if (!OteTvServerConfig.ALLOW_YOUTUBE.get()) {
                    return new Verdict(RejectReason.YOUTUBE_DISABLED, "");
                }
                return mode == Mode.BUNDLED_ONLY ? new Verdict(RejectReason.SOURCE_NOT_ALLOWED, "bundled only") : Verdict.ok();
            }
            case SHARED_LOCAL_ASSET -> {
                return mode == Mode.BUNDLED_ONLY ? new Verdict(RejectReason.SOURCE_NOT_ALLOWED, "bundled only") : Verdict.ok();
            }
            case HTTPS_VIDEO, HLS_CLEAR -> {
                if (mode == Mode.BUNDLED_ONLY) {
                    return new Verdict(RejectReason.SOURCE_NOT_ALLOWED, "bundled only");
                }
                try {
                    // Re-validate against the current structural policy (http / private literals only when enabled).
                    MediaSource.fromFields(source.type(), source.toFields(), structuralPolicy());
                } catch (SourceException e) {
                    return new Verdict(RejectReason.SOURCE_REJECTED, e.issue().name().toLowerCase(Locale.ROOT));
                }
                String host = source.networkHost().orElse("");
                if (mode == Mode.APPROVED_HOSTS && !isApproved(host, OteTvServerConfig.APPROVED_HOSTS.get())) {
                    return new Verdict(RejectReason.SOURCE_NOT_ALLOWED, host);
                }
                return Verdict.ok();
            }
            default -> {
                return new Verdict(RejectReason.SOURCE_REJECTED, "not shareable");
            }
        }
    }

    /** Exact host match, or a suffix match for entries written as {@code .example.com} or {@code *.example.com}. */
    public static boolean isApproved(String host, List<? extends String> approved) {
        String h = host.toLowerCase(Locale.ROOT);
        for (String entry : approved) {
            String e = entry.trim().toLowerCase(Locale.ROOT);
            if (e.isEmpty()) {
                continue;
            }
            if (e.startsWith("*.")) {
                e = e.substring(1);
            }
            if (e.startsWith(".")) {
                if (h.endsWith(e) || h.equals(e.substring(1))) {
                    return true;
                }
            } else if (h.equals(e)) {
                return true;
            }
        }
        return false;
    }
}
