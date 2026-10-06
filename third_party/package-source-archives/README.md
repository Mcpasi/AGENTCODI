# Pinned original package source archives

These complete, unchanged GNU attr 2.6.0 and acl 2.4.0 source archives are build
inputs for the Package Edition source-built bootstrap and catalog. They are
not APK assets or binary dependency seeds. Their original upstream authors,
copyright statements and GPL/LGPL COPYING texts remain inside the archives;
AGENTCODI's Apache license does not replace their licenses.

Savannah timed out from GitHub's builders over both HTTP and HTTPS. The
checked alternative mirrors did not carry these exact versions. The files
were recovered from the complete corresponding-source artifact of
[successful build 37448382664](https://github.com/Mcpasi/AGENTCODI/actions/runs/37448382664),
commit 80fc2b0ed134a120a5d6edb5dc254566c8df08d8, using
[recovery run 37456334585](https://github.com/Mcpasi/AGENTCODI/actions/runs/37456334585).
The complete outer source-archive hash, original member paths, upstream URLs
and recovered byte hashes are recorded in PROVENANCE.json. Both files match
the original SHA-256 values in the pinned Termux recipes:

| File | SHA-256 |
| --- | --- |
| attr-2.6.0.tar.gz | d42fa374513180bb48cb11a46696f488240e5124ff1e6ad88b0abff706985612 |
| acl-2.4.0.tar.gz | 73c853c3d44e1f693e5a96a986f1bd19d3d0dac2c7d453e796177774bc4e5f6a |

The versioned edition overlay uses immutable raw GitHub URLs for these files
while retaining the original source checksums and versions. It does not depend
on an expiring CI artifact or change source bytes. The usual source build,
recipe/source archive retention, ELF checks and license ownership audits
continue to apply. The branch-only Package source archive checks workflow
verifies both committed hashes and the retained original legal files.
