package de.agentcodi.runtime;

import de.agentcodi.core.CrashReportFormatter;
import de.agentcodi.storage.CrashReportStore;

import java.io.File;
import java.io.IOException;

public final class CrashDiagnostics {
    private final CrashReportStore store;

    private CrashDiagnostics(CrashReportStore store) {
        this.store = store;
    }

    public static CrashDiagnostics open(File appFilesDirectory) throws IOException {
        return new CrashDiagnostics(CrashReportStore.open(appFilesDirectory));
    }

    public String read() throws IOException {
        return store.read();
    }

    public void record(String source, Thread thread, Throwable error) throws IOException {
        store.write(CrashReportFormatter.format(source, thread, error));
    }

    public void clear() throws IOException {
        store.clear();
    }
}

