package com.example;

/**
 * Cooperative stop/abort signaling shared between the UI (which sets the flags) and the processing
 * engine (which polls them). The worker thread is never cancelled; it only ever reads these.
 */
interface RunControl
{
    /** True if a graceful Stop or an Abort has been requested (processing should wind down). */
    boolean shouldStop();

    /** True if an Abort has been requested; checked mid-file so large hashes can stop promptly. */
    boolean isAbortRequested();
}
