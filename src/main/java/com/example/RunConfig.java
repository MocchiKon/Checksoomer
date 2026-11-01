package com.example;

import java.io.File;

/**
 * Immutable snapshot of a run's configuration, captured on the EDT when Start is pressed so the
 * worker thread never reads UI-bound fields that the user might change mid-run.
 */
record RunConfig(File workingDirectory, File subset, Mode mode, boolean disableDbVerification)
{
}
