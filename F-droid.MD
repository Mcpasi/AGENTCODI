# F-Droid: Übernahmen und Build-Umbau

Stand: 20. Oktober 2026. Arbeitsbranch: `fdroid/source-build-and-editor`.

Diese Datei dokumentiert die Anpassungen dieses Arbeitsstands. Bestehende
Dokumente, insbesondere README, CHANGELOG, NOTICE und SECURITY, bleiben
unverändert. An AGENTCODI-Package-Edition werden keine Änderungen vorgenommen.

## Verglichene Stände

| Repository | Ausgangscommit |
| --- | --- |
| AGENTCODI | `ed581ed40b97e79af526756ea9137c7826cdb004` |
| AGENTCODI-Package-Edition | `e964ffaa736593b3c354726df0bb210e3b317317` |
| Mcpasi/codex-termux | `af5fec312538b25568fd83a0090efe3c417807d1` |
| OpenAI-Basis des Codex-Forks | `657a993cbee87acf52d14b758ce49dbd46d1b8eb` (`rust-v0.153.2`) |

Der Codex-Fork behält Version `0.153.3-agentcodi.2`. Die App-Version bleibt
`0.7.6-preview.1` mit VersionCode 87; dieser Branch veröffentlicht kein Release.

## Übernommene Bestandteile

| Bestandteil | Integration in AGENTCODI |
| --- | --- |
| Workspace-Editor | Neue Module `file-editor-contracts`, `file-editor-client` und `file-editor-syntax`; Anbindung über `WorkspaceEditorRepository` und den vorhandenen Dateibrowser. |
| Editor-Oberfläche | Zeilennummern, Umbruch, Syntaxfarben für JavaScript/TypeScript, Python, HTML und CSS, Speichern, Export und Strg+S. |
| Neue Dateien | Namensprüfung, zunächst ungespeicherter Entwurf, Anlage erst beim Speichern und Schutz gegen bereits vorhandene Zieldateien. |
| Texttreue | Striktes UTF-8, Erhaltung einer vorhandenen BOM sowie von LF, CRLF und gemischten Zeilenenden; Dateigrenze von 1 MiB. Größere Dateien bleiben in der paginierten Vorschau. |
| Lebenszyklus | Ungespeicherte Änderungen beim Verlassen berücksichtigen; Entwurf, Auswahl, Scrollposition und laufende Speicherung bei Konfigurationswechseln erhalten. |
| Dateizugriffe | Descriptor-relative native Zugriffe, Ablehnung von Symbol- und Hardlinks, Prüfung von Dateiidentität und Änderungszeit, vollständiges Schreiben in eine temporäre Nachbardatei mit anschließendem atomarem Austausch. |
| Browser-Import | Dateien über den Android-Dokumentwähler in den bestehenden Workspace-Import übernehmen. Der Import führt Dateien nicht aus. |
| Markdown | Darstellung von Assistentenantworten, Plänen und Reasoning-Karten mit Überschriften, Listen, Code, Hervorhebungen, Tabellen und geprüften Links. Benutzereingaben und Werkzeugausgaben behalten ihren Wortlaut. Das gespeicherte Transkript wird durch die Darstellung nicht verändert. |
| Prüfungen | Editor-, Syntax-, Markdown- und Dateigrenzentests sowie Android-Oberflächentests auf API 29 und 35. |

Die neuen JNI-Methoden zum Erstellen und Ersetzen von Dateien wurden gezielt in
die vorhandene JNI-Schnittstelle übernommen. Das Laufzeitmodell von AGENTCODI
mit Protected mode und seinen Toolchain-Grenzen bleibt erhalten.

## Weitere geprüfte Bestandteile

| Bestandteil der Package-Edition | Ergebnis des Vergleichs |
| --- | --- |
| Paketverwaltung, APT-Repository und Paket-Bootstrap | Hängen am eigenen Paketpräfix, der Package-Edition-Anwendungskennung und dem dortigen Laufzeitmodell. Eine Übernahme benötigt eine gesonderte Architekturänderung. |
| Paketbereiche im Dateibrowser | Setzen die dortige Paketverwaltung voraus. Der übernommene Editor arbeitet ausschließlich im AGENTCODI-Workspace. |
| Vollständige Chat-Aufteilung und weitere Terminal-Umbauten | Enthalten gekoppelte Änderungen an Session-, Transcript-, Terminal- und Laufzeitverträgen. Die unabhängige Markdown-Darstellung wurde herausgelöst; die übrige Umstrukturierung wurde nicht pauschal kopiert. |
| Package-Edition-Identität, Symbole und Android-Zielversion | Gehören zu einer anderen App. AGENTCODI behält seine Kennung `de.agentcodi.app`, Mindest-API 29 und Ziel-API 35. |

## Codex und V8 aus den Fork-Rezepten

Der APK-Build verwendet kein beliebiges lokales Codex-NPM-Archiv mehr.
`scripts/build-codex-from-source.sh` steuert den Quellbuild. Die unveränderlichen
Eingaben stehen in `scripts/fdroid/source-lock.json`:

- Commit und Repository des eigenen Codex-termux-Forks;
- SHA-256 der beiden Fork-Workflows, der Cargo-Sperrdatei und der relevanten
  Prüf- und Patchskripte;
- Rust `1.95.0`, Ziel `aarch64-linux-android`, NDK `28.2.13676358`, SDK 36,
  SDK Build-tools `36.0.0` und LLVM 21 für V8-Bindgen;
- rusty_v8 `150.4.0` aus Commit
  `5c15a6995c9bb4bacd3e341b59fff32c909c80bf` einschließlich der gepinnten
  Submodule und des erforderlichen V8-Sandbox-Profils;
- GN und Ninja aus den V8-DEPS mit unveränderlichen CIPD-Paket-IDs sowie
  SHA-256-Prüfsummen für Archive und ausführbare Werkzeuge;
- `android_platform` und `catapult` aus den Commit-Pins der V8-DEPS.

Der Builder liest die Build-Blöcke aus den geprüften Fork-Rezepten
`.github/workflows/termux-npm-build-publish.yml` und
`.github/workflows/rusty-v8-android-release.yml`. Er ergänzt `--frozen`, baut V8
vorab aus dessen Quellen und übergibt dieses Ergebnis an das Codex-Rezept.
Die fünf Release-Einstellungen des Android-Jobs werden ebenfalls aus dem
geprüften Rezept gelesen: Optimierungsstufe 3, Thin-LTO, 16 Codegen-Einheiten,
Panic-Abbruch und Symbol-Stripping. Geerbte Cargo-Profile werden verworfen;
fehlende, doppelte oder dynamische Profilwerte werden abgelehnt. Der
Quellbuild-Nachweis enthält das tatsächlich verwendete Profil.
Der V8-Sandbox-Test des Forks bleibt obligatorisch. Das überholte Skript
`scripts/build_rusty_v8_android.sh`, das lediglich das Plain-Profil erzeugt,
wird nicht verwendet.

Die Phasen sind getrennt:

1. `checkout`: exakten Fork-Commit holen und Quellen/Rezeptprüfsummen prüfen.
2. `prepare`: V8 samt Submodulen, Compiler-/Sysroot-Eingaben und gesperrte
   Cargo-Abhängigkeiten einschließlich der Host- und Plattformzweige der
   Sperrdateien für den späteren Build bereitstellen.
3. `build`: die beiden Rezepte mit Offline-Cargo und `--frozen` ausführen.
4. `verify`: erzeugtes Paket und seinen Quellbuild-Nachweis prüfen.

Das Ergebnis ist `output/codex-source/package.tgz` mit ausschließlich Codex,
Code-Mode-Host, C++-Laufzeit, Original-Lizenz/NOTICE und Paketmetadaten.
Für das gemeinsame Runtimeset der APK verwendet die Montage weiterhin ihre
separat gesperrte Termux-C++-Laufzeit.
Tar-Reihenfolge, Eigentümer, Rechte und Zeitstempel sowie der Gzip-Header sind
festgelegt. `source-build.json` bindet Quellstand, Builder, Source-Lock, V8 und
die einzelnen Nutzdaten an ihre SHA-256-Prüfsummen. Der APK-Build prüft diesen
Nachweis, bevor er dessen Ausgabehashes verwendet. Veraltete oder veränderte
Ausgaben werden abgelehnt.

Der native Supervisor setzt bereits `CODEX_CODE_MODE_HOST_PATH`. Deshalb bleibt
das neu gebaute Codex-ELF unverändert; die bisherige Ersetzung eines Hostnamens
an einem festen Byte-Offset entfällt. Die Protokollschema-Prüfungen bleiben
aktiv. Source-Lock und Quellbuild-Nachweis werden zusätzlich in
`assets/third-party/codex/` der APK abgelegt.

Die bisherigen Artefakthashes im Buildskript bleiben als historische Basis für
das bestehende Aktualisierungswerkzeug erhalten. Der neue Buildpfad überschreibt
die Ausgabehashes ausschließlich aus dem geprüften Quellbuild-Nachweis. Das
bisherige `update-codex-runtime.sh` ist kein Einstieg in diesen Quellbuildpfad;
es würde außerdem bestehende Dokumentationsdateien aktualisieren.

## Reproduzierbarere APK-Erstellung

- `scripts/build-fdroid-apk.sh` fordert eine **unsignierte** APK an und aktiviert
  den Offline-Modus. Es wird kein Debug-Schlüssel angelegt und kein privater
  Release-Schlüssel benötigt.
- Fehlende oder abweichende Download-Eingaben führen im Offline-Modus zum
  Abbruch; der Build lädt keinen Ersatz nach.
- `SOURCE_DATE_EPOCH` folgt standardmäßig dem AGENTCODI-Commit und kann für
  Vergleichsbuilds ausdrücklich vorgegeben werden. Sprache und Zeitzone sind
  `C.UTF-8` und UTC; die Prozess-Umask ist festgelegt.
- Das eingebettete npm/Python-ZIP, Klassen-JARs und die abschließende APK
  erhalten sortierte Einträge, einheitliche Zeitstempel und Rechte sowie
  bereinigte ZIP-Zusatzfelder. Die APK wird danach
  mit zipalign ausgerichtet. Der native App-Build und Codex entfernen bekannte
  absolute Quellpfade über Compiler-Präfixabbildungen.
- Das Ubuntu-26.04-Basisimage der APK-Montage ist auf einen OCI-Digest
  festgelegt. Python und Git gehören ausdrücklich zu dessen Werkzeugen.
  GitHub Actions ermittelt `SOURCE_DATE_EPOCH` vor dem Root-Container und
  übergibt es an den Build.
- Die private Binärspiegelung ist für die Vorbereitung mit
  `AGENTCODI_INPUTS_USE_MIRROR=0` abschaltbar. Codex-Archive und vorberechnete
  Codex-Schemata werden aus der alten Eingabeliste entfernt.
- `scripts/fdroid/compare-apks.py` akzeptiert ausschließlich identische
  vollständige APK-Bytes. Gleiche Nutzdaten bei unterschiedlichen ZIP-Metadaten
  gelten weiterhin als fehlgeschlagener Reproduzierbarkeitsvergleich.

Die Workflows prüfen Java, C++, Android-Kompilierung, Editor/Markdown-Oberfläche
und die Buildhilfen. Der neue Workflow `Codex from source` baut den Fork auf
Linux x86_64 mit den gesperrten Rezepten. Sein Build läuft über `unshare --net`
ohne Netzwerk. Der bisherige ARM64-APK-Workflow verwendet das daraus erzeugte
Artefakt und dessen Nachweis. Ein manueller reiner Container-Preflight benötigt
keinen vollständigen Codex-Build.

## Aufruf

Für den Quellbau empfehlen sich 16 GiB RAM und mindestens 50 GiB freier
Plattenspeicher für Quellen, Toolchains und temporäre LTO-Dateien.
Auf dem Linux-x86_64-Builder müssen die oben genannten
Toolchains sowie `build-essential`, CMake, Perl, pkg-config, protoc und die
weiteren Host-Abhängigkeiten des V8-Rezepts vorhanden sein. GN und Ninja werden in der
Vorbereitung aus den zusätzlich gesperrten Werkzeugpaketen bereitgestellt.
`ANDROID_HOME`, `ANDROID_NDK_HOME` und gegebenenfalls
`V8_SYSTEM_LIBCLANG_DIR` wählen diese Installationen aus. Optional lassen sich
Quellcache und Ausgabe mit `AGENTCODI_SOURCE_CACHE` und
`AGENTCODI_CODEX_BUILD_DIR` außerhalb des Repos ablegen.

```sh
./scripts/build-codex-from-source.sh plan
./scripts/build-codex-from-source.sh prepare

# In einem Builder ohne Netzwerk ausführen:
./scripts/build-codex-from-source.sh build
./scripts/build-codex-from-source.sh verify
```

Die APK-Montage benötigt weiterhin die bestehende ARM64/Bionic-Buildumgebung.
Dorthin müssen das gesamte Verzeichnis `output/codex-source` und die geprüften
Android-Eingaben übertragen bzw. eingebunden werden. Ein abweichendes
Codex-Ausgabeverzeichnis muss über `AGENTCODI_CODEX_BUILD_DIR` ausgewählt sein.

```sh
# Vorbereitung mit Netzwerk, ohne privaten Mirror:
AGENTCODI_INPUTS_USE_MIRROR=0 .github/ci/fetch-build-inputs.sh
.github/ci/verify-build-inputs.sh

# Anschließend in der vorbereiteten ARM64-Umgebung ohne Netzwerk:
./scripts/build-fdroid-apk.sh
# Ausgabe: output/apk/AGENTCODI-unsigned.apk
```

Ein vollständiger Nachweis benötigt zwei unabhängige Quell- und APK-Builds mit
gleichem Commit, Source-Lock, Toolchain-Stand und `SOURCE_DATE_EPOCH`, aber
unterschiedlichen Arbeitsverzeichnissen und leeren Build-Ausgaben:

```sh
python3 scripts/fdroid/compare-apks.py \
  /build-a/output/apk/AGENTCODI-unsigned.apk \
  /build-b/output/apk/AGENTCODI-unsigned.apk
```

## Validierung dieses Arbeitsstands

- Architekturprüfungen und Synchronität der Build-Eingabeliste erfolgreich.
- 362 Java-Tests erfolgreich, einschließlich der übernommenen Editor-, Syntax-
  und Markdown-Tests.
- Alle acht portablen C++-Testsuiten erfolgreich.
- Android-Ressourcen und vollständige Java-Anwendung gegen API 35 kompiliert;
  Manifest-Komponenten und Anwendungsidentität geprüft.
- Java-Anwendung mit dem gepinnten R8 `9.2.23` in DEX übersetzt;
  unsignierte Ressourcen-/DEX-Test-APK normalisiert und ZIP-Ausrichtung geprüft.
  Diese Testdatei enthält keine vollständige native Runtime und ist kein Release.
- Geänderte native App-/JNI-Bibliothek mit NDK r28c für Android ARM64 kompiliert.
- 96 Robolectric-Oberflächentests auf API 29 und 35 erfolgreich; erzeugte
  Editor-Ansichten geprüft.
- Elf Tests der Buildhilfen erfolgreich: deterministische ZIP-/Tar-Ausgabe
  einschließlich des eingebetteten Runtime-ZIPs, Rezept-Release-Profile,
  echte Bytevergleiche, sichere Archivinhalte, Quellen-/Rezeptauswahl und
  Ablehnung veränderter Build-Nachweise.
- Workflow-YAML, darin enthaltene Shell-Blöcke und Git-Diff geprüft.
- GitHub Actions prüft den Implementierungsstand `bb8c2a7` erfolgreich in allen
  fünf Jobs, einschließlich Android-Kompilierung und der Editor-/Markdown-UI:
  [Tests, Run 39412971358](https://github.com/Mcpasi/AGENTCODI/actions/runs/39412971358).
- Das festgelegte Ubuntu-Image wurde als OCI-Index geprüft; es enthält die
  benötigte Variante Linux ARM64/v8.

Der vollständige Codex-/V8-Quellbuild für Android ARM64 wurde erfolgreich in
einem Container ohne Netzwerkverbindung ausgeführt. Er erzeugt sowohl die CLI
als auch den Code-Mode-Host aus den festgelegten Fork-Rezepten. Die anschließende
Prüfung mit `scripts/build-codex-from-source.sh verify` ist erfolgreich:
Quellstand, Builder, Source-Lock, Archiv und sämtliche Nutzdaten stimmen mit
dem erzeugten `source-build.json` überein.

Der Fork-Prüfer bestätigt am V8-Archiv, dass
`v8__V8__IsSandboxEnabled` den Wert 1 zurückgibt. Beide erzeugten Programme
sind AArch64-ELFs mit `/system/bin/linker64` als Interpreter und
`PT_LOAD`-Ausrichtung `0x4000` (16 KiB).

Das erzeugte Quellpaket ist 109.486.147 Bytes groß. Seine Prüfsummen sind:

| Ausgabe | SHA-256 |
| --- | --- |
| `package.tgz` | `c3a8ad1aaeb28eebad2ad142484109b6cc83209dc20a5b290622d5956f3f1947` |
| `package/bin/codex.bin` | `163be2fda0bf38bb2704f90a6394adf498fb81f9493d4f4fcc60fca0717f4bd1` |
| `package/bin/codex-code-mode-host` | `7d11d5f13ddde3022bf269bf99394532e1a5f575ec9692125c19b45b84332840` |
| V8-Archiv | `915b94d6c4022cc4f8a46dc1a61c96755f04a4d55b9d9c6f17e9e15cd5d0bfb3` |

Zwei unabhängige vollständige Quell- und APK-Vergleichsbuilds stehen weiterhin
aus. Der erfolgreiche Quellbau belegt noch keine bitgleiche Gesamt-APK.

Die Quellvorbereitung wurde einschließlich
aller V8-Submodule, Compiler-/Sysroot-Eingaben und Cargo-Abhängigkeiten ausgeführt.
Der netzlose Quellbuild hat die Bindgen-Prüfung bestanden. Ein erster
vollständiger Versuch deckte einen versteckten Download von GN/Ninja im
V8-Buildskript auf. Diese Werkzeuge wurden daraufhin mit Paket-IDs und
Prüfsummen in den Vorbereitungsschritt verschoben; manipulierte Werkzeuge
werden vor dem Offline-Build abgelehnt. Auch der vorbereitete Chromium-Clang
wird ausdrücklich an den V8-Build übergeben. Beide von V8 verwendeten
NDK-Verzeichnisse zeigen auf denselben NDK r28c; der implizite r26c-Fallback
entfällt. Die Android-Hilfsrepos werden vorab auf ihre DEPS-Commits ausgecheckt,
statt sie während des Builds ohne Revision zu klonen.

Der erste anschließende Codex-Versuch wurde im Offline-Modus wegen eines
fehlenden `arboard`-Pakets abgewiesen. Die Vorbereitung lädt deshalb jetzt die
vollständigen gesperrten Cargo-Abhängigkeiten statt nur des Android-Zweigs.
Die erweiterte Vorbereitung ist erfolgreich durchgelaufen; der Codex-Bau
wurde mit dem bereits aus Quellen erzeugten V8 erfolgreich abgeschlossen.
Der vollständige GitHub-APK-Versuch
[Run 39361774824](https://github.com/Mcpasi/AGENTCODI/actions/runs/39361774824)
auf dem früheren Stand `538720a` scheiterte im Quellbuild-Job; die APK-Montage
wurde dort übersprungen. Dieser Lauf liegt vor der Cargo-Korrektur in
`bb8c2a7`.

## Öffentliche Eingaben: tatsächlicher Abrufversuch

Die Vorbereitung wurde mit deaktiviertem Mirror und einem Abrufversuch pro
Eingabe ausgeführt. Ergebnis: eine bereits vorhandene Eingabe verifiziert,
16 Eingaben öffentlich geladen und 15 Eingaben mit HTTP 404 fehlgeschlagen.
Betroffen sind die bisherigen Pins für:

- aapt2 `16.0.0.4-1`, Abseil `20260526.0`, Protobuf `35.1`, fmt `11.2.0`;
- libc++ `29`, Expat `2.8.2`, libpng `1.6.58`;
- Node `24.18.0`, SQLite `3.53.4`, OpenSSL `3.6.3`;
- npm `11.19.0`, Python `3.14.6-1`, libffi `3.5.2`, liblzma `5.8.3`;
- patchelf `0.19.1`.

Die Original-Prüfsummen werden beibehalten. Der Build ersetzt diese Dateien
nicht durch neuere Binärversionen. Die fehlenden öffentlichen Eingaben verhindern
bereits vor der vollständigen APK-Erstellung einen eigenständigen öffentlichen
Neuaufbau. Eine private Sicherung kann bestehende Entwicklungsbuilds unterstützen,
behebt aber diese F-Droid-Hürde nicht.

## Verbleibende F-Droid-Hürden

Dieser Branch ist eine technische Vorbereitung, keine Zusage einer bereits
erreichten F-Droid-Aufnahme oder bitgleichen Gesamt-APK.

1. Der APK-Build führt weiterhin Android-ARM64-Werkzeuge und Runtime-Smokes aus.
   Die Montage muss für einen üblichen F-Droid-Linux-x86_64-Builder weiter
   aufgeteilt bzw. portiert werden.
2. Die gemeinsame libc++-Laufzeit, Node, Python, deren Bibliotheken und das
   eingecheckte ripgrep-ELF stammen
   weiterhin aus vorgebauten Termux-Eingaben. Für einen vollständig aus Quellen
   erzeugten F-Droid-Build sind auch hierfür nachvollziehbare Quellrezepte und
   vollständige Lizenz-/Quellnachweise erforderlich.
3. Die bestehende Buildumgebung installiert Host-Pakete über APT. Der Fork pinnt
   Compiler-Hauptversionen und Android-Toolchains, aber noch nicht das gesamte
   Host-System, alle Hook-Downloads und jedes Buildwerkzeug auf einen
   unveränderlichen Stand.
4. Bestehende Notice-Dokumente und der mitgelieferte Notice-Text behalten auf
   ausdrücklichen Wunsch ihren bisherigen Inhalt und historische Artefakthashes.
   Die aktuelle Quellbuild-Provenienz ist in den zusätzlichen JSON-Dateien
   enthalten. Eine spätere Veröffentlichung benötigt einen konsistenten
   rechtlichen Gesamtstand.
5. Reale Geräteprüfungen von Runtime, Sandbox, Editor und Installation sowie
   ein unabhängiger vollständiger Vergleich bleiben Teil der Release-Prüfung.
