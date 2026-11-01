package com.example;

/**
 * Thrown when the database self-checksum could not be read or recomputed (e.g. the file is
 * momentarily locked by antivirus, file sharing, or another process). This is a transient,
 * retryable condition, distinct from a genuine checksum mismatch (corruption), so the UI can
 * offer a retry instead of the hard "corruption" block.
 */
final class DbVerificationException extends Exception
{
    DbVerificationException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
