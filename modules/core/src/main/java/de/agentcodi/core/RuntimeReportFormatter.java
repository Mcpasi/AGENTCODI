package de.agentcodi.core;

public final class RuntimeReportFormatter {
    private RuntimeReportFormatter() {
    }

    public static String format(RuntimeSnapshot snapshot) {
        StringBuilder report = new StringBuilder();
        report.append(BuildIdentity.summary()).append('\n');
        report.append("Phase: ").append(snapshot.getPhase()).append('\n');
        report.append("Status: ").append(snapshot.getMessage()).append('\n');
        if (!snapshot.getEngineVersion().isEmpty()) {
            report.append("Engine: ").append(snapshot.getEngineVersion()).append('\n');
        }
        if (!snapshot.getDiagnostics().isEmpty()) {
            report.append("Diagnose: ").append(snapshot.getDiagnostics()).append('\n');
        }
        if (!snapshot.getWorkspacePath().isEmpty()) {
            report.append("Workspace: ").append(snapshot.getWorkspacePath()).append('\n');
        }
        if (!snapshot.getExecutionModeId().isEmpty()) {
            report.append("Execution mode: ")
                .append(snapshot.getExecutionModeId())
                .append('\n');
        }
        if (!snapshot.getPermissionProfileId().isEmpty()) {
            report.append("Permission profile: ")
                .append(snapshot.getPermissionProfileId())
                .append('\n');
        }
        return report.toString();
    }
}
