# Stable Package Edition debug signing

The Package Edition development APK uses the public AOSP `testkey` identity.
This is an intentionally public development fixture, not a private release key.
Do not use this key to authenticate an official release. The release builder
retains its external private-keystore requirements and rejects this certificate.

Both the PKCS#8 key (stored as Base64 text) and PEM certificate are tracked,
so cold builds, cache eviction and different runners keep the same identity.
`scripts/sign-debug-apk.py` validates their SHA-256 digests before signing and
checks the certificate in the signed APK before returning it. Missing or
modified material fails the build; it never generates a replacement key.
An older `.cache/android/agentcodi-debug.keystore` is ignored and preserved.

Certificate DER SHA-256:
`a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc`

PKCS#8 DER SHA-256:
`495675d32e89a149d5abe191f4e9c0e218b9068714e9b53a7c91e164a0741a23`

The private test key is used only during the build. It must never be packaged
as an app asset. This debug identity is independent of the production signing
identity.

## Source and license

Source: [AOSP platform_build](https://github.com/aosp-mirror/platform_build/tree/045a3d6a3e359633a14853a5a5e1e4f2a11cbdae/target/product/security)
at commit `045a3d6a3e359633a14853a5a5e1e4f2a11cbdae`:

- `testkey.x509.pem`, Git blob `e242d83e2bf72169ab0abd5280d4d455caef71eb`.
- `testkey.pk8`, Git blob `586c1bd5cf96f9358f36b37ea98fef93f4d0a8e3`;
  `testkey.pk8.b64` decodes to those exact bytes.
- `UPSTREAM-README.md` preserves the upstream development-only instructions.

The source package declares [Android-Apache-2.0](https://github.com/aosp-mirror/platform_build/blob/045a3d6a3e359633a14853a5a5e1e4f2a11cbdae/Android.bp).
The Apache 2.0 terms are included in `LICENSE`.

## Existing installations

APKs produced before `0.1.0-package.3` by fresh CI runners used random keys.
Their private keystores were not retained in CI artifacts or the input cache.
Android requires a compatible signing key as well as an increasing versionCode.
A higher versionCode, the same distinguished name or the same package ID cannot
repair a changed certificate.

Without the original private key, an installation with one of the old
certificates cannot be upgraded in place to this stable identity. Export and
verify all required data before the one-time removal and reinstallation.
A backup of shared workspace files does not automatically include the app's
private conversations, credentials or installed packages. Uninstalling removes
private app data. Subsequent development APKs with this identity and a higher
versionCode can satisfy the signing requirement for updates; physical device
update validation remains outstanding.
