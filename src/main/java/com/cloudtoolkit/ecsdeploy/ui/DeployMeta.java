package com.cloudtoolkit.ecsdeploy.ui;

/**
 * Structured store kept inside the host {@code remark} column, preserving any pre-existing base text.
 * Format: {@code {"base":"...","deployDir":"...","buildType":"...","cmd":<id>}}.
 */
public final class DeployMeta {

    public final String base;
    public final String deployDir;
    public final String buildType;
    public final int commandId;

    public DeployMeta(String base, String deployDir, String buildType, int commandId) {
        this.base = base;
        this.deployDir = deployDir;
        this.buildType = buildType;
        this.commandId = commandId;
    }

    public static String serialize(String base, String deployDir, String buildType, int commandId) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"base\":\"").append(escape(base == null ? "" : base)).append('"');
        sb.append(",\"deployDir\":\"").append(escape(deployDir)).append('"');
        sb.append(",\"buildType\":\"").append(escape(buildType)).append('"');
        sb.append(",\"cmd\":").append(commandId).append('}');
        return sb.toString();
    }

    public static DeployMeta parse(String remark) {
        if (remark == null || !remark.contains("\"deployDir\"")) {
            return new DeployMeta(remark, "", "", -1);
        }
        String base = field(remark, "base");
        String dir = field(remark, "deployDir");
        String type = field(remark, "buildType");
        String cmd = field(remark, "cmd");
        int cmdId = -1;
        try {
            cmdId = Integer.parseInt(cmd);
        } catch (NumberFormatException ignored) {
            // keep -1
        }
        return new DeployMeta(base, dir, type, cmdId);
    }

    private static String field(String json, String key) {
        int idx = json.indexOf('"' + key + "\":");
        if (idx < 0) {
            return "";
        }
        int start = idx + key.length() + 3;
        if (json.charAt(start) == '"') {
            int end = json.indexOf('"', start + 1);
            return end < 0 ? "" : json.substring(start + 1, end);
        }
        int end = json.indexOf('}', start);
        return end < 0 ? "" : json.substring(start, end).trim();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
