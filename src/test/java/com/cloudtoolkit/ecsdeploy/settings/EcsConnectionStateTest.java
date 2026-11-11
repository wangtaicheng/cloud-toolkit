package com.cloudtoolkit.ecsdeploy.settings;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;

class EcsConnectionStateTest {

    @Test
    void stateHasNoSecretFields() {
        assertFalse(hasSensitiveValueField(EcsConnectionState.class));
        assertFalse(hasSensitiveValueField(EcsConnection.class));
    }

    private static boolean hasSensitiveValueField(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(f -> f.getType() == String.class || f.getType() == char[].class)
                .map(Field::getName)
                .map(String::toLowerCase)
                .anyMatch(n -> n.contains("password") || n.contains("passphrase")
                        || n.contains("privatekeycontent") || n.contains("secret"));
    }
}
