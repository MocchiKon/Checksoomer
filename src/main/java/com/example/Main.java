package com.example;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Swing UI and run orchestration. Owns the run controls and the cooperative stop/abort flags
 * (it implements {@link RunControl}), drives a {@link ChecksumEngine} on a background worker, and
 * mirrors engine {@link Progress} updates into the progress widgets. All processing logic lives in
 * the engine and its collaborators; this class is intentionally free of database and IO concerns.
 */
public class Main extends JFrame implements RunControl
{
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private static final String DB_VERIFY_FAILED_MESSAGE =
            "Database checksum verification failed! Cannot proceed.\n\n" +
                    "If the previous session ended unexpectedly (crash, power loss), the stored\n" +
                    "checksum may be stale. You can tick \"Disable database checksum verification\"\n" +
                    "for one run to regenerate it.";

    private static final String DB_VERIFY_UNREADABLE_MESSAGE =
            "Could not read the database to verify it.\n\n" +
                    "The file may be temporarily locked (antivirus, file sharing, or another\n" +
                    "program). This is usually transient - please try again. If it persists,\n" +
                    "check the file's permissions and that no other process is using it.";

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private volatile boolean stopRequested = false;
    private volatile boolean abortRequested = false;
    private volatile boolean exitWhenDone = false;

    private JTextField workingDirField;
    private JTextField pickedSubsetField;
    private JComboBox<Mode> modeComboBox;
    private JCheckBox disableDbVerificationCheckBox;
    private JLabel progressLabel;
    private JProgressBar progressBar;
    private JLabel dataSizeLabel;
    private JButton stopButton;
    private JButton abortButton;
    private final List<JComponent> runControls = new ArrayList<>();
    private File workingDirectory;
    private File pickedSubset;

    private final Progress progress = new Progress(new UiProgress());

    public Main()
    {
        initializeGUI();
    }

    public static void main(String[] args)
    {
        SwingUtilities.invokeLater(() -> {
            try
            {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            }
            catch (Exception e)
            {
                log.warn("System look and feel unavailable, falling back to default", e);
            }
            new Main().setVisible(true);
        });
    }

    /**
     * Returns true if processing should stop (either Abort or graceful Stop was requested).
     * Both are cooperative {@code volatile} flags; the worker thread is never cancelled.
     */
    @Override
    public boolean shouldStop()
    {
        return stopRequested || abortRequested;
    }

    @Override
    public boolean isAbortRequested()
    {
        return abortRequested;
    }

    private void initializeGUI()
    {
        setTitle(AppLayout.APP_NAME + " (Checksum Manager)");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter()
        {
            @Override
            public void windowClosing(WindowEvent e)
            {
                handleWindowClosing();
            }
        });
        setLayout(new GridBagLayout());
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);

        // Working Directory
        gbc.gridx = 0;
        gbc.gridy = 0;
        add(new JLabel("Working Directory:"), gbc);

        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        workingDirField = new JTextField(30);
        workingDirField.setEditable(false);
        add(workingDirField, gbc);

        gbc.gridx = 2;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        var browseWorkingDirButton = new JButton("Browse");
        browseWorkingDirButton.addActionListener(e -> chooseWorkingDirectory());
        add(browseWorkingDirButton, gbc);

        // Subset
        gbc.gridx = 0;
        gbc.gridy = 1;
        add(new JLabel("Subset (Optional):"), gbc);

        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        pickedSubsetField = new JTextField(30);
        pickedSubsetField.setEditable(false);
        add(pickedSubsetField, gbc);

        gbc.gridx = 2;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        var browsePickedSubsetButton = new JButton("Browse");
        browsePickedSubsetButton.addActionListener(e -> choosePickedSubset());
        add(browsePickedSubsetButton, gbc);

        gbc.gridx = 3;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        var clearPickedSubsetButton = new JButton("Clear");
        clearPickedSubsetButton.addActionListener(e -> clearPickedSubset());
        add(clearPickedSubsetButton, gbc);

        // Mode Selection
        gbc.gridx = 0;
        gbc.gridy = 2;
        add(new JLabel("Mode:"), gbc);

        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        modeComboBox = new JComboBox<>(Mode.values());
        add(modeComboBox, gbc);

        // Database Verification Checkbox
        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.gridwidth = 4;
        disableDbVerificationCheckBox = new JCheckBox("Disable database checksum verification");
        add(disableDbVerificationCheckBox, gbc);

        // Progress Bar
        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.gridwidth = 4;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setString("0 / 0");
        progressBar.setVisible(false);
        add(progressBar, gbc);

        // Data Size Label
        gbc.gridx = 0;
        gbc.gridy = 5;
        gbc.gridwidth = 4;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        dataSizeLabel = new JLabel("Processed: 0.00 GB");
        dataSizeLabel.setVisible(false);
        add(dataSizeLabel, gbc);

        // Progress Label
        gbc.gridx = 0;
        gbc.gridy = 6;
        gbc.gridwidth = 4;
        gbc.fill = GridBagConstraints.NONE;
        progressLabel = new JLabel("Ready");
        progressLabel.setVisible(false);
        add(progressLabel, gbc);

        // Start Button
        gbc.gridx = 0;
        gbc.gridy = 7;
        gbc.gridwidth = 1;
        gbc.fill = GridBagConstraints.NONE;
        var startButton = new JButton("Start");
        startButton.addActionListener(e -> startProcessing());
        add(startButton, gbc);

        // Stop Button (graceful - waits for current file to finish)
        gbc.gridx = 1;
        gbc.gridy = 7;
        gbc.gridwidth = 1;
        gbc.fill = GridBagConstraints.NONE;
        stopButton = new JButton("Stop");
        stopButton.setToolTipText("Gracefully stop after current file finishes processing");
        stopButton.addActionListener(e -> stopProcessing());
        stopButton.setEnabled(false);
        add(stopButton, gbc);

        // Abort Button (stops as soon as possible, between 1 MB reads of the current file)
        gbc.gridx = 2;
        gbc.gridy = 7;
        gbc.gridwidth = 1;
        gbc.fill = GridBagConstraints.NONE;
        abortButton = new JButton("Abort");
        abortButton.setToolTipText("Abort as soon as possible, even mid-file (checksum result for current file will be discarded)");
        abortButton.addActionListener(e -> abortProcessing());
        abortButton.setEnabled(false);
        add(abortButton, gbc);

        // Controls that must be locked while a run is in progress.
        runControls.add(startButton);
        runControls.add(browseWorkingDirButton);
        runControls.add(browsePickedSubsetButton);
        runControls.add(clearPickedSubsetButton);
        runControls.add(modeComboBox);
        runControls.add(disableDbVerificationCheckBox);

        pack();
        setLocationRelativeTo(null);
    }

    private void handleWindowClosing()
    {
        if (!isRunning.get())
        {
            dispose();
            System.exit(0);
            return;
        }
        int choice = JOptionPane.showConfirmDialog(this,
                "A run is in progress. Abort it and exit?",
                "Exit", JOptionPane.YES_NO_OPTION);
        if (choice == JOptionPane.YES_OPTION)
        {
            abortRequested = true;
            exitWhenDone = true;
            stopButton.setEnabled(false);
            abortButton.setEnabled(false);
        }
    }

    private void setRunControlsEnabled(boolean enabled)
    {
        for (JComponent control : runControls)
        {
            control.setEnabled(enabled);
        }
    }

    private void showProgressWidgets()
    {
        SwingUtilities.invokeLater(() -> {
            // Only pack when the widgets were actually hidden, so a user-resized window is left alone.
            if (!progressBar.isVisible())
            {
                progressBar.setVisible(true);
                dataSizeLabel.setVisible(true);
                progressLabel.setVisible(true);
                pack();
            }
        });
    }

    private void chooseWorkingDirectory()
    {
        var chooser = new JFileChooser(System.getProperty("user.dir"));
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        int result = chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION)
        {
            workingDirectory = chooser.getSelectedFile();
            workingDirField.setText(workingDirectory.getAbsolutePath());

            // Prevent using a subset selected for a previous working directory.
            if (pickedSubset != null && !FsPaths.isWithin(pickedSubset, workingDirectory))
            {
                clearPickedSubset();
            }
        }
    }

    private void choosePickedSubset()
    {
        if (workingDirectory == null)
        {
            JOptionPane.showMessageDialog(this, "Please select working directory first.");
            return;
        }

        var chooser = new JFileChooser(workingDirectory);
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        int result = chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION)
        {
            File selectedFile = chooser.getSelectedFile();
            if (!FsPaths.isWithin(selectedFile, workingDirectory))
            {
                JOptionPane.showMessageDialog(this,
                        "Selected subset must be within the working directory.\n" +
                                "Working Directory: " + workingDirectory.getAbsolutePath() + "\n" +
                                "Selected: " + selectedFile.getAbsolutePath(),
                        "Invalid Subset Selection",
                        JOptionPane.ERROR_MESSAGE);
            }
            else if (FsPaths.isSame(selectedFile, workingDirectory))
            {
                JOptionPane.showMessageDialog(this,
                        "Selecting the working directory itself is the same as no subset.",
                        "Subset Cleared", JOptionPane.INFORMATION_MESSAGE);
                clearPickedSubset();
            }
            else
            {
                pickedSubset = selectedFile;
                pickedSubsetField.setText(pickedSubset.getAbsolutePath());
            }
        }
    }

    private void clearPickedSubset()
    {
        pickedSubset = null;
        pickedSubsetField.setText("");
    }

    private void startProcessing()
    {
        if (workingDirectory == null)
        {
            JOptionPane.showMessageDialog(this, "Please select a working directory.");
            return;
        }
        // Prevent double-start
        if (!isRunning.compareAndSet(false, true))
        {
            return;
        }
        stopRequested = false;
        abortRequested = false;
        exitWhenDone = false;
        progress.reset();
        showProgressWidgets();

        // Capture an immutable snapshot of the configuration on the EDT, so the worker
        // never reads UI-bound fields that the user might change mid-run.
        final RunConfig cfg = new RunConfig(
                workingDirectory,
                pickedSubset,
                (Mode) modeComboBox.getSelectedItem(),
                disableDbVerificationCheckBox.isSelected());
        log.info("Starting run: mode={}, workingDir={}, subset={}, dbVerificationDisabled={}",
                cfg.mode(), cfg.workingDirectory(), cfg.subset(), cfg.disableDbVerification());

        final ChecksumEngine engine = new ChecksumEngine(this, progress);

        SwingWorker<Void, Void> worker = new SwingWorker<>()
        {
            @Override
            protected Void doInBackground()
            {
                long startTime = System.currentTimeMillis();
                RunStats stats = new RunStats(cfg.mode());
                boolean errored = false;
                boolean verificationFailed = false;
                try
                {
                    SwingUtilities.invokeLater(() -> {
                        setRunControlsEnabled(false);
                        stopButton.setEnabled(true);
                        abortButton.setEnabled(true);
                    });

                    boolean verified = false;
                    boolean transientVerifyError = false;
                    try
                    {
                        verified = cfg.disableDbVerification() || engine.verifyDatabaseChecksum(cfg);
                    }
                    catch (AbortedException ae)
                    {
                        // Aborted while hashing the DB file: leave verified=false, fall through to the abort outcome.
                    }
                    catch (DbVerificationException dve)
                    {
                        // Could not read the DB/sidecar (transient/locked) — NOT corruption; offer a retry.
                        transientVerifyError = true;
                        log.warn("Database verification could not be completed for {}", cfg.workingDirectory(), dve);
                    }

                    if (verified)
                    {
                        stats = engine.run(cfg);
                    }
                    else if (transientVerifyError)
                    {
                        verificationFailed = true;
                        SwingUtilities.invokeLater(() -> {
                            progressLabel.setText("Database verification could not be completed");
                            JOptionPane.showMessageDialog(Main.this,
                                    DB_VERIFY_UNREADABLE_MESSAGE, "Error", JOptionPane.ERROR_MESSAGE);
                        });
                    }
                    else if (!abortRequested)
                    {
                        verificationFailed = true;
                        log.warn("Database checksum verification failed for {}", cfg.workingDirectory());
                        SwingUtilities.invokeLater(() -> {
                            progressLabel.setText("Database verification failed");
                            JOptionPane.showMessageDialog(Main.this,
                                    DB_VERIFY_FAILED_MESSAGE, "Error", JOptionPane.ERROR_MESSAGE);
                        });
                    }
                }
                catch (Exception e)
                {
                    errored = true;
                    log.error("Run failed", e);
                    SwingUtilities.invokeLater(() -> {
                        progressLabel.setText("Error: " + e.getMessage());
                        JOptionPane.showMessageDialog(Main.this,
                                "An error occurred: " + e.getMessage(),
                                "Error", JOptionPane.ERROR_MESSAGE);
                    });
                }

                if (!errored && !verificationFailed)
                {
                    long durationMs = System.currentTimeMillis() - startTime;
                    // Test modes record failures into stats, not progress.errorCount(); use the right
                    // source per mode so the label reflects failures (parity with the popup).
                    long failures = switch (cfg.mode())
                    {
                        case NEW_TEST, RESUME_TEST -> stats.failures();
                        default -> progress.errorCount();
                    };
                    String formattedDuration = Format.duration(durationMs);
                    String outcomeWord = abortRequested ? "Aborted" : stopRequested ? "Stopped" : "Completed";
                    String prefix = (!abortRequested && !stopRequested) ? "Completed in " : outcomeWord + " after ";
                    String summary = cfg.mode() == Mode.CLEAN_CHECKSUMS_FOR_REMOVED_FILES
                            ? " - removed " + stats.entriesRemoved + " entries"
                            : "";
                    String errorSuffix = failures > 0 ? " with " + failures + " issue(s) (see log)" : "";
                    final String label = prefix + formattedDuration + summary + errorSuffix;
                    SwingUtilities.invokeLater(() -> progressLabel.setText(label));
                    log.info("Run {} ({}) in {} - {}, issues={}",
                            outcomeWord, cfg.mode(), formattedDuration, stats.summaryLine(), failures);

                    if (!exitWhenDone)
                    {
                        showStatsPopup(stats, outcomeWord, durationMs, cfg.disableDbVerification());
                    }
                }
                return null;
            }

            @Override
            protected void done()
            {
                // Reset run state only after ALL worker-thread work (and any summary popup) is finished,
                // so a new run can't reset the shared stop/abort flags or progress while the old run is
                // still reading them. done() runs on the EDT.
                isRunning.set(false);
                setRunControlsEnabled(true);
                stopButton.setEnabled(false);
                abortButton.setEnabled(false);
                if (exitWhenDone)
                {
                    // saveChecksum already ran in ChecksumEngine.run's finally before we got here.
                    dispose();
                    System.exit(0);
                }
            }
        };
        worker.execute();
    }

    /**
     * Gracefully stop processing after the current file finishes.
     */
    private void stopProcessing()
    {
        if (!isRunning.get())
        {
            return;
        }
        stopRequested = true;
        SwingUtilities.invokeLater(() -> {
            stopButton.setEnabled(false);
            progressLabel.setText("Stopping after current file...");
        });
    }

    /**
     * Request an abort. Takes effect as soon as possible, even mid-file (between 1 MB reads).
     * A second click is a no-op because the buttons are disabled here.
     */
    private void abortProcessing()
    {
        abortRequested = true;
        SwingUtilities.invokeLater(() -> {
            stopButton.setEnabled(false);
            abortButton.setEnabled(false);
        });
    }

    /**
     * Shows a summary dialog of what a run accomplished. Shown for Completed/Stopped/Aborted
     * outcomes (Stop/Abort show partial stats). Not shown after a hard error or when exiting.
     */
    private void showStatsPopup(RunStats stats, String outcome, long durationMs, boolean verificationDisabled)
    {
        long bytes = progress.processedBytes();
        long errors = progress.errorCount();
        double seconds = durationMs / 1000.0;

        StringBuilder sb = new StringBuilder();
        if (stats.sidecarWriteFailed)
        {
            sb.append("⚠ Could not update the database self-checksum (see log).\n")
                    .append("  The next session may report a verification failure; re-run, or check\n")
                    .append("  free disk space and permissions.\n\n");
        }
        if (verificationDisabled)
        {
            sb.append("Note: database verification was disabled - the self-checksum baseline was\n")
                    .append("  (re)generated from UNVERIFIED data.\n\n");
        }
        switch (stats.mode)
        {
            case NEW_TEST, RESUME_TEST ->
            {
                long tested = stats.tested();
                long failures = stats.failures();
                if (failures > 0)
                {
                    sb.append("⚠ ").append(failures).append(" file(s) failed verification\n\n");
                }
                appendLine(sb, "Outcome:", outcome);
                appendLine(sb, "Elapsed:", Format.duration(durationMs));
                appendLine(sb, "Files tested:", String.valueOf(tested));
                appendLine(sb, "  OK:", String.valueOf(stats.ok));
                appendLine(sb, "  Wrong checksum:", String.valueOf(stats.wrongChecksum));
                appendLine(sb, "  Missing:", String.valueOf(stats.missing));
                appendLine(sb, "  Invalid path:", String.valueOf(stats.invalidPath));
                appendLine(sb, "  Error reading:", String.valueOf(stats.errorCalculating));
                appendLine(sb, "Data hashed:", Format.gb(bytes));
                appendLine(sb, "Throughput:", Format.mbPerSecond(bytes, seconds));
                if (failures > 0 && stats.detailLogPath != null)
                {
                    appendDetailLocation(sb, "Failed checksums (and other issues) are listed in:", stats.detailLogPath);
                }
            }
            case COMPUTE_CHECKSUMS ->
            {
                long scanned = stats.newEntries + stats.updatedEntries + stats.skippedUnchanged;
                if (errors > 0)
                {
                    sb.append("⚠ ").append(errors).append(" file(s) could not be processed\n\n");
                }
                appendLine(sb, "Outcome:", outcome);
                appendLine(sb, "Elapsed:", Format.duration(durationMs));
                appendLine(sb, "Files scanned:", String.valueOf(scanned));
                appendLine(sb, "  New:", String.valueOf(stats.newEntries));
                appendLine(sb, "  Updated:", String.valueOf(stats.updatedEntries));
                appendLine(sb, "  Skipped (unchanged):", String.valueOf(stats.skippedUnchanged));
                appendLine(sb, "Data hashed:", Format.gb(bytes));
                appendLine(sb, "Throughput:", Format.mbPerSecond(bytes, seconds));
                if (errors > 0 && stats.detailLogPath != null)
                {
                    appendDetailLocation(sb, "Files that could not be processed are listed in:", stats.detailLogPath);
                }
            }
            case CLEAN_CHECKSUMS_FOR_REMOVED_FILES ->
            {
                appendLine(sb, "Outcome:", outcome);
                appendLine(sb, "Elapsed:", Format.duration(durationMs));
                appendLine(sb, "Entries scanned:", String.valueOf(stats.entriesScanned));
                appendLine(sb, "Entries removed:", String.valueOf(stats.entriesRemoved));
                if (stats.entriesRemoved > 0 && stats.detailLogPath != null)
                {
                    appendDetailLocation(sb, "Removed entries are listed in:", stats.detailLogPath);
                }
            }
        }

        final String body = sb.toString();
        final String title = "Run summary - " + stats.mode;
        SwingUtilities.invokeLater(() -> {
            JTextArea area = new JTextArea(body);
            area.setEditable(false);
            area.setOpaque(false);
            area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            JScrollPane scrollPane = new JScrollPane(area);
            scrollPane.setBorder(null);
            scrollPane.setPreferredSize(new Dimension(540, Math.min(400, 28 + body.split("\n", -1).length * 18)));
            JOptionPane.showMessageDialog(Main.this, scrollPane, title, JOptionPane.INFORMATION_MESSAGE);
        });
    }

    private static void appendLine(StringBuilder sb, String label, String value)
    {
        sb.append(String.format("%-22s%s%n", label, value));
    }

    private static void appendDetailLocation(StringBuilder sb, String description, String path)
    {
        sb.append('\n').append(description).append('\n').append("  ").append(path).append('\n');
    }

    /**
     * Bridges {@link Progress} updates from the worker thread onto the Swing progress widgets,
     * always marshaling onto the EDT.
     */
    private final class UiProgress implements Progress.Listener
    {
        @Override
        public void onReset()
        {
            SwingUtilities.invokeLater(() -> {
                progressBar.setValue(0);
                progressBar.setString("0 / 0");
                dataSizeLabel.setText("Processed: 0.00 GB");
                progressLabel.setText("Starting...");
            });
        }

        @Override
        public void onTotal(long total)
        {
            SwingUtilities.invokeLater(() -> progressBar.setString("0 / " + total));
        }

        @Override
        public void onStatus(String status)
        {
            SwingUtilities.invokeLater(() -> progressLabel.setText(status));
        }

        @Override
        public void onProgress(long processedFiles, long totalFiles, long processedBytes, String status)
        {
            SwingUtilities.invokeLater(() -> {
                updateBar(processedFiles, totalFiles, processedBytes);
                if (status != null)
                {
                    progressLabel.setText(status);
                }
            });
        }

        @Override
        public void onComplete(long processedFiles, long processedBytes)
        {
            SwingUtilities.invokeLater(() -> {
                progressBar.setValue(100);
                progressBar.setString(processedFiles + " / " + processedFiles);
                dataSizeLabel.setText(Format.processedGbText(processedBytes));
            });
        }

        @Override
        public void onFinal(long processedFiles, long totalFiles, long processedBytes)
        {
            SwingUtilities.invokeLater(() -> updateBar(processedFiles, totalFiles, processedBytes));
        }

        /**
         * Sets the bar value/string and the processed-bytes label from a progress snapshot (on the EDT).
         */
        private void updateBar(long processedFiles, long totalFiles, long processedBytes)
        {
            if (totalFiles > 0)
            {
                progressBar.setValue(percentComplete(processedFiles, totalFiles));
                progressBar.setString(processedFiles + " / " + totalFiles);
            }
            else
            {
                progressBar.setString(processedFiles + " / ?");
            }
            dataSizeLabel.setText(Format.processedGbText(processedBytes));
        }

        /**
         * Work-remaining percentage that truncates (floor) so the bar can't show 100% one file early.
         */
        private int percentComplete(long processedFiles, long totalFiles)
        {
            return (int) Math.min(100L, processedFiles * 100 / totalFiles);
        }
    }
}
