package de.agentcodi.storage;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;

public final class CrashReportStore {
    private static final int MAX_BYTES = 24 * 1024;
    private static final String REPORT_NAME = "last-crash.txt";
    private static final String TEMPORARY_NAME = ".last-crash.tmp";

    private final File reportFile;
    private final File temporaryFile;

    private CrashReportStore(File stateDirectory) {
        reportFile = new File(stateDirectory, REPORT_NAME);
        temporaryFile = new File(stateDirectory, TEMPORARY_NAME);
    }

    public static CrashReportStore open(File appFilesDirectory) throws IOException {
        WorkspaceLayout layout = WorkspaceLayout.create(appFilesDirectory);
        return new CrashReportStore(layout.getState());
    }

    public synchronized String read() throws IOException {
        if (!reportFile.exists()) {
            return "";
        }
        requireRegularNonSymbolic(reportFile);
        if (reportFile.length() > MAX_BYTES) {
            throw new IOException("Crash report exceeds its size limit");
        }

        try (FileInputStream input = new FileInputStream(reportFile);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_BYTES) {
                    throw new IOException("Crash report grew beyond its size limit");
                }
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    public synchronized void write(String report) throws IOException {
        rejectSymbolicLink(reportFile);
        rejectSymbolicLink(temporaryFile);
        if (temporaryFile.exists() && !temporaryFile.delete()) {
            throw new IOException("Could not replace temporary crash report");
        }

        byte[] content = bound(report).getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(temporaryFile, false)) {
            output.write(content);
            output.getFD().sync();
        }
        restrictToOwner(temporaryFile);

        try {
            Files.move(
                temporaryFile.toPath(),
                reportFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(
                temporaryFile.toPath(),
                reportFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            );
        }
        restrictToOwner(reportFile);
    }

    public synchronized void clear() throws IOException {
        rejectSymbolicLink(reportFile);
        if (reportFile.exists() && !reportFile.isFile()) {
            throw new IOException("Crash report path is not a regular file");
        }
        Files.deleteIfExists(reportFile.toPath());
    }

    private static String bound(String report) {
        String value = report == null ? "" : report;
        int maximumCharacters = (MAX_BYTES - 64) / 4;
        if (value.length() <= maximumCharacters) {
            return value;
        }
        return value.substring(0, maximumCharacters) + "\n... report truncated\n";
    }

    private static void requireRegularNonSymbolic(File file) throws IOException {
        rejectSymbolicLink(file);
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Crash report path is not a regular file");
        }
    }

    private static void rejectSymbolicLink(File file) throws IOException {
        if (Files.isSymbolicLink(file.toPath())) {
            throw new IOException("Symbolic crash report paths are not accepted");
        }
    }

    private static void restrictToOwner(File file) {
        file.setReadable(false, false);
        file.setWritable(false, false);
        file.setExecutable(false, false);
        file.setReadable(true, true);
        file.setWritable(true, true);
    }
}

