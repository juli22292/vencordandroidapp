# VencordAndroidApp (`vencordandroidapp.apk`)

**VencordAndroidApp** ist die offizielle Android-Installer-App (im Stil des offiziellen Vencord-Windows-10/11-Installers), mit der **VencordForAndroid** und eigene Plugins in Discord auf dem Handy installiert, repariert und wieder vollständig deinstalliert werden können.

## Features
- **Windows 10/11 Vencord Installer GUI:**
  - **Install:** Installiert `VencordForAndroid` und alle Plugins aus [`juli22292/vencordforandroid`](https://github.com/juli22292/vencordforandroid) in Discord.
  - **Reinstall / Repair:** Installiert `VencordForAndroid` sauber neu, falls ein Fehler auftritt.
  - **Uninstall:** Entfernt `VencordForAndroid` inklusive aller installierten Plugins wieder rückstandslos.
- **Updates:**
  - **Installer-App (`VencordAndroidApp`):** APK-Updates stehen auf GitHub bereit. Bei einem privaten Repository ist eine GitHub-Anmeldung im Browser erforderlich. [Installer-Releases](https://github.com/juli22292/vencordandroidapp/releases)
  - **In-App-Mod & Plugins (`VencordForAndroid`):** Der Installer enthält das Core-Bundle und verwendet es auch offline. Über „Reinstall / Repair“ lässt sich das mitgelieferte Bundle in einer bereits gepatchten Installation aktualisieren.

## Download

Version `0.1.0` von **`vencordandroidapp.apk`** enthält VencordForAndroid `0.1.1` für die nativen Discord-Einstellungen und verwendet LSPatch 1.2 zum Verpacken der APKs. [APK herunterladen](https://github.com/juli22292/vencordandroidapp/releases/tag/0.1.0) [LSPatch 1.2 und Quellcode](https://github.com/JingMatrix/LSPatch/releases/tag/v1.2)

Beim Wechsel von Installer `0.0.8` zu `0.0.9` erfordert Android wegen des geänderten APK-Signierzertifikats eine Neuinstallation der Installer-App. Der Schlüssel zum Signieren gepatchter Discord-APKs liegt in den App-Daten des Installers. Android kann deshalb bei der Reparatur auch für Discord eine bestätigte Neuinstallation verlangen.
