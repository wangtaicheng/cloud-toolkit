package com.cloudtoolkit.ecsdeploy.util;

import java.nio.charset.StandardCharsets;
import java.util.Collection;

public final class OutputSanitizer {

    private OutputSanitizer() {
    }

    public static String sanitize(String line, Collection<String> secrets) {
        if (line == null || secrets == null) {
            return line;
        }
        String result = line;
        for (String secret : secrets) {
            if (secret != null && !secret.isEmpty()) {
                result = result.replace(secret, "***");
            }
        }
        return result;
    }

    public static String decodeUtf8(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
