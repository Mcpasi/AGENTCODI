# CI-only Android 10 / API 29 Bionic. Do not copy these libraries into the APK.
# The Termux image's aosp-libs 9.0.0-r76-4 predates native ELF TLS; V8's
# relaxed TLS accesses otherwise address unrelated pthread storage and crash.
FROM redroid/redroid:10.0.0-latest@sha256:f41e76f39b5e6343a1608a1c76b117e2f3469f60db068bf8db9b5b6c808724cc AS api29
FROM termux/termux-docker:aarch64@sha256:e19ea56dd687563849826cbda57da714ae23277ee463e21f39917dbc0a59bab4
COPY --from=api29 /system/apex/com.android.runtime.debug/bin/linker64 /data/data/com.termux/files/usr/opt/aosp/bin/linker64
COPY --from=api29 /system/apex/com.android.runtime.debug/lib64/bionic/ /data/data/com.termux/files/usr/opt/aosp/lib64/
