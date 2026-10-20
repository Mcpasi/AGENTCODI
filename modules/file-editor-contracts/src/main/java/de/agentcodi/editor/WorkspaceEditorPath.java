package de.agentcodi.editor;

import java.nio.charset.StandardCharsets;

/** New files use a single filename within the selected directory. */
public final class WorkspaceEditorPath {
    private WorkspaceEditorPath() {
    }

    public static String newFilePath(String directory, String name) throws WorkspaceEditorException {
        if (directory == null || name == null || name.trim().isEmpty()
            || !name.equals(name.trim()) || directory.length() > 2048 || name.length() > 255) {
            throw invalidName();
        }
        requireComponent(name);
        if (!directory.isEmpty()) {
            for (String component : directory.split("/", -1)) {
                requireComponent(component);
            }
        }
        String path = directory.isEmpty() ? name : directory + "/" + name;
        if (path.length() > 2048 || path.split("/", -1).length > 64) {
            throw invalidName();
        }
        return path;
    }

    private static void requireComponent(String name) throws WorkspaceEditorException {
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)
            || name.getBytes(StandardCharsets.UTF_8).length > 255) {
            throw invalidName();
        }
        for (int index = 0; index < name.length(); index++) {
            char value = name.charAt(index);
            if (value < 0x20 || value == 0x7f || value == '/' || value == '\\' || value == ':') {
                throw invalidName();
            }
            if (Character.isHighSurrogate(value)) {
                if (index + 1 >= name.length() || !Character.isLowSurrogate(name.charAt(++index))) {
                    throw invalidName();
                }
            } else if (Character.isLowSurrogate(value)) {
                throw invalidName();
            }
        }
    }

    private static WorkspaceEditorException invalidName() {
        return new WorkspaceEditorException(WorkspaceEditorException.Reason.INVALID_NAME);
    }
}
