package de.agentcodi.browser;

public final class WorkspaceBreadcrumb {
    private final String label;
    private final String relativePath;

    public WorkspaceBreadcrumb(String label, String relativePath) {
        if (label == null || relativePath == null
            || label.length() > 255
            || relativePath.length()
                > WorkspaceBrowserLimits.MAXIMUM_RELATIVE_PATH_CHARACTERS) {
            throw new IllegalArgumentException("Workspace breadcrumb is invalid");
        }
        this.label = label;
        this.relativePath = relativePath;
    }

    public String getLabel() {
        return label;
    }

    public String getRelativePath() {
        return relativePath;
    }
}
