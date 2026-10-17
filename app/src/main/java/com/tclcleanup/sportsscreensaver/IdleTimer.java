package com.tclcleanup.sportsscreensaver;

import android.os.Handler;

/** A reusable inactivity timer. All methods must be called from the main thread. */
final class IdleTimer {
    private final Handler handler;
    private final long timeoutMs;
    private final Runnable onIdle;
    private boolean running;
    private final Runnable timeout;

    IdleTimer(Handler handler, long timeoutMs, Runnable onIdle) {
        this.handler = handler;
        this.timeoutMs = timeoutMs;
        this.onIdle = onIdle;
        this.timeout = () -> {
            if (!running) return;
            running = false;
            this.onIdle.run();
        };
    }

    void start() {
        running = true;
        handler.removeCallbacks(timeout);
        handler.postDelayed(timeout, timeoutMs);
    }

    void reset() {
        start();
    }

    void stop() {
        running = false;
        handler.removeCallbacks(timeout);
    }
}
