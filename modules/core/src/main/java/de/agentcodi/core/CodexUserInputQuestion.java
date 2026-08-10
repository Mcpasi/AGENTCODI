package de.agentcodi.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CodexUserInputQuestion {
    private final String id;
    private final String header;
    private final String question;
    private final List<CodexUserInputOption> options;
    private final boolean otherAllowed;
    private final boolean secret;

    public CodexUserInputQuestion(
        String id,
        String header,
        String question,
        List<CodexUserInputOption> options,
        boolean otherAllowed,
        boolean secret
    ) {
        this.id = id == null ? "" : id;
        this.header = header == null ? "" : header;
        this.question = question == null ? "" : question;
        this.options = Collections.unmodifiableList(new ArrayList<CodexUserInputOption>(
            options == null ? Collections.<CodexUserInputOption>emptyList() : options
        ));
        this.otherAllowed = otherAllowed;
        this.secret = secret;
    }

    public String getId() {
        return id;
    }

    public String getHeader() {
        return header;
    }

    public String getQuestion() {
        return question;
    }

    public List<CodexUserInputOption> getOptions() {
        return options;
    }

    public boolean isOtherAllowed() {
        return otherAllowed;
    }

    public boolean isSecret() {
        return secret;
    }
}
