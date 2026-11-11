package com.cloudtoolkit.ecsdeploy.ui;

import com.intellij.notification.NotificationGroup;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;

public final class DeployNotifier {

    private static final String GROUP_ID = "cloud-toolkit.ecs.deploy";

    private DeployNotifier() {
    }

    public static void success(Project project, String host, String fileName) {
        publish(project, "已部署 " + fileName + " 到 " + host, NotificationType.INFORMATION);
    }

    public static void failure(Project project, String message) {
        publish(project, message, NotificationType.ERROR);
    }

    private static void publish(Project project, String content, NotificationType type) {
        NotificationGroup group = NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID);
        group.createNotification("Cloud Toolkit 部署", content, type).notify(project);
    }
}
