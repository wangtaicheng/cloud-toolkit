package com.cloudtoolkit.ecsdeploy.ui;

import com.cloudtoolkit.ecsdeploy.deploy.DeployObserver;
import com.cloudtoolkit.ecsdeploy.deploy.DeployStage;
import com.cloudtoolkit.ecsdeploy.transfer.TransferProgress;
import com.cloudtoolkit.ecsdeploy.util.OutputSanitizer;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.execution.ui.ExecutionConsole;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;

import javax.swing.JComponent;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

public final class DeployConsole implements DeployObserver {

    public enum Result { SUCCESS, FAILURE, CANCELLED }

    private final Collection<String> secrets;
    private final ConsoleView console;
    private volatile int lastPercent = -1;
    private volatile Result result = Result.FAILURE;

    public DeployConsole(Project project, Collection<String> secrets) {
        this.secrets = List.copyOf(secrets);
        this.console = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole();
    }

    public JComponent getComponent() {
        return console.getComponent();
    }

    public ExecutionConsole getExecutionConsole() {
        return console;
    }

    public static String resultText(Result result) {
        return switch (result) {
            case SUCCESS -> EcsDeployMessages.message("result.success");
            case FAILURE -> EcsDeployMessages.message("result.failure");
            case CANCELLED -> EcsDeployMessages.message("result.cancelled");
        };
    }

    public static String stageTitle(DeployStage stage) {
        return EcsDeployMessages.message("stage." + stage.name());
    }

    public void markCancelled() {
        result = Result.CANCELLED;
    }

    public void finish() {
        print(resultText(result), result == Result.FAILURE
                ? ConsoleViewContentType.ERROR_OUTPUT
                : ConsoleViewContentType.SYSTEM_OUTPUT);
    }

    @Override
    public void onStage(DeployStage stage) {
        print("== " + stageTitle(stage) + " ==", ConsoleViewContentType.SYSTEM_OUTPUT);
    }

    @Override
    public void onOutput(boolean stderr, String line) {
        String text = OutputSanitizer.sanitize(line, secrets);
        ConsoleViewContentType type;
        if (stderr) {
            type = ConsoleViewContentType.ERROR_OUTPUT;
        } else if (text.contains("警告") || text.toLowerCase(Locale.ROOT).contains("warning")) {
            type = ConsoleViewContentType.LOG_WARNING_OUTPUT;
        } else {
            type = ConsoleViewContentType.NORMAL_OUTPUT;
        }
        print(text, type);
    }

    @Override
    public void onProgress(TransferProgress progress) {
        int percent = progress.percent();
        if (percent != lastPercent) {
            lastPercent = percent;
            print("上传进度：" + percent + "%", ConsoleViewContentType.NORMAL_OUTPUT);
        }
    }

    @Override
    public void onFinished(boolean success) {
        if (result != Result.CANCELLED) {
            result = success ? Result.SUCCESS : Result.FAILURE;
        }
    }

    private void print(String text, ConsoleViewContentType type) {
        ApplicationManager.getApplication().invokeLater(() -> console.print(text + "\n", type));
    }
}
