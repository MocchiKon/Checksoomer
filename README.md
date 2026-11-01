## About
Checksoomer is a desktop application for computing, storing, and verifying file checksums in a selected working directory. It helps detect bit-rot, missing files and changed files.

## How to use
1. Launch the app and choose a **Working Directory**.
2. Optionally choose a **Subset** (single file or subfolder) to limit processing scope.
3. Select a mode and click **Start**.
4. Use **Stop** to finish processing after computing checksum for current file, or **Abort** to cancel immediately.

Modes and use cases:
- **COMPUTE_CHECKSUMS**: Saves/updates checksums for files (new files or with new modification dates) in the local database. Use this for initial indexing or after adding/changing files. Newly computed entries are marked as already tested (they were just hashed), so `RESUME_TEST` will not re-verify them — run `NEW_TEST` for a full re-verification. Any files that cannot be read or directories that cannot be walked are recorded in a timestamped error log instead of aborting the run.
- **NEW_TEST**: Starts a new test and verifies files against stored checksums from the beginning. Use this for a full integrity check. The reset of the "tested" flags is scoped to the selected subset: with a subset chosen, only entries inside it are re-tested; the rest of the database keeps its tested state.
- **RESUME_TEST**: Continues verification only for entries not yet marked as tested. Use this after stopping a previous test run.
- **CLEAN_CHECKSUMS_FOR_REMOVED_FILES**: Removes database entries for files that no longer exist and writes a removal log. Use this after deleting/moving files outside the app.

After each run a summary popup reports what happened (files tested/OK/failed, new/updated/skipped, entries removed, data hashed, throughput). When a run produced failures, errors, or removals, the popup also shows the full path of the log file that lists the details.

## Where files are stored
- All app files for a working directory live in a `Checksoomer` subdirectory that the app creates inside the working directory: `<working-directory>/Checksoomer/`.
- This directory holds the SQLite database, its self-checksum, the timestamped error logs, and the removed-entries log. The whole directory (including SQLite's `-journal`/`-wal`/`-shm` sidecar files) is excluded from checksum scanning, so app files are never hashed.
- Each log file begins with comment lines (prefixed with `#`) describing its line format:
  - **Error logs** are tab-separated: `STATUS<TAB>PATH<TAB>EXPECTED_CHECKSUM<TAB>ACTUAL_CHECKSUM`, where `STATUS` is one of `WRONG_CHECKSUM`, `MISSING`, `INVALID_PATH`, `ERROR_CALCULATING`, `ERROR_COMPUTING`, `ERROR_WALKING`.
  - **Removed-entries log** lines look like `REMOVED ENTRY [REASON]: <relative-path> | Checksum: <stored-checksum>`, where `REASON` is `MISSING_FILE` or `INVALID_PATH`.
- All logs are written as UTF-8.
- Diagnostic logging (stack traces, run boundaries) is written to `~/.checksoomer/checksoomer.log`, outside any scanned working directory.

> Note: databases created by older versions stored these files at the working-directory root. Such legacy files are now ignored (the database is read from `Checksoomer/`), so the first run after upgrading creates a fresh database under `Checksoomer/`. Run `COMPUTE_CHECKSUMS` to re-index.

## Technical

- Checksums are generated with **SHA-1**. SHA-1 is chosen for **speed**, to detect bit-rot and accidental corruption — the stated goal. It is *not* collision-resistant and is **not** intended to defend against a motivated attacker deliberately tampering with files.
- Metadata and checksums are stored in a local **SQLite** database under `Checksoomer/` in the working directory. Each row also records the file's size and modification time; `COMPUTE_CHECKSUMS` skips re-hashing a file only when both still match the stored values.
- The app also stores a checksum of the database file itself and verifies it before operations (unless disabled in the UI).
- Internal app files (the entire `Checksoomer/` directory) is excluded from checksum scanning.
- Paths in database are stored relative to the working directory for portability.
- **Symlinks are not followed or hashed.** The scan does not follow symbolic links, so a symlink to a regular file is skipped entirely (it is not reported as missing — it is simply not part of the index). Only regular files reached without crossing a symlink are checksummed.
