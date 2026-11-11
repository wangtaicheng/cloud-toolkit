package com.cloudtoolkit.ecsdeploy.ui;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeployProcessHandlerTest {

    @Test
    void destroyCancelsExactlyOnce() {
        AtomicInteger calls = new AtomicInteger();
        DeployProcessHandler handler = new DeployProcessHandler(calls::incrementAndGet);

        handler.destroyProcess();
        handler.destroyProcess();

        assertEquals(1, calls.get());
    }
}
