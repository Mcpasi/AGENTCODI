package de.agentcodi.tests;

import de.agentcodi.core.ChatMessage;
import de.agentcodi.core.CredentialGuard;

import java.util.Arrays;

public final class CredentialGuardTest {
    private CredentialGuardTest() {
    }

    public static int run() {
        detectsCredentialShapesWithoutStringifyingMutableInput();
        detectsPasswordAndClientSecretForms();
        detectsCredentialValuesSplitAcrossArguments();
        detectsCredentialFileNames();
        permitsOrdinaryConversationText();
        redactsCredentialShapesAtTheChatProjectionBoundary();
        return 6;
    }

    private static void detectsCredentialFileNames() {
        TestSupport.assertTrue(
            CredentialGuard.isLikelyCredentialFileName("auth.json"),
            "canonical Codex credential filename"
        );
        TestSupport.assertTrue(
            CredentialGuard.isLikelyCredentialFileName("folder/.env.production"),
            "environment credential filename"
        );
        TestSupport.assertTrue(
            CredentialGuard.isLikelyCredentialFileName("password.txt"),
            "named credential filename"
        );
        TestSupport.assertFalse(
            CredentialGuard.isLikelyCredentialFileName("credential-input.txt"),
            "ordinary credential documentation filename"
        );
    }

    private static void detectsPasswordAndClientSecretForms() {
        TestSupport.assertTrue(
            CredentialGuard.containsLikelyCredential("password=x"),
            "short password assignment"
        );
        TestSupport.assertTrue(
            CredentialGuard.containsLikelyCredential("password=&"),
            "punctuation-only password assignment"
        );
        TestSupport.assertTrue(
            CredentialGuard.containsLikelyCredential("client_secret=fixture-client-secret"),
            "client secret assignment"
        );
        TestSupport.assertTrue(
            CredentialGuard.containsLikelyCredential("--password\nfixture-password"),
            "multiline password option"
        );
        TestSupport.assertTrue(
            CredentialGuard.containsLikelyCredential(
                "--client-secret \"fixture-client-secret\""
            ),
            "quoted client secret option"
        );
        TestSupport.assertTrue(
            CredentialGuard.isLikelyCredentialName("clientSecret"),
            "camel-case client secret field name"
        );
        TestSupport.assertFalse(
            CredentialGuard.isLikelyCredentialName("client_secret_file"),
            "credential transport field name"
        );
    }

    private static void detectsCredentialValuesSplitAcrossArguments() {
        TestSupport.assertTrue(
            CredentialGuard.containsLikelyCredential(Arrays.asList(
                "--password",
                "fixture-password"
            )),
            "password split across argv"
        );
        TestSupport.assertTrue(
            CredentialGuard.containsLikelyCredential(Arrays.asList(
                "--client_secret",
                "&"
            )),
            "punctuation-only client secret split across argv"
        );
        TestSupport.assertTrue(
            CredentialGuard.containsLikelyCredential(Arrays.asList(
                "--api-key",
                "fixture-api-value"
            )),
            "existing credential labels are also correlated across argv"
        );
    }

    private static void detectsCredentialShapesWithoutStringifyingMutableInput() {
        TestSupport.assertTrue(
            CredentialGuard.containsLikelyCredential("sk-fixture123456789"),
            "OpenAI key shape"
        );
        TestSupport.assertTrue(
            CredentialGuard.containsLikelyCredential("Bearer fixture-token-123"),
            "bearer shape"
        );
        char[] mutable = "access token = fixture-token-123".toCharArray();
        TestSupport.assertTrue(
            CredentialGuard.containsLikelyCredential(mutable),
            "named mutable token"
        );
        TestSupport.assertEquals(
            Character.valueOf('a'),
            Character.valueOf(mutable[0]),
            "scanner does not mutate caller input"
        );
    }

    private static void permitsOrdinaryConversationText() {
        TestSupport.assertFalse(
            CredentialGuard.containsLikelyCredential(
                "Erkläre die sichere Speicherung eines API-Keys ohne Beispielwert."
            ),
            "credential discussion is allowed"
        );
        TestSupport.assertFalse(
            CredentialGuard.containsLikelyCredential("task-sk-short"),
            "embedded or short token fragments are allowed"
        );
        TestSupport.assertFalse(
            CredentialGuard.containsLikelyCredential(Arrays.asList(
                "--password-stdin",
                "--client-secret-file",
                "credential-input.txt"
            )),
            "non-value credential transport options remain allowed"
        );
    }

    private static void redactsCredentialShapesAtTheChatProjectionBoundary() {
        ChatMessage message = new ChatMessage(
            "fixture",
            ChatMessage.Role.ASSISTANT,
            "OpenAI API key: sk-fixture123456789",
            false
        );
        TestSupport.assertFalse(
            message.getText().contains("sk-fixture123456789"),
            "chat projection removes credential"
        );
        TestSupport.assertContains(message.getText(), "<redacted>", "chat redaction marker");
    }
}
