# VencordAndroidApp (`vencordandroidapp.apk`)

**VencordAndroidApp** installiert **VencordForAndroid** in der vorhandenen Discord-App auf Android.

## Features

- **Install** installiert das mitgelieferte Core-Bundle und dessen Android-Plugins in Discord. [VencordForAndroid](https://github.com/juli22292/vencordforandroid)
- **Reinstall / Repair** aktualisiert das Core-Bundle in einer bereits gepatchten Installation.
- **Uninstall** entfernt nach Android-Bestätigung die gepatchte Discord-Installation.

## Download

Version `0.1.2` von **`vencordandroidapp.apk`** enthält VencordForAndroid `0.1.3` mit nativen Symbolen und fünf offiziellen Plugin-Portierungen. [APK herunterladen](https://github.com/juli22292/vencordandroidapp/releases/download/0.1.2/vencordandroidapp.apk)

Aktualisiere den vorhandenen Installer und wähle **Reinstall / Repair**, um das neue Bundle in Discord zu installieren. Der Installer verwendet LSPatch 1.2 zum Verpacken der APKs. [LSPatch 1.2 und Quellcode](https://github.com/JingMatrix/LSPatch/releases/tag/v1.2)

Beim Wechsel von Installer `0.0.8` zu `0.0.9` erfordert Android wegen des geänderten APK-Signierzertifikats eine Neuinstallation der Installer-App. Der Schlüssel zum Signieren gepatchter Discord-APKs liegt in den App-Daten des Installers. Android kann deshalb bei der Reparatur auch für Discord eine bestätigte Neuinstallation verlangen.
