package com.cloudtoolkit.ecsdeploy.deploy;

import com.cloudtoolkit.ecsdeploy.deploy.DeployException.ConfigurationException;
import com.cloudtoolkit.ecsdeploy.settings.EcsConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeployContextTest {

    @Test
    void validateRejectsMissingArtifact(@TempDir Path dir) {
        assertThrows(ConfigurationException.class, () -> ctx(dir.resolve("missing.jar")).validate());
    }

    @Test
    void validateRejectsDirectoryArtifact(@TempDir Path dir) {
        assertThrows(ConfigurationException.class, () -> ctx(dir).validate());
    }

    @Test
    void validateRejectsEmptyArtifact(@TempDir Path dir) throws Exception {
        Path empty = Files.createFile(dir.resolve("app.jar"));
        assertThrows(ConfigurationException.class, () -> ctx(empty).validate());
    }

    @Test
    void validateFailsWhenConnectionIsNull(@TempDir Path dir) throws Exception {
        Path jar = Files.write(dir.resolve("app.jar"), new byte[]{1});
        DeployContext c = new DeployContext(null, "s", jar, "/opt/app", "", "start");
        assertThrows(ConfigurationException.class, c::validate);
    }

    @Test
    void toStringNeverContainsSecret(@TempDir Path dir) throws Exception {
        Path jar = Files.write(dir.resolve("app.jar"), new byte[]{1});
        DeployContext c = new DeployContext(connection(), "SUPER_SECRET", jar, "/opt/app", "", "start");
        assertFalse(c.toString().contains("SUPER_SECRET"));
    }

    private static DeployContext ctx(Path artifact) {
        return new DeployContext(connection(), "s", artifact, "/opt/app", "", "start");
    }

    private static EcsConnection connection() {
        EcsConnection c = new EcsConnection();
        c.id = "c1";
        c.host = "127.0.0.1";
        c.username = "root";
        return c;
    }
}
