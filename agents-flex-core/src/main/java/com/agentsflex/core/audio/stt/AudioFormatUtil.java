package com.agentsflex.core.audio.stt;

import com.agentsflex.core.util.StringUtil;

import java.io.File;
import java.net.URI;
import java.util.Locale;

public final class AudioFormatUtil {

    private AudioFormatUtil() {
    }

    /**
     * 根据文件名猜测格式
     */
    public static String guess(File file) {
        if (file == null) {
            return null;
        }
        return extractExtension(file.getName());
    }

    /**
     * 根据 URL 猜测格式
     */
    public static String guess(String url) {
        if (StringUtil.noText(url)) {
            return null;
        }

        try {
            URI uri = URI.create(url);
            return extractExtension(uri.getPath());
        } catch (Exception e) {
            return extractExtension(url);
        }
    }

    /**
     * 根据字节数组猜测格式
     */
    public static String guess(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }

        return detectFromHeader(bytes, bytes.length);
    }

    private static String extractExtension(String path) {
        if (StringUtil.noText(path)) {
            return null;
        }

        int slash = Math.max(
            path.lastIndexOf('/'),
            path.lastIndexOf('\\')
        );

        String fileName = slash >= 0
            ? path.substring(slash + 1)
            : path;

        int dot = fileName.lastIndexOf('.');

        if (dot < 0 || dot == fileName.length() - 1) {
            return null;
        }

        return fileName.substring(dot + 1)
            .toLowerCase(Locale.ROOT);
    }

    private static String detectFromHeader(byte[] bytes, int length) {

        if (bytes == null || length <= 0) {
            return null;
        }

        // WAV
        if (length >= 12
            && startsWith(bytes, "RIFF")
            && containsAt(bytes, 8, "WAVE")) {
            return "wav";
        }

        // MP3(ID3)
        if (length >= 3
            && startsWith(bytes, "ID3")) {
            return "mp3";
        }

        // MP3(Frame Header)
        if (length >= 2
            && (bytes[0] & 0xFF) == 0xFF
            && ((bytes[1] & 0xE0) == 0xE0)) {
            return "mp3";
        }

        // FLAC
        if (length >= 4
            && startsWith(bytes, "fLaC")) {
            return "flac";
        }

        // OGG
        if (length >= 4
            && startsWith(bytes, "OggS")) {
            return "ogg";
        }

        // WEBM / MKV
        if (length >= 4
            && (bytes[0] & 0xFF) == 0x1A
            && (bytes[1] & 0xFF) == 0x45
            && (bytes[2] & 0xFF) == 0xDF
            && (bytes[3] & 0xFF) == 0xA3) {
            return "webm";
        }

        // AAC(ADTS)
        if (length >= 2
            && (bytes[0] & 0xFF) == 0xFF
            && ((bytes[1] & 0xF6) == 0xF0)) {
            return "aac";
        }

        // M4A / MP4
        if (length >= 12
            && containsAt(bytes, 4, "ftyp")) {
            return "m4a";
        }

        return null;
    }

    private static boolean startsWith(byte[] bytes, String magic) {

        byte[] target = magic.getBytes();

        if (bytes.length < target.length) {
            return false;
        }

        for (int i = 0; i < target.length; i++) {
            if (bytes[i] != target[i]) {
                return false;
            }
        }

        return true;
    }

    private static boolean containsAt(byte[] bytes,
                                      int offset,
                                      String magic) {

        byte[] target = magic.getBytes();

        if (bytes.length < offset + target.length) {
            return false;
        }

        for (int i = 0; i < target.length; i++) {
            if (bytes[offset + i] != target[i]) {
                return false;
            }
        }

        return true;
    }
}
