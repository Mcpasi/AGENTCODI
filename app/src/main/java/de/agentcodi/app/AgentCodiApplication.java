package de.agentcodi.app;

import android.app.Application;
import android.os.Process;

import de.agentcodi.runtime.CrashDiagnostics;

public final class AgentCodiApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        installCrashRecorder();
    }

    private void installCrashRecorder() {
        final Thread.UncaughtExceptionHandler previous =
            Thread.getDefaultUncaughtExceptionHandler();
        try {
            final CrashDiagnostics diagnostics = CrashDiagnostics.open(getFilesDir());
            Thread.setDefaultUncaughtExceptionHandler(
                new Thread.UncaughtExceptionHandler() {
                    @Override
                    public void uncaughtException(Thread thread, Throwable error) {
                        try {
                            diagnostics.record(
                                "uncaught",
                                thread,
                                error
                            );
                        } catch (Throwable ignored) {
                            // Crash reporting must never replace the original failure.
                        }
                        if (previous != null) {
                            previous.uncaughtException(thread, error);
                        } else {
                            Process.killProcess(Process.myPid());
                        }
                    }
                }
            );
        } catch (Throwable ignored) {
            // The application must remain launchable even if diagnostics cannot initialize.
        }
    }
}
