#!/usr/bin/env python3
"""A host crash must not pass because the submitted source contains a marker."""
import unittest
from community_host_output import verify_host_output


class HostOutputTests(unittest.TestCase):
    def test_actual_text_result(self):
        for output in ("Output:\nmarker\n", [{"type": "input_text", "text": "marker"}]):
            message = [{"type": "custom_tool_call_output", "call_id": "probe", "output": output}]
            self.assertEqual(verify_host_output(message, "probe", "marker"), output)

    def test_crash_and_marker_in_source(self):
        history = [{"type": "custom_tool_call", "call_id": "probe", "input": "text('marker');"},
                   {"type": "custom_tool_call_output", "call_id": "probe",
                    "output": "code-mode host exited with status signal: 11 (SIGSEGV)"}]
        with self.assertRaises(AssertionError):
            verify_host_output(history, "probe", "marker")

    def test_missing_and_other_call_outputs(self):
        for history in ([], [{"type": "custom_tool_call_output", "call_id": "other", "output": "marker"}]):
            with self.assertRaises(AssertionError):
                verify_host_output(history, "probe", "marker")

    def test_source_echo_in_error(self):
        history = [{"type": "custom_tool_call_output", "call_id": "probe",
                    "output": "Error evaluating text('marker');"}]
        with self.assertRaises(AssertionError):
            verify_host_output(history, "probe", "marker")


if __name__ == "__main__":
    unittest.main()
