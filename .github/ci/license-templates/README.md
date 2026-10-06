# Standard terms used for omitted crate license files

MIT.txt is copied unchanged from spdx/license-list-data commit
31ba1a50e5397e00a304dbadc76531740e89ee48, text/MIT.txt:
https://github.com/spdx/license-list-data/blob/31ba1a50e5397e00a304dbadc76531740e89ee48/text/MIT.txt

The collector uses it only when the checksum-verified published crate declares
MIT and omits an original legal file. The SPDX year/holder placeholder is not
presented as an upstream notice. Published authors and original copyright
statements are retained separately; no dates are inferred.

For reviewed MIT-or-Apache declarations, the collector selects the declared
Apache-2.0 alternative and copies the complete Apache text from the exact Codex
source LICENSE. Unsupported declarations remain unresolved. Four regression
tests verify this behavior. Original supplied license files are not rewritten.
