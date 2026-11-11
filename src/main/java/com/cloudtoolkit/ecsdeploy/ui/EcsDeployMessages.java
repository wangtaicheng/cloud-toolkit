package com.cloudtoolkit.ecsdeploy.ui;

import java.util.ResourceBundle;

final class EcsDeployMessages {

    private static final ResourceBundle BUNDLE = ResourceBundle.getBundle("messages.EcsDeployBundle");

    private EcsDeployMessages() {
    }

    static String message(String key) {
        return BUNDLE.getString(key);
    }
}
