# BotDesk

Application Android (Kotlin + Jetpack Compose) pour discuter avec vos bots Telegram, suivre leur activité et parcourir/télécharger les sauvegardes publiées dans vos canaux Telegram privés. Elle se connecte avec **votre compte Telegram** via **TDLib** (pas la Bot API). Aucun serveur BotDesk : l'IA et les traitements restent dans votre backend ; l'app affiche les messages et envoie des ordres.

## Obtenir l'APK

1. Créez un dépôt GitHub et poussez ce dossier (branche `main`).
2. L'onglet **Actions** lance `Build APK`. Le **premier** build compile TDLib depuis la source officielle (30 à 90 min). Les suivants réutilisent le cache.
3. Téléchargez l'artefact `BotDesk-debug-apk` (APK installable directement).
4. Après un premier build réussi, notez la révision TDLib affichée dans le log (« TDLib révision : … ») et définissez-la dans *Settings → Variables → Actions* sous le nom `TDLIB_REF`, pour figer la version.

APK signé (optionnel) : ajoutez les secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

## Premier lancement

1. Créez une application sur https://my.telegram.org → *API development tools* et saisissez `api_id` / `api_hash` dans l'app (stockés chiffrés via le Keystore Android, jamais dans le dépôt).
2. Connectez-vous (numéro, code, mot de passe 2FA si activé).
3. Onglet **Sauvegardes** : activez les canaux/groupes qui servent de dépôts.
4. Paramètres : définissez un PIN (4 chiffres) et, si possible, la biométrie.

## Fonctions de la V1

- Détection des bots : conversations privées dont l'interlocuteur est un bot ; favoris, catégories, noms et descriptions locaux.
- Chat : envoi de messages et de commandes (liste des commandes déclarées par le bot), boutons inline (callback/lien), réception de documents.
- Confirmation avant toute action dont le texte contient un mot-clé sensible (configurable).
- Tableau de bord : activité récente, favoris, dernières sauvegardes. Sans signal fiable, le statut reste « inconnu » (une activité récente n'est pas une preuve de bon fonctionnement).
- Sauvegardes : parcours des fichiers d'un canal (500 derniers messages max), recherche, téléchargement vers l'emplacement de votre choix, aperçu texte, détection du format (extensible dans `FilePreview.kt`).
- Alertes/rapports : messages récents des bots et dépôts, classés par mots-clés configurables.
- Sécurité : PIN haché (PBKDF2), biométrie, verrouillage en arrière-plan, base TDLib chiffrée, export/import de configuration chiffré (AES-GCM).

## Hors périmètre V1 (à décider, cf. spécification)

Notifications push/arrière-plan, boutons de clavier de réponse, rapports PDF/graphiques, multi-comptes, iOS.

## Développement local

Android Studio Koala+, JDK 17. Le code TDLib (`org/drinkless/tdlib/*.java` et `libtdjni.so`) est généré par la CI : pour compiler en local, compilez TDLib avec `td/example/android` puis copiez `Client.java` et `TdApi.java` dans `app/src/main/java/org/drinkless/tdlib/` et les `libs/<abi>/libtdjni.so` dans `app/src/main/jniLibs/`.
Le wrapper Gradle n'est pas inclus : Android Studio le génère, ou lancez `gradle wrapper --gradle-version 8.7`.

## Structure

```
app/src/main/kotlin/com/botdesk/app/
  core/   SecureStore (Keystore), LockManager (PIN/biométrie), ConfigBackup (export chiffré)
  data/   LocalStore (JSON : catégories, favoris, canaux, mots-clés)
  td/     Telegram (unique point d'accès à TDLib), Msg
  ui/     écrans Compose
```
"# BotDesk" 
