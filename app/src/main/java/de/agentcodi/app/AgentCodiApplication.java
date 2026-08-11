package de.agentcodi.app;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Process;

import de.agentcodi.runtime.CrashDiagnostics;
import de.agentcodi.runtime.AgentRuntimeService;

public final class AgentCodiApplication extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguage.attach(base));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        installCrashRecorder();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfiguration) {
        super.onConfigurationChanged(newConfiguration);
        AgentRuntimeService.refreshLocalizedNotification();
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
