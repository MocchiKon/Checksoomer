package com.example;

/** Outcome of verifying a single file against its stored checksum. */
enum ResultKind
{OK, WRONG_CHECKSUM, MODIFIED_DIFF_CHECKSUM, MISSING, INVALID_PATH, ERROR_CALCULATING}
