# Roadmap: AGENTCODI Package Edition

Stand: 2026-10-05. Ausschließlich Branch `Mcpasi/package-edition`; kein Merge nach `main`.

## Ziel und feste Entscheidungen

Nutzer installieren eigene Pakete, die Codex und das Terminal direkt verwenden können. Diese zweite Entwicklungslinie nutzt `targetSdk 28`, bietet ausschließlich Full access und richtet sich an erfahrene Nutzer. Androids Isolation zwischen Apps bleibt bestehen; eine zusätzliche Workspace-Sandbox wird hier nicht angeboten.

Ein Target-SDK-Wechsel allein liefert weder einen Paketmanager noch eine passende Paketquelle. Programme benötigen Android ARM64/Bionic und den richtigen Installationspräfix. Die am 2026-10-03 vom Nutzer gewählte Paketarchitektur — eigener Präfix, minimaler Bootstrap und eigenes signiertes Repository aus Termux-Paketrezepten — ist in Abschnitt 3 festgehalten.

## 1. Grundlage

- [x] Separaten Branch vom Main-Stand `ff27ec7c30d373a864e845e9a7ceeae3380dd103` anlegen.
- [x] Target SDK in Manifest, Build-Skript und BuildIdentity auf 28 setzen; Minimum SDK 29 beibehalten.
- [x] App-Modus auf Full access beschränken, einschließlich Dienst-Neustart und alter Launch-Intents.
- [x] Geschützte Modusauswahl und JIT-Schalter aus den Einstellungen entfernen.
- [x] Deutsche und englische Texte, dauerhafte Chat-Kennzeichnung und README-Warnung aktualisieren.
- [x] Beschreibbaren Übergangspräfix `$HOME/.local` mit `bin/lib/include/share/etc/tmp` anlegen. Die verwaltete Paketbasis wechselt später gemäß Abschnitt 3 auf ein separates `files/usr`.
- [x] Präfix-Binaries und Bibliotheken für App-Server und Codex-Kommandos vor die Übergangswerkzeuge setzen.
- [x] Shell-Funktionen entfernen, die selbst installierte Programme gleichen Namens überschreiben.
- [x] Regressionstests für beständige Installationen, Modusvertrag, SDK-Pins und tatsächliche Ausführung eigener Programme ergänzen.
- [x] Android-Quellen und Ressourcen zusätzlich in GitHub Actions gegen API 35 kompilieren; Manifest-Target 28 und Mindestniveau 29 prüfen.
- [ ] Android-Gerätetest: Programm aus dem beschreibbaren Präfix starten, denselben Befehl durch Codex ausführen, Dienst/Prozess neu starten und erneut prüfen.
- [x] Getrennte Application-ID, Versionslinie und APK-Namen für parallele Installation festlegen und durchgängig umsetzen: `de.agentcodi.pkg`, eigene Linie `0.1.0-package.1` ab `versionCode 1`, APK-Dateien `AGENTCODI-Package-*` und CI-Artefakt `agentcodi-package-debug-apk`. Der Anzeigename lautet AGENTCODI Package; der Java-Namespace bleibt `de.agentcodi.app`.

Die Installationsidentität ist unabhängig vom Java-Namespace. Alle Manifest-Komponenten verwenden vollständige Klassennamen; AAPT2 erzeugt Ressourcen weiter unter `de.agentcodi.app`. Die Android-CI vergleicht die Installations-/Versionsangaben in Manifest, Build-Skript, BuildIdentity und verknüpften Ressourcen und prüft, dass jede Manifest-Komponente als Java-Klasse existiert. Ein echter Installations-/Parallelbetriebtest bleibt Teil der offenen Android-Gerätetests. Bisherige Daten der gemeinsamen ID werden nicht automatisch übernommen; Export/Import ist in der README beschrieben.

Die Edition verwendet ausschließlich `:danger-full-access`; das Protected-Modul, aktive Protected-Verträge und JIT-Auswahl sind entfernt. Verbliebene alte boolesche Übergabeparameter sind stets false oder weisen true ausdrücklich zurück. Alte Launch-Intents werden auf Full access migriert. Die historische interne Mode-ID `compatibility` bleibt zur Kompatibilität mit bestehenden Sitzungsdaten erhalten.

## 2. Community-App-Server anbinden

Der eigene Fork basiert laut GitHub auf `DioNanos/codex-termux`. Geprüftes Community-Release vom 2026-09-24:

- Repository: https://github.com/DioNanos/codex-termux
- Release: `v0.156.1-termux.1`, Upstream `rust-v0.156.1`
- Quellcommit des Release-Tags: `ea762071ec4acbf1531fcc7daf47524836f70a09`
- Archiv: `mmmbuto-codex-cli-termux-0.156.1-termux.1.tgz`
- SHA-256 laut GitHub-Release-Asset-Digest: `44cee2f3a4a110fd79d4f7d61378d46fd72406f45cffb3163e809d63e86d946a`

Diese Angaben sind die aktive, vollständig angepinnte Community-Runtime. Archiv, Quellcommit, ELF-Dateien, APK-Relokation und erzeugte Schemas werden in GitHub Actions geprüft.

- [x] Release-Archiv in CI herunterladen, Prüfsumme verifizieren und Inhalt einschließlich Code-mode-Host, Lizenzen und Abhängigkeiten untersuchen.
- [x] Quellcommit und vollständige Binär-/Schema-Prüfsummen erfassen; keine alten Hashes oder Binäroffsets wiederverwenden.
- [x] `scripts/update-codex-runtime.sh`, CodexRuntimeUpdater/Metadata/LocalSource, BuildIdentity, Build-Input-Liste und Notices auf den Community-Kanal umstellen.
- [x] App-Server-JSON-Schema gegen alle verwendeten RPCs prüfen: Initialize, Login, Models, Permission Profiles, Thread/Turn, Approvals, Terminal-PTY, MCP und Connectors.
- [x] Anpassungen für umbenannte Felder, Fähigkeiten oder Methoden in Client/SessionController umsetzen und mit realem App-Server prüfen.
- [x] Code-mode-Host-Auflösung prüfen. Falls weiterhin eine APK-Bibliothek umbenannt wird, den neuen Offset am neuen Artefakt bestimmen.
- [x] Native Startargumente auf Full access reduzieren und das unbenutzte `agentcodi-workspace`-Profil entfernen.
- [x] Alte Protected-/JIT-Verträge, Module, Ressourcen und Tests gezielt ablösen; übrige Regressionen behalten.
- [x] CI-Sandbox-Sonderoptionen und seccomp/ptrace-/Protected-Smokes im Edition-Build durch Full-access-Smokes ersetzen.
- [x] Keine Runtime-Aktualisierung darf wieder den Mcpasi-Sandbox-Fork auswählen.

### Ergebnis der Community-Archivprüfung — 2026-10-03

Umgesetzt mit `.github/ci/community-codex-release.json`, `inspect-community-codex.py` und dem zusätzlichen Tests-Job `Community Codex release inspection`, ausschließlich auf diesem Branch. Der erfolgreiche [CI-Lauf 37158009969](https://github.com/Mcpasi/AGENTCODI/actions/runs/37158009969) für Commit `5a89b6a3a4e2871b3952d10b2201215527e4ea10` prüft den Release-Asset-Digest, den aufgelösten Tag-Quellcommit und die tatsächlich heruntergeladenen Archivbytes. Das [Prüfartefakt](https://github.com/Mcpasi/AGENTCODI/actions/runs/37158009969/artifacts/11286760213) enthält das vollständige Dateiinventar mit SHA-256, ELF-Berichte, Metadaten, Launcher-Befunde sowie LICENSE/NOTICE.

- Das Archiv enthält 12 reguläre Dateien, darunter `codex.bin`, den separaten `codex-code-mode-host` und `libc++_shared.so`. Der Hostname ist im Codex-Binary vorhanden; seine tatsächliche Auflösung beziehungsweise ein APK-Relokationsoffset ist damit noch nicht bestätigt.
- Beide ausführbaren ELF-Dateien sind ARM64/ELF64 mit Interpreter `/system/bin/linker64` und RUNPATH `$ORIGIN:$ORIGIN`. Die zwei identischen Einträge sind gleichwertig zum selben Bibliotheksordner; fremde oder leere Suchpfade werden abgelehnt.
- `codex.bin` benötigt dynamisch `libdl.so/libm.so/libc.so`, der Code-mode-Host zusätzlich `liblog.so`. Die mitgelieferte `libc++_shared.so` benötigt `libc.so/libm.so/libdl.so`; die beiden Programme haben in diesem Release keinen direkten DT_NEEDED-Eintrag für libc++. Alle ermittelten dynamischen Abhängigkeiten sind Android-Systembibliotheken.
- Keine npm-Paketabhängigkeiten sind deklariert. Die JavaScript-Launcher deklarieren Node.js `>=18.0.0`; das Postinstall-Skript passt Shebangs anhand des laufenden Node-Interpreters an. Shell- und JavaScript-Launcher enthalten weiterhin den Termux-Standardpräfix `/data/data/com.termux/files/usr`. Sie werden bei der späteren Integration an die Editions-Umgebung angepasst.
- Paketlizenz und LICENSE sind Apache-2.0; NOTICE nennt OpenAI, Davide A. Guglielmi und Ratatui/MIT. Separate Lizenztexte für libc++ und statisch eingebundene Rust-/V8-Abhängigkeiten sind im Archiv nicht enthalten. Ihre vollständigen Notices bleiben vor der späteren Auslieferung zu prüfen.
- Das archivierte README nennt veraltet `rust-v0.155.0`; Release und Paketbeschreibung nennen `rust-v0.156.1`. Die Prüfung dokumentiert diese Abweichung und verwendet die gepinnten Release-/Quellangaben.

Neu ermittelte ELF-Prüfsummen, ausschließlich für dieses unveränderte Release-Archiv:

| Datei | SHA-256 |
| --- | --- |
| `codex.bin` | `6cbfa7f1660095e9cf2df7de242014579a0fb0d42545652fb0e22d1b6c8571a5` |
| `codex-code-mode-host` | `8afb196579c3fd8ecac558dbebfcba5467f91389b3754e485728ce6904e6ceaf` |
| `libc++_shared.so` | `430f7cde7c1a88042bb9e39ae1c1f4b9f5819f3bd95acd1453d2e02c63ba1870` |

Architekturchecks, 309 Java-Tests, alle 8 portablen C++-Suites, Android-Quellen/Ressourcen gegen API 35 und die Release-Prüfung einschließlich 8 neuer Archiv-/Suchpfadtests sind erfolgreich. Die anfänglichen CI-Ursachen (anonymes GitHub-API-Ratenlimit und zu strenger Vergleich der doppelten ORIGIN-Einträge) sind behoben.

Kein npm-Install/Postinstall und kein Community-ELF wurden ausgeführt; diese statische Prüfung verändert keine APK-Runtime. Schema-Erzeugung und -Prüfsummen, RPC-/Startkompatibilität, Host-Relokation und der aktive Kanalwechsel bleiben die nächsten offenen Schritte. Geräte-Tests bleiben offen und wurden gemäß Nutzeranweisung übersprungen.

### Abschluss der Community-Anbindung — 2026-10-04

Der aktive Build verwendet `0.156.1-termux.1`; das ELF meldet die Upstream-Version
`codex-cli 0.156.1`. Die Versionsprüfung berücksichtigt diese Unterscheidung.
Updater, Metadatenprüfung und Quellprüfung akzeptieren den DioNanos-Kanal,
verlangen den passenden Release-Tag und lehnen `-agentcodi` sowie eine fremde
Source-Remote ab. Die 33 Download-Einträge im Build-Input-Manifest werden aus
dem Build-Skript erzeugt; veraltete lokale Sandbox-/Schema-Inputs sind entfernt.
LICENSE und NOTICE des Community-Archivs werden unverändert ins APK übernommen;
historische Credits sind klar als historische Angaben erhalten.

| Pin | Ermittelter Wert |
| --- | --- |
| Community-Quellcommit | `ea762071ec4acbf1531fcc7daf47524836f70a09` |
| OpenAI-Upstream-Commit | `b412ff32c417f855c2b2d1581b77058eed87c84b` |
| Unverändertes App-Server-ELF SHA-256 | `6cbfa7f1660095e9cf2df7de242014579a0fb0d42545652fb0e22d1b6c8571a5` |
| Unveränderter Code-mode-Host SHA-256 | `8afb196579c3fd8ecac558dbebfcba5467f91389b3754e485728ce6904e6ceaf` |
| APK-App-Server-ELF SHA-256 | `cf1b406252928b0d68cb0f8f81adde6a02bf357a7fffb762d10cb503a235be06` |
| Gesamtes App-Server-Schema SHA-256 | `eb1ba91bd0fab656523092f6ed7de3ea7aef278921a650f14dc871ae7dcfaf84` |
| v2-Schema SHA-256 | `995fc3b8f8c469f6787e8fc5be4038c4f31359025edd8480b862e83355f3bf3b` |
| Host-Namensfeld im neuen ELF | Byteoffset `10568364`; `codex-code-mode-host` → `libcodex-codehost.so` |

Der neue Offset stammt aus dem eindeutigen Install-context-Datenfeld des
Community-Artefakts. Die Änderung erhält die Binärlänge und ändert genau diesen
Eintrag; weitere Host-Namensreferenzen bleiben unverändert. Vollständige
ELF-Prüfsummen sichern das Ergebnis ab.

Die ARM64/Bionic-CI erzeugt die Schemas mit dem tatsächlichen ELF, validiert
378 vom Java-Client gesendete Test-RPCs und prüft zusätzlich Login-, Approval-
und User-input-Formate. Verwendete Methoden für Initialize, Models, Permission
Profiles, Thread/Turn, Terminal, MCP und Connectors sind im Schema vorhanden.
Der echte App-Server führt Full-access-Kommandos aus, liest und schreibt
synthetische Dateien außerhalb des Workspaces, verarbeitet PTY-Operationen und
MCP-/Connector-Abfragen. Import-Kontext wird mit dem kanonischen Dateipfad statt des sichtbaren Labels
in der tatsächlichen Modellanfrage nachgewiesen. Der native Bootstrap liest
Thread-IDs gezielt aus dem Thread-Objekt, unabhängig vom davor stehenden
`activePermissionProfile`.
Ein lokaler Responses-API-Dummy schließt einen Turn
durch den umbenannten Code-mode-Host ab; dessen JavaScript-Ergebnis wird im
Folgerequest geprüft. Nach dem Runtime-Neustart werden die Sitzung fortgesetzt
und ein selbst installiertes Programm erneut ausgeführt. Keine echten
OpenAI-Zugangsdaten oder externe Modellanfragen sind dafür erforderlich.

Native Startargumente enthalten weder JIT noch das alte Workspace-Profil.
Protected-/JIT-spezifische Module, UI-Ressourcen und Tests wurden gezielt durch
Full-access-Vertragsprüfungen ersetzt; die übrigen Regressionen bleiben erhalten.
Der Edition-APK-Build benötigt keine besonderen seccomp-/ptrace-/AppArmor-Optionen
und prüft Full access statt der alten Workspace-Isolation.

Die Root-README ist vollständig Englisch. Abschnitt 3 und 4 sowie echte
Android-Gerätetests bleiben offen; Gerätetests wurden auf ausdrücklichen
Nutzerwunsch nicht ausgeführt.

## 3. Paket-Bootstrap und Workspace vervollständigen

### Beschlossene Paketarchitektur — 2026-10-03

Der Nutzer hat diese Lösung ausdrücklich gewählt. Sie ist die Grundlage für die spätere Umsetzung; die Architekturentscheidung muss nicht erneut erfragt werden. In diesem Schritt wird nur die Entscheidung dokumentiert, noch kein Bootstrap oder Paketrepository implementiert.

- **Termux-Paketrezepte wiederverwenden:** Die benötigten Pakete einschließlich ihrer Abhängigkeiten aus [termux/termux-packages](https://github.com/termux/termux-packages) für Android ARM64/Bionic, die eigene AGENTCODI-Application-ID und den eigenen Installationspräfix neu bauen. Die Termux-App selbst wird weder eingebunden noch als vollständige App-Version gepinnt.
- **Eigener Präfix außerhalb des Benutzer-Homes:** Für die Paketbasis `files/usr` verwenden, beispielsweise `/data/data/de.agentcodi.pkg/files/usr`. Die separate Application-ID ist endgültig auf `de.agentcodi.pkg` festgelegt und in Manifest sowie Build-Konfiguration umgesetzt. Benutzer-Home, Workspace und `CODEX_HOME` bleiben separate Verzeichnisse. Der vorhandene Präfix `$HOME/.local` ist eine Übergangslösung und wird für die verwaltete Paketbasis abgelöst.
- **Minimaler Bootstrap:** Nur Shell, APT, dpkg, Zertifikate und die dazu notwendigen Abhängigkeiten als Anfangsbasis bereitstellen. AGENTCODI übernimmt Installation und Initialisierung dieses Bootstraps.
- **Eigenes signiertes Paketrepository:** Eine eigene CI baut die angebotenen Pakete aus den Termux-Rezepten. Das Repository liefert Installation, Aktualisierung und Abhängigkeitsauflösung; ein schlanker `pkg`-Befehl kann APT bedienen, etwa `pkg install python`, `pkg install nodejs` oder `pkg install git`.
- **Gezielte reproduzierbare Pins:** Stand der Paketrezepte, Build-Toolchain, Paketversionen und Artefaktprüfsummen festhalten. Aktualisierungen bewusst testen und veröffentlichen. Ein kontrollierter Satz an Build-Anpassungen reicht; die komplette Termux-App muss dafür nicht übernommen werden.
- **Einheitliche Laufzeit:** Codex-Kommandos, Terminal, App-Server und lokale stdio-MCP-Prozesse verwenden dieselbe Paketinstallation und abgestimmte `PATH/PREFIX/LD_LIBRARY_PATH/HOME/TMPDIR`-Werte.

Die offiziellen Termux-DEBs sind häufig für `/data/data/com.termux/files/usr` gebaut. Sie werden nicht allgemein durch Entpacken, Ändern von `PATH` oder Setzen von `apt --root` kompatibel. Die reguläre Paketquelle dieser Edition enthält deshalb eigene Builds für den festgelegten Präfix; offizielle Termux-Binärrepositories werden nicht als austauschbare Quelle beigemischt.

Der angebotene Paketkatalog umfasst nur Pakete, die für diese Edition samt Abhängigkeiten gebaut und geprüft wurden. Das eigene Repository benötigt laufende Pflege und Sicherheitsupdates. Pakete mit Abhängigkeiten von Termux-App-Komponenten oder Termux:API sind gesondert anzupassen, bevor sie angeboten werden.

PRoot mit virtuellen Termux-Pfaden und eine allgemeine nachträgliche Relokation fertiger DEBs sind nicht der gewählte Ansatz. Die Edition soll ihre Pakete direkt und nativ unter dem eigenen Präfix ausführen.

Das Termux-Buildsystem dokumentiert anpassbare App- und Präfixvariablen in [scripts/properties.sh](https://github.com/termux/termux-packages/blob/master/scripts/properties.sh) und unterstützt eigene Bootstrap-Builds über [scripts/build-bootstraps.sh](https://github.com/termux/termux-packages/blob/master/scripts/build-bootstraps.sh). Bei der Umsetzung müssen die Werte in der Build-Konfiguration konsistent gesetzt werden; ein bloßer Laufzeit-Export ersetzt den Neubau nicht.

### Umsetzungsschritte

- [x] Architekturentscheidung des Nutzers dokumentieren: eigener Präfix, minimaler Bootstrap und signiertes Repository aus neu gebauten Termux-Paketrezepten.
- [x] Separate Application-ID endgültig festlegen und in der App umsetzen, bevor Pakete mit absoluten Pfaden gebaut werden: `de.agentcodi.pkg`; zukünftiger verwalteter Präfix `/data/data/de.agentcodi.pkg/files/usr`.
- [x] Verwalteten Präfix auf `files/usr` außerhalb des Benutzer-Homes umstellen. Bestehende Dateien in `$HOME/.local` erhalten; Übergang/Migration und Suchreihenfolge dokumentieren und testen.
- [x] Reproduzierbaren Stand von `termux-packages` und Toolchain festlegen; gezielte Build-Anpassungen für App-ID, Präfix und Repository-URLs versionieren. Bootstrap, Paketmetadaten, Shebangs, RPATH/RUNPATH und Konfigurationen auf denselben finalen Pfad ausrichten.
- [x] Minimalen ARM64-Bootstrap mit Shell, APT, dpkg, Zertifikaten und Abhängigkeiten bauen; Initialisierung sowie Reparatur nach abgebrochener Installation integrieren.
- [x] Eigene CI für Paket- und Abhängigkeitsbuilds aufsetzen; zuerst Bootstrap und einen kleinen Katalog wie Python, Node.js/npm, Git und ripgrep prüfen, danach erweitern.
- [x] Eigenes signiertes APT-Repository mit Vertrauensschlüssel, HTTPS, Veröffentlichungsablauf und Aktualisierungsstrategie einrichten.
- [x] Schlanken `pkg`-Befehl beziehungsweise dokumentierte APT-Bedienung für Installation, Aktualisierung und Entfernung bereitstellen.
- [ ] Gemeinsame Umgebungsdefinition für App-Server, Codex-Kommandos, Terminal und lokale stdio-MCP-Prozesse prüfen; `PATH/PREFIX/LD_LIBRARY_PATH/HOME/TMPDIR` dürfen nicht auseinanderlaufen.
- [ ] npm-Global-Prefix/-Cache sowie Python-User-/venv-/pip-Pfade nutzbar machen. Die bisherigen Wrapper erzwingen noch eigene Pfade und deaktivieren Python-User-Site.
- [ ] Installation, Aktualisierung, Entfernung und Status im Terminal dokumentieren; Pakete über App-Neustart und APK-Update erhalten.
- [ ] Paketpfade und installierte Versionen bei Bedarf in Diagnose/Terminal anzeigen; bisherige Aktivierungsanzeigen ersetzen.
- [ ] Workspace-Browser und Import/Export sinnvoll erweitern, wenn Paketdateien dort zugänglich sein sollen. Kontodaten sollen weiterhin nicht versehentlich exportiert werden.
- [ ] Android 10 und eine aktuelle Android-Version auf echter ARM64-Hardware prüfen: ELF, Skript/Shebang, dynamische Bibliothek, npm/pip, PTY und stdio-MCP.

### Verwalteter Präfix — 2026-10-04

Die App legt die Paketbasis jetzt als `usr` direkt unter ihrem kanonischen
Files-Verzeichnis an und übergibt sie ausdrücklich über Java/JNI an den nativen
Supervisor. `HOME`, Workspace, `CODEX_HOME` und temporäre Dateien bleiben getrennt.
Die gemeinsame Suchreihenfolge lautet `files/usr/bin`, `$HOME/.local/bin`,
APK-Aliase, Android-Systempfade; Bibliotheken werden aus `files/usr/lib`,
`$HOME/.local/lib` und den nativen APK-Bibliotheken gesucht. App-Server und
Codex-Shell-Konfiguration verwenden dieselbe Pfadfunktion; Terminal und
geerbte stdio-MCP-Umgebung erhalten denselben Vertrag.

Bestehende Dateien unter `$HOME/.local` bleiben unverändert erhalten. Es gibt
keine automatische Verschiebung oder Relokation; Pakete mit eingebetteten
absoluten Pfaden müssen gezielt für den neuen Präfix neu installiert werden.
README und Regressionen decken Bestandserhaltung, Suchvorrang, Legacy-Fallback,
Prozess-Neustart und Ablehnung ungeeigneter Präfixverzeichnisse ab. Der echte
ARM64/Bionic-APK-Smoke prüft den Vertrag über Codex- und Terminal-Shell.
Bootstrap, Startkatalog-CI und das öffentliche signierte Paketrepository sind unten dokumentiert.
Gerätetests bleiben gemäß Nutzeranweisung offen und werden übersprungen.

### Reproduzierbarer Paket-Buildvertrag — 2026-10-04

Umgesetzt mit `scripts/package-edition/lock.json`, dem kleinen versionierten
`overlay.json`, `prepare.py` und `verify-prefix.py`. Der Rezeptstand ist
`termux/termux-packages@b6af76b353140fe17f299248fca1ac13ea91c5c5`
(Git-Tree `34c914ef107a5552e9c850299be67050cbabe4eb`). Der amd64-Buildcontainer
ist per Digest `sha256:1db92723f6a82fd3ba45288d68ff99dbdeb08a3e9f3f0c4750115178cc7a6879`
aus dem erfolgreichen [Upstream-Build](https://github.com/termux/termux-packages/actions/runs/35882400849/job/107253886726)
gepinnt. NDK r30, SDK 9123335 samt Archiv-SHA-256, Build-Tools 37.0.0,
Host-LLVM 21 sowie ARM64/Bionic/API 29 sind festgelegt. Die festen Containerbytes
pinnen auch die Host-Abhängigkeiten; dort wird kein Paket-Upgrade ausgeführt.
API 29 ist das native Mindestniveau; das Manifest bleibt bei Target SDK 28.

Die Vorbereitung verlangt einen sauberen Checkout, prüft Commit, Tree,
einzelne Blob-IDs und eindeutige Änderungskontexte und schreibt ausschließlich
eine separate Rezeptkopie. App-ID und Home werden vor der Ableitung der
Upstream-Pfade eingestellt: Paketbasis `/data/data/de.agentcodi.pkg/files/usr`,
Home `files/agentcodi/home`. Patchersetzung, Bootstrap-Pfadvorlagen,
Shebang-Massage, Linker-RUNPATH und Debian-Metadaten verwenden dieselbe
Paketbasis. Rekursive Builds erhalten dieselben Pins und
`SOURCE_DATE_EPOCH=1791110575`. Fremde ABIs, glibc und heruntergeladene
Binärabhängigkeiten einschließlich automatischer zyklischer Seeds werden
abgelehnt; solche Zyklen benötigen später explizit geprüfte Editions-Seeds.

`repo.json` und das APT-Rezept verwenden ausschließlich die geplante
HTTPS-Quelle `https://mcpasi.github.io/AGENTCODI/apt/package-edition`
mit `stable main` und `signed-by=$PREFIX/etc/apt/keyrings/agentcodi-package.gpg`.
Der upstream Termux-Keyring wird nicht eingebunden. Veröffentlichung und
Vertrauensschlüssel sind weiterhin der offene Repository-Schritt; bis dahin
ist die Quelle nicht benutzbar. Es wurde kein Repository veröffentlicht.

Der neue Tests-Job „Package recipe and toolchain contracts“ prüft die
Vorbereitung zweimal, echte Upstream-Pfadableitungen, Bootstrap-Vorlagen,
Patch-/Shebang-Ersetzung, APT-Konfiguration und Ablehnungsfälle.
Er baut im gepinnten Container mit der tatsächlichen NDK ein ARM64-Test-ELF
und eine Bibliothek, verwendet den echten Debian-Metadaten-/Archiv-Hook,
vergleicht zwei DEB-Erzeugungen und prüft Interpreter, RUNPATH, Payload-,
Symlink-, Skript- und Metadatenpfade. Das Artefakt enthält Compiler-/
Host-Inventar, Vorbereitung, ELF-Berichte, Paketmetadaten, Test-DEB und SHA-256.
Die FUSE-/Sysroot-Einrichtung und die echten Bootstrap-Pakete werden erst
beim folgenden vollständigen Build geprüft.

Die Startpakete für den folgenden Bootstrap sind Dash, APT, dpkg und
Zertifikate samt Abhängigkeiten. Das Test-DEB ist kein auslieferbarer
Bootstrap; AGENTCODI-Initialisierung/Reparatur, echte Paketbuilds und
signierte Veröffentlichung bleiben offen. Termux-App-/API-/Exec-/Tools-
Komponenten erfordern eigene Anpassungen und werden vorerst abgelehnt.
Die [Build-Dokumentation](scripts/package-edition/README.md) beschreibt Pins,
Anpassungen, Prüfungen und den Aktualisierungsablauf. Gerätetests werden
gemäß Nutzeranweisung übersprungen.

### Minimaler ARM64-Bootstrap und Wiederherstellung — 2026-10-04

Der Editions-Bootstrap wird aus den gepinnten Termux-Rezepten vollständig für
ARM64/Bionic/API 29 und `/data/data/de.agentcodi.pkg/files/usr` gebaut.
Dash stellt `sh` bereit; Bash wird für Paket-Konfigurationsskripte mitgeliefert.
APT, dpkg, CA-Zertifikate und ihre Laufzeitabhängigkeiten ergeben 47 Pakete.
Build-Abhängigkeiten werden ebenfalls aus Quellen gebaut und anschließend nicht
in den Laufzeit-Bootstrap übernommen. Die libc++-Compilerlaufzeit stammt wie
im gepinnten Rezept vorgesehen aus der festgelegten NDK. Termux-App-/API-/Exec-/Tools-/Keyring-
Komponenten und fremde Binärrepositories bleiben ausgeschlossen.

`assemble-bootstrap.py` prüft Depends/Pre-Depends samt Versionen, Skript-
Interpreter und ELF-Abhängigkeiten. Paketpfade, Shebangs, RUNPATH, Symlinks,
Konfigurationsdateien und ARM64/Bionic werden vor dem Packen geprüft.
Die dpkg-Datenbank enthält vollständige Dateilisten einschließlich gemeinsam
registrierter Elternverzeichnisse, Prüfsummen, Konfigurationsdatei-MD5
und den Zustand „unpacked“. Die gemeinsamen Verzeichniseinträge verhindern
Entfernungsversuche geschützter Eltern beim Deinstallieren späterer Pakete;
Änderungen an Konfigurationsdateien können damit
bei späteren Paketupdates erkannt werden. ZIP-Reihenfolge und Zeitstempel
sind fest. Das Artefakt enthält die ausgewählten DEBs, ZIP, Größen-/SHA-256-
Manifest, Versions-/ELF-Bericht, SHA256SUMS und das zugehörige Quellenarchiv
mit Rezepten, Patches und Editions-Buildskripten.

Das APK enthält ZIP, Manifest und Bericht als Assets. `PackageBootstrap`
installiert vor dem App-Server-Start in ein privates Staging-Verzeichnis und
prüft jede Datei gegen Größe, SHA-256 und Dateimodus. Bestehende nicht
kollidierende Präfixdateien bleiben erhalten; Kollisionen werden gemeldet.
Veröffentlichung erfolgt über atomare Umbenennungen mit Backup.
Ein Abbruch beim Entpacken wird beim nächsten Start erneut versucht; ein
Abbruch zwischen Umbenennungen stellt zuerst den bisherigen Präfix wieder her.
Der native Initialisierungsschritt führt `dpkg --configure -a` mit expliziter
Paketumgebung, Zeitlimit und privatem Log aus. Erst nach Erfolg entsteht der
Ready-Marker. Fehler oder Abbruch lassen die Konfiguration beim nächsten Start
fortsetzen. Log: `files/agentcodi/logs/package-bootstrap.log`.

Ein bereits initialisierter Präfix wird bei App-Neustart und APK-Update nicht
erneut entpackt. Installierte Pakete, APT-/dpkg-Zustand und Benutzeränderungen
bleiben erhalten; HOME, CODEX_HOME und der alte HOME/.local-Präfix ebenso.
Eine Bootstrap-Version wird nicht über eine bestehende Installation kopiert;
spätere Aktualisierungen erfolgen über den noch einzurichtenden signierten
Paketkanal.

Die CI-Ursachen wurden behoben: unbenötigte APT-Dokumentations-/Archiver-
Buildabhängigkeiten, der nicht mehr unterstützte GnuPG-gpgv-only-Schalter,
verbliebene Perl-Helfer, fehlende dpkg-Prüfsummen sowie der Laufzeitvertrag
des Testcontainers. libandroid-selinux behält die Editions-CFLAGS in seinem
Makefile und validiert den Quellcommit
`1cbcdf624c248c66cd6153311d3e681ba1f9ff2a`; das Quellenarchiv enthält
seinen sauberen Git-Snapshot. Die Paket-Toolchain nutzt einheitlich `-femulated-tls`:
Das gepinnte Bionic-CI-Image basiert auf Android 9 und unterstützt die ab
Compile-API 29 standardmäßig erzeugten nativen TLS-Relokationen nicht.
API 29 bleibt das Mindestniveau. Der CI-Harness lädt zusätzlich eine ausschließlich
für Tests gebaute reallocarray-Bibliothek für das API-28-Referenzimage vor.
Sie entspricht der überlaufgeprüften API-29-Implementierung aus AOSP-Commit
`290c0cb5044b643e5d6cbcb1a5b275541ca3a89e` und wird auf ARM64/Bionic
auf Allokation, Vergrößerung, Bestandserhaltung und ENOMEM bei Überlauf geprüft.
Bibliothek und Quellen liegen in einem separaten CI-Artefakt und werden nicht
im Bootstrap oder APK installiert; Android 10+ stellt die Funktion selbst bereit. Ein echter NDK-Thread-local-Test und die
Ablehnung nativer AArch64-TLS-Relokationen sichern den Vertrag ab.

Der erfolgreiche [Quellbuild](https://github.com/Mcpasi/AGENTCODI/actions/runs/37215181811/job/111474115967)
liefert die quellgebauten DEBs und das ursprüngliche Quellenarchiv. Die aktuelle
Assembly liefert das [Bootstrap- und Quellenartefakt](https://github.com/Mcpasi/AGENTCODI/actions/runs/37219054636/artifacts/11309996366).
ZIP-SHA-256: `d875abf8f90fe7e70494ce8b268da826e575031275f75ca611e89ae64512041a`.
Manifest-SHA-256: `275ef6dad15d6cdcfa8a5e7765bc697b5923da1e7400f637bb022f44cd18eccd`.
Die nachfolgenden Builds dürfen diese DEBs nur bei identischen Paket-
Buildinputs und geprüftem Artefakt wiederverwenden; aktuelle Assembly- und
ELF-Prüfungen werden erneut ausgeführt.

Verifikation: [Tests-Lauf 37219054346](https://github.com/Mcpasi/AGENTCODI/actions/runs/37219054346)
und [APK-Lauf 37219054636](https://github.com/Mcpasi/AGENTCODI/actions/runs/37219054636)
für Implementierungscommit `070c37845e19931501d692ad0eb081da16296663` sind erfolgreich.
Der ARM64/Bionic-Smoke installiert die echte ZIP über den Java-Installer,
konfiguriert alle 47 Pakete mit Android-dpkg, startet Shell/APT/gpgv, prüft
die registrierte APT-Version über apt-cache policy, Zertifikate und Konfigurationsdatei-Metadaten und installiert, startet sowie
entfernt ein lokales Testpaket. Die 313 Java-Tests enthalten
Wiederherstellung nach Entpack-/Konfigurationsabbruch, Umbenennungsabbruch,
Integritäts-/Pfadfehler und Bestandserhaltung über APK-Updates.
15 Präfix-/TLS-Prüfungen und 9 Bootstrap-Assembly-Tests sind erfolgreich,
ebenso die acht portablen C++-Suites, Android-Kompilierung und Community-Runtime.
Der vollständige Debug-APK-Build prüft die eingebetteten Bootstrap-Assets,
native Kompilierung, Full-access-/PTY-/App-Server-Smokes sowie APK-Identität,
Signatur und Alignment. Das [Debug-APK-Artefakt](https://github.com/Mcpasi/AGENTCODI/actions/runs/37219054636/artifacts/11309621629)
enthält die geprüfte Package Edition (
`AGENTCODI-Package-0.1.0-package.1-arm64-v8a-debug.apk`, 169 MiB;
SHA-256 `3f395698a6100003854b5884efcb7b58288e9a9216f31f8abc46b1078c9ea987`).
Gerätetests wurden wie angeordnet übersprungen und bleiben offen.

Das signierte Online-Repository, der Vertrauensschlüssel, `pkg`, der größere
Paketkatalog und die APK-Verkleinerung bleiben die nächsten Roadmap-Schritte.
Die geplante HTTPS-Quelle kann ohne veröffentlichten Schlüssel noch nicht
authentifiziert werden; es gibt keinen unsicheren Fallback. Kein PR, Merge
oder Release wurde erstellt. `main` bleibt unverändert.

### Paket- und Abhängigkeits-CI mit Startkatalog — 2026-10-04

Die branchgebundene Workflow-Datei `.github/workflows/packages.yml` prüft
den normalen App-Bootstrap sowie vier getrennte Quellbuild-Gruppen:
Python 3.14.6, Node.js LTS 24.18.0 mit npm 11.20.0, Git 2.56.0 und
ripgrep 15.2.0. `catalog.json` ergänzt das Bootstrap-Overlay, ohne dessen
Paketrezepte zu ändern. Python ist ohne Tk ausgelegt; Git verzichtet auf
GUI und optionale Perl-/Python-Integrationen. npm verwendet einen festen
Git-Commit. Alle angebotenen Pakete und ihre Ziel-Buildabhängigkeiten
werden für ARM64/Bionic/API 29 unter dem Editionspräfix aus Quellen gebaut.

Jedes Katalogartefakt enthält die Laufzeit-DEBs, Paketversionen, Hashes,
Vorbereitung und zugehörige Quellen einschließlich Build-Abhängigkeiten.
`all-built-packages.json` dokumentiert die Prüfung aller erzeugten DEBs,
auch der ausschließlich beim Build benötigten Pakete. Abhängigkeitsversionen,
Dateikollisionen, ausführbare Skript-Interpreter, ELF-Bibliotheken und
Präfixpfade werden geprüft. Der ZIP im Katalogartefakt dient ausschließlich
als CI-Prüfabbild; das APK enthält weiterhin den normalen minimalen Bootstrap.

Erfolgreiche Quellbuild-Artefakte desselben Branches dürfen nur nach Prüfung
der Herkunft, des erfolgreichen Quelljobs, der Prüfsummen und unveränderter
relevanter Buildinputs wiederverwendet werden. Eine Änderung ausschließlich
am Git-Rezept kann eine andere Gruppe nur dann unbeeinflusst lassen, wenn
deren Paketbericht belegt, dass Git nicht gebaut wurde. Aktuelle Validatoren
und Quellenarchivierung laufen erneut; Herkunft und Verbraucher-Commit
stehen in `source-build.json`. Geänderte Buildinputs oder abgelaufene
Artefakte führen zu einem frischen Quellbuild. Ein manueller Lauf mit
`source_run_id=0` baut vollständig neu.

Die ARM64/Bionic-Laufzeitjobs installieren den echten App-Bootstrap über
den Java-Initializer. Anschließend installiert APT die lokal quellgebauten
Katalog-DEBs mit ihrer Abhängigkeitsauflösung. Geprüft werden Python mit
nativen Modulen, Node mit Crypto/ICU, npm/npx mit Offline-Pack-/Run-Schritten,
Git mit Commit/fsck und ripgrep mit PCRE2. Jede Gruppe wird danach entfernt
und über dieselben lokalen DEBs erneut installiert; Paketstatus und
Funktionsnachweise werden als Artefakte gespeichert. Onlinequellen und
unsichere APT-Ausnahmen werden dafür nicht benötigt.

Die aufgetretenen CI-Ursachen sind behoben: vollständiger Rezeptgraph trotz
nicht angebotener Subpakete, Quellenzuordnung synthetischer `-static`-Pakete,
Archivierung von DEB-Namen mit Doppelpunkten, kanonische RUNPATH-Unterpfade
innerhalb des eigenen `lib` sowie die Unterscheidung ausführbarer Skripte
von nicht ausführbaren Bibliotheksvorlagen. Git liefert keine Hook-Vorlage
mit fehlendem Perl-Interpreter und kein Python-abhängiges `git-p4` mehr aus.
Der API-28-Testcontainer erhält zusätzlich `getloadavg` aus der
AOSP-API-29-Implementierung; Grenzen und Ergebnisse werden vor den Smokes
geprüft. Diese Kompatibilitätsbibliothek bleibt ausschließlich ein CI-Artefakt
und wird weder im Bootstrap noch im APK installiert. Das native
Mindestniveau bleibt API 29.

Verifikation des CI-Implementierungscommits
`be4219642e31b1899191fea1bf9ae63bc4c30d2c`:
[Tests](https://github.com/Mcpasi/AGENTCODI/actions/runs/37241404970)
mit allen sieben Jobs und
[Paketkatalog mit ARM64/Bionic-Laufzeitprüfungen](https://github.com/Mcpasi/AGENTCODI/actions/runs/37241405136)
mit allen zehn Jobs sind erfolgreich.
Der [vollständige APK-Build](https://github.com/Mcpasi/AGENTCODI/actions/runs/37238815700)
für `0309a3a32d858dd771f89bfed287833ba128befb` ist ebenfalls erfolgreich;
danach änderten sich ausschließlich Katalog-Workflow und Test-Harness.
Die abschließende Laufzeitkorrektur bildet das App-Cache-Verzeichnis für
APT im Container ab und verwendet vorhandene, leere Quellkonfigurationen. Die CI behauptet keine unabhängige bitweise
Reproduzierbarkeit aller Compiler-Ausgaben; Quellpins und Artefakthashes
machen Eingaben und Ergebnisse überprüfbar.
Gerätetests wurden gemäß Nutzeranweisung übersprungen und bleiben offen.
Das anschließend umgesetzte eigene signierte APT-Repository ist im folgenden Abschnitt dokumentiert.
Kein PR, Merge oder Release wurde erstellt; `main` bleibt unverändert.

### Signiertes Repository für den Startkatalog — 2026-10-05

Der Startkatalog mit Python, Node.js LTS/npm, Git und ripgrep ist zusammen
mit dem Bootstrap und seinen Laufzeitabhängigkeiten für
`https://mcpasi.github.io/AGENTCODI/apt/package-edition` als eigenes
ARM64-APT-Repository gebaut, signiert, geprüft und öffentlich veröffentlicht.
`repository.json` pinnt den
öffentlichen Primärschlüssel
`2768291D12B6C3D22CFBAF9EEC79CBDF7E93DC89`, sieben Tage Gültigkeit
und ein 950-MB-Budget. Die private Signierung erfolgt ausschließlich
über Actions-Secrets in einem temporären GnuPG-Verzeichnis.

Das lokale Paket `agentcodi-package-keyring` wird im Bootstrap installiert
und besitzt den auf diese Quelle begrenzten Schlüssel unter
`$PREFIX/etc/apt/keyrings/agentcodi-package.gpg`. Ein bereits initialisierter
Präfix ohne diesen Schlüssel erhält beim APK-Update ausschließlich die
manifestgeprüfte öffentliche Datei; Paketdaten und vorhandene Schlüssel
bleiben erhalten. Die nachträgliche dpkg-Zuordnung erfolgt über
`apt install agentcodi-package-keyring`. Die neuen Java-Regressionen
prüfen Bestandserhaltung sowie abgebrochene/beschädigte Schlüsselmigration.

Der branchgebundene Katalogworkflow baut nach seinen bisherigen Quell- und
Laufzeitprüfungen einen vollständig geprüften gemeinsamen Snapshot:
inhaltadressierter DEB-Pool, Packages/Packages.gz, by-hash, signiertes
Payload-/Herkunftsmanifest sowie Release, InRelease und Release.gpg.
Unabhängige Builds gemeinsamer Abhängigkeiten dürfen unterschiedliche
Installed-Size-Werte haben; alle anderen Laufzeitmetadaten müssen
übereinstimmen. Der gewählte DEB behält seine tatsächlichen Metadaten.
Präfix-, ELF-, Interpreter-, Abhängigkeits- und Kollisionsprüfungen laufen
auch über die gesamte kombinierte Paketmenge.

Die vollständigen Quellen einschließlich Buildabhängigkeiten werden über
gemeinsame komprimierte SHA-256-Objekte und gruppenbezogene Quellmanifeste
bereitgestellt. Dateien, Rechte, Zeiten und Links bleiben erhalten; die
Hashes der ursprünglichen Quellarchive stehen in der signierten Herkunft.
`download-sources.py` authentifiziert Release und sämtliche benötigten
Quellobjekte und stellt ein vollständiges Archiv wieder her. Regressionen
prüfen Archiv-Roundtrips sowie die Ablehnung beschädigter Quellen.
Damit entfällt die mehrfache Speicherung großer identischer Quellarchive,
die den ersten Snapshot auf 1,36 GB vergrößert hatte.

Host-APT prüft die Signaturen und lädt die Laufzeitpakete. Ein zusätzlicher
ARM64/Bionic-Job nutzt echtes Android-APT/dpkg über einen HTTPS-Testserver:
Installation, Entfernung und erneute Installation aller Kataloggruppen,
Versionsprüfung sowie signiertes Testpaket-Upgrade von 1.0 auf 2.0.
Ungültige Schlüssel, Signaturen, Index-/DEB-Prüfsummen und abgelaufene
Metadaten müssen abgelehnt werden. Nur nach erfolgreichen Prüfungen
veröffentlicht GitHub Pages den kompletten Snapshot. Anschließend werden
öffentliche HTTPS-Auslieferung, aktueller Run/Commit, Signaturen, by-hash,
Katalog-DEBs und alle referenzierten Quellobjekte geprüft.

Aktualisierungen bewahren den geprüften vorherigen Pool, Quellen und
Index-Hashes. Downgrades und veränderte veröffentlichte Paketbytes ohne
Versionsanhebung werden abgelehnt. Der manuelle Workflow mit
`repository_action=publish` erneuert den Snapshot vor Ablauf der sieben
Tage. Für eine reine Signaturerneuerung wird `source_run_id` auf den
geprüften Kataloglauf (aktuell `37336548937`) gesetzt, damit unveränderte
DEBs wiederverwendet werden; `source_run_id=0` baut ausdrücklich neu.
`repository_previous_run_id=0` ermittelt den letzten abgeschlossenen
Lauf mit erfolgreichem tatsächlichem Pages-Deployment, auch wenn danach
die öffentliche Endprüfung fehlgeschlagen ist. Schlüsselwechsel verwenden
überlappende öffentliche Vertrauensanker und eine angehobene Keyring-Version.

Die README beschreibt `apt update`, `apt install`, `apt upgrade`,
`apt remove` und `dpkg-query -W`. Ein zusätzlicher `pkg`-Wrapper
ist optional und wurde in diesem Schritt nicht ergänzt.

Verifikation des Implementierungscommits
`d9816fac268b1b113019a5cea1bfab5e46056c6a`:
[Tests](https://github.com/Mcpasi/AGENTCODI/actions/runs/37336548510)
mit allen sieben Jobs einschließlich 315 Java-Tests sind erfolgreich.
Der [Paketkatalog](https://github.com/Mcpasi/AGENTCODI/actions/runs/37336548937)
hat alle 14 Signier-, Quellbuild-, Bootstrap-, Laufzeit- und
Veröffentlichungsjobs bestanden.
Der signierte Snapshot umfasst 67 Pakete und vollständige Quellen in
648.375.829 Bytes; das
[Repository-Artefakt](https://github.com/Mcpasi/AGENTCODI/actions/runs/37336548937/artifacts/11357750277)
und der
[ARM64/Bionic-Prüfnachweis](https://github.com/Mcpasi/AGENTCODI/actions/runs/37336548937/artifacts/11357495578)
stehen bereit.
[APK-Build](https://github.com/Mcpasi/AGENTCODI/actions/runs/37336548822)
und [Debug-APK-Artefakt](https://github.com/Mcpasi/AGENTCODI/actions/runs/37336548822/artifacts/11356239632)
für `d9816fac268b1b113019a5cea1bfab5e46056c6a` sind erfolgreich.
Tests, Paket-/Repository-Workflow und APK-Lauf prüfen denselben
Implementierungscommit; die abschließende Roadmap-Änderung betrifft
nur die Dokumentation.

**Öffentliche Veröffentlichung abgeschlossen:** Der Nutzer hat
`Mcpasi/package-edition` für die Umgebung `github-pages` zugelassen.
Der wiederholte Veröffentlichungsjob konnte den Snapshot veröffentlichen;
seine ursprüngliche Endprüfung löste durch Tausende parallele Einzelabfragen
jedoch HTTP 503/429 bei Pages aus. Die Ursache ist behoben: kleine Quellen
werden zusätzlich in höchstens 16 signierten ZIP-Dateien angeboten. Die
CI lädt diese Dateien, prüft jedes enthaltene Quellobjekt gegen den signierten
Hash und fragt nur die übrigen großen Quellen einzeln ab. HTTP-Anfragen
sind auf zwei pro Sekunde begrenzt und transiente Antworten werden mit
Backoff behandelt. Vollständige Quellen und Einzel-URLs bleiben erhalten.

Auch ein tatsächlich veröffentlichter Snapshot mit anschließend
fehlgeschlagener Endprüfung wird als Vorgänger erkannt und nach erneuter
Signatur-/Hashprüfung bewahrt. Der korrigierte Kataloglauf
37336548937 ist vollständig erfolgreich.
Die öffentliche
[signierte Release-Datei](https://mcpasi.github.io/AGENTCODI/apt/package-edition/dists/stable/InRelease)
ist per HTTPS erreichbar. Die CI prüft beide gepinnten Release-Signaturen,
Gültigkeit, aktuellen Verbraucher-Commit/-Run, Index- und by-hash-Prüfsummen,
die angebotenen Katalog-DEBs sowie die vollständigen Quellen:
16 signierte ZIP-Dateien werden einschließlich jedes enthaltenen Objekts
verifiziert; weitere 219 große Quelldateien werden einzeln auf Erreichbarkeit
und signierte Größe geprüft. Der Startkatalog und das öffentliche signierte Repository
sind umgesetzt und abgehakt. Die dokumentierte APT-Bedienung ist ebenfalls
abgehakt; die nachfolgenden Paketpfad-/Umgebungsarbeiten bleiben getrennte
Roadmap-Schritte.

Gerätetests wurden wie angeordnet übersprungen und bleiben offen.
Kein PR, Merge oder Release wurde erstellt. Alle Zugriffe erfolgten über den
GitHub Connector; `main` bleibt auf
`ff27ec7c30d373a864e845e9a7ceeae3380dd103`.

## 4. Build verkleinern und veröffentlichbare Edition erstellen

Erst nach funktionierendem Bootstrap die bisher enthaltenen nutzerinstallierbaren Pakete entfernen.

- [ ] Bundled Node.js, npm, Python, ripgrep und nur von ihnen benötigte Bibliotheken/Archive/Lizenzen aus dem APK entfernen.
- [ ] Vorher Abhängigkeiten des App-Servers und Code-mode-Hosts auf diese Werkzeuge prüfen; zwingend notwendige Basiswerkzeuge im Bootstrap behalten.
- [ ] PackagedToolRuntime, Tool-Alias-/Activation-/ELF-Attestor-Code und Runtime-Startvalidierung an den Paket-Bootstrap anpassen.
- [ ] Build-Skript, Dockerfile, CI-Input-Manifest, Restore-/Preflight-Prüfungen und Cache-Schlüssel auf die minimalen Edition-Abhängigkeiten reduzieren.
- [ ] Eigene APK-Artefakte für diesen Branch erzeugen; keine regulären Main-Releases überschreiben.
- [ ] Architekturchecks und Java-/C++-/Android-Smokes auf den endgültigen Paketvertrag ausrichten.
- [ ] Notices, README, SECURITY und Build-Dokumentation mit der tatsächlich ausgelieferten Paketbasis abgleichen.
- [ ] Installations-/Update-Test inklusive niedrigem Target SDK, Foreground Service, Notifications, Login, Dateiauswahl und Backups durchführen.
- [ ] Finales APK auf Gerät testen und erst danach als Package Edition veröffentlichen.

## Bisherige Verifikation des Grundlagenabschnitts

Erfolgreicher [GitHub-Actions-Lauf](https://github.com/Mcpasi/AGENTCODI/actions/runs/37154718553) für Commit `801f44d82fe5aa4398d12395383a0806bb89ec41`:

- Architekturchecks erfolgreich.
- 309 Java-Tests erfolgreich.
- Alle 8 portablen C++-Testsuiten erfolgreich, einschließlich tatsächlicher Ausführung eines selbst installierten Programms über den Supervisor.
- Android-Java-Quellen und Ressourcen gegen API 35 erfolgreich kompiliert; Target SDK 28, Minimum SDK 29 und Editions-Anzeigename geprüft.

Zusätzlich deckt ein Terminal-Shell-Test den Vorrang selbst installierter Programme gegenüber früheren festen Shell-Funktionen ab.

Alle Repository-Zugriffe und Änderungen erfolgen ausschließlich über den GitHub Connector. Die Community-Anbindung aus Abschnitt 2, der minimale Paket-Bootstrap und die Startkatalog-CI aus Abschnitt 3 sind umgesetzt. Das signierte Paketrepository ist einschließlich öffentlicher HTTPS-Veröffentlichung und ARM64-/APT-Laufzeittests umgesetzt. Katalogerweiterung, verkleinerter Build und echte Gerätetests folgen in Abschnitt 3/4. Die ursprünglichen Verifikationsangaben oben beschreiben den vorausgehenden Grundlagenabschnitt.

## Verifikation der Community-Anbindung

Geprüfter Implementierungscommit: `1fed889377c980f66cdd6eabc0dba2dbcce0b9de`.

- [Tests-Lauf 37165001117](https://github.com/Mcpasi/AGENTCODI/actions/runs/37165001117): alle sechs Jobs erfolgreich — Architektur/Manifest, 303 Java-Tests, acht portable C++-Suites, Android-Kompilierung, Community-Archivprüfung und echter ARM64/Bionic-App-Server.
- [Runtime-Prüfartefakt](https://github.com/Mcpasi/AGENTCODI/actions/runs/37165001117/artifacts/11289216891): erzeugte Schemas, vollständige ELF-/Archivbefunde und Protokollnachweis.
- [APK-Lauf 37165001114](https://github.com/Mcpasi/AGENTCODI/actions/runs/37165001114): vollständiger Debug-APK-Build einschließlich nativer Full-access-, PTY-, Import-Kontext- und Übergangswerkzeug-Smokes erfolgreich.
- Geräteabhängige Linker-Tests wurden mit `AGENTCODI_SKIP_DEVICE_LINKER_TESTS=1` übersprungen; echte Installations-/Hardwaretests bleiben offen.
- `main` bleibt auf `ff27ec7c30d373a864e845e9a7ceeae3380dd103`. Kein PR, Merge oder Release wurde eingereicht.

Das [Debug-APK-Artefakt](https://github.com/Mcpasi/AGENTCODI/actions/runs/37165001114/artifacts/11289032769) enthält `AGENTCODI-Package-0.1.0-package.1-arm64-v8a-debug.apk` (149 MiB; SHA-256 `2ebf610159251aea8caa3766d19ded24399efcd05360f19160aab68833529247`). Signatur, Alignment, Application-ID, ABI und enthaltene Runtime wurden erfolgreich geprüft. Dies ist ein CI-Artefakt; eine öffentliche Release-Veröffentlichung aus Abschnitt 4 wurde nicht vorgenommen.
