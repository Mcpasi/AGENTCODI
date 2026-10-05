#!/usr/bin/env python3
"""Adapt npm bin-links to Android's actual env interpreter, preserving script bytes."""
from pathlib import Path
import sys

path = Path(sys.argv[1]) / "node_modules/bin-links/lib/fix-bin.js"
text = path.read_text()
old = "module.exports = fixBin\n"
if text.count(old) != 1:
    raise SystemExit("Pinned npm bin-links export context changed")
new = r"""// AGENTCODI: Android has /system/bin/env, not /usr/bin/env.
// Patch only npm-managed Node script headers, including local .bin links.
module.exports = async (file, mode = execMode) => {
  await fixBin(file, mode)
  const handle = await open(file, 'r')
  let header
  try {
    const buffer = Buffer.alloc(256)
    const { bytesRead } = await handle.read(buffer, 0, buffer.length, 0)
    header = buffer.subarray(0, bytesRead).toString('utf8')
  } finally {
    await handle.close()
  }
  const match = header.match(/^#![ \t]*\/usr\/bin\/env[ \t]+node(?=[ \t\r\n]|$)/)
  if (!match) {
    return
  }
  const content = await readFile(file)
  const replacement = Buffer.from('#!/system/bin/env node')
  await writeFileAtomic(file, Buffer.concat([
    replacement, content.subarray(Buffer.byteLength(match[0])),
  ]), { mode })
}
"""
path.write_text(text.replace(old, new))
