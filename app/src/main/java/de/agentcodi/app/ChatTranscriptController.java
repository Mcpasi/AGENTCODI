package de.agentcodi.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import de.agentcodi.core.ChatMessage;
import de.agentcodi.core.CodexTranscriptItem;
import de.agentcodi.core.CrashReportFormatter;
import de.agentcodi.runtime.WorkspaceImageExporter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Renders transcript rows and owns asynchronous image inspection/export. */
final class ChatTranscriptController {
    private static final int IMAGE_EXPORT_REQUEST_CODE = 7001;
    private final Activity activity;
    private final UiTheme theme;
    private final Handler handler;
    private final TranscriptScrollView messageScroll;
    private final LinearLayout messagesContainer;
    private final View newOutputButton;
    private final Map<String, TranscriptRow> rowsByKey =
        new LinkedHashMap<String, TranscriptRow>();
    private final List<TranscriptRow> renderedTranscriptRows = new ArrayList<TranscriptRow>();
    private final TranscriptCardPresentation.ExpansionState transcriptExpansion =
        new TranscriptCardPresentation.ExpansionState();
    private final ExecutorService imageOperations = Executors.newSingleThreadExecutor();
    private String renderedThreadId = "";
    private String pendingImageExportPath = "";
    private TextView emptyTranscript;
    private boolean followingEnd = true;
    private boolean touchingTranscript;
    private boolean viewportUpdatePending;
    private boolean adjustingViewport;
    private ReadingPosition pendingReadingPosition;
    private boolean destroyed;
    private final ViewTreeObserver.OnGlobalLayoutListener transcriptLayoutListener =
        new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                applyViewportUpdate();
            }
        };

    ChatTranscriptController(Activity activity, UiTheme theme, Handler handler,
        TranscriptScrollView messageScroll, LinearLayout messagesContainer, View newOutputButton) {
        this.activity = activity;
        this.theme = theme;
        this.handler = handler;
        this.messageScroll = messageScroll;
        this.messagesContainer = messagesContainer;
        this.newOutputButton = newOutputButton;
        messageScroll.getViewTreeObserver().addOnGlobalLayoutListener(transcriptLayoutListener);
        messageScroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View view, int x, int y, int oldX, int oldY) {
                if (destroyed || adjustingViewport) {
                    return;
                }
                if (viewportUpdatePending) {
                    // Layout can clamp the old offset after a removal. Other scrolls
                    // (including flings and accessibility actions) are the user's choice.
                    if (ChatTranscriptController.this.messageScroll.isLayingOut()) {
                        return;
                    }
                    viewportUpdatePending = false;
                    pendingReadingPosition = null;
                }
                followingEnd = isAtTranscriptEnd();
                if (followingEnd) {
                    ChatTranscriptController.this.newOutputButton.setVisibility(View.GONE);
                }
            }
        });
        messageScroll.setGestureListener(new TranscriptScrollView.GestureListener() {
            @Override
            public void onGesture(MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    // A gesture takes precedence over an update awaiting layout.
                    viewportUpdatePending = false;
                    pendingReadingPosition = null;
                    touchingTranscript = true;
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    touchingTranscript = false;
                }
                followingEnd = isAtTranscriptEnd();
                if (!touchingTranscript && viewportUpdatePending
                    && !ChatTranscriptController.this.messageScroll.isLayoutRequested()
                    && !ChatTranscriptController.this.messagesContainer.isLayoutRequested()) {
                    applyViewportUpdate();
                }
            }
        });
        newOutputButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                followingEnd = true;
                pendingReadingPosition = null;
                viewportUpdatePending = true;
                ChatTranscriptController.this.newOutputButton.setVisibility(View.GONE);
                // Also works when no further layout is needed.
                if (!ChatTranscriptController.this.messagesContainer.isLayoutRequested()) {
                    applyViewportUpdate();
                }
            }
        });
    }

    void close() {
        destroyed = true;
        viewportUpdatePending = false;
        pendingReadingPosition = null;
        messageScroll.getViewTreeObserver().removeOnGlobalLayoutListener(transcriptLayoutListener);
        messageScroll.setOnScrollChangeListener(null);
        messageScroll.setGestureListener(null);
        newOutputButton.setOnClickListener(null);
        newOutputButton.setVisibility(View.GONE);
        imageOperations.shutdownNow();
    }

    boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != IMAGE_EXPORT_REQUEST_CODE) {
            return false;
        }
        final String sourcePath = pendingImageExportPath;
        pendingImageExportPath = "";
        final Uri destination = data == null ? null : data.getData();
        if (resultCode != Activity.RESULT_OK || destination == null || sourcePath.isEmpty()) {
            return true;
        }
        final android.content.Context applicationContext = activity.getApplicationContext();
        if (!submitImageOperation(new Runnable() {
            @Override
            public void run() {
                try {
                    final WorkspaceImageExporter.ImageExport exported =
                        WorkspaceImageExporter.export(
                            applicationContext,
                            sourcePath,
                            destination
                    );
                    showExportToast(
                        activity.getString(R.string.image_exported, exported.getDisplayName()),
                        Toast.LENGTH_LONG
                    );
                } catch (Throwable error) {
                    showExportFailure(sourcePath, error);
                }
            }
        })) {
            showExportToast(
                activity.getString(R.string.image_export_start_failed),
                Toast.LENGTH_LONG
            );
        }
        return true;
    }

    void renderTranscript(String threadId, List<CodexTranscriptItem> items) {
        if (destroyed) {
            return;
        }
        transcriptExpansion.update(threadId, items);
        boolean threadChanged = !threadId.equals(renderedThreadId);
        ReadingPosition readingPosition = viewportUpdatePending
            ? pendingReadingPosition : captureReadingPosition();
        boolean changed = threadChanged;
        boolean newOutput = false;
        if (threadChanged) {
            for (TranscriptRow row : renderedTranscriptRows) {
                messagesContainer.removeView(row.root);
            }
            renderedThreadId = threadId;
            rowsByKey.clear();
            renderedTranscriptRows.clear();
            followingEnd = true;
            readingPosition = null;
            pendingReadingPosition = null;
            newOutputButton.setVisibility(View.GONE);
        }

        Set<String> keys = new HashSet<String>();
        for (CodexTranscriptItem item : items) {
            keys.add(transcriptKey(item));
        }
        Iterator<Map.Entry<String, TranscriptRow>> previous = rowsByKey.entrySet().iterator();
        while (previous.hasNext()) {
            Map.Entry<String, TranscriptRow> entry = previous.next();
            if (!keys.contains(entry.getKey())) {
                messagesContainer.removeView(entry.getValue().root);
                previous.remove();
                changed = true;
            }
        }

        if (items.isEmpty()) {
            renderedTranscriptRows.clear();
            if (emptyTranscript == null) {
                emptyTranscript = theme.text("", 14, theme.secondary);
                emptyTranscript.setGravity(Gravity.CENTER);
                emptyTranscript.setPadding(theme.dp(12), theme.dp(40), theme.dp(12), theme.dp(40));
            }
            String label = activity.getString(threadId.isEmpty()
                ? R.string.chat_select : R.string.chat_no_messages);
            if (!label.contentEquals(emptyTranscript.getText())) {
                emptyTranscript.setText(label);
                changed = true;
            }
            if (emptyTranscript.getParent() == null) {
                messagesContainer.addView(emptyTranscript);
                changed = true;
            }
            followingEnd = true;
            newOutputButton.setVisibility(View.GONE);
            if (changed) {
                scheduleViewportUpdate(null);
            }
            return;
        }
        if (emptyTranscript != null && emptyTranscript.getParent() != null) {
            messagesContainer.removeView(emptyTranscript);
            changed = true;
        }
        renderedTranscriptRows.clear();
        for (int index = 0; index < items.size(); index++) {
            CodexTranscriptItem item = items.get(index);
            String key = transcriptKey(item);
            TranscriptRow row = rowsByKey.get(key);
            if (row == null) {
                row = createTranscriptRow(item);
                rowsByKey.put(key, row);
                changed = true;
                newOutput = true;
            } else if (bindTranscriptRow(row, item)) {
                changed = true;
                newOutput = true;
            }
            // Appends leave all existing children attached. Moves only detach the moved row.
            if (messagesContainer.getChildAt(index) != row.root) {
                messagesContainer.removeView(row.root);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                );
                params.topMargin = index == 0 ? 0 : theme.dp(10);
                messagesContainer.addView(row.root, index, params);
                changed = true;
            } else {
                LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) row.root.getLayoutParams();
                int margin = index == 0 ? 0 : theme.dp(10);
                if (params.topMargin != margin) {
                    params.topMargin = margin;
                    row.root.setLayoutParams(params);
                    changed = true;
                }
            }
            renderedTranscriptRows.add(row);
        }
        if (changed) {
            scheduleViewportUpdate(readingPosition);
            if (newOutput && !threadChanged && (!followingEnd || touchingTranscript)) {
                newOutputButton.setVisibility(View.VISIBLE);
            }
        }
    }

    private boolean bindTranscriptRow(TranscriptRow row, CodexTranscriptItem item) {
        boolean changed = false;
        if (row.card != null) {
            changed = row.card.bind(item);
        } else {
            String value = messageBody(item.getMessage());
            String label = messageRole(item.getMessage());
            if (!value.contentEquals(row.text.getText()) || !label.contentEquals(row.role.getText())) {
                row.text.setText(value);
                row.role.setText(label);
                styleTranscriptRow(row, item);
                changed = true;
            }
        }
        bindImageAction(row, item);
        return changed;
    }

    private TranscriptRow createTranscriptRow(CodexTranscriptItem item) {
        LinearLayout root;
        LinearLayout content;
        LinearLayout bubble = null;
        TextView role = null;
        TextView text = null;
        TranscriptCardView card = null;
        if (item.isMessage()) {
            root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            bubble = new LinearLayout(activity);
            bubble.setOrientation(LinearLayout.VERTICAL);
            bubble.setPadding(theme.dp(14), theme.dp(12), theme.dp(14), theme.dp(12));
            role = theme.text(messageRole(item.getMessage()), 11, theme.secondary);
            role.setTypeface(Typeface.DEFAULT_BOLD);
            role.setLetterSpacing(0.08f);
            bubble.addView(role);
            text = theme.text(messageBody(item.getMessage()), 14, theme.primary);
            text.setTextIsSelectable(true);
            text.setLineSpacing(0.0f, 1.2f);
            theme.addWithTopMargin(bubble, text, 6);
            LinearLayout.LayoutParams bubbleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
            if (item.getMessage().getRole() == ChatMessage.Role.USER) {
                bubbleParams.setMarginStart(theme.dp(28));
            }
            root.addView(bubble, bubbleParams);
            content = bubble;
        } else {
            card = new TranscriptCardView(activity, theme, transcriptExpansion);
            card.bind(item);
            root = card;
            content = card.contentContainer();
        }

        TextView imageStatus = theme.text("", 12, theme.secondary);
        imageStatus.setLineSpacing(0.0f, 1.15f);
        imageStatus.setVisibility(View.GONE);
        theme.addWithTopMargin(content, imageStatus, 6);

        ImageButton imageAction = theme.iconButton(
            R.drawable.ic_chat_download,
            activity.getString(R.string.image_export)
        );
        imageAction.setVisibility(View.GONE);
        LinearLayout.LayoutParams imageActionParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        imageActionParams.setMarginStart(theme.dp(0));
        imageActionParams.topMargin = theme.dp(6);
        content.addView(imageAction, imageActionParams);

        TranscriptRow row = new TranscriptRow(
            root, bubble, role, text, card, imageStatus, imageAction
        );
        if (card == null) {
            styleTranscriptRow(row, item);
        }
        bindImageAction(row, item);
        return row;
    }

    private void styleTranscriptRow(TranscriptRow row, CodexTranscriptItem item) {
        ChatMessage.Role speaker = item.getMessage().getRole();
        int accent;
        int fill;
        if (speaker == ChatMessage.Role.USER) {
            accent = theme.accent;
            fill = theme.tintedSurface(theme.accent, theme.dark ? 0.16f : 0.08f);
        } else if (speaker == ChatMessage.Role.SYSTEM) {
            accent = theme.warning;
            fill = theme.tintedSurface(theme.warning, theme.dark ? 0.14f : 0.08f);
        } else {
            accent = theme.secondary;
            fill = theme.surface;
        }
        row.bubble.setBackground(theme.background(
            fill,
            speaker == ChatMessage.Role.ASSISTANT
                ? theme.border
                : theme.tintedSurface(accent, 0.3f),
            18
        ));
        row.role.setTextColor(accent);
        row.text.setTextColor(theme.primary);
    }

    private void bindImageAction(TranscriptRow row, CodexTranscriptItem item) {
        String imagePath = item.getReportedImagePath();
        if (item.isStreaming() || imagePath.isEmpty()) {
            row.imagePath = "";
            row.imageState = ImageValidationState.NONE;
            row.imageInfo = null;
            row.imageFailure = "";
            row.imageStatus.setVisibility(View.GONE);
            row.imageAction.setVisibility(View.GONE);
            row.imageAction.setOnClickListener(null);
            return;
        }
        if (!imagePath.equals(row.imagePath)) {
            row.imagePath = imagePath;
            beginImageInspection(row, imagePath);
            return;
        }
        applyImageAction(row);
    }

    private void beginImageInspection(final TranscriptRow row, final String imagePath) {
        row.imageState = ImageValidationState.CHECKING;
        row.imageInfo = null;
        row.imageFailure = "";
        row.checkingMessage = activity.getString(R.string.image_path_checking);
        applyImageAction(row);
        final android.content.Context applicationContext = activity.getApplicationContext();
        if (!submitImageOperation(new Runnable() {
            @Override
            public void run() {
                try {
                    final WorkspaceImageExporter.ImageExport image =
                        WorkspaceImageExporter.inspect(applicationContext, imagePath);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            completeImageInspection(row, imagePath, image, "");
                        }
                    });
                } catch (Throwable error) {
                    final String failure = exportFailureMessage(error);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            completeImageInspection(row, imagePath, null, failure);
                        }
                    });
                }
            }
        })) {
            completeImageInspection(
                row,
                imagePath,
                null,
                activity.getString(R.string.image_inspection_start_failed)
            );
        }
    }

    private void completeImageInspection(
        TranscriptRow row,
        String imagePath,
        WorkspaceImageExporter.ImageExport image,
        String failure
    ) {
        if (!isCurrentImageRow(row, imagePath)) {
            return;
        }
        ReadingPosition position = viewportUpdatePending
            ? pendingReadingPosition : captureReadingPosition();
        row.imageInfo = image;
        row.imageFailure = failure == null ? "" : failure;
        row.imageState = image == null
            ? ImageValidationState.INVALID
            : ImageValidationState.VALID;
        applyImageAction(row);
        scheduleViewportUpdate(position);
    }

    private void applyImageAction(final TranscriptRow row) {
        if (row.imageState == ImageValidationState.NONE) {
            row.imageStatus.setVisibility(View.GONE);
            row.imageAction.setVisibility(View.GONE);
            row.imageAction.setOnClickListener(null);
            return;
        }
        row.imageStatus.setVisibility(View.VISIBLE);
        row.imageAction.setVisibility(View.VISIBLE);
        if (row.imageState == ImageValidationState.CHECKING) {
            row.imageStatus.setText(row.checkingMessage);
            row.imageStatus.setTextColor(theme.secondary);
            theme.setIcon(
                row.imageAction,
                R.drawable.ic_chat_hourglass,
                activity.getString(R.string.image_inspection_running)
            );
            row.imageAction.setOnClickListener(null);
            theme.setEnabled(row.imageAction, false);
            return;
        }
        if (row.imageState == ImageValidationState.INVALID) {
            row.imageStatus.setText(row.imageFailure);
            row.imageStatus.setTextColor(theme.danger);
            theme.setIcon(
                row.imageAction,
                R.drawable.ic_chat_refresh,
                activity.getString(R.string.image_path_recheck)
            );
            row.imageAction.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View ignored) {
                    beginImageInspection(row, row.imagePath);
                }
            });
            theme.setEnabled(row.imageAction, true);
            return;
        }
        WorkspaceImageExporter.ImageExport image = row.imageInfo;
        row.imageStatus.setText(
            activity.getString(
                R.string.image_workspace_confirmed,
                image.getDisplayName(),
                ChatUiFormatting.readableByteCount(activity, image.getByteCount())
            )
        );
        row.imageStatus.setTextColor(theme.secondary);
        theme.setIcon(
            row.imageAction,
            R.drawable.ic_chat_download,
            activity.getString(R.string.image_export)
        );
        row.imageAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View ignored) {
                verifyAndOpenImageExport(row, row.imagePath);
            }
        });
        theme.setEnabled(row.imageAction, true);
    }

    private void verifyAndOpenImageExport(
        final TranscriptRow row,
        final String imagePath
    ) {
        row.imageState = ImageValidationState.CHECKING;
        row.checkingMessage = activity.getString(R.string.image_pre_export_check);
        applyImageAction(row);
        final android.content.Context applicationContext = activity.getApplicationContext();
        if (!submitImageOperation(new Runnable() {
            @Override
            public void run() {
                try {
                    final WorkspaceImageExporter.ImageExport image =
                        WorkspaceImageExporter.inspect(applicationContext, imagePath);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!isCurrentImageRow(row, imagePath)) {
                                return;
                            }
                            row.imageState = ImageValidationState.VALID;
                            row.imageInfo = image;
                            row.imageFailure = "";
                            applyImageAction(row);
                            openImageExportDocument(row, imagePath, image);
                        }
                    });
                } catch (Throwable error) {
                    final String failure = exportFailureMessage(error);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            completeImageInspection(row, imagePath, null, failure);
                        }
                    });
                }
            }
        })) {
            completeImageInspection(
                row,
                imagePath,
                null,
                activity.getString(R.string.image_recheck_start_failed)
            );
        }
    }

    private void openImageExportDocument(
        TranscriptRow row,
        String sourcePath,
        WorkspaceImageExporter.ImageExport image
    ) {
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(image.getMimeType());
            intent.putExtra(Intent.EXTRA_TITLE, image.getDisplayName());
            pendingImageExportPath = sourcePath;
            activity.startActivityForResult(intent, IMAGE_EXPORT_REQUEST_CODE);
        } catch (Throwable error) {
            pendingImageExportPath = "";
            row.imageState = ImageValidationState.VALID;
            applyImageAction(row);
            Toast.makeText(
                activity,
                R.string.document_picker_open_failed,
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private boolean isCurrentImageRow(TranscriptRow row, String imagePath) {
        return !destroyed
            && imagePath.equals(row.imagePath)
            && row.root.getParent() != null;
    }

    private boolean submitImageOperation(Runnable operation) {
        if (destroyed || imageOperations.isShutdown()) {
            return false;
        }
        try {
            imageOperations.execute(operation);
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    private void showExportFailure(final String imagePath, Throwable error) {
        final String message = exportFailureMessage(error);
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (destroyed) {
                    return;
                }
                for (TranscriptRow row : renderedTranscriptRows) {
                    if (imagePath.equals(row.imagePath)) {
                        row.imageInfo = null;
                        row.imageFailure = message;
                        row.imageState = ImageValidationState.INVALID;
                        applyImageAction(row);
                    }
                }
                if (!activity.isFinishing()) {
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void showExportToast(final String message, final int duration) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!destroyed && !activity.isFinishing()) {
                    Toast.makeText(activity, message, duration).show();
                }
            }
        });
    }

    private String exportFailureMessage(Throwable error) {
        String reason = error == null || error.getMessage() == null
            ? activity.getString(R.string.common_unknown_error)
            : UiText.errorReason(
                activity,
                CrashReportFormatter.redactVisibleText(error.getMessage(), 180)
            );
        return activity.getString(R.string.image_export_path_invalid, reason);
    }

    private int transcriptScrollEnd() {
        return Math.max(0, messagesContainer.getBottom() + messageScroll.getPaddingBottom()
            - messageScroll.getHeight());
    }

    private boolean isAtTranscriptEnd() {
        return transcriptScrollEnd() - messageScroll.getScrollY() <= theme.dp(24);
    }

    private ReadingPosition captureReadingPosition() {
        if (followingEnd && !touchingTranscript) {
            return null;
        }
        int scrollY = messageScroll.getScrollY();
        List<RowAnchor> anchors = new ArrayList<RowAnchor>();
        int firstVisible = renderedTranscriptRows.size();
        for (int index = 0; index < renderedTranscriptRows.size(); index++) {
            if (renderedTranscriptRows.get(index).root.getBottom()
                > scrollY + messageScroll.getPaddingTop()) {
                firstVisible = index;
                break;
            }
        }
        // If the first visible row disappears, keep the nearest surviving row in place.
        for (int index = firstVisible; index < renderedTranscriptRows.size(); index++) {
            View row = renderedTranscriptRows.get(index).root;
            anchors.add(new RowAnchor(row, row.getTop() - scrollY));
        }
        for (int index = firstVisible - 1; index >= 0; index--) {
            View row = renderedTranscriptRows.get(index).root;
            anchors.add(new RowAnchor(row, row.getTop() - scrollY));
        }
        return new ReadingPosition(anchors, scrollY);
    }

    private void scheduleViewportUpdate(ReadingPosition position) {
        if (!viewportUpdatePending) {
            pendingReadingPosition = position;
        }
        viewportUpdatePending = true;
        if (followingEnd && !touchingTranscript) {
            pendingReadingPosition = null;
        }
    }

    private void applyViewportUpdate() {
        if (destroyed || touchingTranscript || messageScroll.getHeight() == 0
            || !messageScroll.isShown() || (!viewportUpdatePending && !followingEnd)) {
            return;
        }
        int target = transcriptScrollEnd();
        if (!followingEnd) {
            target = pendingReadingPosition == null
                ? messageScroll.getScrollY() : pendingReadingPosition.scrollY;
            if (pendingReadingPosition != null) {
                for (RowAnchor anchor : pendingReadingPosition.anchors) {
                    if (anchor.row.getParent() == messagesContainer) {
                        target = anchor.row.getTop() - anchor.offset;
                        break;
                    }
                }
            }
        }
        adjustingViewport = true;
        try {
            // scrollTo does not transfer focus out of the composer like fullScroll can.
            messageScroll.scrollTo(0, Math.max(0, Math.min(target, transcriptScrollEnd())));
            viewportUpdatePending = false;
            pendingReadingPosition = null;
            followingEnd = isAtTranscriptEnd();
            if (followingEnd) {
                newOutputButton.setVisibility(View.GONE);
            }
        } finally {
            adjustingViewport = false;
        }
    }

    private static final class RowAnchor {
        private final View row;
        private final int offset;

        private RowAnchor(View row, int offset) {
            this.row = row;
            this.offset = offset;
        }
    }

    private static final class ReadingPosition {
        private final List<RowAnchor> anchors;
        private final int scrollY;

        private ReadingPosition(List<RowAnchor> anchors, int scrollY) {
            this.anchors = anchors;
            this.scrollY = scrollY;
        }
    }

    private String messageRole(ChatMessage message) {
        String role = message.getRole() == ChatMessage.Role.USER
            ? activity.getString(R.string.transcript_role_you)
            : message.getRole() == ChatMessage.Role.ASSISTANT
                ? activity.getString(R.string.transcript_role_codex)
                : activity.getString(R.string.transcript_role_system);
        return role + (message.isStreaming()
            ? " · " + activity.getString(R.string.transcript_stream)
            : "");
    }

    private String messageBody(ChatMessage message) {
        return message.getRole() == ChatMessage.Role.SYSTEM
            ? UiText.coreStatus(activity, message.getText())
            : UiText.streamText(activity, message.getText());
    }

    private static String transcriptKey(CodexTranscriptItem item) {
        return item.getKind().name() + ":" + item.getId();
    }

    private enum ImageValidationState {
        NONE,
        CHECKING,
        VALID,
        INVALID
    }

    private static final class TranscriptRow {
        private final LinearLayout root;
        private final LinearLayout bubble;
        private final TextView role;
        private final TextView text;
        private final TranscriptCardView card;
        private final TextView imageStatus;
        private final ImageButton imageAction;
        private String imagePath = "";
        private ImageValidationState imageState = ImageValidationState.NONE;
        private WorkspaceImageExporter.ImageExport imageInfo;
        private String imageFailure = "";
        private String checkingMessage = "";

        private TranscriptRow(
            LinearLayout root,
            LinearLayout bubble,
            TextView role,
            TextView text,
            TranscriptCardView card,
            TextView imageStatus,
            ImageButton imageAction
        ) {
            this.root = root;
            this.bubble = bubble;
            this.role = role;
            this.text = text;
            this.card = card;
            this.imageStatus = imageStatus;
            this.imageAction = imageAction;
        }
    }


}
