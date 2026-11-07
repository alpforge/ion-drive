# Ion Drive

An unofficial fork of the [Proton Drive Android app](https://github.com/ProtonDriveApps/android-drive)
that lets other apps **save changes back to files in your Drive**.

The official app lets other apps open Drive files through the Android file picker (Storage Access
Framework), but refuses to open an existing file for writing. Ion Drive accepts it: the new content
is uploaded in the background as a **new revision** of the file, so the version history is kept on
Proton's side. If the file was changed elsewhere in the meantime, the content is uploaded as a
conflict copy next to it instead of being lost.

It installs next to the official app (package `io.github.alpforge.iondrive`) and otherwise behaves
like Proton Drive.

> [!WARNING]
> This is **not** an official Proton app and is not supported by Proton. Your account and files are
> handled by a modified client: use it at your own risk, and only if you are comfortable with
> [Proton's terms of service](https://proton.me/legal/terms).

## Install

Download the APK from the [releases](https://github.com/alpforge/ion-drive/releases), or add this repository to
[Obtainium](https://github.com/ImranR98/Obtainium) to get updates. Each release follows an
official Proton Drive version.

## Documentation

How it works, known limitations, building and the update process are described (in French) in
[FORK.md](../FORK.md). The original Proton README is in [README.md](../README.md).

## License

GPL-3.0, like the upstream project (see [LICENSE](../LICENSE)). "Proton" and "Proton Drive" are
trademarks of Proton AG; this project is not affiliated with Proton AG.
