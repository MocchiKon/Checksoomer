package com.example;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe progress counters with throttled notification to a {@link Listener} (the UI).
 * <p>
 * Counters are updated on the worker thread; the listener is invoked at most once every
 * {@link #UI_POST_INTERVAL_NANOS} for the high-frequency {@link #fileProcessed} updates, so the
 * worker never floods the UI thread on large datasets. The other notifications (reset, total,
 * status, complete) fire immediately as they are infrequent.
 */
final class Progress
{
    /**
     * Snaps the listener to the true final counts after a stop/abort (the last throttled {@link #fileProcessed}
     * post may be stale). Leaves the bar at its real percentage rather than forcing it to 100%.
     */
    void snapFinal()
    {
        listener.onFinal(processedFiles.get(), totalFiles.get(), processedBytes.get());
    }

    private static final long UI_POST_INTERVAL_NANOS = 100_000_000L; // 100 ms

    private final Listener listener;

    private final AtomicLong totalFiles = new AtomicLong(0);
    private final AtomicLong processedFiles = new AtomicLong(0);
    private final AtomicLong processedBytes = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);

    // Worker-side UI-throttle timestamp (only touched by the worker thread).
    private long lastUiPostNanos = 0;

    Progress(Listener listener)
    {
        this.listener = listener;
    }

    void reset()
    {
        totalFiles.set(0);
        processedFiles.set(0);
        processedBytes.set(0);
        errorCount.set(0);
        lastUiPostNanos = 0;
        listener.onReset();
    }

    void setTotal(long total)
    {
        totalFiles.set(total);
        listener.onTotal(total);
    }

    /** Reports a transient status message (e.g. "Counting files...") without touching counters. */
    void status(String status)
    {
        listener.onStatus(status);
    }

    /** Increments the processed counters and posts a throttled progress + status update to the listener. */
    void fileProcessed(long bytes, String status)
    {
        long pf = processedFiles.incrementAndGet();
        processedBytes.addAndGet(bytes);
        if (uiPostDue())
        {
            listener.onProgress(pf, totalFiles.get(), processedBytes.get(), status);
        }
    }

    long incrementErrors()
    {
        return errorCount.incrementAndGet();
    }

    /**
     * Snaps the listener to a "100% complete" view using the final counts. Callers must only invoke
     * this on normal completion (not after a stop/abort), so residual count-vs-walk drift and
     * error-path gaps don't leave the bar below 100%.
     */
    void snapComplete()
    {
        listener.onComplete(processedFiles.get(), processedBytes.get());
    }

    /** Receives progress changes. Implementations marshal to the UI thread as needed. */
    interface Listener
    {
        void onReset();

        void onTotal(long total);

        void onStatus(String status);

        void onProgress(long processedFiles, long totalFiles, long processedBytes, String status);

        void onComplete(long processedFiles, long processedBytes);

        /**
         * Snaps the labels to the final counts after a stop/abort, WITHOUT forcing the bar to 100%
         * (the run did not complete). Unlike {@link #onProgress} this is not throttled.
         */
        void onFinal(long processedFiles, long totalFiles, long processedBytes);
    }

    long processedBytes()
    {
        return processedBytes.get();
    }

    long errorCount()
    {
        return errorCount.get();
    }

    /**
     * Returns true at most once every {@link #UI_POST_INTERVAL_NANOS}. Called only from the worker thread.
     */
    private boolean uiPostDue()
    {
        long now = System.nanoTime();
        if (now - lastUiPostNanos >= UI_POST_INTERVAL_NANOS)
        {
            lastUiPostNanos = now;
            return true;
        }
        return false;
    }
}
