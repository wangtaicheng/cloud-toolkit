package com.cloudtoolkit.ecsdeploy.deploy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LocalBuildArtifactTest {

    @TempDir
    Path dir;

    @Test
    void findsTheMavenArtifact() throws Exception {
        Path target = dir.resolve("target");
        Files.createDirectories(target);
        Path jar = target.resolve("app.jar");
        Files.writeString(jar, "payload");
        Files.writeString(target.resolve("app-sources.jar"), "payload");
        assertEquals(jar, LocalBuilder.findArtifact(dir, true));
    }

    @Test
    void findsTheGradleArtifact() throws Exception {
        Path libs = dir.resolve("build").resolve("libs");
        Files.createDirectories(libs);
        Path jar = libs.resolve("app.jar");
        Files.writeString(jar, "payload");
        assertEquals(jar, LocalBuilder.findArtifact(dir, false));
    }

    @Test
    void ignoresJarsOutsideTheBuildDirectory() throws Exception {
        Files.writeString(dir.resolve("random.jar"), "payload");
        assertNull(LocalBuilder.findArtifact(dir, true));
        assertNull(LocalBuilder.findArtifact(dir, false));
    }
}