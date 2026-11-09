package com.coflnet.lowballing;

/**
 * Which hub the lowballer advertises in. It alternates between the start hub
 * and the one after it whenever every ad message is on cooldown, and when the
 * hub it wants is full it keeps retrying that hub from the fallback hub.
 */
final class LowballingHubs {
    private LowballingHubs() {
    }

    /** Hub to switch to once all messages are on cooldown in {@code current}. */
    static int alternate(int current, int start, int max) {
        int second = Math.min(start + 1, Math.max(start, max));
        return current == start ? second : start;
    }

    /**
     * True when we are in a fallback hub because the target hub was full and
     * the retry wait since arriving there has passed.
     */
    static boolean retryTargetDue(boolean targetFull, int current, int target,
                                  long arrivedAt, long now, long retryMs) {
        return targetFull && target > 0 && current != target && now - arrivedAt >= retryMs;
    }
}
