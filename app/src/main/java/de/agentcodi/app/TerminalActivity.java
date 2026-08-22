package de.agentcodi.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import de.agentcodi.core.BuildIdentity;
import de.agentcodi.core.CredentialGuard;
import de.agentcodi.core.RuntimePhase;
import de.agentcodi.core.RuntimeSnapshot;
import de.agentcodi.core.TerminalSessionSnapshot;
import de.agentcodi.runtime.AgentRuntimeService;

import java.io.IOException;
import java.util.Arrays;

public final class TerminalActivity extends Activity {
    private static final long ACTIVE_REFRESH_MILLISECONDS = 180L;
    private static final long IDLE_REFRESH_MILLISECONDS = 700L;
    private static final long TOOL_STATUS_REFRESH_MILLISECONDS = 650L;
    private static final int MAXIMUM_COMMAND_CHARACTERS = 4095;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            render();
            if (refreshActive && !destroyed) {
                TerminalSessionSnapshot terminal = AgentRuntimeService.terminalSnapshot();
                handler.postDelayed(
                    this,
                    terminal.isRunning() || terminal.isStarting()
                        ? ACTIVE_REFRESH_MILLISECONDS
                        : IDLE_REFRESH_MILLISECONDS
                );
            }
        }
    };

    private UiTheme theme;
    private TextView statusView;
    private TextView outputView;
    private ScrollView outputScroll;
    private EditText inputView;
    private Button startButton;
    private Button stopButton;
    private Button sendButton;
    private Button nodeButton;
    private Button npmButton;
    private Button pythonButton;
    private Button ripgrepButton;
    private Button controlCButton;
    private Button tabButton;
    private Button escapeButton;
    private long renderedRevision = Long.MIN_VALUE;
    private long toolStatusCheckedAt = Long.MIN_VALUE;
    private boolean renderedNodeEnabled;
    private boolean renderedNpmEnabled;
    private boolean renderedPythonEnabled;
    private boolean renderedRipgrepEnabled;
    private boolean refreshActive;
    private boolean uiReady;
    private boolean destroyed;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguage.attach(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            theme = new UiTheme(this);
            setContentView(buildContent());
            uiReady = true;
        } catch (Throwable error) {
            uiReady = false;
            TextView emergency = new TextView(this);
            emergency.setPadding(32, 48, 32, 48);
            emergency.setText(R.string.terminal_initialization_failed);
            setContentView(emergency);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        destroyed = false;
        refreshActive = true;
        handler.removeCallbacks(refreshTask);
        handler.post(refreshTask);
    }

    @Override
    protected void onStop() {
        refreshActive = false;
        handler.removeCallbacks(refreshTask);
        wipeInput();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        refreshActive = false;
        handler.removeCallbacksAndMessages(null);
        wipeInput();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            resizeTerminal();
        }
    }

    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setPadding(theme.dp(14), theme.dp(18), theme.dp(14), theme.dp(12));
        root.setBackgroundColor(theme.page);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        Button back = theme.compactButton(getString(R.string.terminal_back));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });
        topBar.addView(back);
        TextView title = theme.text(getString(R.string.terminal_title), 24, theme.primary);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1.0f
        );
        titleParams.leftMargin = theme.dp(12);
        topBar.addView(title, titleParams);
        root.addView(topBar);

        TextView explanation = theme.text(
            getString(R.string.terminal_description),
            13,
            theme.secondary
        );
        explanation.setLineSpacing(0.0f, 1.16f);
        theme.addWithTopMargin(root, explanation, 10);

        statusView = theme.text(getString(R.string.terminal_status_checking), 13, theme.primary);
        statusView.setPadding(theme.dp(12), theme.dp(9), theme.dp(12), theme.dp(9));
        statusView.setBackground(theme.background(theme.surfaceRaised, theme.border, 12));
        theme.addWithTopMargin(root, statusView, 10);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        startButton = theme.compactButton(getString(R.string.terminal_start));
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (!AgentRuntimeService.startTerminal(terminalRows(), terminalColumns())) {
                    Toast.makeText(
                        TerminalActivity.this,
                        R.string.terminal_start_failed,
                        Toast.LENGTH_LONG
                    ).show();
                }
            }
        });
        actions.addView(startButton, weightedButtonParams(1.0f, 0));
        stopButton = theme.compactButton(getString(R.string.terminal_stop));
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                AgentRuntimeService.stopTerminal();
            }
        });
        actions.addView(stopButton, weightedButtonParams(1.0f, 6));
        nodeButton = theme.compactButton(getString(
            R.string.terminal_enable_node,
            BuildIdentity.NODE_RUNTIME_VERSION
        ));
        nodeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendLiteral("agentcodi-toolchain install node\n");
            }
        });
        theme.addWithTopMargin(root, actions, 8);

        LinearLayout toolActions = new LinearLayout(this);
        toolActions.setOrientation(LinearLayout.HORIZONTAL);
        toolActions.addView(nodeButton, weightedButtonParams(1.0f, 0));
        npmButton = theme.compactButton(getString(
            R.string.terminal_enable_npm,
            BuildIdentity.NPM_RUNTIME_VERSION
        ));
        npmButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendLiteral("agentcodi-toolchain install npm\n");
            }
        });
        toolActions.addView(npmButton, weightedButtonParams(1.0f, 6));

        LinearLayout secondaryToolActions = new LinearLayout(this);
        secondaryToolActions.setOrientation(LinearLayout.HORIZONTAL);
        pythonButton = theme.compactButton(getString(
            R.string.terminal_enable_python,
            BuildIdentity.PYTHON_RUNTIME_VERSION
        ));
        pythonButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendLiteral("agentcodi-toolchain install python\n");
            }
        });
        secondaryToolActions.addView(pythonButton, weightedButtonParams(1.0f, 0));
        ripgrepButton = theme.compactButton(getString(
            R.string.terminal_enable_ripgrep,
            BuildIdentity.RIPGREP_RUNTIME_VERSION
        ));
        ripgrepButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendLiteral("agentcodi-toolchain install ripgrep\n");
            }
        });
        secondaryToolActions.addView(ripgrepButton, weightedButtonParams(1.0f, 6));
        theme.addWithTopMargin(root, toolActions, 6);
        theme.addWithTopMargin(root, secondaryToolActions, 6);

        outputScroll = new ScrollView(this);
        outputScroll.setFillViewport(true);
        outputScroll.setBackground(theme.background(0xFF050A12, theme.border, 12));
        outputView = theme.text(getString(R.string.terminal_output_empty), 13, 0xFFE5EEF8);
        outputView.setTypeface(Typeface.MONOSPACE);
        outputView.setTextIsSelectable(false);
        outputView.setHorizontallyScrolling(true);
        outputView.setLineSpacing(0.0f, 1.05f);
        outputView.setPadding(theme.dp(12), theme.dp(12), theme.dp(12), theme.dp(12));
        outputScroll.addView(outputView, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        LinearLayout.LayoutParams outputParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1.0f
        );
        outputParams.topMargin = theme.dp(8);
        root.addView(outputScroll, outputParams);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controlCButton = controlButton(
            getString(R.string.terminal_control_c),
            new char[] {0x03}
        );
        controls.addView(controlCButton, weightedButtonParams(1.0f, 0));
        tabButton = controlButton(getString(R.string.terminal_tab), new char[] {'\t'});
        controls.addView(tabButton, weightedButtonParams(1.0f, 6));
        escapeButton = controlButton(getString(R.string.terminal_escape), new char[] {0x1b});
        controls.addView(escapeButton, weightedButtonParams(1.0f, 6));
        Button clearButton = theme.compactButton(getString(R.string.terminal_clear));
        clearButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                AgentRuntimeService.clearTerminalOutput();
            }
        });
        controls.addView(clearButton, weightedButtonParams(1.0f, 6));
        theme.addWithTopMargin(root, controls, 8);

        LinearLayout composer = new LinearLayout(this);
        composer.setOrientation(LinearLayout.HORIZONTAL);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        inputView = new EditText(this);
        inputView.setHint(R.string.terminal_input_hint);
        inputView.setHintTextColor(theme.secondary);
        inputView.setTextColor(theme.primary);
        inputView.setTypeface(Typeface.MONOSPACE);
        inputView.setSingleLine(true);
        inputView.setSaveEnabled(false);
        inputView.setFreezesText(false);
        inputView.setInputType(
            InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        );
        inputView.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        if (Build.VERSION.SDK_INT >= 26) {
            inputView.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
            inputView.setAutofillHints(new String[0]);
        }
        inputView.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView view, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEND
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    sendCommand();
                    return true;
                }
                return false;
            }
        });
        composer.addView(inputView, new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1.0f
        ));
        sendButton = theme.compactButton(getString(R.string.terminal_send));
        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendCommand();
            }
        });
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        sendParams.leftMargin = theme.dp(8);
        composer.addView(sendButton, sendParams);
        theme.addWithTopMargin(root, composer, 8);
        return root;
    }

    private Button controlButton(String label, final char[] sequence) {
        Button button = theme.compactButton(label);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                char[] copy = Arrays.copyOf(sequence, sequence.length);
                sendCharacters(copy);
            }
        });
        return button;
    }

    private LinearLayout.LayoutParams weightedButtonParams(float weight, int leftMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            weight
        );
        params.leftMargin = theme.dp(leftMargin);
        return params;
    }

    private void sendCommand() {
        Editable editable = inputView.getText();
        if (editable == null || editable.length() == 0) {
            return;
        }
        if (editable.length() > MAXIMUM_COMMAND_CHARACTERS) {
            Toast.makeText(this, R.string.terminal_input_too_long, Toast.LENGTH_LONG).show();
            wipeInput();
            return;
        }
        if (CredentialGuard.containsLikelyCredential(editable)) {
            Toast.makeText(this, R.string.terminal_credential_warning, Toast.LENGTH_LONG).show();
            wipeInput();
            return;
        }
        char[] command = new char[editable.length() + 1];
        for (int index = 0; index < editable.length(); index++) {
            command[index] = editable.charAt(index);
        }
        command[command.length - 1] = '\n';
        wipeInput();
        sendCharacters(command);
    }

    private void sendLiteral(String value) {
        char[] characters = value.toCharArray();
        sendCharacters(characters);
    }

    private void sendCharacters(char[] characters) {
        try {
            AgentRuntimeService.sendTerminalInput(characters);
        } catch (IOException error) {
            Toast.makeText(this, R.string.terminal_send_failed, Toast.LENGTH_LONG).show();
        } finally {
            Arrays.fill(characters, '\0');
        }
    }

    private void render() {
        if (!uiReady || statusView == null) {
            return;
        }
        RuntimeSnapshot runtime = AgentRuntimeService.snapshot();
        TerminalSessionSnapshot terminal = AgentRuntimeService.terminalSnapshot();
        boolean runtimeReady = runtime.getPhase() == RuntimePhase.READY;
        long now = SystemClock.elapsedRealtime();
        if (toolStatusCheckedAt == Long.MIN_VALUE
            || now - toolStatusCheckedAt >= TOOL_STATUS_REFRESH_MILLISECONDS) {
            renderedNodeEnabled = AgentRuntimeService.isNodeRuntimeEnabled();
            renderedNpmEnabled = AgentRuntimeService.isNpmRuntimeEnabled();
            renderedPythonEnabled = AgentRuntimeService.isPythonRuntimeEnabled();
            renderedRipgrepEnabled = AgentRuntimeService.isRipgrepRuntimeEnabled();
            toolStatusCheckedAt = now;
        }
        boolean nodeEnabled = renderedNodeEnabled;
        boolean npmEnabled = renderedNpmEnabled;
        boolean pythonEnabled = renderedPythonEnabled;
        boolean ripgrepEnabled = renderedRipgrepEnabled;
        if (!runtimeReady) {
            statusView.setText(R.string.terminal_status_runtime_required);
            statusView.setTextColor(theme.danger);
        } else if (terminal.isStarting()) {
            statusView.setText(R.string.terminal_status_starting);
            statusView.setTextColor(theme.secondary);
        } else if (!terminal.getFailure().isEmpty()) {
            statusView.setText(getString(
                R.string.terminal_status_failed,
                terminal.getFailure()
            ));
            statusView.setTextColor(theme.danger);
        } else if (terminal.isRunning()) {
            String enabledTools = enabledTools(
                nodeEnabled,
                npmEnabled,
                pythonEnabled,
                ripgrepEnabled
            );
            statusView.setText(enabledTools.isEmpty()
                ? getString(R.string.terminal_status_running)
                : getString(R.string.terminal_status_running_tools_enabled, enabledTools));
            statusView.setTextColor(theme.accent);
        } else if (terminal.getExitCode() != Integer.MIN_VALUE) {
            statusView.setText(getString(
                R.string.terminal_status_exited,
                Integer.valueOf(terminal.getExitCode())
            ));
            statusView.setTextColor(theme.secondary);
        } else {
            statusView.setText(R.string.terminal_status_stopped);
            statusView.setTextColor(theme.secondary);
        }

        boolean running = terminal.isRunning();
        nodeButton.setText(getString(
            nodeEnabled ? R.string.terminal_node_enabled : R.string.terminal_enable_node,
            BuildIdentity.NODE_RUNTIME_VERSION
        ));
        npmButton.setText(getString(
            npmEnabled ? R.string.terminal_npm_enabled : R.string.terminal_enable_npm,
            BuildIdentity.NPM_RUNTIME_VERSION
        ));
        pythonButton.setText(getString(
            pythonEnabled ? R.string.terminal_python_enabled : R.string.terminal_enable_python,
            BuildIdentity.PYTHON_RUNTIME_VERSION
        ));
        ripgrepButton.setText(getString(
            ripgrepEnabled
                ? R.string.terminal_ripgrep_enabled
                : R.string.terminal_enable_ripgrep,
            BuildIdentity.RIPGREP_RUNTIME_VERSION
        ));
        theme.setEnabled(startButton, runtimeReady && !running && !terminal.isStarting());
        theme.setEnabled(stopButton, running || terminal.isStarting());
        theme.setEnabled(sendButton, running);
        theme.setEnabled(nodeButton, running && !nodeEnabled);
        theme.setEnabled(npmButton, running && !npmEnabled);
        theme.setEnabled(pythonButton, running && !pythonEnabled);
        theme.setEnabled(ripgrepButton, running && !ripgrepEnabled);
        theme.setEnabled(controlCButton, running);
        theme.setEnabled(tabButton, running);
        theme.setEnabled(escapeButton, running);
        inputView.setEnabled(running);

        if (renderedRevision != terminal.getRevision()) {
            renderedRevision = terminal.getRevision();
            outputView.setText(terminal.getOutput().isEmpty()
                ? getString(R.string.terminal_output_empty)
                : terminal.getOutput());
            outputScroll.post(new Runnable() {
                @Override
                public void run() {
                    if (refreshActive && !destroyed && uiReady) {
                        outputScroll.fullScroll(View.FOCUS_DOWN);
                    }
                }
            });
        }
    }

    private String enabledTools(
        boolean nodeEnabled,
        boolean npmEnabled,
        boolean pythonEnabled,
        boolean ripgrepEnabled
    ) {
        StringBuilder enabled = new StringBuilder();
        if (nodeEnabled) {
            enabled.append("Node.js ").append(BuildIdentity.NODE_RUNTIME_VERSION);
        }
        if (npmEnabled) {
            appendEnabledTool(enabled, "npm " + BuildIdentity.NPM_RUNTIME_VERSION);
        }
        if (pythonEnabled) {
            appendEnabledTool(enabled, "Python " + BuildIdentity.PYTHON_RUNTIME_VERSION);
        }
        if (ripgrepEnabled) {
            appendEnabledTool(enabled, "ripgrep " + BuildIdentity.RIPGREP_RUNTIME_VERSION);
        }
        return enabled.toString();
    }

    private static void appendEnabledTool(StringBuilder enabled, String tool) {
        if (enabled.length() > 0) {
            enabled.append(", ");
        }
        enabled.append(tool);
    }

    private void resizeTerminal() {
        if (uiReady && outputView != null) {
            AgentRuntimeService.resizeTerminal(terminalRows(), terminalColumns());
        }
    }

    private int terminalRows() {
        if (outputScroll == null || outputScroll.getHeight() <= 0) {
            return 24;
        }
        return Math.max(4, Math.min(1000, outputScroll.getHeight() / theme.dp(18)));
    }

    private int terminalColumns() {
        if (outputScroll == null || outputScroll.getWidth() <= 0) {
            return 80;
        }
        return Math.max(20, Math.min(1000, outputScroll.getWidth() / theme.dp(8)));
    }

    private void wipeInput() {
        if (inputView == null) {
            return;
        }
        Editable editable = inputView.getText();
        if (editable != null) {
            for (int index = 0; index < editable.length(); index++) {
                editable.replace(index, index + 1, "\0");
            }
            editable.clear();
        }
    }
}
