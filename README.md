> **Package Edition — nur für Power User und erfahrene Nutzer.** Diese Version bietet ausschließlich **Full access**. Codex und selbst installierte Programme können alle für die App erreichbaren Dateien lesen, verändern oder löschen, einschließlich Codex-Kontodaten. Androids App-Isolation gegenüber anderen Apps bleibt bestehen; innerhalb dieser App gibt es keine Workspace-Sandbox.
>
> **Entwicklungsstand:** Der Paketpräfix und Full access sind eingerichtet. Die Umstellung auf den Community-App-Server, ein Paketmanager und der schlankere APK-Build stehen noch aus. Siehe [Roadmap](ROADMAP-package-edition.md). Diese Entwicklungslinie wird nicht in `main` gemergt.

<div align="center">

# AGENTCODI Package Edition

### Codex workflows, native on Android — with user-installed tools.

[Website](https://devsblog.com/) · [Issues](../../issues) · [Roadmap](ROADMAP-package-edition.md)

![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
![Architecture](https://img.shields.io/badge/Architecture-ARM64-555555)
![Target SDK](https://img.shields.io/badge/targetSdk-28-orange)
![License](https://img.shields.io/badge/License-Apache%202.0-blue)

</div>

## Diese Entwicklungslinie

Alle Änderungen dieser zweiten Version liegen ausschließlich in `Mcpasi/package-edition`. Die reguläre AGENTCODI-Version wird weiterhin in `main` gepflegt.

AGENTCODI bietet native Codex-Chats, Workspace-Import und Export, Dateivorschau, ein interaktives Terminal und MCP-Verwaltung. Der App-Server läuft lokal; Modellanfragen benötigen weiterhin Internetzugang und OpenAI-Authentifizierung. Die Oberfläche ist auf Deutsch und Englisch verfügbar.

Die Package Edition setzt bewusst `targetSdk 28` ein, um die Android-Beschränkung für die Ausführung von Dateien in beschreibbarem App-Speicher bei Apps mit Target SDK ab 29 zu vermeiden. Das Mindestniveau bleibt Android 10 / API 29. Ein niedrigeres Target SDK ersetzt keine passende ARM64/Bionic-Laufzeit und macht Termux-Pakete nicht automatisch mit einem anderen Installationspfad kompatibel.

## Full access

Full access ist der einzige App-Modus — auch nach einem Neustart des Runtime-Dienstes. Der geschützte Modus und seine Just-in-time-Berechtigungseinstellung werden in dieser Edition nicht angeboten. Der Chat zeigt Full access dauerhaft an.

Optional können Befehle und Dateiänderungen mit der Codex-Richtlinie `untrusted` bestätigt werden. Diese Bestätigungen sind keine Dateisystem-Isolation. Ein installiertes Programm läuft mit den Rechten der App.

Workspace, Benutzer-Home und `CODEX_HOME` bleiben als Verzeichnisse getrennt. Diese organisatorische Trennung schützt Kontodaten nicht vor Programmen im selben App-Prozess. Import, Vorschau und Export verwenden weiterhin ihre eigenen Dateiprüfungen.

## Eigene Programme

Der beschreibbare Installationspräfix ist `$PREFIX = $HOME/.local`. Beim Start legt AGENTCODI `bin`, `lib`, `include`, `share`, `etc` und `tmp` an; vorhandene Installationen bleiben erhalten.

Codex-Kommandos und das Terminal erhalten dieselbe Suchreihenfolge:

```text
PATH=$PREFIX/bin:<bisherige APK-Tool-Aliase>:/system/bin:/system/xbin
LD_LIBRARY_PATH=$PREFIX/lib:<native APK-Bibliotheken>
```

Eigene Programme haben damit Vorrang vor den bisherigen Tools. Die Terminal-Shell definiert keine festen Funktionen mehr für `node`, `npm`, `python` oder `rg`, die diesen Vorrang überschreiben könnten.

Für einen ersten Gerätetest kann ein eigenes Shell-Programm installiert werden:

```sh
printf '#!/system/bin/sh\nprintf "package-edition-ok\\n"\n' > "$PREFIX/bin/package-check"
chmod 700 "$PREFIX/bin/package-check"
package-check
```

Anschließend kann Codex `package-check` als normalen Befehl ausführen. Das ist ein Funktionstest des Installationspfads; ein `pkg`-/APT-Paketmanager ist noch nicht enthalten.

Native Pakete müssen für Android ARM64/Bionic gebaut sein und den tatsächlichen Präfix unterstützen. Bestehende Termux-DEBs enthalten häufig feste Pfade wie `/data/data/com.termux/files/usr`. Einfaches Entpacken nach `$PREFIX` reicht dann nicht aus. Paketquellen und Bootstrap werden im nächsten Bauabschnitt festgelegt.

Die bisherigen mitgelieferten Node.js-, npm-, Python- und ripgrep-Laufzeiten bleiben vorerst als Übergang erhalten. Ihre Aktivierungs- und Laufzeitprüfungen gelten weiterhin; insbesondere sind die alten npm-/Python-Wrapper noch keine allgemeine Paketverwaltung.

## Runtime und Build

Der aktive Build verwendet vorläufig weiterhin den gepinnten Mcpasi-Fork `0.153.3-agentcodi.2`. Als Migrationsziel wurde der Community-Ursprung [DioNanos/codex-termux](https://github.com/DioNanos/codex-termux) mit Release [v0.156.1-termux.1](https://github.com/DioNanos/codex-termux/releases/tag/v0.156.1-termux.1) geprüft. Der Austausch erfordert neue Artefakt- und Schema-Pins sowie App-Server-Kompatibilitätstests; er ist noch nicht umgesetzt.

Die bestehenden GitHub-Tests laufen bei jedem Branch-Push. Der APK-Workflow kann manuell für `Mcpasi/package-edition` ausgeführt werden. Die komplette Runtime-/Build-Migration ist vor Veröffentlichung eines Package-Edition-APKs zu erledigen.

```sh
./scripts/test.sh
./scripts/build-debug-apk.sh
```

Die Build-Umgebung ist in [.github/ci/README.md](.github/ci/README.md) dokumentiert. Dort beschriebene Prüfungen für den bisherigen Fork und geschützten Modus sind derzeit noch Teil des Übergangsbuilds.

Die Edition verwendet momentan dieselbe Application-ID `de.agentcodi.app` wie die reguläre Version und kann daher nicht parallel installiert werden. Eine getrennte Installationsidentität ist Bestandteil der Roadmap. Vor Wechsel zwischen den Entwicklungslinien eigene Dateien sichern. Neuere Android-Versionen können beim Installieren auf das niedrige Target SDK hinweisen.

## Lizenz

Originaler Java-/C++-App-Code, Tests, Ressourcen, Build-Automation und Dokumentation:

Copyright 2026 Pascal (Mc Pasi). [Apache License 2.0](LICENSE).

Lizenzen und Hinweise zu weiterhin enthaltenen Drittanbieterkomponenten stehen in [NOTICE.md](NOTICE.md) und in der App.

AGENTCODI ist ein unabhängiges Open-Source-Projekt und weder mit OpenAI verbunden noch von OpenAI unterstützt.
