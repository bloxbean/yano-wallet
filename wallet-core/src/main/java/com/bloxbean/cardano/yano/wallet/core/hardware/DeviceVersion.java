package com.bloxbean.cardano.yano.wallet.core.hardware;

/**
 * The version of the Cardano application running on a connected device
 * (ADR-034). Used to gate features: the wallet refuses to serialize for an app
 * version it does not support rather than risk a mis-signed transaction.
 *
 * @param major            app major version
 * @param minor            app minor version
 * @param patch            app patch version
 * @param developmentBuild true if the device reports a non-production/debug app
 */
public record DeviceVersion(int major, int minor, int patch, boolean developmentBuild) {

    /** Dotted version string, e.g. "8.1.2". */
    public String version() {
        return major + "." + minor + "." + patch;
    }

    /** True if this version is at least {@code (major, minor, patch)}. */
    public boolean isAtLeast(int reqMajor, int reqMinor, int reqPatch) {
        if (major != reqMajor) {
            return major > reqMajor;
        }
        if (minor != reqMinor) {
            return minor > reqMinor;
        }
        return patch >= reqPatch;
    }

    @Override
    public String toString() {
        return version() + (developmentBuild ? " (dev)" : "");
    }
}
