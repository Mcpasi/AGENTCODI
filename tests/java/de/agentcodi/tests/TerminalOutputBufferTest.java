package de.agentcodi.tests;

import de.agentcodi.core.TerminalOutputBuffer;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class TerminalOutputBufferTest {
    private TerminalOutputBufferTest() {
    }

    public static int run() {
        decodesSplitUtf8AndStripsTerminalControls();
        boundsOutputAndMarksOmission();
        keepsOmissionMarkerAcrossRepeatedTrims();
        redactsCredentialShapesFromSnapshots();
        finishesIncompleteUtf8Safely();
        removesRemainingControlsAndResetsIncompleteAnsi();
        return 6;
    }

    private static void decodesSplitUtf8AndStripsTerminalControls() {
        TerminalOutputBuffer buffer = new TerminalOutputBuffer();
        byte[] first = new byte[] {'o', 'k', ' ', (byte) 0xe2, (byte) 0x82};
        byte[] second = new byte[] {
            (byte) 0xac,
            0x1b,
            '[',
            '3',
            '1',
            'm',
            '!',
            0x1b,
            '[',
            '0',
            'm',
            '\r',
            '\n'
        };
        buffer.append(first);
        buffer.append(second);
        TestSupport.assertEquals("ok €!\n", buffer.snapshot(), "terminal normalization");
        buffer.clear();
        TestSupport.assertEquals("", buffer.snapshot(), "terminal clear");
    }

    private static void boundsOutputAndMarksOmission() {
        TerminalOutputBuffer buffer = new TerminalOutputBuffer();
        byte[] oversized = new byte[TerminalOutputBuffer.MAXIMUM_CHARACTERS + 4096];
        Arrays.fill(oversized, (byte) 'x');
        buffer.append(oversized);
        String snapshot = buffer.snapshot();
        TestSupport.assertTrue(
            snapshot.length() <= TerminalOutputBuffer.MAXIMUM_CHARACTERS,
            "terminal snapshot limit"
        );
        TestSupport.assertTrue(
            snapshot.startsWith("[earlier terminal output omitted]"),
            "terminal omission marker"
        );
    }

    private static void keepsOmissionMarkerAcrossRepeatedTrims() {
        TerminalOutputBuffer buffer = new TerminalOutputBuffer();
        byte[] oversized = new byte[TerminalOutputBuffer.MAXIMUM_CHARACTERS + 4096];
        Arrays.fill(oversized, (byte) 'x');
        buffer.append(oversized);
        buffer.append("\nolder command output\n".getBytes(StandardCharsets.UTF_8));
        buffer.append(oversized);
        String snapshot = buffer.snapshot();
        TestSupport.assertTrue(
            snapshot.length() <= TerminalOutputBuffer.MAXIMUM_CHARACTERS,
            "terminal snapshot limit after a second trim"
        );
        TestSupport.assertFalse(
            snapshot.contains("older command output"),
            "second trim drops the oldest terminal output"
        );
        TestSupport.assertTrue(
            snapshot.startsWith("[earlier terminal output omitted]"),
            "terminal omission marker survives a second trim"
        );
        TestSupport.assertEquals(
            Integer.valueOf(-1),
            Integer.valueOf(snapshot.indexOf(
                "[earlier terminal output omitted]",
                1
            )),
            "terminal omission marker is not duplicated"
        );
    }

    private static void redactsCredentialShapesFromSnapshots() {
        TerminalOutputBuffer buffer = new TerminalOutputBuffer();
        buffer.append(
            "authorization=secret-value-12345\n".getBytes(StandardCharsets.UTF_8)
        );
        String snapshot = buffer.snapshot();
        TestSupport.assertFalse(snapshot.contains("secret-value-12345"), "terminal secret");
        TestSupport.assertContains(snapshot, "<redacted>", "terminal redaction marker");
    }

    private static void finishesIncompleteUtf8Safely() {
        TerminalOutputBuffer buffer = new TerminalOutputBuffer();
        buffer.append(new byte[] {(byte) 0xf0, (byte) 0x9f});
        buffer.finish();
        TestSupport.assertEquals("�", buffer.snapshot(), "incomplete UTF-8 replacement");
    }

    private static void removesRemainingControlsAndResetsIncompleteAnsi() {
        TerminalOutputBuffer buffer = new TerminalOutputBuffer();
        buffer.append(new byte[] {
            'a',
            0x7f,
            (byte) 0xc2,
            (byte) 0x85,
            'b',
            0x1b,
            ']',
            'h',
            'i',
        });
        buffer.finish();
        buffer.append("visible".getBytes(StandardCharsets.UTF_8));
        TestSupport.assertEquals(
            "abvisible",
            buffer.snapshot(),
            "terminal controls and incomplete ANSI state"
        );
    }
}
