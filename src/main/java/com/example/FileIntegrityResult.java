package com.example;

/**
 * The result of verifying one file: its {@link ResultKind}, an optional pre-formatted error-log
 * line (null when OK), and how many bytes were hashed (0 when nothing was read).
 */
record FileIntegrityResult(ResultKind kind, String errorMessage, long bytesHashed) { }
