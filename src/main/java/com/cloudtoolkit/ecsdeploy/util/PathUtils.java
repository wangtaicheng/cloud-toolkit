package com.cloudtoolkit.ecsdeploy.util;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class PathUtils {

    private PathUtils() {
    }

    public static String normalizeRemoteDirectory(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = raw.replace('\\', '/').trim();
        while (normalized.contains("//")) {
            normalized = normalized.replace("//", "/");
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    public static boolean isAbsoluteRemote(String path) {
        return path != null && normalizeRemoteDirectory(path).startsWith("/");
    }

    public static Path expandHome(String localPath) {
        String resolved = localPath;
        if (resolved.startsWith("~")) {
            resolved = System.getProperty("user.home") + resolved.substring(1);
        }
        return Paths.get(resolved).toAbsolutePath();
    }

    public static String remoteFilePath(String dir, String fileName) {
        String normalized = normalizeRemoteDirectory(dir);
        if (normalized.isEmpty() || "/".equals(normalized)) {
            return "/" + fileName;
        }
        return normalized + "/" + fileName;
    }
}
