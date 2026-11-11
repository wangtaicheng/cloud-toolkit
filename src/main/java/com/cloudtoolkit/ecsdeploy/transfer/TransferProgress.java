package com.cloudtoolkit.ecsdeploy.transfer;

public record TransferProgress(long transferred, long total) {

    public int percent() {
        if (total <= 0) {
            return 0;
        }
        return (int) Math.min(100L, Math.max(0L, (long) ((double) transferred / total * 100)));
    }
}
