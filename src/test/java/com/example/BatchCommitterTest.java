package com.example;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link BatchCommitter}.
 */
class BatchCommitterTest
{
    @Test
    void shouldCommitImmediatelyWhenCreatedWithZeroInterval()
    {
        BatchCommitter committer = new BatchCommitter(0);
        boolean[] called = {false};

        // With zero interval, should commit immediately
        assertTrue(committer.tryCommit(() -> called[0] = true), "Zero interval should trigger immediate commit");
        assertTrue(called[0], "Action should have been called");
    }

    @Test
    void shouldNotCommitImmediatelyWhenCreatedWithDefaultInterval()
    {
        BatchCommitter committer = new BatchCommitter();
        boolean[] called = {false};

        // Should not commit immediately (just created)
        assertFalse(committer.tryCommit(() -> called[0] = true), "Default interval should not commit immediately after creation");
        assertFalse(called[0], "Action should not have been called");
    }

    @Test
    void shouldCommitWhenIntervalHasElapsed() throws InterruptedException
    {
        // Use a 100ms interval for testing
        BatchCommitter committer = new BatchCommitter(100);
        boolean[] called = {false};

        // Initially should not commit
        assertFalse(committer.tryCommit(() -> called[0] = true), "Should not commit immediately");

        // Wait for interval to elapse
        Thread.sleep(120);

        // Now should commit
        assertTrue(committer.tryCommit(() -> called[0] = true), "Should commit after interval elapses");
        assertTrue(called[0], "Action should have been called");
    }
}