package com.cloudtoolkit.ecsdeploy.transfer;

/**
 * Asks the user whether the key of an unknown host may be trusted.
 *
 * <p>The question is asked after the handshake failed, so the implementation has to show its own
 * dialog (it is called from a background thread).</p>
 */
@FunctionalInterface
public interface HostKeyTrust {

    /**
     * @param host the host the key belongs to
     * @param port the port of the host
     * @param fingerprint the SHA256 fingerprint of the key
     * @return {@code true} when the key may be trusted and written to the known hosts file
     */
    boolean confirmTrust(String host, int port, String fingerprint);

    /**
     * Confirms replacing a changed key (possible man-in-the-middle attack).
     *
     * @return {@code true} when the old entry may be removed and the new key trusted
     */
    default boolean confirmReplacement(String host, int port, String fingerprint) {
        return confirmTrust(host, port, fingerprint);
    }
}
