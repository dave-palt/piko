/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.videoQuality;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import app.morphe.extension.shared.Logger;

/**
 * One-shot (cached) probe of the SoC's decoder landscape for the codecs IG serves.
 * Codec identity comes from the CDN URL (self-identifying: .hevc. / .av1. markers
 * or IG's type marker where 101=AVC, 102=HEVC), then MediaCodecList tells us
 * whether a decoder exists and whether it is hardware (fixed-function, cheap) or
 * software (CPU decode = the heat case).
 */
public final class CodecCapabilities {

    private CodecCapabilities() {}

    public static final class CodecInfo {
        public final String mime;      // e.g. video/hevc
        public final String name;      // HEVC / AV1 / AVC / VP9
        public final boolean decodable;
        public final boolean hardware; // meaningful only when decodable
        public final int efficiency;   // lower = fewer bytes at same quality: AV1 < HEVC < VP9 < AVC

        CodecInfo(String mime, String name, boolean decodable, boolean hardware, int efficiency) {
            this.mime = mime;
            this.name = name;
            this.decodable = decodable;
            this.hardware = hardware;
            this.efficiency = efficiency;
        }
    }

    private static volatile Map<String, CodecInfo> cache;

    /** codec type (variant tag) + URL → codec info for this device. */
    public static CodecInfo of(int type, String url) {
        Map<String, CodecInfo> m = cache;
        if (m == null) {
            synchronized (CodecCapabilities.class) {
                if (cache == null) cache = probe();
                m = cache;
            }
        }
        String key = keyFor(type, url);
        CodecInfo info = m.get(key);
        if (info != null) return info;
        return new CodecInfo(null, codecName(key), false, false, 9);
    }

    private static String keyFor(int type, String url) {
        if (url != null) {
            String u = url.toLowerCase(Locale.US);
            if (u.contains(".hevc.") || u.contains("/hevc/")) return "hevc";
            if (u.contains(".av1.") || u.contains("/av1/")) return "av1";
            if (u.contains(".vp9.") || u.contains("/vp9/")) return "vp9";
            if (u.contains(".h264.") || u.contains("/h264/")) return "avc";
        }
        // IG type markers observed in the ladder: 101 = AVC (H.264), 102 = HEVC,
        // 105/110 = AV1 on newer bases. Unknown types degrade to AVC labels.
        switch (type) {
            case 102: return "hevc";
            case 105:
            case 110: return "av1";
            default: return "avc";
        }
    }

    private static String codecName(String key) {
        switch (key) {
            case "hevc": return "HEVC";
            case "av1": return "AV1";
            case "vp9": return "VP9";
            default: return "AVC";
        }
    }

    private static String mimeOf(String key) {
        switch (key) {
            case "hevc": return "video/hevc";
            case "av1": return "video/av1";
            case "vp9": return "video/x-vnd.on2.vp9";
            default: return "video/avc";
        }
    }

    private static int efficiencyOf(String key) {
        switch (key) {
            case "av1": return 1;
            case "hevc": return 2;
            case "vp9": return 3;
            default: return 4;
        }
    }

    /** MediaCodecList sweep, done once per process. Fail-open: assume decodable. */
    private static Map<String, CodecInfo> probe() {
        Map<String, CodecInfo> result = new HashMap<>();
        String[] keys = {"avc", "hevc", "av1", "vp9"};
        try {
            MediaCodecInfo[] infos = MediaCodecList.getCodecInfos();
            for (String key : keys) {
                String mime = mimeOf(key);
                boolean decodable = false;
                boolean hardware = false;
                if (infos != null) {
                    for (MediaCodecInfo ci : infos) {
                        if (ci.isEncoder()) continue;
                        try {
                            ci.getCapabilitiesForType(mime);
                        } catch (IllegalArgumentException e) {
                            continue; // not a decoder for this mime
                        }
                        decodable = true;
                        if (isHardware(ci.getName())) {
                            hardware = true;
                            break; // hw beats sw; stop looking
                        }
                    }
                }
                result.put(key, new CodecInfo(mime, codecName(key), decodable, hardware, efficiencyOf(key)));
            }
        } catch (Throwable t) {
            Logger.printException(() -> "CodecCapabilities probe failed", t);
            for (String key : keys) {
                result.put(key, new CodecInfo(mimeOf(key), codecName(key), true, true, efficiencyOf(key)));
            }
        }
        return result;
    }

    private static boolean isHardware(String codecName) {
        if (codecName == null) return false;
        String n = codecName.toLowerCase(Locale.US);
        // Android software codecs first — the explicit deny list
        if (n.startsWith("c2.android") || n.startsWith("omx.google")
                || n.startsWith("c2.google") || n.contains(".sw.")) return false;
        // Known hardware vendor prefixes
        if (n.startsWith("omx.qcom") || n.startsWith("c2.qti") || n.startsWith("omx.mtk")
                || n.startsWith("c2.mtk") || n.startsWith("omx.sec") || n.startsWith("c2.sec")
                || n.startsWith("omx.nvidia") || n.startsWith("omx.rk") || n.startsWith("omx.intel")
                || n.startsWith("omx.amlogic") || n.startsWith("c2.amlogic")) return true;
        // C2/OMX vendor convention: any remaining vendor-prefixed name is hardware
        if (n.startsWith("c2.") || n.startsWith("omx.")) return true;
        return false;
    }
}
