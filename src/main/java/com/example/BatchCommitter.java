package com.example;

/**
 * Tracks elapsed time since last commit and decides when to commit based on a time threshold.
 * Commits are triggered when a configurable interval has passed since the last commit,
 * providing periodic durability for long-running checksum operations.
 */
final class BatchCommitter
{
    private long lastCommitTime = System.currentTimeMillis();
    private long commitIntervalMs;

    /**
     * Creates a BatchCommitter with the default 5-minute commit interval.
     */
    BatchCommitter()
    {
        this(5 * 60 * 1000); // 5 minutes
    }

    /**
     * Creates a BatchCommitter with a custom commit interval (for testing).
     *
     * @param commitIntervalMs the interval in milliseconds between commits
     */
    BatchCommitter(long commitIntervalMs)
    {
        this.commitIntervalMs = commitIntervalMs;
    }

    /**
     * Attempts to commit if enough time has elapsed since the last commit.
     * Executes the provided action if a commit is due, then marks the time.
     *
     * @param action the action to perform if a commit is due (typically a commit operation)
     * @return true if the action was executed (commit was due), false otherwise
     */
    boolean tryCommit(Runnable action)
    {
        long now = System.currentTimeMillis();
        if (now - lastCommitTime >= commitIntervalMs)
        {
            action.run();
            lastCommitTime = now;
            return true;
        }
        return false;
    }
}