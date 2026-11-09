package com.coflnet.lowballing;

public final class LowballingHubsTest {
    private LowballingHubsTest() {
    }

    public static void main(String[] args) {
        // all messages on cooldown: hub 1 -> 2, hub 2 -> 1
        check(LowballingHubs.alternate(1, 1, 20) == 2, "1 -> 2");
        check(LowballingHubs.alternate(2, 1, 20) == 1, "2 -> 1");
        // stuck in a fallback hub (e.g. 3): back to the start hub
        check(LowballingHubs.alternate(3, 1, 20) == 1, "3 -> 1");
        // only one hub allowed: stay
        check(LowballingHubs.alternate(1, 1, 1) == 1, "single hub stays");

        // hub 1 was full, we are in hub 2: retry after 10 s, not before
        long arrived = 1_000_000L;
        check(!LowballingHubs.retryTargetDue(true, 2, 1, arrived, arrived + 9_999L, 10_000L),
                "no retry before 10 s");
        check(LowballingHubs.retryTargetDue(true, 2, 1, arrived, arrived + 10_000L, 10_000L),
                "retry after 10 s");
        // landed in the target hub: no more retries
        check(!LowballingHubs.retryTargetDue(true, 1, 1, arrived, arrived + 60_000L, 10_000L),
                "in target hub");
        // not there because of "full" (e.g. hub rotation): no retry
        check(!LowballingHubs.retryTargetDue(false, 2, 1, arrived, arrived + 60_000L, 10_000L),
                "not full -> no retry");
        System.out.println("PASS: hub rotation on cooldown and full-hub retry");
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError("FAIL: " + label);
        }
    }
}
