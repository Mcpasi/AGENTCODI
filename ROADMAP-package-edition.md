# Roadmap: AGENTCODI Package Edition

Stand: 2026-10-03. Ausschließlich Branch `Mcpasi/package-edition`; kein Merge nach `main`.

## Ziel und feste Entscheidungen

Nutzer installieren eigene Pakete, die Codex und das Terminal direkt verwenden können. Diese zweite Entwicklungslinie nutzt `targetSdk 28`, bietet ausschließlich Full access und richtet sich an erfahrene Nutzer. Androids Isolation zwischen Apps bleibt bestehen; eine zusätzliche Workspace-Sandbox wird hier nicht angeboten.

Ein Target-SDK-Wechsel allein liefert weder einen Paketmanager noch eine passende Paketquelle. Programme benötigen Android ARM64/Bionic und den richtigen Installationspräfix.

## 1. Grundlage

- [x] Separaten Branch vom Main-Stand `ff27ec7c30d373a864e845e9a7ceeae3380dd103` anlegen.
- [x] Target SDK in Manifest, Build-Skript und BuildIdentity auf 28 setzen; Minimum SDK 29 beibehalten.
- [x] App-Modus auf Full access beschränken, einschließlich Dienst-Neustart und alter Launch-Intents.
- [x] Geschützte Modusauswahl und JIT-Schalter aus den Einstellungen entfernen.
- [x] Deutsche und englische Texte, dauerhafte Chat-Kennzeichnung und README-Warnung aktualisieren.
- [x] Beschreibbaren Präfix `$HOME/.local` mit `bin/lib/include/share/etc/tmp` anlegen.
- [x] Präfix-Binaries und Bibliotheken für App-Server und Codex-Kommandos vor die Übergangswerkzeuge setzen.
- [x] Shell-Funktionen entfernen, die selbst installierte Programme gleichen Namens überschreiben.
- [x] Regressionstests für beständige Installationen, Modusvertrag, SDK-Pins und tatsächliche Ausführung eigener Programme ergänzen.
- [x] Android-Quellen und Ressourcen zusätzlich in GitHub Actions gegen API 35 kompilieren; Manifest-Target 28 und Mindestniveau 29 prüfen.
- [ ] Android-Gerätetest: Programm aus dem beschreibbaren Präfix starten, denselben Befehl durch Codex ausführen, Dienst/Prozess neu starten und erneut prüfen.
- [ ] Getrennte Application-ID, Versionslinie und APK-Namen für parallele Installation festlegen und durchgängig umsetzen. Der Anzeigename lautet bereits AGENTCODI Package.

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

- [ ] Paketquelle und Bootstrap definieren. Standard-Termux-Pakete sind häufig an `/data/data/com.termux/files/usr` gebunden; für diese App sind neu gebaute Pakete oder eine geprüfte Relokation notwendig.
- [ ] Entscheidung zu finalem Präfix mit separater Application-ID treffen; danach Bootstrap, Repository-Metadaten, Shebangs, RPATH/RUNPATH und Konfigurationen konsistent darauf bauen.
- [ ] Minimalen Paketmanager einschließlich HTTPS, Zertifikaten, Signaturprüfung, Abhängigkeitsauflösung und Reparatur nach abgebrochener Installation integrieren.
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
