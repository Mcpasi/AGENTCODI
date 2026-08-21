package de.agentcodi.imports;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Bounded, duplicate-free snapshot of files attached to one Codex input. */
public final class WorkspaceImportSelection {
    private WorkspaceImportSelection() {
    }

    public static List<ImportedWorkspaceFile> copyOf(
        List<ImportedWorkspaceFile> values
    ) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        if (values.size() > WorkspaceImportLimits.MAXIMUM_FILES_PER_MESSAGE) {
            throw new IllegalArgumentException("Too many imported documents are selected");
        }
        List<ImportedWorkspaceFile> result =
            new ArrayList<ImportedWorkspaceFile>(values.size());
        Set<String> paths = new HashSet<String>();
        long totalBytes = 0L;
        for (ImportedWorkspaceFile value : values) {
            if (value == null) {
                throw new IllegalArgumentException("Imported document must not be null");
            }
            if (!paths.add(value.getRelativePath())) {
                throw new IllegalArgumentException("Imported document paths must be unique");
            }
            if (totalBytes > WorkspaceImportLimits.MAXIMUM_TOTAL_BYTES
                - value.getByteCount()) {
                throw new IllegalArgumentException("Imported documents exceed the total limit");
            }
            totalBytes += value.getByteCount();
            result.add(value);
        }
        return Collections.unmodifiableList(result);
    }

    public static long totalBytes(List<ImportedWorkspaceFile> values) {
        long total = 0L;
        for (ImportedWorkspaceFile value : copyOf(values)) {
            total += value.getByteCount();
        }
        return total;
    }
}
