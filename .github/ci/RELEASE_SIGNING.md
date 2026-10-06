# Signierte Release-APK der Package Edition

Der Workflow [APK Release](../workflows/apk-release.yml) arbeitet ausschließlich
auf `Mcpasi/package-edition`. Er verwendet denselben Container, die gepinnten
Build-Eingaben und den geprüften Bootstrap wie der Debug-Build und ruft
`scripts/build-release-apk.sh` auf. `main` muss dafür nicht geändert werden.

## Einmalige Einrichtung in GitHub

Unter **Settings → Secrets and variables → Actions → New repository secret**
diese fünf Repository-Secrets hinterlegen:

| Secret | Inhalt |
| --- | --- |
| `AGENTCODI_RELEASE_KEYSTORE_BASE64` | Base64-Inhalt des eigenen privaten JKS- oder PKCS12-Keystores. |
| `AGENTCODI_RELEASE_STORE_PASSWORD` | Keystore-Passwort, unverändert und ohne Zeilenumbruch. |
| `AGENTCODI_RELEASE_KEY_PASSWORD` | Passwort des privaten Schlüssels; bei PKCS12 üblicherweise identisch mit dem Keystore-Passwort. |
| `AGENTCODI_RELEASE_KEY_ALIAS` | Alias des privaten Schlüssels; 1–128 Zeichen aus Buchstaben, Ziffern, Punkt, Unterstrich und Bindestrich. |
| `AGENTCODI_RELEASE_CERT_SHA256` | SHA-256-Fingerprint des Zertifikats dieses Schlüssels; 64 Hex-Zeichen, auch mit Doppelpunkten akzeptiert. |

Keystore und Passwörter bleiben außerhalb des Repos. Einen vorhandenen
Release-Keystore beibehalten, wenn er bereits für diese App verwendet wurde.
Falls noch keiner existiert, kann er lokal erstellt werden; `keytool` fragt die
Passwörter interaktiv ab:

```sh
keytool -genkeypair -keystore agentcodi-release.jks -storetype JKS \
  -alias agentcodi-release -keyalg RSA -keysize 4096 -validity 10000
```

Base64 unter Linux erzeugen und den gesamten Dateiinhalt als Secret eintragen:

```sh
base64 -w 0 agentcodi-release.jks > agentcodi-release.jks.base64
```

Den Fingerprint über `keytool -list -v` aus der Zeile **SHA256** übernehmen.
Alternativ die Zertifikatsbytes hashen:

```sh
keytool -exportcert -keystore agentcodi-release.jks \
  -alias agentcodi-release -file agentcodi-release.der
openssl dgst -sha256 agentcodi-release.der
```

Die Keystore- und Base64-Datei sowie die Passwörter sicher aufbewahren; sie
dürfen nicht eingecheckt werden. Der Workflow erzeugt keinen Ersatzschlüssel.
Der öffentliche AOSP-Testschlüssel und Android-Debug-Zertifikate werden vom
Release-Build abgelehnt.

`AGENTCODI_INPUTS_TOKEN` wird wie beim bestehenden APK-Workflow nur für den
privaten Build-Input-Spiegel verwendet. Wenn die gepinnten Eingaben upstream
verfügbar sind, funktioniert deren Abruf auch ohne diesen Token.

## Build starten und APK herunterladen

Relevante Pushes auf `Mcpasi/package-edition` starten **APK Release** automatisch.
Der Einrichtungs-Commit startet bereits einen Lauf. Solange Secrets fehlen,
stoppt er mit deren Namen vor dem Bootstrap- und Container-Build. Nach dem
Eintragen der Secrets diesen Lauf unter **Actions → APK Release → Re-run all
jobs** erneut starten; eine Codeänderung ist dafür nicht nötig.

Spätere Änderungen an App, Modulen, Scripts oder Release-Build-Eingaben
starten einen neuen Lauf. Der Workflow enthält auch `workflow_dispatch`;
GitHub zeigt dessen manuellen Start erst an, wenn die Workflow-Datei auf dem
Default-Branch bekannt ist. Für diesen ausschließlich auf dem Edition-Branch
eingerichteten Workflow sind Push und erneutes Starten des vorhandenen Laufs
die verfügbaren Einstiege. Dafür `main` nicht ändern.

Nach einem erfolgreichen Build stehen diese Artefakte bereit:

- `agentcodi-package-release-apk`: versionierte und unversionierte signierte
  Release-APK mit portablen `.sha256`-Dateien.
- `agentcodi-package-release-apk-contract`: Payload-, Bootstrap- und
  Lizenznachweise dieser APK.

Die Signatur muss genau einen Signer mit dem hinterlegten SHA-256-Fingerprint
enthalten. Der Produktionsbuilder prüft außerdem App-ID, Version, ABI,
Alignment, nicht debuggable Manifest und vollständige Lizenznachweise.
Der Workflow veröffentlicht keinen GitHub Release.

## Temporäre Signing-Dateien und Updates

Der CI-Einstieg prüft die Secrets und legt Dateien mit Modus `0600` in einem
`0700`-Verzeichnis unter `RUNNER_TEMP` an. Docker erhält dieses Verzeichnis
read-only außerhalb von `/workspace`; Passwörter werden über Dateien an
`apksigner` gegeben. Rohwerte gelangen nicht in Docker-Argumente, Build-Cache,
APK oder Artefakte. Exit-/Signal-Traps und ein `always()`-Schritt entfernen das
Signing-Verzeichnis auch bei abgebrochenen oder fehlgeschlagenen Builds.

Für spätere Android-Updates denselben privaten Signer beibehalten und den
`versionCode` mit `scripts/bump-version.sh` erhöhen. Eine mit dem öffentlichen
Debug-Schlüssel installierte App kann nicht direkt mit einem anderen
Release-Schlüssel aktualisiert werden. Physische Android-Installation,
Update und Datenhaltung bleiben separate Gerätetests.
