package de.agentcodi.runtime;

import android.content.Context;

import de.agentcodi.editor.WorkspaceEditorDocument;
import de.agentcodi.editor.WorkspaceEditorException;
import de.agentcodi.editor.WorkspaceEditorLimits;
import de.agentcodi.editor.WorkspaceEditorStore;
import de.agentcodi.editor.client.WorkspaceFileEditor;
import de.agentcodi.storage.WorkspaceFileAccess;
import de.agentcodi.storage.WorkspaceFileEdit;
import de.agentcodi.storage.WorkspaceLayout;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;

/** Local editor facade. It exposes only the workspace, never installed package roots. */
public final class WorkspaceEditorRepository {
    private final WorkspaceFileEditor editor;

    private WorkspaceEditorRepository(WorkspaceEditorStore store) {
        editor = new WorkspaceFileEditor(store);
    }

    public static WorkspaceEditorRepository create(Context context) throws IOException {
        final File workspace = WorkspaceLayout.create(context.getFilesDir()).getWorkspace();
        final WorkspaceFileAccess.Opener opener = NativeWorkspaceFileAccess.opener();
        final WorkspaceFileEdit.Creator creator = NativeWorkspaceFileAccess.creator();
        return new WorkspaceEditorRepository(new WorkspaceEditorStore() {
            @Override public void checkCreate(String relativePath) throws IOException {
                try {
                    WorkspaceFileEdit.checkCreate(workspace, relativePath);
                } catch (FileAlreadyExistsException error) {
                    throw new WorkspaceEditorException(WorkspaceEditorException.Reason.FILE_EXISTS);
                }
            }

            @Override public void create(String relativePath, byte[] contents) throws IOException {
                try {
                    WorkspaceFileEdit.create(workspace, relativePath, contents,
                        WorkspaceEditorLimits.MAXIMUM_FILE_BYTES, creator);
                } catch (FileAlreadyExistsException error) {
                    throw new WorkspaceEditorException(WorkspaceEditorException.Reason.FILE_EXISTS);
                }
            }

            @Override
            public byte[] read(String relativePath) throws IOException {
                return WorkspaceFileEdit.read(
                    workspace, relativePath, WorkspaceEditorLimits.MAXIMUM_FILE_BYTES, opener
                );
            }

            @Override
            public void replace(String relativePath, byte[] expected, byte[] replacement)
                throws IOException {
                try {
                    WorkspaceFileEdit.replace(workspace, relativePath, expected, replacement,
                        WorkspaceEditorLimits.MAXIMUM_FILE_BYTES, opener);
                } catch (WorkspaceFileEdit.FileChangedException error) {
                    throw new WorkspaceEditorException(WorkspaceEditorException.Reason.FILE_CHANGED);
                }
            }
        });
    }

    public WorkspaceEditorDocument open(String relativePath) throws IOException {
        return editor.open(relativePath);
    }

    public WorkspaceEditorDocument newDocument(String directory, String name) throws IOException {
        return editor.newDocument(directory, name);
    }

    public WorkspaceEditorDocument save(WorkspaceEditorDocument document, String text)
        throws IOException {
        return editor.save(document, text);
    }
}
