package com.coflnet;

import CoflCore.CoflCore;
import CoflCore.handlers.EventRegistry;
import baritone.api.BaritoneAPI;
import java.lang.reflect.Modifier;
import java.util.Timer;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Releases bundled runtimes whose background workers otherwise keep the client JVM alive. */
final class ClientRuntimeLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(ClientRuntimeLifecycle.class);

    private ClientRuntimeLifecycle() {}

    static void stop() {
        try {
            var wrapper = CoflCore.getWrapper();
            if (wrapper != null) wrapper.stop();
        } catch (RuntimeException e) {
            LOG.warn("Could not close the Cofl connection", e);
        }
        EventRegistry.chatThreadPool.shutdownNow();
        EventRegistry.tickThreadPool.shutdownNow();
        stopFlipTimer();
        stopBaritone();
    }

    private static void stopFlipTimer() {
        if (CoflCore.flipHandler == null || CoflCore.flipHandler.fds == null) return;
        var flips = CoflCore.flipHandler.fds;
        try {
            // CoflSkyCore has no close API for its private housekeeping timer.
            for (var field : flips.getClass().getDeclaredFields()) {
                if (!Timer.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                var timer = (Timer) field.get(flips);
                if (timer != null) timer.cancel();
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warn("Could not stop the Cofl housekeeping timer", e);
        }
    }

    private static void stopBaritone() {
        var provider = BaritoneAPI.getProvider();
        for (var baritone : provider.getAllBaritones()) {
            try {
                baritone.getPathingBehavior().cancelEverything();
                baritone.getWorldProvider().ifWorldLoaded(world -> world.getCachedWorld().save());
            } catch (RuntimeException e) {
                LOG.warn("Could not finish the Baritone world cache", e);
            }
        }
        try {
            // The bundled Baritone exposes its shared executor through an obfuscated
            // static getter. Match its return type so this does not depend on that name.
            for (var method : provider.getPrimaryBaritone().getClass().getDeclaredMethods()) {
                if (!Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0
                        || !Executor.class.isAssignableFrom(method.getReturnType())) continue;
                if (method.invoke(null) instanceof ExecutorService workers) workers.shutdownNow();
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warn("Could not stop the Baritone workers", e);
        }
    }
}
