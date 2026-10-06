# Roadmap: AGENTCODI Package Edition

Stand: 2026-10-06. Ausschließlich Branch `Mcpasi/package-edition`; kein Merge nach `main`.

### MPL-2.0-Quellenzugang — 2026-10-06

Die zwölf MPL-Komponenten der Community-Runtime erhalten vollständige,
unveränderte Quellen im APK: zehn per Cargo.lock prüfsummengebundene
Crate-Archive und ein Git-Snapshot für beide nucleo-Komponenten. Der lesbare
Quellenhinweis erläutert die kostenlose Offline-Bereitstellung; die Lizenzansicht
bietet „MPL-Quellen speichern“ über den Android-Dokumentauswahldialog. Originale
Urheberhinweise und Lizenzdateien bleiben enthalten. Der APK-Vertrag prüft
Quellen-/Versionsabdeckung, Paketmanifeste, Lizenzdateien und alle Lieferbytes.
Regressionen verhindern unbemerkten Hinweisverlust und neue MPL-Abhängigkeiten
ohne aktualisierte Quellen. APT-Rezepte, Bootstrap-Build-Eingaben und Katalogpakete
werden hierfür nicht verändert. CI-/APK-Nachweise werden nach Abschluss ergänzt.
Der Nutzer meldet die bisherigen Release-APK-Gerätetests einschließlich APT bis
MCP als bestanden; die neue Quellen-Speicherfunktion wurde damit noch nicht geprüft.

Die Checklisten zeigen den aktuellen Umsetzungsstand. Datierte Ergebnis- und Verifikationsabschnitte dokumentieren frühere Meilensteine; ihre Testzahlen, Artefakte und Prüfsummen gehören zum jeweils genannten Commit. Bootstrap, Startkatalog und öffentliches signiertes APT-Repository sind umgesetzt. Die npm-/Python-Pfade und die gemeinsame Prozessumgebung sind umgesetzt und in CI geprüft; die aktuellen Nachweise stehen im Ergebnisabschnitt „Gemeinsame Paketumgebung und npm-/Python-Pfade“. Die Paketdiagnose und die Workspace-Browser-/Import-/Export-Erweiterung sind umgesetzt; die nutzerinstallierbaren Übergangswerkzeuge sind aus dem APK entfernt. Die verbliebenen Legacy-Helfer und Transportparameter sind bereinigt. Build-Skript, Dockerfile, CI-Inputs, Restore-/Preflight-Prüfungen und Cache-Schlüssel sind auf die aktiven Edition-Abhängigkeiten reduziert. Der endgültige Test-/Payloadvertrag und der Abgleich der gelieferten Lizenzmaterialien sind umgesetzt; die aktuellen Nachweise stehen im Ergebnisabschnitt „Finaler Testvertrag und Lizenzabgleich“. Die Community-Rust-/V8-Abhängigkeitstexte und die drei paketlokalen Bootstrap-Lizenzzuordnungen sind ergänzt; Quellen-, Versions- und Artefaktbindungen werden im APK-Vertrag geprüft. Der neue Ergebnisabschnitt „Ergänzung der fehlenden Lizenzen“ dokumentiert diesen Stand. Der Nutzer meldet erfolgreiche Paketinstallation und -benutzung auf einem Gerät; die vollständige Gerätevalidierung und der Gerätetest des MCP-Freigabefixes bleiben offen. Die APT-Veröffentlichung ist getrennt von einem GitHub-Release der APK.

## Ziel und feste Entscheidungen

Nutzer installieren eigene Pakete, die Codex und das Terminal direkt verwenden können. Diese zweite Entwicklungslinie nutzt `targetSdk 28`, bietet ausschließlich Full access und richtet sich an erfahrene Nutzer. Androids Isolation zwischen Apps bleibt bestehen; eine zusätzliche Workspace-Sandbox wird hier nicht angeboten.

Ein Target-SDK-Wechsel allein liefert weder einen Paketmanager noch eine passende Paketquelle. Programme benötigen Android ARM64/Bionic und den richtigen Installationspräfix. Die am 2026-10-03 vom Nutzer gewählte Paketarchitektur — eigener Präfix, minimaler Bootstrap und eigenes signiertes Repository aus Termux-Paketrezepten — ist in Abschnitt 3 festgehalten.

## 1. Grundlage

- [x] Separaten Branch vom Main-Stand `ff27ec7c30d373a864e845e9a7ceeae3380dd103` anlegen.
- [x] Target SDK in Manifest, Build-Skript und BuildIdentity auf 28 setzen; Minimum SDK 29 beibehalten.
- [x] App-Modus auf Full access beschränken, einschließlich Dienst-Neustart und alter Launch-Intents.
- [x] Geschützte Modusauswahl und JIT-Schalter aus den Einstellungen entfernen.
- [x] Deutsche und englische Texte, dauerhafte Chat-Kennzeichnung und README-Warnung aktualisieren.
- [x] Beschreibbaren Übergangspräfix `$HOME/.local` mit `bin/lib/include/share/etc/tmp` anlegen. Die verwaltete Paketbasis ist inzwischen gemäß Abschnitt 3 auf `files/usr` umgestellt; bestehende `$HOME/.local`-Dateien bleiben erhalten.
- [x] Präfix-Binaries und Bibliotheken für App-Server und Codex-Kommandos vor die Übergangswerkzeuge setzen.
- [x] Shell-Funktionen entfernen, die selbst installierte Programme gleichen Namens überschreiben.
- [x] Regressionstests für beständige Installationen, Modusvertrag, SDK-Pins und tatsächliche Ausführung eigener Programme ergänzen.
- [x] Android-Quellen und Ressourcen zusätzlich in GitHub Actions gegen API 35 kompilieren; Manifest-Target 28 und Mindestniveau 29 prüfen.
- [ ] Android-Gerätetest: Programm aus dem beschreibbaren Präfix starten, denselben Befehl durch Codex ausführen, Dienst/Prozess neu starten und erneut prüfen.
- [x] Getrennte Application-ID, Versionslinie und APK-Namen für parallele Installation festlegen und durchgängig umsetzen: `de.agentcodi.pkg`, eigene Linie `0.1.0-package.1` ab `versionCode 1`, APK-Dateien `AGENTCODI-Package-*` und CI-Artefakt `agentcodi-package-debug-apk`. Der Anzeigename lautet AGENTCODI Package; der Java-Namespace bleibt `de.agentcodi.app`.

Die Installationsidentität ist unabhängig vom Java-Namespace. Alle Manifest-Komponenten verwenden vollständige Klassennamen; AAPT2 erzeugt Ressourcen weiter unter `de.agentcodi.app`. Die Android-CI vergleicht die Installations-/Versionsangaben in Manifest, Build-Skript, BuildIdentity und verknüpften Ressourcen und prüft, dass jede Manifest-Komponente als Java-Klasse existiert. Ein echter Installations-/Parallelbetriebtest bleibt Teil der offenen Android-Gerätetests. Bisherige Daten der gemeinsamen ID werden nicht automatisch übernommen; Export/Import ist in der README beschrieben.

Die Edition verwendet ausschließlich `:danger-full-access`; das Protected-Modul, aktive Protected-Verträge und JIT-Auswahl sind entfernt. Verbliebene alte boolesche Übergabeparameter sind stets false oder weisen true ausdrücklich zurück. Alte Launch-Intents werden auf Full access migriert. Die historische interne Mode-ID `compatibility` bleibt zur Kompatibilität mit bestehenden Sitzungsdaten erhalten.

## 2. Community-App-Server anbinden

Die aktive Community-Runtime stammt direkt aus dem gepinnten Release von `DioNanos/codex-termux`. Geprüftes Community-Release vom 2026-09-24:

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

- Das Archiv enthält 12 reguläre Dateien, darunter `codex.bin`, den separaten `codex-code-mode-host` und `libc++_shared.so`. Der Hostname ist im Codex-Binary vorhanden; dieser statische Prüfschritt bestätigte noch keine tatsächliche Auflösung oder APK-Relokation. Beides wurde beim anschließend dokumentierten Abschluss der Community-Anbindung geprüft.
- Beide ausführbaren ELF-Dateien sind ARM64/ELF64 mit Interpreter `/system/bin/linker64` und RUNPATH `$ORIGIN:$ORIGIN`. Die zwei identischen Einträge sind gleichwertig zum selben Bibliotheksordner; fremde oder leere Suchpfade werden abgelehnt.
- `codex.bin` benötigt dynamisch `libdl.so/libm.so/libc.so`, der Code-mode-Host zusätzlich `liblog.so`. Die mitgelieferte `libc++_shared.so` benötigt `libc.so/libm.so/libdl.so`; die beiden Programme haben in diesem Release keinen direkten DT_NEEDED-Eintrag für libc++. Alle ermittelten dynamischen Abhängigkeiten sind Android-Systembibliotheken.
- Keine npm-Paketabhängigkeiten sind deklariert. Die JavaScript-Launcher deklarieren Node.js `>=18.0.0`; das Postinstall-Skript passt Shebangs anhand des laufenden Node-Interpreters an. Shell- und JavaScript-Launcher enthalten weiterhin den Termux-Standardpräfix `/data/data/com.termux/files/usr`. Die spätere Integration verwendet die ELF-Dateien direkt; diese npm-/JavaScript-Launcher werden weder installiert noch ausgeführt.
- Paketlizenz und LICENSE sind Apache-2.0; NOTICE nennt OpenAI, Davide A. Guglielmi und Ratatui/MIT. Separate Lizenztexte für libc++ und statisch eingebundene Rust-/V8-Abhängigkeiten sind im Archiv nicht enthalten. LICENSE/NOTICE werden unverändert ins APK übernommen. Der damalige Abgleich in Abschnitt 4 erfasste diese gelieferte Menge und die fehlenden Rust-/V8-Abhängigkeitshinweise. Der spätere Ergebnisabschnitt „Ergänzung der fehlenden Lizenzen“ dokumentiert die separate, gepinnte Ergänzung dieser Materialien.
- Das archivierte README nennt veraltet `rust-v0.155.0`; Release und Paketbeschreibung nennen `rust-v0.156.1`. Die Prüfung dokumentiert diese Abweichung und verwendet die gepinnten Release-/Quellangaben.

Neu ermittelte ELF-Prüfsummen, ausschließlich für dieses unveränderte Release-Archiv:

| Datei | SHA-256 |
| --- | --- |
| `codex.bin` | `6cbfa7f1660095e9cf2df7de242014579a0fb0d42545652fb0e22d1b6c8571a5` |
| `codex-code-mode-host` | `8afb196579c3fd8ecac558dbebfcba5467f91389b3754e485728ce6904e6ceaf` |
| `libc++_shared.so` | `430f7cde7c1a88042bb9e39ae1c1f4b9f5819f3bd95acd1453d2e02c63ba1870` |

Architekturchecks, 309 Java-Tests, alle 8 portablen C++-Suites, Android-Quellen/Ressourcen gegen API 35 und die Release-Prüfung einschließlich 8 neuer Archiv-/Suchpfadtests sind erfolgreich. Die anfänglichen CI-Ursachen (anonymes GitHub-API-Ratenlimit und zu strenger Vergleich der doppelten ORIGIN-Einträge) sind behoben.

In diesem statischen Prüfschritt wurden weder npm-Install/Postinstall noch Community-ELFs ausgeführt und keine APK-Runtime verändert. Schema-Erzeugung und -Prüfsummen, RPC-/Startkompatibilität, Host-Relokation und Kanalwechsel wurden anschließend umgesetzt; der folgende Abschnitt dokumentiert den Abschluss. Gerätetests bleiben offen und wurden gemäß Nutzeranweisung übersprungen.

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

Die Root-README ist vollständig Englisch. Die danach umgesetzte Paketbasis,
der Startkatalog und das signierte Repository sind in Abschnitt 3 dokumentiert.
Die anschließend umgesetzten Paketpfad-/Umgebungsarbeiten sind in Abschnitt 3
mit ihren CI-Nachweisen dokumentiert. Die APK-Verkleinerung aus Abschnitt 4 ist inzwischen umgesetzt.
Echte Android-Gerätetests bleiben offen; Gerätetests wurden
auf ausdrücklichen Nutzerwunsch nicht ausgeführt.

## 3. Paket-Bootstrap und Workspace vervollständigen

### Beschlossene Paketarchitektur — 2026-10-03

Der Nutzer hat diese Lösung ausdrücklich gewählt. Am 2026-10-03 wurde zunächst die Architekturentscheidung dokumentiert; Bootstrap und Paketrepository wurden anschließend wie unten beschrieben umgesetzt. Die Entscheidung muss nicht erneut erfragt werden.

- **Termux-Paketrezepte wiederverwenden:** Die benötigten Pakete einschließlich ihrer Abhängigkeiten aus [termux/termux-packages](https://github.com/termux/termux-packages) für Android ARM64/Bionic, die eigene AGENTCODI-Application-ID und den eigenen Installationspräfix neu bauen. Die Termux-App selbst wird weder eingebunden noch als vollständige App-Version gepinnt.
- **Eigener Präfix außerhalb des Benutzer-Homes:** Für die Paketbasis `files/usr` verwenden, beispielsweise `/data/data/de.agentcodi.pkg/files/usr`. Die separate Application-ID ist endgültig auf `de.agentcodi.pkg` festgelegt und in Manifest sowie Build-Konfiguration umgesetzt. Benutzer-Home, Workspace und `CODEX_HOME` bleiben separate Verzeichnisse. Der frühere Präfix `$HOME/.local` bleibt als Legacy-Fallback erhalten; die verwaltete Paketbasis liegt inzwischen in `files/usr`.
- **Minimaler Bootstrap:** Nur Shell, APT, dpkg, Zertifikate und die dazu notwendigen Abhängigkeiten als Anfangsbasis bereitstellen. AGENTCODI übernimmt Installation und Initialisierung dieses Bootstraps.
- **Eigenes signiertes Paketrepository:** Eine eigene CI baut die angebotenen Pakete aus den Termux-Rezepten. Das Repository liefert Installation, Aktualisierung und Abhängigkeitsauflösung. Die umgesetzte Bedienung nutzt APT, etwa `apt install python nodejs-lts npm git ripgrep`; ein zusätzlicher `pkg`-Befehl bleibt optional.
- **Gezielte reproduzierbare Pins:** Stand der Paketrezepte, Build-Toolchain, Paketversionen und Artefaktprüfsummen festhalten. Aktualisierungen bewusst testen und veröffentlichen. Ein kontrollierter Satz an Build-Anpassungen reicht; die komplette Termux-App muss dafür nicht übernommen werden.
- **Einheitliche Laufzeit:** Codex-Kommandos, Terminal, App-Server und lokale stdio-MCP-Prozesse verwenden dieselbe Paketinstallation und abgestimmte `PATH/PREFIX/LD_LIBRARY_PATH/HOME/TMPDIR`-Werte.

Die offiziellen Termux-DEBs sind häufig für `/data/data/com.termux/files/usr` gebaut. Sie werden nicht allgemein durch Entpacken, Ändern von `PATH` oder Setzen von `apt --root` kompatibel. Die reguläre Paketquelle dieser Edition enthält deshalb eigene Builds für den festgelegten Präfix; offizielle Termux-Binärrepositories werden nicht als austauschbare Quelle beigemischt.

Der angebotene Paketkatalog umfasst nur Pakete, die für diese Edition samt Abhängigkeiten gebaut und geprüft wurden. Das eigene Repository benötigt laufende Pflege und Sicherheitsupdates. Pakete mit Abhängigkeiten von Termux-App-Komponenten oder Termux:API sind gesondert anzupassen, bevor sie angeboten werden.

PRoot mit virtuellen Termux-Pfaden und eine allgemeine nachträgliche Relokation fertiger DEBs sind nicht der gewählte Ansatz. Die Edition soll ihre Pakete direkt und nativ unter dem eigenen Präfix ausführen.

Das Termux-Buildsystem dokumentiert anpassbare App- und Präfixvariablen in [scripts/properties.sh](https://github.com/termux/termux-packages/blob/master/scripts/properties.sh) und unterstützt eigene Bootstrap-Builds über [scripts/build-bootstraps.sh](https://github.com/termux/termux-packages/blob/master/scripts/build-bootstraps.sh). Bei der Umsetzung müssen die Werte in der Build-Konfiguration konsistent gesetzt werden; ein bloßer Laufzeit-Export ersetzt den Neubau nicht.

### Umsetzungsschritte

- [x] Architekturentscheidung des Nutzers dokumentieren: eigener Präfix, minimaler Bootstrap und signiertes Repository aus neu gebauten Termux-Paketrezepten.
- [x] Separate Application-ID endgültig festlegen und in der App umsetzen, bevor Pakete mit absoluten Pfaden gebaut werden: `de.agentcodi.pkg`; verwalteter Präfix `/data/data/de.agentcodi.pkg/files/usr`.
- [x] Verwalteten Präfix auf `files/usr` außerhalb des Benutzer-Homes umstellen. Bestehende Dateien in `$HOME/.local` erhalten; Übergang/Migration und Suchreihenfolge dokumentieren und testen.
- [x] Reproduzierbaren Stand von `termux-packages` und Toolchain festlegen; gezielte Build-Anpassungen für App-ID, Präfix und Repository-URLs versionieren. Bootstrap, Paketmetadaten, Shebangs, RPATH/RUNPATH und Konfigurationen auf denselben finalen Pfad ausrichten.
- [x] Minimalen ARM64-Bootstrap mit Shell, APT, dpkg, Zertifikaten und Abhängigkeiten bauen; Initialisierung sowie Reparatur nach abgebrochener Installation integrieren.
- [x] Eigene CI für Paket- und Abhängigkeitsbuilds aufsetzen; zuerst Bootstrap und einen kleinen Katalog wie Python, Node.js/npm, Git und ripgrep prüfen, danach erweitern.
- [x] Eigenes signiertes APT-Repository mit Vertrauensschlüssel, HTTPS, Veröffentlichungsablauf und Aktualisierungsstrategie einrichten.
- [x] Dokumentierte APT-Bedienung für Installation, Aktualisierung und Entfernung bereitstellen. Ein zusätzlicher `pkg`-Wrapper ist optional und bisher nicht umgesetzt.
- [x] Gemeinsame Umgebungsdefinition für App-Server, Codex-Kommandos, Terminal und lokale stdio-MCP-Prozesse nach den npm-/Python-Pfadanpassungen vollständig prüfen. Der echte ARM64/Bionic-App-Server prüft identische `PATH/PREFIX/LD_LIBRARY_PATH/HOME/TMPDIR`-Werte in Codex-Shell, Terminal und stdio-MCP, einschließlich der privaten Codex-Sitzungshilfsprogramme im gemeinsamen `PATH`.
- [x] npm-Global-Prefix/-Cache sowie Python-User-/venv-/pip-Pfade nutzbar machen. Normale Nutzerkonfiguration und Python-User-Site sind aktiv; lokale Global-/User-/venv-Installationen, ausführbare Skripte, npx und Entfernung sind unter ARM64/Bionic geprüft.
- [x] Installation, Aktualisierung, Entfernung und Status im Terminal dokumentieren; Pakete über App-Neustart und APK-Update erhalten. APT-Bedienung ist in der README dokumentiert, Bestandserhaltung durch Java-Regressionen geprüft; echte Geräte-/Update-Tests bleiben separat offen.
- [x] Paketpfade und installierte Versionen bei Bedarf in Diagnose/Terminal anzeigen; bisherige Aktivierungsanzeigen ersetzen. Die Terminal-Schaltfläche „Paketdiagnose“ fragt aktuelle Umgebungswerte, Befehlsauflösung und Versions-/Statusdaten der verwalteten dpkg-Datenbank ab.
- [x] Workspace-Browser und Import/Export für ausdrücklich gewählte Paketbereiche erweitern. Kontodaten bleiben außerhalb der zugänglichen Wurzeln; bekannte Zugangsdaten-Pfade und Links sind in Paketbereichen gesperrt.
- [ ] Android 10 und eine aktuelle Android-Version auf echter ARM64-Hardware prüfen: ELF, Skript/Shebang, dynamische Bibliothek, npm/pip, PTY und stdio-MCP.

### Verwalteter Präfix — 2026-10-04

Die App legt die Paketbasis jetzt als `usr` direkt unter ihrem kanonischen
Files-Verzeichnis an und übergibt sie ausdrücklich über Java/JNI an den nativen
Supervisor. `HOME`, Workspace, `CODEX_HOME` und temporäre Dateien bleiben getrennt.
Codex ergänzt beim Start private Sitzungshilfsprogramme im gemeinsamen `PATH`.
Danach lautet die Paket-Suchreihenfolge `files/usr/bin`, `$HOME/.local/bin`,
APK-Aliase, Android-Systempfade; Bibliotheken werden aus `files/usr/lib`,
`$HOME/.local/lib` und den nativen APK-Bibliotheken gesucht. App-Server und
Codex-Shell-Konfiguration beziehen die Paketwerte aus derselben nativen
Definition. Codex-Kommandos und Terminal übernehmen den zur Laufzeit um
Sitzungshilfsprogramme ergänzten Server-`PATH`; geerbte stdio-MCP-Prozesse
verwenden denselben tatsächlichen Pfad. Der abschließende Abgleich ist im
Ergebnisabschnitt vom 2026-10-05 beschrieben.

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

`repo.json` und das APT-Rezept verwenden ausschließlich die eigene
HTTPS-Quelle `https://mcpasi.github.io/AGENTCODI/apt/package-edition`
mit `stable main` und `signed-by=$PREFIX/etc/apt/keyrings/agentcodi-package.gpg`.
Der upstream Termux-Keyring wird nicht eingebunden. Zum Stand dieses
Buildvertrags war die Quelle noch nicht veröffentlicht; Vertrauensschlüssel
und öffentliche Veröffentlichung wurden anschließend im unten beschriebenen
signierten Repository-Schritt umgesetzt.

Der neue Tests-Job „Package recipe and toolchain contracts“ prüft die
Vorbereitung zweimal, echte Upstream-Pfadableitungen, Bootstrap-Vorlagen,
Patch-/Shebang-Ersetzung, APT-Konfiguration und Ablehnungsfälle.
Er baut im gepinnten Container mit der tatsächlichen NDK ein ARM64-Test-ELF
und eine Bibliothek, verwendet den echten Debian-Metadaten-/Archiv-Hook,
vergleicht zwei DEB-Erzeugungen und prüft Interpreter, RUNPATH, Payload-,
Symlink-, Skript- und Metadatenpfade. Das Artefakt enthält Compiler-/
Host-Inventar, Vorbereitung, ELF-Berichte, Paketmetadaten, Test-DEB und SHA-256.
Die FUSE-/Sysroot-Einrichtung und die echten Bootstrap-Pakete wurden
anschließend beim folgenden vollständigen Build geprüft.

Die Startpakete des anschließend umgesetzten Bootstraps sind Dash, APT,
dpkg und Zertifikate samt Abhängigkeiten. Das Test-DEB dieses Buildvertrags
war kein auslieferbarer Bootstrap; Initialisierung/Reparatur, echte
Paketbuilds und signierte Veröffentlichung sind in den folgenden
Ergebnisabschnitten dokumentiert. Termux-App-/API-/Exec-/Tools-Komponenten
erfordern eigene Anpassungen und werden weiterhin abgelehnt.
Die [Build-Dokumentation](scripts/package-edition/README.md) beschreibt Pins,
Anpassungen, Prüfungen und den Aktualisierungsablauf. Gerätetests werden
gemäß Nutzeranweisung übersprungen.

### Minimaler ARM64-Bootstrap und Wiederherstellung — 2026-10-04

Der Editions-Bootstrap wird aus den gepinnten Termux-Rezepten vollständig für
ARM64/Bionic/API 29 und `/data/data/de.agentcodi.pkg/files/usr` gebaut.
Dash stellt `sh` bereit; Bash wird für Paket-Konfigurationsskripte mitgeliefert.
Der damalige Bootstrap mit APT, dpkg, CA-Zertifikaten und ihren Laufzeitabhängigkeiten umfasste 47 Pakete, noch ohne das später ergänzte Editions-Keyring-Paket.
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
Paketaktualisierungen erfolgen über den inzwischen veröffentlichten signierten
APT-Kanal. Die begrenzte Migration eines fehlenden Vertrauensschlüssels ist
im Repository-Abschnitt beschrieben.

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
lieferte die quellgebauten DEBs und das ursprüngliche Quellenarchiv. Die damalige
Assembly lieferte das [Bootstrap- und Quellenartefakt](https://github.com/Mcpasi/AGENTCODI/actions/runs/37219054636/artifacts/11309996366).
ZIP-SHA-256: `d875abf8f90fe7e70494ce8b268da826e575031275f75ca611e89ae64512041a`.
Manifest-SHA-256: `275ef6dad15d6cdcfa8a5e7765bc697b5923da1e7400f637bb022f44cd18eccd`.
Die nachfolgenden Builds dürfen diese DEBs nur bei identischen Paket-
Buildinputs und geprüftem Artefakt wiederverwenden; aktuelle Assembly- und
ELF-Prüfungen werden erneut ausgeführt.

Verifikation: [Tests-Lauf 37219054346](https://github.com/Mcpasi/AGENTCODI/actions/runs/37219054346)
und [APK-Lauf 37219054636](https://github.com/Mcpasi/AGENTCODI/actions/runs/37219054636)
für Implementierungscommit `070c37845e19931501d692ad0eb081da16296663` sind erfolgreich.
Der damalige ARM64/Bionic-Smoke installierte die echte ZIP über den Java-Installer,
konfigurierte alle 47 Pakete mit Android-dpkg, startete Shell/APT/gpgv und prüfte
die registrierte APT-Version über apt-cache policy, Zertifikate und
Konfigurationsdatei-Metadaten. Er installierte, startete und entfernte außerdem
ein lokales Testpaket. Die 313 Java-Tests enthalten
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

Startkatalog, Vertrauensschlüssel und signiertes Online-Repository wurden
anschließend umgesetzt; die folgenden Abschnitte dokumentieren die Nachweise.
Die Quelle wird ausschließlich mit dem gepinnten Schlüssel authentifiziert;
es gibt keinen unsicheren Fallback. Weitere Katalogerweiterungen bleiben optional. Die damals noch offene
APK-Verkleinerung ist inzwischen umgesetzt. Ein `pkg`-Wrapper bleibt optional.
Kein PR, Merge oder GitHub-Release der APK wurde erstellt. `main` bleibt unverändert.

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
Kein PR, Merge oder GitHub-Release der APK wurde erstellt; `main` bleibt unverändert.

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
geprüften Kataloglauf aus dem neuesten Ergebnisabschnitt gesetzt, damit unveränderte
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
abgehakt; der nachfolgende Ergebnisabschnitt dokumentiert die inzwischen
umgesetzten Paketpfad-/Umgebungsarbeiten.

Gerätetests wurden wie angeordnet übersprungen und bleiben offen.
Kein PR, Merge oder GitHub-Release der APK wurde erstellt. Alle Zugriffe erfolgten über den
GitHub Connector; `main` bleibt auf
`ff27ec7c30d373a864e845e9a7ceeae3380dd103`.

### Gemeinsame Paketumgebung und npm-/Python-Pfade — 2026-10-05

Implementierung und erfolgreiche CI-Prüfung dieses Schritts erfolgten ausschließlich
auf `Mcpasi/package-edition`. Die gemeinsame Prozessumgebung und ihre
npm-/Python-Pfadvoraussetzung sind umgesetzt; beide Checklistenpunkte sind abgehakt.

Eine gemeinsame native Definition liefert die Umgebung des App-Servers und
die Codex-Shell-Konfiguration. Codex ergänzt beim Start sein privates
Sitzungshilfsverzeichnis im `PATH`. Codex-Kommandos und Terminal übernehmen
diesen tatsächlich laufenden Server-Pfad über `inherit="core"` und eine explizite
Variablenliste; ein statischer `PATH`-Override würde dagegen von stdio-MCP
abweichen. Die übrigen Werte kommen weiter aus der gemeinsamen Definition.
Der Laufzeittest vergleicht die tatsächlichen Pfade von Codex-Shell, Terminal
und MCP direkt und prüft zugleich den unveränderten Paket-Suchpfad.
`PATH/PREFIX/LD_LIBRARY_PATH/HOME/TMPDIR` sowie npm-Präfix und Cache-Home
werden zusätzlich mit einem echten stdio-MCP-Server unter ARM64/Bionic geprüft.
`TERMUX_VERSION=agentcodi-package-edition` aktiviert dabei ausschließlich die
Android-Umgebungsweitergabe des gepinnten Community-Releases. Es wird keine
Termux-App installiert und kein offizielles Termux-Binärrepository verwendet.
Explizite Umgebungsüberschreibungen eines MCP-Servers bleiben Nutzerkonfiguration.

npm-Global-Installationen verwenden standardmäßig `$HOME/.local`; npm liest
wieder die normale Nutzerkonfiguration und nutzt `$HOME/.npm` als Standardcache.
Python-User-Site bleibt aktiviert; Nutzerpakete und Skripte liegen ebenfalls
unter `$HOME/.local`. pip verwendet `$HOME/.cache/pip`. venvs behalten ihre
eigenen Installationspfade. Der Katalog bietet die bereits aus dem gepinnten
Python-Quellarchiv erzeugten `python-ensurepip-wheels` für `ensurepip --user`
und Offline-venv-Erstellung an. Der gebündelte Übergangs-Python unterstützt
User-Site; venv/pip verwenden den durch APT installierten Editions-Python.

npm und das gebündelte Übergangs-npm passen beim Erstellen ihrer ausführbaren
Links den üblichen Node-Shebang `/usr/bin/env node` auf Androids
`/system/bin/env node` an. Die restlichen Skriptbytes bleiben erhalten.
Die Editions-npm-Rezeptrevision wurde auf `11.20.0-1` angehoben, damit bereits veröffentlichte
Paketbytes nicht unter derselben Version ersetzt werden.

Die neuen Laufzeitprüfungen installieren und entfernen reine lokale
npm-/Python-Testpakete ohne Netz-Zugriff. Sie prüfen Global-Prefix, Cache,
benutzerdefinierte npm-Konfiguration, npx, User-Site und getrennte venvs.
Quellbuild-Artefakte dürfen bei geänderter Root-Auswahl nur wiederverwendet
werden, wenn unveränderte relevante Rezepte und der vollständige
Producer-Bericht alle angeforderten DEBs belegen; aktuelle Assembly und
Laufzeitprüfungen laufen erneut. Änderungen am npm-Rezept erzwingen für die
Node-Gruppe einen neuen Quellbuild.

Verifizierter Implementierungscommit: `f1f1ee3ed7e8d918b40e844a4c1729b4a5502a9c`.
Die [Tests](https://github.com/Mcpasi/AGENTCODI/actions/runs/37362245260)
haben alle sieben Jobs bestanden, einschließlich 315 Java-Tests und acht
portablen C++-Testsuiten. Der
[APK-Build](https://github.com/Mcpasi/AGENTCODI/actions/runs/37362245644)
hat alle drei Jobs bestanden; der ARM64/Bionic-Smoke prüft echte
Codex-Shell-, Terminal- und stdio-MCP-Prozesse mit identischen Umgebungswerten
sowie die Übergangs-npm-/Python-Pfade. Das
[Debug-APK-Artefakt](https://github.com/Mcpasi/AGENTCODI/actions/runs/37362245644/artifacts/11367960019)
steht bereit.

Der [Paketkatalog](https://github.com/Mcpasi/AGENTCODI/actions/runs/37362245741)
hat alle 14 Jobs bestanden. Dazu gehören die erneute Prüfung des neuen Node-/npm-Quellbuilds,
die geprüfte Python-Wheel-Assembly, reale npm-/pip-Installationen und
Deinstallationen sowie getrennte venvs unter ARM64/Bionic,
die signierte APT-Veröffentlichung und ihre öffentliche HTTPS-Endprüfung.
Der [signierte Repository-Snapshot](https://github.com/Mcpasi/AGENTCODI/actions/runs/37362245741/artifacts/11369690652)
und sein [ARM64/Bionic-Prüfnachweis](https://github.com/Mcpasi/AGENTCODI/actions/runs/37362245741/artifacts/11370025576)
sind als CI-Artefakte verfügbar.
Der [neue Node-/npm-Quellbuild](https://github.com/Mcpasi/AGENTCODI/actions/runs/37346622005/job/111886746281)
ist als eigener Producer-Job erfolgreich. Der aktuelle Katalog prüft dessen
unveränderte relevante Build-Eingaben und Paketprüfsummen, stellt die
aktuelle Assembly zusammen und wiederholt alle Laufzeitprüfungen.
Die drei Implementierungsnachweise beziehen sich auf denselben Commit
`f1f1ee3ed7e8d918b40e844a4c1729b4a5502a9c`. Die anschließend geänderte
Roadmap und CI-Konfiguration verändern den App-Code nicht.

GitHub brach spätere Dokumentationsprüfungen vor dem Teststart ab:
„The job was not acquired by Runner of type hosted even after multiple
attempts“. Ein zusätzlicher Diagnoseschritt liest die Abbruchmeldungen des
vorherigen Commits in das CI-Log. Die unveränderten acht portablen
C++-Testsuiten verwenden jetzt den verfügbaren `ubuntu-24.04-arm`-Pool;
ihr Treiber baut native Fixtures und ermittelt den Host-Bibliothekspfad
über den Systemcompiler. Der zusätzliche
[Tests-Lauf](https://github.com/Mcpasi/AGENTCODI/actions/runs/37375227949)
für `da6dae58dcffc513fefcb221828ac2f03cf934ca` hat alle sieben Jobs
bestanden, einschließlich der vollständigen acht C++-Suiten auf ARM64.
Der folgende Abschlusscommit ergänzt ausschließlich diesen Roadmap-Nachweis.

Gerätetests werden gemäß Nutzeranweisung übersprungen und bleiben offen.
Kein PR oder Merge; `main` bleibt unverändert.

### Paketdiagnose im Terminal — 2026-10-05

Die bisherigen Aktivierungsschaltflächen und festen APK-Versionsanzeigen sind
durch eine Schaltfläche „Paketdiagnose“ ersetzt. Jeder Aufruf liest die aktuelle
Terminalumgebung und fragt ausschließlich die verwaltete Datenbank
`$PREFIX/var/lib/dpkg` mit `$PREFIX/bin/dpkg-query` ab. Paketname, tatsächliche
Version einschließlich Epoch/Revision und dpkg-Status werden angezeigt.
Befehlsauflösung und Paketstatus stehen getrennt: ein Legacy-/APK-Befehl
belegt keine APT-Installation; `config-files` kennzeichnet entfernte Pakete
mit verbliebener Konfiguration. Fehlende Pfade, Datenbank oder Query-Werkzeug
sowie Query-Fehler werden ausdrücklich ausgegeben.

Die Diagnose installiert oder aktiviert nichts und benötigt keinen Netzzugriff.
Sie zeigt nur ausgewählte Paketumgebungsvariablen; Kontodaten werden nicht
gelesen. Ihre Ausgabe bleibt wie die übrige Terminalausgabe im Speicher.
Die Bedienelemente und Erläuterungen sind Deutsch/Englisch; technische
Diagnoseüberschriften und dpkg-Statuswerte bleiben Englisch.
npm-/pip-/venv-Inventare sind separat abzufragen, wie in der README dokumentiert.
Zum damaligen Stand blieben die Übergangswerkzeuge und ihr internes
Aktivierungsverfahren bis zum APK-Verkleinerungsschritt erhalten; die damalige
README beschrieb den expliziten Legacy-Befehl. Diese Werkzeuge und APIs sind
inzwischen entfernt, wie die folgenden Ergebnisabschnitte dokumentieren.

Fünf neue Java-Regressionen führen den tatsächlichen Diagnosebefehl mit dem
realen `dpkg-query` gegen isolierte Testdaten aus. Sie prüfen aktuelle Pfade,
verwaltete Versionen und Status, Update/Entfernung mit Legacy-Fallback,
fehlende Datenbank/Werkzeuge, fehlerhafte Metadaten und einen fehlenden Präfix.
Architekturprüfungen verlangen jetzt die Diagnose statt der alten
Aktivierungsanzeige. Die Java-Suite benötigt dafür `sh` und `dpkg-query` auf
dem Testhost; beide sind in den Ubuntu-Runnern und im APK-Buildcontainer
vorhanden und werden nur mit isolierten temporären Metadaten verwendet.

Verifizierter Implementierungscommit: `139fb464ee9a2760e0131b6973f6c3a680d591c9`.
Der [Tests-Lauf 37382642660](https://github.com/Mcpasi/AGENTCODI/actions/runs/37382642660)
hat alle sieben Jobs bestanden: 320 Java-Tests, alle acht portablen C++-Suiten,
Android-Quellen/Ressourcen gegen API 35, Community-Archiv-/ARM64-Bionic-Prüfung
und Paket-/Toolchain-Verträge. Der
[APK-Lauf 37382642782](https://github.com/Mcpasi/AGENTCODI/actions/runs/37382642782)
hat alle drei Jobs bestanden, einschließlich Bootstrap-Build, Bootstrap-Smoke
und vollständigem APK-Build mit echten App-Server-/PTY-/MCP-Laufzeitprüfungen.
Das [Debug-APK-Artefakt](https://github.com/Mcpasi/AGENTCODI/actions/runs/37382642782/artifacts/11376077909)
enthält die separate Package Edition; Identität, Signatur, Alignment, ABI und
Payload sind geprüft. APK-SHA-256:
`13ccd2acd5468d69ec55e99aad507132cf95d9651181a5a482a29a42b06611d6`.

Die Implementierungsläufe hatten keine Fehlschläge. Geräteabhängige
Linkerprüfungen wurden mit `AGENTCODI_SKIP_DEVICE_LINKER_TESTS=1`
übersprungen; echte Geräte-/Installations-/Update-Tests bleiben offen.
Der Abschlusscommit ergänzt ausschließlich diese Nachweise und Hinweise zu
den CI-Testvoraussetzungen; der App-Code bleibt unverändert.
Kein PR, Merge oder APK-Release; `main` bleibt auf
`ff27ec7c30d373a864e845e9a7ceeae3380dd103`.

### Workspace-Browser und Paketdatei-Import/Export — 2026-10-05

Umgesetzt ausschließlich auf `Mcpasi/package-edition`. Der Browser bietet
die getrennten Bereiche Workspace, APT-Pakete (`files/usr`) und Nutzerpakete
(`$HOME/.local`) mit eigener Navigation und Vorschau. Paketbereiche sind
nur lesbar; sie eröffnen weder das gesamte HOME noch CODEX_HOME. Normale
Workspace-Exporte enthalten weiterhin keinen Paketpräfix.

Einzeldateien und ausdrücklich gewählte Paketordner lassen sich über den
Android-Dokumentdialog exportieren. Bestehende Größen-, Datei-, Scan- und
Tiefengrenzen sowie quellnahe No-follow-/Hardlink-/Änderungsprüfungen und
Rollback bleiben aktiv. Native Zugriffe erhalten die geprüfte absolute
Wurzel unverändert, damit ein ausgetauschter Wurzellink nicht vor
`O_NOFOLLOW` aufgelöst wird. Ausstehende Exporte behalten ihren ursprünglichen
Bereich. Paket-ZIP-Namen kennzeichnen die Quelle.

Bekannte Zugangsdaten-Pfade einschließlich `auth.json`, `codex-home`,
`.codex`, `.ssh`, `.npmrc`, `.pypirc`, `.netrc`, `.git-credentials`
sowie APT-`auth.conf`/`auth.conf.d` sind in Paketbereichen für Vorschau
und Export gesperrt, auch bei direkter Auswahl. Links und solche Pfade
werden beim ZIP-Export ausgelassen und gezählt. Unter beliebigen anderen
Namen kopierte Geheimnisse kann eine Namensprüfung nicht erkennen; README
und SECURITY erklären diese Grenze ebenso wie den unveränderten Full access.

„Datei importieren“ übernimmt eine einzelne Android-Datei beliebigen Typs,
einschließlich DEB/ZIP, bytegenau nach `workspace/imports`, mit eigenem
Zufallsnamen und sicherer Dateiendung. Der Browser zeigt den genauen Pfad.
Das bestehende Importmodul prüft das temporäre Leserecht, die 512-MiB-Grenze,
Kontodaten-Dateinamen und den atomaren No-replace-Abschluss; es speichert
keine URI und installiert, entpackt oder startet nichts. Die Verwendung im
Terminal erfolgt ausdrücklich durch den Nutzer.

ZIPs sind Dateikopien: Links, leere Ordner und Ausführungsrechte fehlen;
sie sind keine vollständige Paket- oder dpkg-Wiederherstellung. Verwaltete
Pakete werden über APT neu installiert. Deutsche/englische Bedienung,
Settings-Texte, README und SECURITY sind auf diesen Vertrag abgestimmt.

Fünf neue Java-Regressionen prüfen Wurzelauswahl, Vorschau/Einzelexport
beider Präfixe, Kontodaten-/Link-Ausschluss im tatsächlichen ZIP,
direkte/unsichere Pfade und ausgetauschte Wurzellinks. Eine zusätzliche
Importregression prüft DEB-/ZIP-Bytes und Endungen ohne Paketinstallation.
Verifizierter Implementierungsstand: `4949dad012634ef403dae1f03fe72cddcb4ccc2e`.
Der [Tests-Lauf 37386821181](https://github.com/Mcpasi/AGENTCODI/actions/runs/37386821181)
hat alle sieben Jobs bestanden: 326 Java-Tests einschließlich der sechs neuen
Regressionen, acht portable C++-Suiten, Android-Quellen/Ressourcen gegen API 35,
Community-Archiv-/ARM64-Bionic-Prüfungen sowie Paket-/Toolchain-Verträge.
Der [APK-Lauf 37386821299](https://github.com/Mcpasi/AGENTCODI/actions/runs/37386821299)
hat alle drei Jobs bestanden, einschließlich Bootstrap-Build,
ARM64/Bionic-Bootstrap-Smoke und vollständigem Debug-APK-Build mit
App-Server-/PTY-/MCP-Laufzeitprüfungen, Identität, Signatur und Alignment.
Das [Debug-APK-Artefakt](https://github.com/Mcpasi/AGENTCODI/actions/runs/37386821299/artifacts/11380222721)
enthält die geprüfte separate Package Edition. APK-SHA-256:
`4e71d975344a67d53fd073cf0228f5868d7e2e0a04296ad8ad61c6760c7cfb67`.

Die abschließenden Implementierungsläufe hatten keine Testfehlschläge.
Frühere laufende Versuche wurden bei nachfolgenden Branch-Commits durch
die bestehende CI-Concurrency-Regel abgebrochen. Geräteabhängige
Linkerprüfungen wurden mit `AGENTCODI_SKIP_DEVICE_LINKER_TESTS=1`
übersprungen; echte Geräte-/Installations-/Update-Tests bleiben offen.
Der Abschlusscommit ergänzt ausschließlich Dokumentation und diese Nachweise;
der geprüfte App-Code und die Ressourcen bleiben unverändert.
Kein PR, Merge oder APK-Release; `main` bleibt unverändert.

## 4. Build verkleinern und veröffentlichbare Edition erstellen

Bootstrap, Startkatalog, signierter Paketkanal und die gemeinsame Paketumgebung funktionieren in CI. Die npm-/Python-Pfadvoraussetzungen sind abgeschlossen. Die bisher enthaltenen nutzerinstallierbaren Pakete sind aus dem APK entfernt. Der aktive Startpfad nutzt ausschließlich die native Codex-Runtime und die installierte Paketbasis. Ungenutzte Legacy-Quellen, Identitätskonstanten und Aktivierungs-/Transport-APIs sind entfernt. Alte private Tool-Verzeichnisse werden nicht mehr angelegt oder als Startvoraussetzung benötigt; vorhandene Nutzerdaten bleiben erhalten. Die Build-Abhängigkeiten, ihre Wiederherstellung und die Cache-Auswahl sind reduziert. Der endgültige Prüfvertrag und der Abgleich gelieferter Lizenzmaterialien sind inzwischen umgesetzt. Die Community-Abhängigkeitstexte und Bootstrap-Lizenzzuordnungen sind ergänzt und geprüft. Offen bleiben die Gerätevalidierung und die anschließende finale APK-Veröffentlichung.

- [x] Bundled Node.js, npm, Python, ripgrep und nur von ihnen benötigte Bibliotheken/Archive/Lizenzen aus dem APK entfernen.
- [x] Vorher Abhängigkeiten des App-Servers und Code-mode-Hosts auf diese Werkzeuge prüfen; zwingend notwendige Basiswerkzeuge im Bootstrap behalten.
- [x] PackagedToolRuntime, Tool-Alias-/Activation-/ELF-Attestor-Code und Runtime-Startvalidierung an den Paket-Bootstrap anpassen. Ausgemusterte Quellen/APIs und feste Tool-Pins entfernt; Startup benötigt nur den aktiven Paketvertrag. Alte Nutzerdaten bleiben erhalten.
- [x] Build-Skript, Dockerfile, CI-Input-Manifest, Restore-/Preflight-Prüfungen und Cache-Schlüssel auf die minimalen Edition-Abhängigkeiten reduzieren.
- [x] Eigene Debug-APK-Artefakte für diesen Branch erzeugen (`agentcodi-package-debug-apk`); keine regulären Main-Releases überschreiben. Das APK enthält keine Übergangswerkzeuge mehr; die Bereinigung ist inzwischen abgeschlossen. Die finale Veröffentlichung benötigt weiterhin die echten Gerätenachweise.
- [x] Architekturchecks und Java-/C++-/Android-Smokes auf den endgültigen Paketvertrag ausrichten.
- [x] Notices, README, SECURITY und Build-Dokumentation mit der tatsächlich ausgelieferten Paketbasis abgleichen. Der Abgleich und die nachfolgende Lizenzergänzung sind umgesetzt; Community-Rust-/V8-Materialien und paketlokale Bootstrap-Zuordnungen sind quellen-/artefaktgebunden. Das Release-Lizenzgate weist jede neu auftretende Lücke zurück.
- [x] Community-Rust-/V8-Abhängigkeitstexte und fehlende Bootstrap-Lizenzzuordnungen für die gepinnten Artefakte ergänzen; Quell-/Versions-/Hashnachweise, App-Lizenzansicht und finales APK-Lizenzgate prüfen.
- [ ] Installations-/Update-Test inklusive niedrigem Target SDK, Foreground Service, Notifications, Login, Dateiauswahl und Backups durchführen.
- [ ] Finales APK auf Gerät testen und erst danach als Package Edition veröffentlichen.

## Historische Verifikation des Grundlagenabschnitts

Erfolgreicher [GitHub-Actions-Lauf](https://github.com/Mcpasi/AGENTCODI/actions/runs/37154718553) für Commit `801f44d82fe5aa4398d12395383a0806bb89ec41`:

- Architekturchecks erfolgreich.
- 309 Java-Tests erfolgreich.
- Alle 8 portablen C++-Testsuiten erfolgreich, einschließlich tatsächlicher Ausführung eines selbst installierten Programms über den Supervisor.
- Android-Java-Quellen und Ressourcen gegen API 35 erfolgreich kompiliert; Target SDK 28, Minimum SDK 29 und Editions-Anzeigename geprüft.

Zusätzlich deckt ein Terminal-Shell-Test den Vorrang selbst installierter Programme gegenüber früheren festen Shell-Funktionen ab.

Alle Repository-Zugriffe und Änderungen erfolgen ausschließlich über den GitHub Connector. Die Community-Anbindung aus Abschnitt 2, der minimale Paket-Bootstrap und die Startkatalog-CI aus Abschnitt 3 sind umgesetzt. Das signierte Paketrepository ist einschließlich öffentlicher HTTPS-Veröffentlichung und ARM64-/APT-Laufzeittests umgesetzt. Die Legacy-Quell-/API-Bereinigung ist inzwischen umgesetzt. Die Build-/Cache-Reduktion ist ebenfalls umgesetzt. Weitere Katalogerweiterung bleibt optional. Endgültiger Architektur-/Smoke-Vertrag und Abgleich der gelieferten Lizenzmaterialien sind inzwischen umgesetzt; die nachfolgende Lizenzergänzung ist ebenfalls umgesetzt; echte Gerätetests und finales APK-Release bleiben in Abschnitt 3/4 offen. Die ursprünglichen Verifikationsangaben oben beschreiben den vorausgehenden Grundlagenabschnitt.

## Historische Verifikation der Community-Anbindung

Geprüfter Implementierungscommit: `1fed889377c980f66cdd6eabc0dba2dbcce0b9de`.

- [Tests-Lauf 37165001117](https://github.com/Mcpasi/AGENTCODI/actions/runs/37165001117): alle sechs Jobs erfolgreich — Architektur/Manifest, 303 Java-Tests, acht portable C++-Suites, Android-Kompilierung, Community-Archivprüfung und echter ARM64/Bionic-App-Server.
- [Runtime-Prüfartefakt](https://github.com/Mcpasi/AGENTCODI/actions/runs/37165001117/artifacts/11289216891): erzeugte Schemas, vollständige ELF-/Archivbefunde und Protokollnachweis.
- [APK-Lauf 37165001114](https://github.com/Mcpasi/AGENTCODI/actions/runs/37165001114): vollständiger Debug-APK-Build einschließlich nativer Full-access-, PTY-, Import-Kontext- und Übergangswerkzeug-Smokes erfolgreich.
- Geräteabhängige Linker-Tests wurden mit `AGENTCODI_SKIP_DEVICE_LINKER_TESTS=1` übersprungen; echte Installations-/Hardwaretests bleiben offen.
- `main` bleibt auf `ff27ec7c30d373a864e845e9a7ceeae3380dd103`. Kein PR, Merge oder GitHub-Release der APK wurde eingereicht.

Das [Debug-APK-Artefakt](https://github.com/Mcpasi/AGENTCODI/actions/runs/37165001114/artifacts/11289032769) enthält `AGENTCODI-Package-0.1.0-package.1-arm64-v8a-debug.apk` (149 MiB; SHA-256 `2ebf610159251aea8caa3766d19ded24399efcd05360f19160aab68833529247`). Signatur, Alignment, Application-ID, ABI und enthaltene Runtime wurden erfolgreich geprüft. Dies ist ein CI-Artefakt; ein öffentliches GitHub-Release der finalen APK aus Abschnitt 4 wurde nicht erstellt.

## Entfernung der APK-Übergangswerkzeuge — 2026-10-05

Umgesetzt ausschließlich auf `Mcpasi/package-edition`. Node.js, npm, Python
und ripgrep einschließlich ihrer ausschließlich benötigten Bibliotheken,
Python-Erweiterungen, npm-/Python-Archive und Lizenzassets werden weder
heruntergeladen noch im APK ausgeliefert. Die Build-Input-Liste enthält
jetzt 13 statt 33 Downloads. AAPT2-Abhängigkeiten sind ausschließlich
Build-Werkzeuge. libc++ bleibt für den nativen App-Code; zlib bleibt für
die PNG-Prüfung und erhält ein eigenes vollständiges Lizenzasset.

Die Community-ELFs benötigen dynamisch ausschließlich Android-Systembibliotheken;
der Code-mode-Host enthält seine eigene JavaScript-Laufzeit. Shell, APT/dpkg,
Zertifikate und ihre Abhängigkeiten bleiben im unveränderten Editions-Bootstrap.
Die APK-Assembly prüft die genaue Menge von sechs nativen Dateien, löst jede
ELF-Abhängigkeit gegen diese Menge oder Android-Systembibliotheken auf und
vergleicht sämtliche ausgelieferten nativen Bytes mit der geprüften Assembly.
Ausgemusterte Tool-Assets werden ausdrücklich zurückgewiesen.

Die aktive Shell reicht Befehle direkt an Androids Shell weiter. App-Server,
Codex, Terminal und stdio-MCP verwenden `files/usr/bin`, dann
`$HOME/.local/bin`, dann Android-Systembefehle; `tool-bin` und frühere
APK-Versions-/Aktivierungsvariablen entfallen aus der Prozessumgebung.
Der Supervisor startet ohne Node-/Python-/ripgrep-ELFs oder Tool-Aliase.
Die Java-Startlogik entpackt keinen alten Tool-Runtime-Asset mehr und entfernt
nur erkannte app-erzeugte Aliase, auch nach einem geänderten APK-Installationspfad.
Fremde Links, reguläre Dateien und Nutzerpakete bleiben erhalten.
Ein neuer Java-Test prüft diese idempotente Migration.

Die aktiven Architekturprüfungen und ARM64/Bionic-Smokes prüfen den minimalen
APK-Vertrag, PTY, Imports, Präfixvorrang, persistente Nutzerprogramme,
gemeinsame stdio-MCP-Umgebung und Runtime-Neustart. Reale npm-/Python-
Paketinstallationen bleiben durch die bereits dokumentierte separate
Paketkatalog-CI abgedeckt. README und deutsche/englische UI-/Lizenztexte
beschreiben den neuen Lieferumfang; historische Notices bleiben als historische
Provenienz getrennt erhalten.

Dieser damalige Schritt entfernte den APK-Payload und passte den notwendigen
aktiven Startpfad an. Ungenutzter PackagedToolRuntime-/Activation-/ELF-Attestor-
Quellcode, alte BuildIdentity-Konstanten und reservierte Transportparameter
blieben zunächst für den nachfolgenden Bereinigungspunkt erhalten.
Dieser ist inzwischen umgesetzt; der folgende Ergebnisabschnitt beschreibt ihn.
Docker-/Preflight-/Cache-Reduktion und Lizenzabgleich waren
damals eigene offene Punkte. Die Build-Reduktion und der Abgleich gelieferter
Lizenzmaterialien sind inzwischen umgesetzt. Fehlende vollständige Community-
Abhängigkeitshinweise bleiben Voraussetzung für die finale Veröffentlichung. Alte entpackte Laufzeitdaten werden
nicht automatisch gelöscht.

Verifizierter Implementierungscommit: `fc0f62fc3c36408788fcd8a4403efe56153f13de`.
Der [Tests-Lauf 37389766041](https://github.com/Mcpasi/AGENTCODI/actions/runs/37389766041)
hat alle sieben Jobs bestanden: 327 Java-Tests, acht portable C++-Suiten,
Android-Quellen/Ressourcen gegen API 35, Community-Archiv-/ARM64-Bionic-Prüfungen
und Paket-/Toolchain-Verträge. Der echte Code-mode-Host führte JavaScript bei
einem eingeschränkten Werkzeug-PATH ohne Node/npm aus; das Protokoll prüft
auch Runtime-Neustart und persistente Nutzerprogramme.
Der [APK-Lauf 37389766895](https://github.com/Mcpasi/AGENTCODI/actions/runs/37389766895)
hat alle drei Jobs bestanden: Bootstrap-Build, Bootstrap-ARM64/Bionic-Smoke
und vollständiger Debug-APK-Build mit App-Server-/PTY-/Import-/MCP-Laufzeitprüfungen.
Identität, Signatur, Alignment, ARM64-ABI, 16-KiB-Segmente, genaue ELF-Menge,
Abhängigkeiten und ausgelieferte native Bytes sind geprüft.
Das [Debug-APK-Artefakt](https://github.com/Mcpasi/AGENTCODI/actions/runs/37389766895/artifacts/11380802926)
enthält die separate Package Edition. APK-SHA-256:
`435d7295a53410cf8aafcbe2bc3c168c9fcdf482c8847bd18c448619eba25c30`.
Die gerundete `du -h`-Größe sinkt von 169 MiB beim zuvor dokumentierten
Workspace-Browser-Build (`4949dad012634ef403dae1f03fe72cddcb4ccc2e`)
auf 123 MiB. Dies bleibt ein CI-Artefakt; eine APK-Veröffentlichung oder
Geräte-/Installationsprüfung wurde nicht vorgenommen.

Der erste Implementierungslauf fand einen fehlenden `LinkOption`-Import im
neuen Java-Migrationstest; derselbe Compilerfehler blockierte auch den
Community-Runtime-Job. Die Ursache ist behoben. Die abschließenden
Implementierungsläufe haben keine Testfehlschläge. Der Abschlusscommit
ergänzt ausschließlich Dokumentation; der geprüfte App-Code bleibt unverändert.
Gerätetests werden gemäß Nutzeranweisung übersprungen und bleiben offen.
Kein PR, Merge oder APK-Release; `main` bleibt unverändert.

## Bereinigung der alten Tool-Runtime und Start-APIs — 2026-10-06

Umgesetzt und in CI verifiziert ausschließlich auf `Mcpasi/package-edition`.

PackagedToolRuntime, Alias-Erstellung, Aktivierungsmarker-Abfragen,
ToolchainCommand mit festen APK-Versionen sowie der ungenutzte native
Toolchain-/ripgrep-Policy-/ELF-Guard-/Attestor-/Injector-Code und seine
ausgemusterten Tests sind entfernt. Java, JNI und ProcessConfig übergeben
nur den aktiven nativen App-Server, Code-mode-Host, Shell und die benötigten
Paket-/Privatverzeichnisse. Der alte JIT-Parameter entfällt aus diesem Start-API;
bestehende Launch-Intents werden weiterhin auf Full access migriert.

Neue Installationen legen `tool-bin`, `tool-runtime` und
`workspace/toolchain` nicht mehr an. Vorhandene Archive, Aktivierungsmarker
und Nutzerdateien bleiben erhalten, werden jedoch weder interpretiert noch
als Startvoraussetzung geprüft. Die eng begrenzte Migration erkannter alter
APK-Aliase bleibt idempotent; verlinkte oder nicht als Verzeichnis vorhandene
Alt-Wurzeln werden ignoriert, ohne ihnen zu folgen.

Die Paketbasis und ihr Bootstrap-/Reparaturvertrag bleiben erhalten.
Kanonische ausführbare APK-Dateien, separate Paket-/Privatwurzeln, private
Bildzustandsdaten und Codex-Konfigurationen werden weiterhin validiert.
Der verwaltete Präfix muss auch vom temporären Verzeichnis getrennt sein.
Der lokale Testtreiber und GitHub-CI bauen jetzt dieselbe aktive Package-Shell.
Sieben portable C++-Suiten behalten Supervisor-, PNG-, Framing-, Lifecycle-
und Dateizugriffsprüfungen. Zwei zusätzliche Java-Regressionen prüfen den
Erhalt alter Daten und das Ignorieren verlinkter Altverzeichnisse;
Layout- und Alias-Migrationstests prüfen außerdem fehlende Altverzeichnisse
und den Erhalt fremder Dateien. Der ARM64/Bionic-APK-Smoke verwendet denselben
reduzierten Startvertrag ohne alte Werkzeuge oder Aktivierungsverzeichnisse.

README, SECURITY und CI-Dokumentation sind angepasst. Die damals folgende
vollständige Build-/Docker-/Preflight-/Cache-Reduktion ist inzwischen umgesetzt.
Der anschließend abgeschlossene Abgleich gelieferter Lizenzmaterialien und
die Ausrichtung aller Architektur-/Smoke-Verträge stehen im folgenden
Ergebnisabschnitt. Vollständige Community-Abhängigkeitshinweise bleiben
Voraussetzung für eine finale APK-Veröffentlichung. Gerätetests werden gemäß Nutzeranweisung übersprungen
und bleiben offen. Kein PR, Merge oder APK-Release; `main` bleibt unverändert.

Verifizierter Implementierungscommit: `28a79dff30d966a5056ac80f5e67476ed9acafd5`.
Der [Tests-Lauf 37392093277](https://github.com/Mcpasi/AGENTCODI/actions/runs/37392093277)
hat alle sieben Jobs bestanden: 320 Java-Tests, alle sieben aktuellen portablen
C++-Suiten (293 Engine-Assertions), Android-Quellen/Ressourcen gegen API 35,
Community-Archiv-/ARM64-Bionic-Prüfungen und Paket-/Toolchain-Verträge.
Der [APK-Lauf 37392093667](https://github.com/Mcpasi/AGENTCODI/actions/runs/37392093667)
hat alle drei Jobs bestanden: Bootstrap-Build, Bootstrap-ARM64/Bionic-Smoke
und vollständiger Debug-APK-Build mit App-Server-/PTY-/Import-/MCP-Prüfungen,
Runtime-Neustart, Präfixvorrang und persistenten Nutzerprogrammen.
Identität, Signatur, Alignment, ARM64-ABI, 16-KiB-Segmente, genaue ELF-Menge,
Abhängigkeiten und ausgelieferte native Bytes sind weiterhin geprüft.
Das [Debug-APK-Artefakt](https://github.com/Mcpasi/AGENTCODI/actions/runs/37392093667/artifacts/11381199454)
enthält die separate Package Edition. APK-SHA-256:
`0e161e1a9d9ae4b91123cbe6c690376405b61c349db3d8c68fd25e4c894e48b9`.
Die gerundete APK-Größe bleibt 123 MiB.

Die erste CI fand einen verbliebenen Verweis auf die entfernte Aktivierungsvariable
im Android-Dialog, eine Browser-Testannahme über den früher automatisch angelegten
Toolchain-Ordner und einen falsch maskierten Zeilenumbruch im neuen C++-Sollwert.
Alle drei Ursachen sind behoben. Der Community-Runtime-Job war durch dieselbe
Java-Testannahme blockiert. Die abschließenden Implementierungsläufe haben
keine Testfehlschläge; der frühere APK-Versuch wurde beim Korrekturcommit
durch die bestehende CI-Concurrency-Regel abgebrochen.

Die entfernten geräteabhängigen Guard-/Attestor-Tests gehören zum ausgemusterten
Code; der aktive CI-Vertrag benötigt `AGENTCODI_SKIP_DEVICE_LINKER_TESTS` nicht
mehr. Echte Geräte-/Installations-/Update-Tests bleiben ausdrücklich offen.
Der folgende Abschlusscommit ergänzt ausschließlich diese Roadmap-Nachweise
und kennzeichnet den Punkt als umgesetzt; geprüfter App-Code, Ressourcen und
Build-Konfiguration bleiben unverändert. Kein PR, Merge oder APK-Release;
`main` bleibt auf `ff27ec7c30d373a864e845e9a7ceeae3380dd103`.

## Reduktion der APK-Build-Abhängigkeiten — 2026-10-06

Umgesetzt ausschließlich auf `Mcpasi/package-edition`. Der APK-Build lädt
`patchelf` nicht mehr herunter, entpackt es nicht und verlangt dessen
Versionsprüfung nicht mehr. Die ungenutzte `llvm-objcopy`-Voraussetzung,
der `script`-Befehl und der unbenutzte allgemeine ELF-Relokationshelfer sind
entfernt. Die benötigte gezielte Codex-Host-/zlib-Relokation bleibt erhalten.
Clang, lld und llvm-strip bleiben für Engine, Shell und Bionic-Smokes gepinnt.

Das generierte CI-Input-Manifest enthält 12 statt 13 Downloads: Community-Codex,
Android-Plattform, R8 und die benötigten AAPT2-/libc++-/zlib-Pakete.
Der source-gebaute Paket-Bootstrap behält seinen separaten geprüften
Wiederherstellungspfad. Der Input-Restorer berücksichtigt ausschließlich das
aktuelle Manifest; die Prüfung bricht jetzt auch dann ab, wenn dessen
Neuerzeugung fehlschlägt. Alte oder beschädigte Manifeste und fehlende oder
veränderte Bytes werden zurückgewiesen. Fünf neue Host-Regressionen prüfen
diese Fälle, die Wiederherstellung nur gelisteter Inputs, den Erhalt fremder
Cache-Dateien und die sichere Auswahl der Cache-Pfade.

Der APK-Workflow verwendet einen eigenen Editions-Cache mit OS, Architektur
und dem Hash von Manifest/Cache-Auswahl im Schlüssel. Er enthält nur die
elf aktuellen Nicht-SDK-Dateien, keine ganzen Alt-Caches oder Build-Ausgaben;
es gibt keine Fallback-Schlüssel. Die Bytes werden vor Verwendung erneut gegen
SHA-256 geprüft und erst nach erfolgreicher Manifest-/Input-Prüfung gespeichert.
Die Android-SDK-Datei bleibt ausschließlich im vorhandenen privaten Mirror
oder beim Upstream. Der Community-Archivpfad bleibt SHA-256-adressiert.

Docker verlangt Ubuntu gcc/libc6-dev und bsdutils nicht mehr. Das finale Image
übernimmt ausschließlich den benötigten Termux-Präfix, ohne Home-/Cache-Daten,
APT-Paketlisten oder das temporäre rekonstruierte Sysroot-DEB.
Die gepinnten NDK-r29-Header/CRT und ihre Termux-Patches bleiben notwendige
Compile-/Link-Inputs; sie werden weiterhin aus verifizierten Quellen
rekonstruiert. Preflight prüft Java 17, ARM64, kanonische System-Shell,
ausführbaren Android-Linker und die aktive LLVM-Version. Eine temporäre
API-29-C++/JNI/zlib-Probe prüft Kompilierung und tatsächliche Bionic-Ausführung.
Die alten manuellen Linker-/Guard-/ptrace-/seccomp-Proben sind entfernt.

README und CI-Build-Dokumentation beschreiben denselben Liefer- und
Build-Vertrag. Die historische Verifikation bleibt an ihre früheren Commits
gebunden; ihr damaliger offener Build-Bereinigungspunkt ist jetzt umgesetzt.
Der anschließend abgeschlossene Architektur-/Smoke-Abgleich und der Abgleich
gelieferter Lizenzmaterialien stehen im folgenden Ergebnisabschnitt.
Vollständige Community-Abhängigkeitshinweise bleiben Veröffentlichungsvoraussetzung. Echte Gerätetests wurden
gemäß Nutzeranweisung übersprungen und bleiben offen.

Verifizierter Implementierungscommit: `28c77f3444e1c54b741f3a55513fb2125641f10d`.
Der [Tests-Lauf 37442948418](https://github.com/Mcpasi/AGENTCODI/actions/runs/37442948418)
hat alle sieben Jobs bestanden: 320 Java-Tests, sieben portable C++-Suiten
(293 Engine-Assertions), Android-Quellen/Ressourcen gegen API 35,
Community-Archiv-/ARM64-Bionic-Prüfungen und Paket-/Toolchain-Verträge,
einschließlich der fünf neuen Build-Input-Regressionen.

Der [APK-Lauf 37442949060](https://github.com/Mcpasi/AGENTCODI/actions/runs/37442949060)
hat alle drei Jobs bestanden: Bootstrap-Build, Bootstrap-ARM64/Bionic-Smoke
und vollständiger Debug-APK-Build. Der neue Cache wurde mit genau den
ausgewählten Dateien gespeichert; alle zwölf Build-Inputs sind SHA-256-geprüft.
Container-Preflight einschließlich der neuen nativen Probe, App-Server-/PTY-/
Import-/MCP-Smokes, Runtime-Neustart, Präfixvorrang und persistente Nutzerprogramme
sind erfolgreich. APK-Identität, Signatur, Alignment, ARM64-ABI,
16-KiB-Segmente, genaue ELF-Menge, Abhängigkeiten und ausgelieferte native
Bytes bleiben geprüft.
Das [Debug-APK-Artefakt](https://github.com/Mcpasi/AGENTCODI/actions/runs/37442949060/artifacts/11402117477)
enthält die separate Package Edition. APK-SHA-256:
`723f76120cc70f698b3b146f3f51d2dbf753d1a0be167fb6fe18b7a1d0c7a0dd`.
Die gerundete APK-Größe bleibt 123 MiB; dieser Schritt reduziert die
Build-Voraussetzungen und die Container-/Input-/Cache-Daten.

Die Implementierungsläufe hatten keine Fehlschläge. Der Abschlusscommit
ergänzt ausschließlich diese Roadmap-Nachweise und die Umsetzungsmarkierung;
geprüfter App-Code, Ressourcen und Build-Konfiguration bleiben unverändert.
Kein PR, Merge oder APK-Release; `main` bleibt auf
`ff27ec7c30d373a864e845e9a7ceeae3380dd103`.

## Finaler Testvertrag und Lizenzabgleich — 2026-10-06

Umgesetzt ausschließlich auf `Mcpasi/package-edition`. Die beiden
Nicht-Hardware-Punkte in Abschnitt 4 sind abgehakt. Der
[finale Testvertrag](scripts/package-edition/TEST_CONTRACT.md) ordnet
Architektur-, Java-, sieben portable C++-, Android-Kompilier- und echte
ARM64/Bionic-Prüfungen dem tatsächlich ausgelieferten Paketvertrag zu.

`apk-contract.json` ist die gemeinsame Definition der sechs nativen ARM64-Dateien
und der erlaubten Assets. Architekturprüfung und APK-Assembly verwenden
denselben Vertrag. Der neue Prüfer vergleicht sämtliche nativen Dateien,
Lizenzassets und Raw-Lizenzressourcen bytegenau mit den geprüften Staging-/Quellen.
Zusätzliche ABIs, unbekannte/alte Assets, doppelte ZIP-Dateien, veränderte Bytes,
Bootstrap-Manifest-/Lizenzindex-/Quellnachweisfehler und vorzeitige Release-Builds
werden zurückgewiesen. Vierzehn neue Host-Regressionen prüfen diese Fälle.
Die vorhandenen ELF-, Identitäts-, Signatur-, Alignment- und Runtime-Prüfungen
bleiben erhalten.

Java prüft den Erhalt der vier nutzerinstallierten Befehlsnamen `node`, `npm`,
`python` und `rg`, ihrer Ausführungsrechte, der verwalteten dpkg-Datenbank und
von Nutzer-Caches. Die native Host-Regression injiziert alte Aktivierungsvariablen
in den Elternprozess und verlangt ihre Entfernung im Kindprozess. Der echte
APK-Smoke verlangt denselben bereinigten Vertrag bei Codex-Kommandos, Package-Shell
und stdio-MCP; die bisherigen Full-access-/PTY-/Import-/Präfix-/Neustartprüfungen
bleiben aktiv. Der Code-mode-Host benötigt weiterhin kein externes Node/npm.

Die Bootstrap-Assembly indexiert originale Lizenzdateien einschließlich der
paketzugehörigen Copyright-Links auf gemeinsame Texte unter `share/LICENSES`.
Die Auflösung erfolgt ausschließlich anhand geprüfter Manifestdateien/-links,
ohne fremde Hostpfade zu lesen. Index und Bericht nennen den installierten
paketzugehörigen Pfad, den tatsächlichen ZIP-Text, Größe und SHA-256.
Elf Bootstrap-Assembly-Regressionen prüfen auch gemeinsame und fehlende Ziele.
Der aktuelle Bootstrap umfasst 48 Pakete mit 56 konkreten Lizenztexten,
106 paketbezogenen Datensätzen und 31 Paketen mit gemeinsamen Lizenzverweisen.

Die Lizenzansicht bietet den paketweisen Bootstrap-Index und liest die
originalen Texte aus der mitgelieferten ZIP. Sie beschreibt den APK-Bootstrap;
später installierte oder aktualisierte Pakete behalten ihre eigenen aktuellen
Hinweise im Präfix. Der libc++-Build prüft den Copyright-Verweis auf das
unveränderte, im Repository festgehaltene Termux-NCSA-Quellmaterial. Vollständige
libc++-/libc++abi-/libunwind-Lizenztexte aus einem dokumentierten LLVM-Quellpin
ergänzen die generische Vorlage. Codex- und zlib-Distributorhinweise bleiben
unverändert. Host-Python ist ausschließlich ein Build-Werkzeug; die zwölf
gepinnten Downloads und der minimale APK-Werkzeugumfang bleiben erhalten.
Historische Sandbox-/Tool-Provenienz bleibt in `NOTICE.md`; sie wird nicht
als aktueller APK-Lieferumfang ausgegeben.

**Historisches Ergebnis des Lizenzabgleichs für den unten genannten Commit:**
Die ausgelieferte Menge und die damals gelieferten Nachweise sind abgeglichen.
Die folgenden vier Befunde wurden anschließend im Ergebnisabschnitt
„Ergänzung der fehlenden Lizenzen“ behoben. Das Community-Archiv liefert weiterhin keine
vollständigen Rust-/V8-Abhängigkeitshinweise für den exakten statisch gelinkten
Release-Build. Außerdem hatten `bzip2`, `gpgv` und `xz-utils` damals keine eigene
paketlokale Lizenzdatei im ausgewählten DEB; diese Befunde sind im historischen
Bericht enthalten. Gemeinsame Texte und die korrespondierenden Quellen
waren verfügbar; daraus wurde keine vollständige paketbezogene Attribution
abgeleitet. Das öffentliche Editions-Keyring-Metadatenpaket verwendet den
AGENTCODI-Apache-2.0-Hinweis. Der damalige Debug-Bericht enthält vier
Lizenzblocker und `final_release_ready=false`; Release-Builds dieses Stands
scheiterten am Lizenzgate. Die verbleibenden Zuordnungen/Nachweise wurden
anschließend ergänzt und werden im aktuellen Format-2-Bericht geprüft.
Die historischen vier Blocker bleiben im damaligen Bericht nachvollziehbar.

Verifizierter Code-/Lizenzindex-Commit:
`c04fdf3c93a5f0313c1df675416a73582df75e24`.
Der nachfolgende Commit
`80fc2b0ed134a120a5d6edb5dc254566c8df08d8`
korrigiert ausschließlich die Pfadangaben der ausgelieferten Lizenzhinweise
und der Repository-Dokumentation; die folgenden Tests-/APK-Nachweise beziehen
sich auf diesen tatsächlichen Payload-Stand.

Der [Tests-Lauf 37448382223](https://github.com/Mcpasi/AGENTCODI/actions/runs/37448382223)
hat alle sieben Jobs bestanden: 320 Java-Tests, sieben portable C++-Suiten
(294 Engine-Assertions), Android-Quellen/Ressourcen gegen API 35,
Community-Archiv-/ARM64-Bionic-Prüfungen sowie Paket-/Toolchain-Verträge.
Die Architekturprüfung besteht einschließlich der 14 APK-Vertragsregressionen;
die elf Bootstrap-Assembly- und fünf Build-Input-Regressionen sind ebenfalls grün.

Der [APK-Lauf 37448382664](https://github.com/Mcpasi/AGENTCODI/actions/runs/37448382664)
hat alle drei Jobs bestanden: Bootstrap-Build, Bootstrap-ARM64/Bionic-Smoke
und vollständiger Debug-APK-Build. App-Server-, Codex-/PTY-/Import-/MCP-Smokes,
Runtime-Neustart, Präfixvorrang und persistente Nutzerprogramme sind erfolgreich.
Der finale APK-Verifier bestätigt die exakte Payload und unveränderte
gelieferte Lizenzbytes; er meldet erwartungsgemäß vier offene Lizenzblocker.
Das [Debug-APK-Artefakt](https://github.com/Mcpasi/AGENTCODI/actions/runs/37448382664/artifacts/11404469000)
enthält die separate Package Edition; der
[APK-Vertragsbericht](https://github.com/Mcpasi/AGENTCODI/actions/runs/37448382664/artifacts/11404995462)
enthält Datei-/Lizenzhashes, Paket-/Quellnachweise und die Veröffentlichungsblocker.
APK-SHA-256:
`5f93a77255498029e44e23a29c54ba83938403163dd4f4b983dc55292ad1d2cf`.
Die gerundete APK-Größe bleibt 123 MiB.

Der [Paketkatalog-Lauf 37447449356](https://github.com/Mcpasi/AGENTCODI/actions/runs/37447449356)
für den Code-/Lizenzindex-Commit hat alle 14 Jobs bestanden:
Quellbuilds für Git, Python, ripgrep und Node.js/npm, Bootstrap-/Werkzeug-Smokes,
signierter APT-Build, ARM64-Installations-/Updateprüfungen und öffentliche
HTTPS-Prüfung einschließlich vollständiger Quellenverfügbarkeit.
Die bestehende CI veröffentlicht dabei automatisch das separate APT-Repository;
dies erstellt kein finales APK-Release.

Der erste Tests-Lauf fand eine veraltete Fixture-Erwartung: die dpkg-MD5-Liste
enthält nach der Ergänzung der Lizenzdatei auch deren Prüfsumme. Der Sollwert
ist korrigiert. Die gemeinsame Termux-Lizenzsammlung und die Copyright-Links
wurden anhand der echten Paketberichte ergänzt; auch der libc++-Lizenzlink
wird nun ohne zusätzlichen Download gegen festgehaltene Quellbytes aufgelöst.
Die finalen Implementierungsläufe sind nach diesen Ursachenbehebungen vollständig grün.

Geräteabhängige Installation, Parallelbetrieb, APK-Updates, Foreground-Service,
Notifications, Login, Dateiauswahl/Backups und reale Hardware-Linkerprüfungen
wurden gemäß Nutzeranweisung nicht ausgeführt und bleiben offen. Hosted
ARM64/Bionic-Container ersetzen diese Tests nicht. Kein PR, Merge oder finales
APK-Release; `main` bleibt auf
`ff27ec7c30d373a864e845e9a7ceeae3380dd103`.

Der Abschlusscommit ergänzt ausschließlich Roadmap-Nachweise und Markierungen.
Die historische Verifikation bleibt an ihre jeweils genannten Commits gebunden.

## Ergänzung der fehlenden Lizenzen — 2026-10-06

Umgesetzt ausschließlich auf `Mcpasi/package-edition`. Der Lizenzpunkt in
Abschnitt 4 ist getrennt von Geräteprüfung und finaler Veröffentlichung
abgehakt. Alle echten Geräte-/Installations-/Update-Tests bleiben offen.

Die unveränderten LICENSE/NOTICE des Community-Archivs werden durch
[quellengebundene Abhängigkeitstexte](third_party/community-codex/README.md)
ergänzt: 1.033 Cargo-Komponenten der Android-Normal-/Build-Abhängigkeiten,
Rust-Standardbibliothek und exakte rusty_v8-/V8-Quellen mit 20 rekursiven
Submodul-Pins. Die Sammlung enthält 669 unterschiedliche Texte und keine
offenen Komponenten. Cargo.lock, Ziel, Quellrevision, Originalautoren,
Archiv-/Dateihashes und die nativen Release-Hashes sind gebunden.
64 ausgelassene Crate-Dateien wurden aus exakt belegten Git-Revisionen
wiedergewonnen. 16 weitere Fälle verwenden vollständig ausgeschriebene,
im checksum-geprüften Crate erklärte MIT-/Apache-Bedingungen; vorhandene
Autoren-/Copyright-Hinweise bleiben erhalten und diese Ergänzungen sind
ausdrücklich gekennzeichnet. Jahreszahlen oder Copyright-Inhaber werden
nicht erfunden. Konservative Build-/V8-Testquellen werden mitgeführt;
dies ist kein Nachweis einer unabhängig reproduzierten nativen Binärdatei.

[Lizenzsammlung 37454140926](https://github.com/Mcpasi/AGENTCODI/actions/runs/37454140926)
besteht einschließlich vier Auswahl-/Attributionsregressionen. Ihre erneute
Erzeugung stimmt bytegenau mit dem festgehaltenen Index und ZIP überein.
Die laufende Regeneration benötigt kein später ablaufendes CI-Artefakt.
Die App bietet eine Komponenten-/Dateiauswahl, einschließlich lesbarer
Darstellung des unverändert aufbewahrten Rust-Copyright-HTMLs.

`bzip2`, `gpgv` und `xz-utils` erhalten die vollständigen rechtlichen Dateien
ihrer Elternpakete unter eigenen, von dpkg verwalteten Pfaden. libbz2/bzip2
verwenden Revision 9, GnuPG/gpgv und liblzma/xz-utils Revision 2.
Der zusätzliche Abgleich fand die unvollständige GPL-only-Deklaration von
`attr` und die im bisherigen XZ-Rezept ausgelassene explizite 0BSD-Zuordnung:
attr nennt nun GPL-2.0 und LGPL-2.1; attr/libacl behalten originale
`doc/COPYING` und `doc/COPYING.LGPL`, GnuPG/gpgv originales `COPYING`.
liblzma/xz-utils behalten auch originales `COPYING.0BSD` neben Zusammenfassung
und GNU-Lizenztexten. attr/libacl verwenden Revision 1. APT kann die
ergänzten Dateien damit auch über Paket-Updates ausliefern.

Savannah war über HTTP und HTTPS nicht erreichbar; die geprüften Spiegel
lieferten die exakten attr-/acl-Versionen nicht. Die vollständigen Originalarchive
wurden aus früherer erfolgreicher, checksum-geprüfter Quell-CI
[wiederhergestellt](https://github.com/Mcpasi/AGENTCODI/actions/runs/37456334585).
[Originalquellen und Provenienz](third_party/package-source-archives/README.md)
liegen unverändert auf diesem Branch; immutable GitHub-URLs ersetzen den
unzuverlässigen Bezug bei identischen Original-SHA-256 und Versionen.
[Archivprüfung 37456619360](https://github.com/Mcpasi/AGENTCODI/actions/runs/37456619360)
bestätigt beide Hashes und originale GPL-/LGPL-Dateien. Es werden keine
Binärpakete als Build-Seeds eingeführt; die zwölf APK-Inputs bleiben unverändert.

Ein frischer Python-Quellbau deckte außerdem einen Fehler in der Katalog-
Rezeptauswahl auf: `python-ensurepip-wheels` wurde nach erfolgreichem Python-Bau
als selbständiges Quellrezept aufgerufen. Der Build löst nun ausgewählte DEBs
auf eindeutige Elternrezepte auf, baut Python einmal und behält beide
Runtime-Pakete in der Auswahl. Vier Katalog-Quellregressionen prüfen
Quellabdeckung sowie Elternauflösung und verweigern unbekannte/mehrdeutige
Rezepte ohne Binärrepository-Fallback.

Geprüfter Implementierungscommit: `7c4ef061ff96841621c30710267a6427435c1640`.
[Tests 37459913151](https://github.com/Mcpasi/AGENTCODI/actions/runs/37459913151)
besteht mit allen sieben Jobs: Java, sieben portable C++-Suiten,
Android-Kompilierung, Community-Archiv-/ARM64-Bionic-Vertrag und Paket-/
Toolchain-Prüfungen. Dazu gehören 20 APK-Vertragsregressionen, elf Bootstrap-
Assembly- und vier Katalog-Quellregressionen.

[APK 37459913761](https://github.com/Mcpasi/AGENTCODI/actions/runs/37459913761)
besteht mit allen drei Jobs: frischer Bootstrap-Quellbau, ARM64/Bionic-Smoke
und vollständiger Debug-APK-Build. Der neue Bootstrap umfasst 48 Pakete,
113 paketbezogene Lizenzdatensätze und 66 konkrete Lizenzdateien ohne Lücke.
Der finale Verifier bestätigt sämtliche nativen/Asset-/Lizenzbytes,
Cargo-/Quell-/Release-Bindungen und dpkg-Eigentümerschaft. Er meldet
**null Lizenzblocker**, entsprechend `license_release_ready=true` im
Format-2-Bericht. `device_tests` bleibt ausdrücklich unausgeführt.

[Debug-APK-Artefakt](https://github.com/Mcpasi/AGENTCODI/actions/runs/37459913761/artifacts/11413840550)
und [APK-Vertragsbericht](https://github.com/Mcpasi/AGENTCODI/actions/runs/37459913761/artifacts/11413935702)
sind verfügbar. Der Bericht hält APK-, Datei- und Lizenz-SHA-256 sowie Paket-/
Quellnachweise fest. Die gerundete APK-Größe ist 124 MiB. Identität, Signatur,
Alignment, ARM64-ABI, 16-KiB-Segmente und bestehende Runtime-Prüfungen bleiben
erfolgreich. Die sechs nativen Dateien und zwölf gepinnten APK-Inputs bleiben
der aktive Vertrag.

Der zusätzliche [Paketkatalog-Lauf 37459913693](https://github.com/Mcpasi/AGENTCODI/actions/runs/37459913693)
führt die frischen Quellbuilds für Git, Python, ripgrep und Node.js/npm,
Bootstrap-/Katalog-ARM64-Bionic-Smokes, signierte Repository-Erzeugung und
öffentliche HTTPS-/Quellenprüfung für denselben Implementierungscommit aus.
Die bestehende Branch-CI veröffentlicht das separate APT-Repository erst
nach erfolgreichen Katalogprüfungen; dies erstellt kein finales APK-Release.
Die Jobergebnisse und zugehörigen Quellen-/DEB-Artefakte bleiben in diesem
Lauf nachvollziehbar.

Der abschließende Dokumentationsabgleich aktualisiert ausschließlich
`ROADMAP-package-edition.md`, `.github/ci/README.md` und `NOTICE.md`.
Der Code-/Payload-Vertrag bleibt an den oben geprüften Implementierungscommit
gebunden. Echte Android-Hardware-, Installations-, Update- und Service-Tests
bleiben gemäß Nutzeranweisung offen. Kein PR, Merge oder finales APK-Release;
`main` bleibt auf `ff27ec7c30d373a864e845e9a7ceeae3380dd103`.

## MCP-Tool-Freigaben und Versionsbump — 2026-10-06

Ausschließlich `Mcpasi/package-edition`; kein PR oder Merge nach `main`.
Der Nutzer meldet erfolgreiche Installation und Benutzung von Paketen auf einem
Gerät. Die MCP-Tool-Nutzung scheiterte: `prompt` fordert korrekt eine Freigabe,
doch der Client kannte `mcpServer/elicitation/request` nicht. Er antwortete mit
`-32601 / Client request is not supported`; die Runtime lehnte das Tool ab,
ohne dass ein Dialog sichtbar wurde.

Der reine [Reproduktionscommit](https://github.com/Mcpasi/AGENTCODI/commit/b3aa230e77e1ee7f00962bfc5ebc05a9d7cf15aa)
und [Tests-Lauf 37492914015](https://github.com/Mcpasi/AGENTCODI/actions/runs/37492914015)
weisen genau diese Ablehnung im Java-Job nach. Die ebenfalls fehlschlagende
Community-Runtime-Prüfung verwendet dieselbe Java-Suite.

Die Korrektur verarbeitet message-only Form-Anfragen mit
`codex_approval_kind=mcp_tool_call` als eigene MCP-Tool-Freigabe. Anders als
Kommando-/Dateifreigaben haben diese Anfragen keine `itemId` oder
`startedAtMs`; `turnId` darf null sein. Thread und vorhandener Turn werden
geprüft. Der Dialog zeigt Server, Anfrage und begrenzte, redigierte Parameter
in Chat, Einstellungen und MCP-Verwaltung. Erlauben gilt einmal; Ablehnen und
Abbrechen führen das Tool nicht aus. Die Antwort verwendet
`action/content/_meta` mit null-Inhalt/-Metadaten, keine Kommando-`decision`.
Stale, volle oder abgelaufene Anfragen werden mit MCP-`cancel` geschlossen.
Serverseitig aufgelöste Anfragen lassen sich nicht nachträglich freigeben.
Formulare mit zusätzlichen Eingabefeldern und URL-Elicitations bleiben
ausdrücklich ununterstützt und werden sicher zurückgewiesen.

Fünf Java-Regressionen prüfen diesen Vertrag einschließlich aller drei
Entscheidungen, null-Turn, Warteschlangenlimit, ungültiger Anfragen,
serverseitiger Auflösung und Ablauf. Die Community-CI validiert die tatsächlich
erzeugten Java-MCP-Anfragen/-Antworten gegen die vom gepinnten ELF erzeugten
Schemas. Zusätzlich prüft ein synthetischer lokaler HTTP-MCP-Server mit
deterministischem Modellfixture die echte ARM64/Bionic-`prompt`-Sperre:
kein Aufruf vor Freigabe, genau ein Aufruf nach Erlauben, kein Aufruf nach
Ablehnen oder Abbrechen. Die abgeschlossenen CI-/APK-Nachweise dieses Implementierungsstands
stehen unten.

Version: `0.1.0-package.2`, Android `versionCode 2`. Manifest, BuildIdentity,
Build-Skript, Identitätstests, Architekturvertrag und aktive Dokumentation
verwenden denselben Stand; frühere Artefakte/Versionsangaben bleiben an ihre
historischen Commits gebunden. Paketinstallation/-benutzung ist als
Nutzerbericht dokumentiert, ohne die vollständige Geräte-/Versionsmatrix als
bestanden zu markieren. Weitere echte Geräteprüfungen und ein Hardware-Retest
des MCP-Fixes werden gemäß Nutzeranweisung übersprungen und bleiben offen.

Der erste erweiterte [Runtime-Lauf](https://github.com/Mcpasi/AGENTCODI/actions/runs/37493948718)
bestand Java, Schema, Kommandos, PTY und den bisherigen Code-mode-Host-Smoke,
scheiterte aber am zusätzlich erzwungenen experimentellen Code-mode-Callback:
der gepinnte Host meldete SIGSEGV, bevor eine MCP-Anfrage entstand. Die
MCP-Freigabeprüfung verwendet jetzt den in der gepinnten Upstream-Testsuite
verwendeten nativen Modell-Funktionsaufruf mit MCP-Namespace in einem eigenen
Runtime-Prozess. Sie prüft weiterhin den echten `prompt`-Pfad und die realen
Java-Antworten; der vorhandene Code-mode-Host-Smoke bleibt erhalten.
Dies ist kein Nachweis einer Reparatur des Community-Hosts für verschachtelte
experimentelle Code-mode-Callbacks. Ein solcher Callback-/Hardware-Nachweis
bleibt außerhalb der hier bestätigten MCP-Freigabeprüfung.

### Verifikation des MCP-Fixes

App-/Versions-/Payload-Commit:
`0968f19930113e3f62090171e93faa278cf96366`.
Die nachfolgende native MCP-Testfixture und ihr Dokumentationsabgleich liegen
auf `6ff314b864ff7848d123165db927bccc6d72a4e9`; App, Ressourcen,
Versionspins und APK-Lieferumfang sind dabei unverändert.

[Tests-Lauf 37494809156](https://github.com/Mcpasi/AGENTCODI/actions/runs/37494809156)
hat alle sieben Jobs bestanden: 325 Java-Tests, alle sieben portablen
C++-Suiten (294 Engine-Assertions), Architektur-/Paket-/Toolchain-Verträge,
Android-Quellen/Ressourcen gegen API 35, Community-Archiv und echte
ARM64/Bionic-Runtime. Die generierten Schemas validieren 418 tatsächliche
Java-RPCs sowie 15 MCP-Freigabeanfragen/-antworten. Der
[Runtime-Nachweis](https://github.com/Mcpasi/AGENTCODI/actions/runs/37494809156/artifacts/11427551406)
hält die drei echten MCP-`prompt`-Entscheidungen fest: nur Erlauben erhöht
den Tool-Aufrufzähler auf eins; Ablehnen und Abbrechen erhöhen ihn nicht.
Der separate bisherige Code-mode-Host-, PTY-, Import-, Full-access- und
Neustartvertrag ist ebenfalls erfolgreich.

[APK-Lauf 37493949315](https://github.com/Mcpasi/AGENTCODI/actions/runs/37493949315)
ist erfolgreich abgeschlossen: neuer Bootstrap aus gepinnten Quellen,
ARM64/Bionic-Bootstrap-Smoke und Debug-APK-Bau auf dem oben genannten
App-/Payload-Commit. Der Build wiederholt 325 Java-Tests, die C++-Hostprüfungen
einschließlich 674 Terminal-Bootstrap-Assertions und die Architekturverträge.
Die fertige APK meldet `de.agentcodi.pkg`, `versionCode 2` und
`versionName 0.1.0-package.2`; Signatur, Alignment, ARM64-ABI sowie
Payload- und Lizenzbytes sind geprüft. Der Payload-/Lizenzprüfer meldet
null Blocker innerhalb dieses Prüfvertrags.

- [APK-Artefakt](https://github.com/Mcpasi/AGENTCODI/actions/runs/37493949315/artifacts/11427764122):
  `AGENTCODI-Package-0.1.0-package.2-arm64-v8a-debug.apk`
  (debug-signiert, nicht debuggable).
- APK-Datei-SHA-256: `7e929e795da754b21b493c1066110def67a4e6901b0d66159d88dcf3e92269eb`.
- [Payload-/Lizenzbericht](https://github.com/Mcpasi/AGENTCODI/actions/runs/37493949315/artifacts/11428327399).
- [Bootstrap und korrespondierende Quellen](https://github.com/Mcpasi/AGENTCODI/actions/runs/37493949315/artifacts/11428401113).

Der durch den Paket-Testvertrag automatisch mitgestartete
[Paketkatalog-Neubau 37493949752](https://github.com/Mcpasi/AGENTCODI/actions/runs/37493949752)
ist zum Stand dieser Dokumentation (2026-10-06, 16:45 UTC) noch nicht komplett:
Signatur-Voraussetzungen, Bootstrap, dessen ARM64/Bionic-Smoke und der
ripgrep-Quellbau sind erfolgreich; Git-, Python- und Node-Quellbau laufen noch.
Es ist zu diesem Zeitpunkt kein Job fehlgeschlagen. Dieser Zwischenstand
belegt keinen abgeschlossenen Katalog-/APT-Publikationslauf und ändert nicht
die abgeschlossenen Tests-/APK-Nachweise oben. Die Katalog-Rezept-Eingaben
wurden für den MCP-Fix nicht geändert.

Der abschließende Nachweis-Commit ändert ausschließlich diese Roadmap und
den Package-Edition-Changelog; App, Versionspins und Build-Lieferumfang
bleiben unverändert. Physische Geräteprüfungen bleiben übersprungen/offen.
Es wurde kein PR, Merge oder finales APK-Release erstellt; `main` bleibt
auf `ff27ec7c30d373a864e845e9a7ceeae3380dd103`.

## Stabile Debug-Signierung und Versionsbump — 2026-10-06

Der Nutzer meldet einen Installations-/Updatekonflikt trotz vollständigem
Android-Versionsbump; der aktuell verwendete Build hat `versionCode 2`.
Die Debug-Keystores wurden ausschließlich auf CI-Runnern erzeugt.

Der Signaturvergleich bestätigt die Ursache:
[APK-Lauf 37459913761](https://github.com/Mcpasi/AGENTCODI/actions/runs/37459913761)
(`0.1.0-package.1` / Code 1) meldet Zertifikats-SHA-256
`3e15a999a522f3c7179ea99b89df80b4e08853819a9417f10d4efdac88bcdc55`;
[APK-Lauf 37493949315](https://github.com/Mcpasi/AGENTCODI/actions/runs/37493949315)
(`0.1.0-package.2` / Code 2) meldet
`66eaf52ce0fe2ff694d15e22b9e22af0cae96c833c36ac28722073d3254a4399`.
`build-debug-apk.sh` erzeugte bei fehlendem lokalem Keystore jeweils ein
zufälliges Schlüsselpaar. Der explizite CI-Input-Cache und die hochgeladenen
Artefakte enthalten diesen Keystore nicht. Die Prüfung des Zertifikatsnamens
erkannte den wechselnden Schlüssel nicht. Gleiche App-ID und höherer
Versionscode reichen Android bei inkompatibler Signatur nicht für ein Update.

Der [reine Reproduktionscommit](https://github.com/Mcpasi/AGENTCODI/commit/87edf38c52c5ec0a1d62638ec14e02e780ae0e4d)
und [Tests-Lauf 37501539976](https://github.com/Mcpasi/AGENTCODI/actions/runs/37501539976)
führen den tatsächlichen Debug-Signierungsblock mit zwei AAPT2-APKs
in leeren, unabhängigen Build-Caches aus.
Der Android-Job scheitert genau am unterschiedlichen Zertifikatsfingerabdruck;
die übrigen sechs Jobs bestehen.

Die Korrektur verwendet ausschließlich für Entwicklungs-APKs den versionierten,
öffentlichen AOSP-Testsignierer. [Herkunft, Lizenz und SHA-256-Pins](scripts/debug-signing/README.md)
sind nachvollziehbar; `sign-debug-apk.py` prüft Schlüssel-/Zertifikatsbytes
und anschließend die tatsächlich signierte APK. Der stabile Zertifikats-Pin ist
`a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc`.
Fehlendes oder verändertes Material beendet den Build; es gibt keine
Schlüssel-Neuerzeugung als Ersatz. Ein vorhandener alter Cache-Keystore wird
ignoriert und erhalten. Der private Testschlüssel bleibt ein öffentliches
Build-Fixture und wird nicht als App-Asset ausgeliefert. Er authentifiziert
kein offizielles Release. Der Release-Pfad behält seine externe private
Keystore-Konfiguration und lehnt dieses öffentliche Testzertifikat explizit ab.

Fünf Signierungsregressionen prüfen kalte Builds/höheren Versionscode,
alte Cache-Keystores, fehlendes Material, manipulierten Schlüssel bzw.
Zertifikat und die Release-Ablehnung. Der neue Stand ist
`0.1.0-package.3` / `versionCode 3`. Java-Identität, Manifest,
Build-Skript, Architekturvertrag und die drei beim vorigen Bump ausgelassenen
nativen Versionspins sind abgeglichen. Historische Build-/CI-Angaben oben
bleiben an ihre ursprünglichen Commits und Artefakte gebunden.

Die APK-CI darf den in Lauf `37493949315` erfolgreich aus gepinnten Quellen
gebauten Bootstrap wiederverwenden. Der bestehende wiederverwendbare Workflow
prüft Branch, Quell-/Build-Eingaben, erfolgreichen Producer, nicht abgelaufenes
Artefakt, SHA-256-Summen und Lock; der aktuelle APK-Bau und dessen Tests laufen
vollständig weiter. Paket-Rezepte wurden für diesen Signierungsfix nicht geändert.

**Übergang vorhandener Installationen:** Der ursprüngliche private
CI-Schlüssel wurde nicht aufbewahrt und lässt sich aus einer APK bzw. ihrem
öffentlichen Zertifikat nicht zurückgewinnen. Daher kann dieser Fix die
bestehende zufällig signierte Installation nicht ohne diesen Schlüssel
aktualisieren. Vor dem einmaligen Entfernen/Neuinstallieren sind alle benötigten
Daten zu exportieren und die Sicherungen zu prüfen. Workspace-ZIPs enthalten
nicht automatisch private Chats, Zugangsdaten oder installierte Pakete;
Deinstallation löscht private App-Daten. Spätere APKs mit dem stabilen
Zertifikat und höherem Versionscode erfüllen die Signaturvoraussetzung.
Physische Installations-, Update- und Datenerhaltungsprüfungen bleiben gemäß
Nutzeranweisung übersprungen/offen. Die abgeschlossenen CI- und APK-Nachweise dieses Fixes stehen unten. Ausschließlich `Mcpasi/package-edition`,
kein PR, kein Merge nach `main`.

Der erste [erweiterte Testlauf 37502585222](https://github.com/Mcpasi/AGENTCODI/actions/runs/37502585222)
bestätigt bereits gleiche Debug-Signierer sowie alle vier weiteren
Signierungsregressionen, scheitert aber an der zusätzlich geprüften
Versionsdifferenz der Fixture: AAPT2 übernimmt die Version aus einem bereits
versionierten Manifest statt sie allein durch CLI-Optionen zu ersetzen.
Die Fixture erstellt deshalb jetzt ein eigenes Manifest mit um eins erhöhtem
Versionscode. Der Test liest beide tatsächlichen APK-Identitäten mit
`aapt2 dump badging` und verlangt gleiche App-ID und steigenden Code.
Die Korrektur betrifft ausschließlich Test-/Dokumentationsdateien; die
App-/Signer-/Versions-/Payload-Dateien auf `5f0632e4286823b6b52a73282c305545d589f6b5`
bleiben unverändert.

### Verifikation der stabilen Debug-Signierung

App-/Signer-/Versions-/Payload-Commit:
`5f0632e4286823b6b52a73282c305545d589f6b5`.
Die korrigierte Testfixture und deren Dokumentation liegen auf
`d92f1ac6f09be5b1b6313d096563ecc48b4df69b`; der Vergleich dieser
Commits zeigt ausschließlich Test-/Dokumentationsänderungen, keinen
geänderten APK-Lieferumfang.

[Tests-Lauf 37503097714](https://github.com/Mcpasi/AGENTCODI/actions/runs/37503097714)
besteht alle sieben Jobs: 325 Java-Tests, sieben portable C++-Suiten,
Architektur-/Paket-/Toolchain-Verträge, Android-Quellen/Ressourcen gegen
API 35, Community-Release-Inspektion und echte ARM64/Bionic-Runtime.
Alle fünf neuen Signierungsregressionen bestehen. Die tatsächlichen
Fixture-APKs haben dieselbe App-ID und `versionCode 3 → 4`; ihre
Signaturen sind mit dem SDK-apksigner geprüft und verwenden beide
`a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc`.

[APK-Lauf 37502586391](https://github.com/Mcpasi/AGENTCODI/actions/runs/37502586391)
ist vollständig erfolgreich: geprüfte Bootstrap-Wiederverwendung,
ARM64/Bionic-Smoke und vollständiger APK-Bau. Der Build wiederholt alle
Hosttests (325 Java-Tests, unter anderem 294 Engine- und 674
Terminal-Bootstrap-Assertions) und die Architekturverträge.
Die fertige `de.agentcodi.pkg`-APK meldet `versionCode 3`,
`versionName 0.1.0-package.3` und tatsächlich den gepinnten
AOSP-Testsignierer
`a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc`.
Signatur, Alignment, ABI, Payload und ausgelieferte Lizenzbytes sind geprüft;
der Payload-/Lizenzvertrag meldet innerhalb seines Umfangs null Blocker.

- [APK-Artefakt](https://github.com/Mcpasi/AGENTCODI/actions/runs/37502586391/artifacts/11430427890):
  `AGENTCODI-Package-0.1.0-package.3-arm64-v8a-debug.apk`.
- APK-Datei-SHA-256:
  `3816238666301182bae8d53d4534c67a19ee16442580f111ac971e76b2e6e430`.
- [Payload-/Lizenzbericht](https://github.com/Mcpasi/AGENTCODI/actions/runs/37502586391/artifacts/11430582818).

Der abschließende Dokumentationsabgleich ändert nur Roadmap, Changelog,
Security Policy und NOTICE-Provenienz; die geprüfte App und ihr Lieferumfang
bleiben unverändert. Die Signierungsfixture und dieser Build ersetzen keine
physische Android-Update-/Datenerhaltungsprüfung. Der Schlüsselwechsel
von einer früheren zufälligen CI-Identität bleibt ohne Originalschlüssel
ein einmaliger Wechsel durch gesicherte Neuinstallation.
Kein PR, Merge oder finales APK-Release. `main` bleibt unverändert auf
`ff27ec7c30d373a864e845e9a7ceeae3380dd103`.
