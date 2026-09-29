package dev.moonbridge.api;

/** Safe action after a guarded transfer failed. */
public enum TransferFailureDisposition {
    /** The exact source backend has been restored and may receive the resumed relay. */
    SOURCE_RESTORED,
    /** Source ownership is uncertain or was not restored; MoonBridge must close the player. */
    DISCONNECT
}
