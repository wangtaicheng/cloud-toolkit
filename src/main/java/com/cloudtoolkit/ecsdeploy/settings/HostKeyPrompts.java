package com.cloudtoolkit.ecsdeploy.settings;

import com.cloudtoolkit.ecsdeploy.transfer.HostKeyTrust;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.Messages;

import java.text.MessageFormat;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Asks the user whether the key of an unknown host may be trusted.
 *
 * <p>The question is asked after the handshake failed, so it is shown from a background thread and
 * blocks until the user answered.</p>
 */
public final class HostKeyPrompts {

    private HostKeyPrompts() {
    }

    /** The prompt used by every entry point of the plugin. */
    public static final HostKeyTrust TRUST = HostKeyPrompts::confirmTrust;

    /** Shows the confirmation dialog and waits for the answer. */
    public static boolean confirmTrust(String host, int port, String fingerprint) {
        String text = MessageFormat.format(message("dialog.host.key.confirm"),
                host + ":" + port, fingerprint);
        return askOnEdt(text);
    }

    /** Shows the replacement dialog (a changed key) and waits for the answer. */
    public static boolean confirmReplacement(String host, int port, String fingerprint) {
        String text = MessageFormat.format(message("dialog.host.key.replace"),
                host + ":" + port, fingerprint);
        return askOnEdt(text);
    }

    private static boolean askOnEdt(String text) {
        if (ApplicationManager.getApplication().isDispatchThread()) {
            return ask(text);
        }
        AtomicBoolean accepted = new AtomicBoolean();
        CountDownLatch answered = new CountDownLatch(1);
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                accepted.set(ask(text));
            } finally {
                answered.countDown();
            }
        });
        try {
            answered.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return accepted.get();
    }

    private static boolean ask(String text) {
        return Messages.showYesNoDialog(ProjectManager.getInstance().getDefaultProject(),
                text, "确认主机密钥", null) == Messages.YES;
    }

    private static String message(String key) {
        return ResourceBundle.getBundle("messages.EcsDeployBundle").getString(key);
    }
}
