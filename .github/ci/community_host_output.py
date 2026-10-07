"""Check the actual exec result rather than its source in model history."""
def verify_host_output(model_input, call_id, marker):
    outputs = [item["output"] for item in model_input
               if item.get("type") == "custom_tool_call_output" and item.get("call_id") == call_id]
    assert len(outputs) == 1, "Expected exactly one code-mode call output"
    output = outputs[0]
    blocks = output if isinstance(output, list) else [output]
    texts = [block if isinstance(block, str) else block.get("text", "") for block in blocks]
    assert any(marker in text.splitlines() for text in texts), "Code-mode result did not emit the marker: " + repr(output)
    return output
