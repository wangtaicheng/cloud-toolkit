package com.cloudtoolkit.ecsdeploy.deploy;

import java.util.List;

public class DeployException extends Exception {

    public DeployException(String message) {
        super(message);
    }

    public DeployException(String message, Throwable cause) {
        super(message, cause);
    }

    public static class ConfigurationException extends DeployException {
        public ConfigurationException(String message) {
            super(message);
        }

        public ConfigurationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class SshConnectException extends DeployException {
        public SshConnectException(String message) {
            super(message);
        }

        public SshConnectException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class SshAuthenticationException extends DeployException {
        public SshAuthenticationException(String message) {
            super(message);
        }

        public SshAuthenticationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class SftpTransferException extends DeployException {
        public SftpTransferException(String message) {
            super(message);
        }

        public SftpTransferException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class RemoteCommandException extends DeployException {
        private final int exitCode;
        private final List<String> tail;

        public RemoteCommandException(int exitCode, List<String> tail) {
            super("Remote command exited with code " + exitCode);
            this.exitCode = exitCode;
            this.tail = List.copyOf(tail);
        }

        public int exitCode() {
            return exitCode;
        }

        public List<String> tail() {
            return tail;
        }
    }

    public static class DeploymentCanceledException extends DeployException {
        public DeploymentCanceledException() {
            super("Deployment cancelled");
        }
    }
}
