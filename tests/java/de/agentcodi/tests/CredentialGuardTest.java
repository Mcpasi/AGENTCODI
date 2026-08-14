package de.agentcodi.tests;

import de.agentcodi.core.ChatMessage;
import de.agentcodi.core.CredentialGuard;

public final class CredentialGuardTest {
    private CredentialGuardTest() {
    }

    public static int run() {
        detectsCredentialShapesWithoutStringifyingMutableInput();
        permitsOrdinaryConversationText();
        redactsCredentialShapesAtTheChatProjectionBoundary();
        return 3;
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
