package com.cloudtoolkit.ecsdeploy.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OutputSanitizerTest {

    @Test
    void masksKnownSecrets() {
        assertEquals("use *** now", OutputSanitizer.sanitize("use SECRET now", List.of("SECRET")));
    }

    @Test
    void decodesUtf8Chinese() {
        assertEquals("部署成功", OutputSanitizer.decodeUtf8("部署成功".getBytes(StandardCharsets.UTF_8)));
    }
}
