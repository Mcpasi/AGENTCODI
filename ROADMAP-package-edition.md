# Roadmap: AGENTCODI Package Edition

Stand: 2026-10-03. Ausschließlich Branch `Mcpasi/package-edition`; kein Merge nach `main`.

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

Die alten geschützten Core-Verträge und das Modul bleiben vorerst für die bestehende Regressionstestsuite und den Übergangsbuild im Repository. Die App startet ausschließlich `:danger-full-access`; die historische interne Mode-ID `compatibility` bleibt zur Kompatibilität mit bestehenden Sitzungsdaten erhalten.

## 2. Community-App-Server anbinden

Der eigene Fork basiert laut GitHub auf `DioNanos/codex-termux`. Geprüftes Community-Release vom 2026-09-24:

- Repository: https://github.com/DioNanos/codex-termux
- Release: `v0.156.1-termux.1`, Upstream `rust-v0.156.1`
- Quellcommit des Release-Tags: `ea762071ec4acbf1531fcc7daf47524836f70a09`
- Archiv: `mmmbuto-codex-cli-termux-0.156.1-termux.1.tgz`
- SHA-256 laut GitHub-Release-Asset-Digest: `44cee2f3a4a110fd79d4f7d61378d46fd72406f45cffb3163e809d63e86d946a`

Diese Angaben sind das überprüfte Migrationsziel, noch nicht die aktive Build-Konfiguration.

- [ ] Release-Archiv in CI herunterladen, Prüfsumme verifizieren und Inhalt einschließlich Code-mode-Host, Lizenzen und Abhängigkeiten untersuchen.
- [ ] Quellcommit und vollständige Binär-/Schema-Prüfsummen erfassen; keine alten Hashes oder Binäroffsets wiederverwenden.
- [ ] `scripts/update-codex-runtime.sh`, CodexRuntimeUpdater/Metadata/LocalSource, BuildIdentity, Build-Input-Liste und Notices auf den Community-Kanal umstellen.
- [ ] App-Server-JSON-Schema gegen alle verwendeten RPCs prüfen: Initialize, Login, Models, Permission Profiles, Thread/Turn, Approvals, Terminal-PTY, MCP und Connectors.
- [ ] Anpassungen für umbenannte Felder, Fähigkeiten oder Methoden in Client/SessionController umsetzen und mit realem App-Server prüfen.
- [ ] Code-mode-Host-Auflösung prüfen. Falls weiterhin eine APK-Bibliothek umbenannt wird, den neuen Offset am neuen Artefakt bestimmen.
- [ ] Native Startargumente auf Full access reduzieren und das unbenutzte `agentcodi-workspace`-Profil entfernen.
- [ ] Alte Protected-/JIT-Verträge, Module, Ressourcen und Tests gezielt ablösen; übrige Regressionen behalten.
- [ ] CI-Sandbox-Sonderoptionen und seccomp/ptrace-/Protected-Smokes im Edition-Build durch Full-access-Smokes ersetzen.
- [ ] Keine Runtime-Aktualisierung darf wieder den Mcpasi-Sandbox-Fork auswählen.

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
- [ ] Verwalteten Präfix auf `files/usr` außerhalb des Benutzer-Homes umstellen. Bestehende Dateien in `$HOME/.local` erhalten; Übergang/Migration und Suchreihenfolge dokumentieren und testen.
- [ ] Reproduzierbaren Stand von `termux-packages` und Toolchain festlegen; gezielte Build-Anpassungen für App-ID, Präfix und Repository-URLs versionieren. Bootstrap, Paketmetadaten, Shebangs, RPATH/RUNPATH und Konfigurationen auf denselben finalen Pfad ausrichten.
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

## Verifikation dieses Bauabschnitts

Erfolgreicher [GitHub-Actions-Lauf](https://github.com/Mcpasi/AGENTCODI/actions/runs/37154718553) für Commit `801f44d82fe5aa4398d12395383a0806bb89ec41`:

- Architekturchecks erfolgreich.
- 309 Java-Tests erfolgreich.
- Alle 8 portablen C++-Testsuiten erfolgreich, einschließlich tatsächlicher Ausführung eines selbst installierten Programms über den Supervisor.
- Android-Java-Quellen und Ressourcen gegen API 35 erfolgreich kompiliert; Target SDK 28, Minimum SDK 29 und Editions-Anzeigename geprüft.

Zusätzlich deckt ein Terminal-Shell-Test den Vorrang selbst installierter Programme gegenüber früheren festen Shell-Funktionen ab.

Alle Repository-Zugriffe und Änderungen erfolgen ausschließlich über den GitHub Connector. Ein vollständiger Android-APK-Build und echte Gerätetests sind noch offen. Der Community-Runtime-Wechsel, Paketmanager und verkleinerte Build sind ausdrücklich offen.
