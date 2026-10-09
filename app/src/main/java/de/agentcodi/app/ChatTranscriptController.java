package de.agentcodi.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import de.agentcodi.core.ChatMessage;
import de.agentcodi.core.CodexTranscriptItem;
import de.agentcodi.core.CrashReportFormatter;
import de.agentcodi.runtime.WorkspaceImageExporter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Renders transcript rows and owns asynchronous image inspection/export. */
final class ChatTranscriptController {
    private static final int IMAGE_EXPORT_REQUEST_CODE = 7001;
    private final Activity activity;
    private final UiTheme theme;
    private final Handler handler;
    private final ScrollView messageScroll;
    private final LinearLayout messagesContainer;
    private final List<String> renderedTranscriptKeys = new ArrayList<String>();
    private final List<TranscriptRow> renderedTranscriptRows = new ArrayList<TranscriptRow>();
    private final TranscriptCardPresentation.ExpansionState transcriptExpansion =
        new TranscriptCardPresentation.ExpansionState();
    private final ExecutorService imageOperations = Executors.newSingleThreadExecutor();
    private String renderedThreadId = "";
    private String pendingImageExportPath = "";
    private boolean destroyed;

    ChatTranscriptController(Activity activity, UiTheme theme, Handler handler,
        ScrollView messageScroll, LinearLayout messagesContainer) {
        this.activity = activity;
        this.theme = theme;
        this.handler = handler;
        this.messageScroll = messageScroll;
        this.messagesContainer = messagesContainer;
    }

    void close() {
        destroyed = true;
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
        transcriptExpansion.update(threadId, items);
        boolean rebuild = !threadId.equals(renderedThreadId)
            || items.size() != renderedTranscriptKeys.size();
        if (!rebuild) {
            for (int index = 0; index < items.size(); index++) {
                if (!transcriptKey(items.get(index)).equals(renderedTranscriptKeys.get(index))) {
                    rebuild = true;
                    break;
                }
            }
        }
        if (rebuild) {
            renderedThreadId = threadId;
            renderedTranscriptKeys.clear();
            renderedTranscriptRows.clear();
            messagesContainer.removeAllViews();
            if (items.isEmpty()) {
                TextView empty = theme.text(
                    threadId.isEmpty()
                        ? activity.getString(R.string.chat_select)
                        : activity.getString(R.string.chat_no_messages),
                    14,
                    theme.secondary
                );
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(theme.dp(12), theme.dp(40), theme.dp(12), theme.dp(40));
                messagesContainer.addView(empty);
                return;
            }
            for (int index = 0; index < items.size(); index++) {
                CodexTranscriptItem item = items.get(index);
                TranscriptRow row = createTranscriptRow(item);
                renderedTranscriptKeys.add(transcriptKey(item));
                renderedTranscriptRows.add(row);
                theme.addWithTopMargin(messagesContainer, row.root, index == 0 ? 0 : 10);
            }
            scrollMessagesToBottom();
            return;
        }
        boolean changed = false;
        for (int index = 0; index < items.size(); index++) {
            CodexTranscriptItem item = items.get(index);
            TranscriptRow row = renderedTranscriptRows.get(index);
            if (row.card != null) {
                changed |= row.card.bind(item);
            } else {
                String value = messageBody(item.getMessage());
                String label = messageRole(item.getMessage());
                if (!value.contentEquals(row.text.getText())
                    || !label.contentEquals(row.role.getText())) {
                    row.text.setText(value);
                    row.role.setText(label);
                    styleTranscriptRow(row, item);
                    changed = true;
                }
            }
            bindImageAction(row, item);
        }
        if (changed) {
            scrollMessagesToBottom();
        }
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
        row.imageInfo = image;
        row.imageFailure = failure == null ? "" : failure;
        row.imageState = image == null
            ? ImageValidationState.INVALID
            : ImageValidationState.VALID;
        applyImageAction(row);
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

    private void scrollMessagesToBottom() {
        messageScroll.post(new Runnable() {
            @Override
            public void run() {
                messageScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
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
