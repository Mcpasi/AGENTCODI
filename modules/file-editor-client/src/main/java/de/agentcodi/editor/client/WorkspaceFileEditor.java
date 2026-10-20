package de.agentcodi.editor.client;

import de.agentcodi.editor.WorkspaceEditorDocument;
import de.agentcodi.editor.WorkspaceEditorException;
import de.agentcodi.editor.WorkspaceEditorLimits;
import de.agentcodi.editor.WorkspaceEditorPath;
import de.agentcodi.editor.WorkspaceEditorStore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Android-independent full-document UTF-8 loading and saving. No Codex dependency. */
public final class WorkspaceFileEditor {
    private final WorkspaceEditorStore store;

    public WorkspaceFileEditor(WorkspaceEditorStore store) {
        if (store == null) {
            throw new IllegalArgumentException("Editor store is required");
        }
        this.store = store;
    }

    public WorkspaceEditorDocument open(String relativePath) throws IOException {
        byte[] bytes = store.read(relativePath);
        requireSize(bytes.length);
        boolean bom = bytes.length >= 3 && bytes[0] == (byte) 0xef
            && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf;
        int offset = bom ? 3 : 0;
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        } catch (CharacterCodingException error) {
            throw new WorkspaceEditorException(WorkspaceEditorException.Reason.UNSUPPORTED_TEXT);
        }
        requireText(text);
        return new WorkspaceEditorDocument(relativePath, text, bytes, bom);
    }

    public WorkspaceEditorDocument newDocument(String directory, String name) throws IOException {
        String path = WorkspaceEditorPath.newFilePath(directory, name);
        store.checkCreate(path);
        return WorkspaceEditorDocument.newFile(path);
    }

    public WorkspaceEditorDocument save(WorkspaceEditorDocument document, String text)
        throws IOException {
        if (document == null || text == null) {
            throw new IllegalArgumentException("Document and text are required");
        }
        // Reject oversized input before allocating its UTF-8 representation.
        requireSize(text.length());
        requireText(text);
        final ByteBuffer encoded;
        try {
            encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(text));
        } catch (CharacterCodingException error) {
            throw new WorkspaceEditorException(WorkspaceEditorException.Reason.UNSUPPORTED_TEXT);
        }
        int offset = document.hasByteOrderMark() ? 3 : 0;
        requireSize(encoded.remaining() + offset);
        byte[] bytes = new byte[encoded.remaining() + offset];
        if (offset != 0) {
            bytes[0] = (byte) 0xef;
            bytes[1] = (byte) 0xbb;
            bytes[2] = (byte) 0xbf;
        }
        encoded.get(bytes, offset, bytes.length - offset);
        if (document.isNewFile()) {
            store.create(document.getRelativePath(), bytes);
        } else {
            store.replace(document.getRelativePath(), document.getSavedBytes(), bytes);
        }
        return new WorkspaceEditorDocument(
            document.getRelativePath(), text, bytes, document.hasByteOrderMark()
        );
    }

    private static void requireSize(int bytes) throws WorkspaceEditorException {
        if (bytes > WorkspaceEditorLimits.MAXIMUM_FILE_BYTES) {
            throw new WorkspaceEditorException(WorkspaceEditorException.Reason.TOO_LARGE);
        }
    }

    private static void requireText(String text) throws WorkspaceEditorException {
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if ((value < 0x20 && value != '\n' && value != '\r' && value != '\t')
                || value == 0x7f) {
                throw new WorkspaceEditorException(WorkspaceEditorException.Reason.UNSUPPORTED_TEXT);
            }
        }
    }
}
