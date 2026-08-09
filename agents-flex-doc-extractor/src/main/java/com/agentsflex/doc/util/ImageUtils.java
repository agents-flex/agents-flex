/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.doc.util;

import java.util.Base64;
import java.util.Locale;

public final class ImageUtils {

    private ImageUtils() {
    }

    public static String toDataUri(byte[] data, String mimeType) {
        return "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(data);
    }

    public static String getMimeTypeFromExtension(String extension) {
        if (extension == null) {
            return null;
        }
        switch (extension.toLowerCase(Locale.ROOT)) {
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "png":
                return "image/png";
            case "gif":
                return "image/gif";
            case "bmp":
                return "image/bmp";
            case "tif":
            case "tiff":
                return "image/tiff";
            default:
                return null;
        }
    }
}
