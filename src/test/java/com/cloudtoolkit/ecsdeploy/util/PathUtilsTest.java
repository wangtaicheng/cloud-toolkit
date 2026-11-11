package com.cloudtoolkit.ecsdeploy.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathUtilsTest {

    @Test
    void normalizeRemoteDirectoryTrimsTrailingSlashAndFoldsSlashes() {
        assertEquals("/opt/app", PathUtils.normalizeRemoteDirectory("/opt//app///"));
        assertEquals("/opt/app", PathUtils.normalizeRemoteDirectory("\\opt\\app"));
    }

    @Test
    void remoteFilePathKeepsSpaces() {
        assertEquals("/opt/my app/app.jar", PathUtils.remoteFilePath("/opt/my app", "app.jar"));
    }

    @Test
    void rejectsRelativeRemoteDirectory() {
        assertFalse(PathUtils.isAbsoluteRemote("opt/app"));
        assertTrue(PathUtils.isAbsoluteRemote("/opt/app"));
    }
}
