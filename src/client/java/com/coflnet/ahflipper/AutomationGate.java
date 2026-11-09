package com.coflnet.ahflipper;

import java.util.concurrent.atomic.AtomicReference;

final class AutomationGate {
    static final class Lease {
        final String owner;
        final boolean bid;
        final long startedAt = System.currentTimeMillis();

        private Lease(String owner, boolean bid) {
            this.owner = owner;
            this.bid = bid;
        }
    }

    private static final long STALE_LEASE_MS = 180_000L;
    private static final AtomicReference<Lease> CURRENT = new AtomicReference<>();
    private static volatile long lastActivityAt;

    private AutomationGate() {
    }

    /** Commands the player starts by hand; they may run while the lowballer trades. */
    private static final java.util.Set<String> MANUAL_OWNERS =
            java.util.Set.of("buy-current", "buy-player", "sell", "analyze");

    /**
     * Automatic AH work (auto-buy, bids, claims, listings) must not open a menu
     * while the lowballer is in a trade: it closed the trade window before.
     */
    static boolean blockedByLowballing(String owner) {
        if (owner == null || owner.startsWith("lowball") || MANUAL_OWNERS.contains(owner)) {
            return false;
        }
        return com.coflnet.lowballing.LowballingMod.holdsGui();
    }

    static Lease tryAcquire(String owner, boolean bid) {
        if (blockedByLowballing(owner)) {
            return null;
        }
        // Releases an orphaned lease first; otherwise a hung flow blocked all
        // AH automation until someone happened to call isBusy().
        currentLease();
        Lease lease = new Lease(owner, bid);
        if (!CURRENT.compareAndSet(null, lease)) {
            return null;
        }
        lastActivityAt = lease.startedAt;
        return lease;
    }

    static Lease acquire(String owner, boolean bid, long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        do {
            Lease lease = tryAcquire(owner, bid);
            if (lease != null) {
                return lease;
            }
            if (System.currentTimeMillis() >= deadline) {
                return null;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        } while (true);
    }

    static void release(Lease lease) {
        if (lease != null && CURRENT.compareAndSet(lease, null)) {
            lastActivityAt = System.currentTimeMillis();
        }
    }

    private static Lease currentLease() {
        Lease lease = CURRENT.get();
        if (lease == null) {
            return null;
        }
        if (System.currentTimeMillis() - lease.startedAt <= STALE_LEASE_MS) {
            return lease;
        }
        if (CURRENT.compareAndSet(lease, null)) {
            lastActivityAt = System.currentTimeMillis();
            System.out.println("[AH-Flipper] AutomationGate: verwaiste Lease '"
                    + lease.owner + "' nach " + (STALE_LEASE_MS / 1000)
                    + "s freigegeben");
        }
        return CURRENT.get();
    }

    static boolean isBusy() {
        return currentLease() != null;
    }

    static boolean isBidBusy() {
        Lease lease = currentLease();
        return lease != null && lease.bid;
    }

    static String owner() {
        Lease lease = currentLease();
        return lease == null ? "" : lease.owner;
    }

    static long lastActivityAt() {
        return lastActivityAt;
    }
}
