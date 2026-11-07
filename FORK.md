# Ion Drive : fork de Proton Drive Android

Fork personnel de [ProtonDriveApps/android-drive](https://github.com/ProtonDriveApps/android-drive), basé sur la version **3.3.1** (branche `fork/3.3.1`).

## Ce que le fork ajoute

L'app officielle expose les fichiers du Drive aux autres apps (sélecteur de fichiers Android, SAF), mais **refuse d'ouvrir un fichier existant en écriture**. Une app qui veut écraser sa sauvegarde dans le Drive échoue.

Dans ce fork, quand une app ouvre un fichier du Drive en écriture :

1. elle écrit dans un fichier local (dossier permanent de l'app, pas le cache) ;
2. quand elle ferme le fichier, un job **WorkManager** envoie le contenu comme **nouvelle version** du fichier Drive, via `FileRevisionUploaderRequest` du SDK Proton. L'historique des versions est conservé côté Proton.

Comme sur le client Windows :
- **l'envoi est persistant** : il attend le réseau, est retenté en cas d'erreur temporaire et survit à l'arrêt de l'app (service au premier plan pendant l'envoi) ;
- **les enregistrements successifs d'un même fichier passent un par un, dans l'ordre** (un travail unique par fichier, `APPEND_OR_REPLACE`). Une écriture ouverte avant la fin de l'envoi précédent part de la version que l'appareil vient d'envoyer, au lieu de signaler un conflit avec lui-même ;
- **rien n'est perdu en cas de conflit** : si le fichier a été modifié ailleurs entre-temps (ou si l'envoi échoue définitivement), le contenu est envoyé comme nouveau fichier à côté : `nom (# Edit conflict AAAA-MM-JJ HHmmssC #).ext` ;
- **les fichiers partagés en lecture seule** n'annoncent plus l'écriture, et leur ouverture en écriture est refusée tout de suite (`FileNotFoundException`), au lieu d'un faux succès.

Modes gérés : `w`, `wt`, `rwt` (contenu remplacé), `rw`, `wa` (on part du contenu actuel : celui du dernier enregistrement pas encore envoyé s'il y en a un, sinon la version téléchargée). Si le contenu n'a pas changé à la fermeture, rien n'est envoyé.

Code concerné :
- `drive/documentsprovider/domain/.../usecase/OpenDocument.kt` : `openDriveLinkFileForWrite`
- `drive/drivelink-upload/domain/.../manager/PendingFileRevisions.kt` : contenus en attente sur l'appareil
- `drive/drivelink-upload/domain/.../entity/FileRevisionChain.kt` : version de base des enregistrements successifs
- `drive/drivelink-upload/domain/.../usecase/UploadFileRevision.kt` : envoi de la nouvelle version
- `drive/drivelink-upload/domain/.../usecase/UploadFileRevisionConflictCopy.kt` : copie de conflit
- `drive/drivelink-upload/data/.../worker/UploadFileRevisionWorker.kt` : job WorkManager

Autres changements :
- applicationId `io.github.alpforge.iondrive`, nom « Ion Drive » et icône propre (un dossier avec un noyau chargé et son orbite) : s'installe **à côté** de l'app officielle, sans être confondue avec elle (le nom fait référence au proton, qui est un ion H⁺) ;
- signature optionnelle avec une clé perso (voir plus bas) ;
- build release possible (voir « Compiler ») ;
- workflow GitHub Actions qui compile l'APK.

## Limites connues

- **Envoi après coup** : l'app appelante reçoit « succès » dès qu'elle ferme le fichier, avant la fin de l'envoi. Les erreurs ne sont pas signalées à l'utilisateur (seulement journalisées) ; en cas de conflit, c'est l'apparition de la copie `(# Edit conflict …)` qui le montre.
- Si la copie de conflit elle-même échoue (ou si le fichier a disparu du Drive), le contenu reste sur l'appareil, dans `…/revision_conflicts/` ou `…/pending_revisions/` du dossier permanent de l'app, jusqu'à la déconnexion du compte.
- Une lecture (`r`) juste après une écriture pas encore envoyée renvoie encore l'ancienne version.
- Un conflit de brouillon laissé par une tentative interrompue de l'appareil lui-même peut produire une copie de conflit inutile (si le SDK ne reconnaît pas ce brouillon comme le sien).
- Pas de miniature générée ni de contrôle SHA-1 pour la nouvelle version.
- Rien n'a été testé sur appareil au moment de l'écriture du patch.

## Compiler

### Via GitHub Actions (recommandé)
Chaque push lance `.github/workflows/build.yml`, qui produit l'APK `prod` **release** dans les *artifacts* du run (onglet Actions). Sur une branche `candidate/<version>`, il publie aussi la release (voir « Mettre à jour vers une nouvelle version de Proton »).

Les deux jobs tournent sur les runners `ubuntu-latest` hébergés par GitHub. Le build a besoin de beaucoup de mémoire (R8, `-Xmx7g` dans `gradle.properties`). Même sur les runners d'un dépôt public (4 vCPU, 16 Go), il est tué avec les réglages par défaut : le workflow limite donc Gradle à 2 tâches en parallèle (`--max-workers=2`) et le daemon Kotlin à 3 Go, et ajoute 12 Go de swap par sécurité. Le build prend alors environ 16 minutes, avec un pic vers 13,7 Go ; la mémoire utilisée est notée chaque minute dans le log du job. Les runners d'un dépôt privé (7 Go) ne suffisent pas.

Le code publié par Proton ne compile pas en release tel quel : `LoggerInitializer` utilise `GetFileLoggerTree`, présent seulement dans `app/src/debug`. Proton a voulu publier la version release (`!release/.../GetFileLoggerTree.kt` dans `app/.gitignore`), mais Git ne peut pas ré-inclure un fichier dont le dossier parent (`release/`) est ignoré. Le fork ajoute `app/src/release/.../GetFileLoggerTree.kt`, une version vide (le log dans un fichier reste réservé au debug), commitée avec `git add -f` sans modifier les fichiers de Proton. Si Proton publie un jour son propre fichier au même endroit, garder le sien.

### En local
JDK 17 et Android SDK (compileSdk 36), puis :
```
./gradlew :app:assembleProdRelease
```
APK : `app/build/outputs/apk/prod/release/`. `:app:assembleProdDebug` fonctionne aussi (APK debug, non minifié). N'utilise pas le flavor `dev` : il pointe vers un serveur interne de Proton.

## Signature (pour pouvoir mettre à jour l'app)

Sans clé perso, chaque build CI est signé avec une clé jetable : il faut désinstaller avant de réinstaller, et on perd la session. Pour éviter ça :

```
keytool -genkeypair -v -keystore fork.jks -alias fork -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 fork.jks   # à copier dans le secret ci-dessous
```
Dans GitHub > Settings > Secrets and variables > Actions, créer :
- les secrets `FORK_KEYSTORE_BASE64`, `FORK_KEYSTORE_PASSWORD` et `FORK_KEY_PASSWORD` ;
- la variable `FORK_KEY_ALIAS` (l'alias de la clé dans le keystore, ici `fork` : ce n'est pas un secret).
Garde `fork.jks` en lieu sûr : sans lui, impossible de mettre à jour l'app installée.

Les releases GitHub sont signées par la CI avec cette clé. Sur une branche `candidate/*`, le build échoue si les secrets manquent.

## Tester

1. Installer l'APK, se connecter à son compte Proton.
2. Depuis un gestionnaire de fichiers compatible SAF, ouvrir un fichier texte du Drive, le modifier, enregistrer.
3. Vérifier sur drive.proton.me que le contenu a changé et qu'une nouvelle version apparaît dans l'historique.
4. Logs : `adb logcat | grep -i "documents\|upload"`.

## Mettre à jour vers une nouvelle version de Proton

Une branche par version de Proton : `fork/<version>` = le tag upstream + les commits du fork par-dessus. La branche d'une version déjà publiée ne bouge plus ; une nouvelle version repart du nouveau tag et on y rejoue les commits par rebase.

### Automatiquement
1. Une routine Claude planifiée (une fois par jour) compare le dernier tag upstream `X.Y.Z` à la dernière branche `fork/*`. Si une version plus récente existe, et que ni `candidate/<version>`, ni `fork/<version>`, ni la release `v<version>` n'existent, elle rebase le fork sur le nouveau tag, met à jour la version citée en haut de ce fichier, puis pousse le tag upstream et la branche **`candidate/<version>`**. Elle vérifie aussi si la nouvelle version de Proton semble ajouter elle-même l'écriture via le documents provider (code de `drive/documentsprovider`, messages de commit, notes de release) et envoie alors une notification push, sans bloquer la mise à jour. Si un conflit ne peut pas être résolu sans risque, elle ne pousse rien et envoie une notification.
2. Sur `candidate/<version>`, la CI compile l'APK release et lance les tests unitaires. Si tout passe, le job `release` crée `fork/<version>` sur le même commit, publie la **pre-release** `v<version>` avec l'APK `ion-drive-<version>.apk`, puis supprime la branche candidate. En cas d'échec, la branche candidate reste en place pour qu'on puisse l'examiner.
3. Tester la pre-release (Obtainium, avec « Include prereleases » activé, l'installe), puis la promouvoir :
   ```
   gh release edit v<version> --prerelease=false --latest
   ```
   Le tag et l'APK ne changent pas, donc Obtainium ne propose pas de nouvelle mise à jour : la version installée est déjà la bonne.

Pour rejouer une version après un échec, supprimer `candidate/<version>` (et `fork/<version>` / la release si le job `release` s'est arrêté en cours), corriger, puis repousser `candidate/<version>`.

### À la main (secours)
```
git remote add upstream https://github.com/ProtonDriveApps/android-drive.git   # une fois
git fetch upstream --tags
git switch -c candidate/3.4.0 fork/3.3.1     # nouvelle branche depuis la précédente
git rebase --onto 3.4.0 3.3.1                # rejoue les commits du fork sur le nouveau tag
git push origin 3.4.0                        # le tag upstream, pour git log 3.4.0..fork/3.4.0
git push -u origin candidate/3.4.0           # la CI fait le reste
```
Résoudre les éventuels conflits commit par commit (surtout `OpenDocument.kt` et `drivelink-upload`), puis `git rebase --continue` ; `git rebase --abort` pour tout annuler. Penser aussi à mettre à jour la version citée en haut de ce fichier.

Pour voir ce que le fork ajoute à une version : `git log 3.3.1..fork/3.3.1`. Proton n'accepte pas de contributions externes, donc ce patch restera à maintenir ici.

## Licence et conditions

Code sous GPL-3.0, comme l'original (voir `LICENSE`) : les modifications restent sous GPL. Ce build n'est ni officiel ni supporté par Proton. L'utilisation d'un client modifié relève des conditions d'utilisation de Proton.
