package com.otectus.otetv.worker.media;

import com.otectus.otetv.core.capability.CodecMatrix;
import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.PluginFeature;
import org.freedesktop.gstreamer.Registry;
import org.freedesktop.gstreamer.Version;

import java.util.ArrayList;
import java.util.List;

/**
 * Initializes GStreamer once and reports which relevant elements exist, so the client can show the detected codec
 * capability. Network source and adaptive demuxer features are demoted to rank NONE in-process as well (defence in
 * depth next to GST_PLUGIN_FEATURE_RANK), so autoplugging can never fetch URLs behind the guarded fetcher's back.
 */
final class GStreamerRuntime {
    static final List<String> DEMOTED = List.of("hlsdemux", "hlsdemux2", "dashdemux", "dashdemux2", "mssdemux", "mssdemux2",
            "souphttpsrc", "curlhttpsrc", "rtspsrc", "rtmpsrc", "rtmp2src", "giosrc", "smbsrc", "ftpsrc", "filesrc", "neonhttpsrc");

    private static volatile boolean initialized;
    private static volatile String version = "";

    private GStreamerRuntime() {
    }

    static synchronized void init() {
        if (initialized) {
            return;
        }
        Gst.init(Version.of(1, 16), "OteTVMediaWorker");
        version = Gst.getVersionString();
        Registry registry = Registry.get();
        for (String name : DEMOTED) {
            try {
                PluginFeature feature = registry.lookupFeature(name);
                if (feature != null) {
                    feature.setRank(PluginFeature.Rank.NONE);
                }
            } catch (RuntimeException ignored) {
                // feature not present
            }
        }
        initialized = true;
        Log.info("initialized " + version);
    }

    static String version() {
        return version;
    }

    static boolean has(String element) {
        try {
            return Registry.get().lookupFeature(element) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    static List<String> availableElements() {
        List<String> out = new ArrayList<>();
        for (String e : CodecMatrix.relevantElements()) {
            if (has(e)) {
                out.add(e);
            }
        }
        return out;
    }
}
