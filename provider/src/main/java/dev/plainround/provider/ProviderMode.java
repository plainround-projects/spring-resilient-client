package dev.plainround.provider;

import java.util.Locale;

/** How the fake provider answers a quote request. */
public enum ProviderMode {

    /** Answers 200 with a price at once. */
    NORMAL,

    /** Answers 200 with a price after a configurable delay (default 5 seconds). */
    SLOW,

    /** Answers 503 at once, the way an overloaded service sheds load. */
    FAIL503,

    /**
     * Accepts the connection and then does not answer for ten seconds, far past any sane read
     * timeout.
     */
    HANG;

    /**
     * Parses a mode name, ignoring case.
     *
     * @param text the name, such as {@code fail503}
     * @return the mode
     * @throws IllegalArgumentException if the name is not one of the four modes
     */
    public static ProviderMode parse(String text) {
        try {
            return valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalArgumentException(
                    "mode must be one of normal, slow, fail503, hang", exception);
        }
    }
}
