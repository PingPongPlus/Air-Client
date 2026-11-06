package com.coflnet.protection;

/**
 * Which side of the AH a protected screen belongs to. Must live outside the
 * mixin package: classes in {@code com.coflnet.mixin} cannot be loaded
 * directly, which made every AuctionProtectionMixin click fail with
 * "Mixin transformation of com.coflnet.mixin.ScreenMode failed".
 */
public enum AuctionScreenMode {
    SELLER,
    BIDDER
}
