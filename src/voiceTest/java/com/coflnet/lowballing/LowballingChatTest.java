package com.coflnet.lowballing;

public final class LowballingChatTest {
    private LowballingChatTest() {
    }

    public static void main(String[] args) {
        // the line from the log that got no answer (emblem before the rank)
        String[] emblem = LowballingChat.splitPlayerChat(
                "[110] ᛝ [✌] [MVP+] atqz: lbin on one of those cakes is 30m");
        check(emblem != null && emblem[0].equals("atqz")
                && emblem[1].equals("lbin on one of those cakes is 30m"), "emblem line");
        String[] tamil = LowballingChat.splitPlayerChat(
                "[391] ௐ [MVP+] iTibo: Lowballing 800m pursse /visit me");
        check(tamil != null && tamil[0].equals("iTibo"), "other emblem");
        String[] plain = LowballingChat.splitPlayerChat("[289] [✌] [MVP+] qbreese: my best offer rn is 190");
        check(plain != null && plain[0].equals("qbreese"), "old format still works");
        String[] noRank = LowballingChat.splitPlayerChat("[50] xVoltrix_: My best offer is 15.5m");
        check(noRank != null && noRank[0].equals("xVoltrix_"), "no rank");
        check(LowballingChat.splitPlayerChat("You have sent a trade request to atqz.") == null,
                "system line is no player line");

        check(LowballingSmartChat.isValueClaim("lbin on one of those cakes is 30m"), "lbin claim");
        check(LowballingSmartChat.isValueClaim("its worth like 20m"), "worth claim");
        check(!LowballingSmartChat.isValueClaim("20m and its yours"), "plain ask is no claim");
        String answer = LowballingSmartChat.answerValueClaim(15_500_000L, "new year cake");
        check(answer.contains("15.5m") && !answer.contains("30m"),
                "answer names our offer, not their number: " + answer);

        long shortReply = LowballingSmartChat.typingDelayMs("ok 15m", 0.5);
        long longReply = LowballingSmartChat.typingDelayMs(
                "lbin isnt what they actually sell for, 15.5m is my best for the new year cake", 0.5);
        check(shortReply >= 1_000L && shortReply < 1_600L, "short reply quick, got " + shortReply);
        check(longReply > 3_500L && longReply <= 5_000L, "long reply takes longer, got " + longReply);
        System.out.println("PASS: emblem chat lines, value claims, typing delay");
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError("FAIL: " + label);
        }
    }
}
