# Roadmap: AGENTCODI Package Edition

Stand: 2026-10-04. Ausschließlich Branch `Mcpasi/package-edition`; kein Merge nach `main`.

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
- [ ] Minimalen ARM64-Bootstrap mit Shell, APT, dpkg, Zertifikaten und Abhängigkeiten bauen; Initialisierung sowie Reparatur nach abgebrochener Installation integrieren.
- [ ] Eigene CI für Paket- und Abhängigkeitsbuilds aufsetzen; zuerst Bootstrap und einen kleinen Katalog wie Python, Node.js/npm, Git und ripgrep prüfen, danach erweitern.
- [ ] Eigenes signiertes APT-Repository mit Vertrauensschlüssel, HTTPS, Veröffentlichungsablauf und Aktualisierungsstrategie einrichten.
- [ ] Schlanken `pkg`-Befehl beziehungsweise dokumentierte APT-Bedienung für Installation, Aktualisierung und Entfernung bereitstellen.
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
Bootstrap und Paketrepository sind weiterhin die folgenden offenen Punkte.
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

Alle Repository-Zugriffe und Änderungen erfolgen ausschließlich über den GitHub Connector. Die Community-Anbindung aus Abschnitt 2 ist umgesetzt; Paketmanager, verkleinerter Build und echte Gerätetests folgen in Abschnitt 3/4. Die ursprünglichen Verifikationsangaben oben beschreiben den vorausgehenden Grundlagenabschnitt.

## Verifikation der Community-Anbindung

Geprüfter Implementierungscommit: `1fed889377c980f66cdd6eabc0dba2dbcce0b9de`.

- [Tests-Lauf 37165001117](https://github.com/Mcpasi/AGENTCODI/actions/runs/37165001117): alle sechs Jobs erfolgreich — Architektur/Manifest, 303 Java-Tests, acht portable C++-Suites, Android-Kompilierung, Community-Archivprüfung und echter ARM64/Bionic-App-Server.
- [Runtime-Prüfartefakt](https://github.com/Mcpasi/AGENTCODI/actions/runs/37165001117/artifacts/11289216891): erzeugte Schemas, vollständige ELF-/Archivbefunde und Protokollnachweis.
- [APK-Lauf 37165001114](https://github.com/Mcpasi/AGENTCODI/actions/runs/37165001114): vollständiger Debug-APK-Build einschließlich nativer Full-access-, PTY-, Import-Kontext- und Übergangswerkzeug-Smokes erfolgreich.
- Geräteabhängige Linker-Tests wurden mit `AGENTCODI_SKIP_DEVICE_LINKER_TESTS=1` übersprungen; echte Installations-/Hardwaretests bleiben offen.
- `main` bleibt auf `ff27ec7c30d373a864e845e9a7ceeae3380dd103`. Kein PR, Merge oder Release wurde eingereicht.

Das [Debug-APK-Artefakt](https://github.com/Mcpasi/AGENTCODI/actions/runs/37165001114/artifacts/11289032769) enthält `AGENTCODI-Package-0.1.0-package.1-arm64-v8a-debug.apk` (149 MiB; SHA-256 `2ebf610159251aea8caa3766d19ded24399efcd05360f19160aab68833529247`). Signatur, Alignment, Application-ID, ABI und enthaltene Runtime wurden erfolgreich geprüft. Dies ist ein CI-Artefakt; eine öffentliche Release-Veröffentlichung aus Abschnitt 4 wurde nicht vorgenommen.
