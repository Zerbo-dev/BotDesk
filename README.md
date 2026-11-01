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

## Fonctions

- **Accueil :** salutation, envoi rapide d'une commande à un bot (confirmation si action sensible), accès rapide aux sections, favoris, activité récente, dernières sauvegardes.
- **Bots :** détection (conversations privées avec un bot), favoris, catégories, noms/descriptions locaux, sourdine par bot, masquage (un bot masqué disparaît des listes, de l'accueil, des alertes et des notifications ; filtre « Masqués » pour le retrouver).
- **Lecteurs internes (aucune autre app nécessaire) :** images (zoom pincement, double-toucher), vidéo et audio (Media3/ExoPlayer, barre de lecture), PDF (pages, toucher pour zoomer), texte/logs/JSON/CSV/SQL (filtre de lignes, JSON indenté), `.gz` texte décompressé à la volée, archives ZIP (liste du contenu + aperçu des fichiers texte). Les messages vocaux et les audios se lisent directement dans la conversation. Les formats sans lecteur proposent « Autre app ». Enregistrement toujours possible, sans modification du fichier.
- **Canaux :** un dépôt s'ouvre sur ses messages (lecture seule) ; le bouton « Fichiers » bascule vers la liste des fichiers, et « Messages » revient.
- **Recherche d'historique :** dans l'onglet Bots, taper un terme puis « Chercher dans tout l'historique » (messages des bots et des dépôts).
- **Interface :** thème sombre/clair, cartes arrondies, avatars, pastilles d'activité (bleu = activité récente, orange = donnée ancienne, gris = inconnu ; jamais vert pour ne pas laisser croire qu'un service fonctionne), séparateurs de jours et bulles dans les conversations, saisie en pilule avec micro/envoi.
- **Bots interactifs en direct :** boutons inline (indicateur de progression, réponses/alertes du bot, liens), messages modifiés ou supprimés par le bot mis à jour en temps réel, indicateur « écrit… », défilement automatique vers les nouveaux messages et bouton ↓.
- **Avatars des bots :** image de votre galerie (recadrée en carré, gardée localement), emoji, ou photo de profil Telegram par défaut. Les images d'avatar ne sont pas incluses dans l'export de configuration.
- **Vidéos :** vignette Telegram avec bouton lecture et durée ; la vidéo ne se télécharge qu'au toucher.
- **Audio :** lecteur intégré avec curseur de position et vitesse 1×/1,5×/2×.
- **Voix :** bouton 🎤 pour dicter un message (reconnaissance vocale du téléphone, envoi automatique optionnel), bouton « 🔊 Écouter » sur les réponses des bots, lecture automatique optionnelle ; choix de la voix du téléphone, vitesse et hauteur réglables ; moteur premium optionnel ElevenLabs (clé API de l'utilisateur, texte envoyé à ElevenLabs, audios mis en cache).
- **Chat :** messages ; bouton « / » qui ouvre la liste des commandes du bot (filtre, insertion, envoi direct, épinglage) ; raccourcis épinglés au-dessus du clavier ; suggestions automatiques quand on tape « / » ; boutons inline, claviers de réponse, réception de fichiers.
- **Sauvegardes :** l'écran n'affiche que les dépôts choisis (sélecteur via « Gérer les dépôts »), parcours (500 derniers messages), recherche par nom/légende/expéditeur, filtre par format, téléchargement tel quel vers l'emplacement de votre choix, aperçu texte, détection de format par signature, confirmation des gros fichiers, historique local des fichiers consultés (hors connexion).
- **Alertes :** messages classés par mots-clés configurables.
- **Notifications :** locales (aucun serveur de push) : bots (aucune/alertes/tous), nouvelles sauvegardes, mode « rester connecté en arrière-plan » (service de premier plan), contenu masqué si un PIN est actif.
- **Réglages :** thème (système/sombre/clair), stockage (taille, vidage du cache, seuil de confirmation), durée de l'historique, mots-clés, catégories, export/import chiffré de la configuration, déconnexion.
- **Sécurité :** PIN haché (PBKDF2), biométrie, verrouillage à la mise en arrière-plan, base TDLib chiffrée, clés API chiffrées (Keystore).

Les statuts de serveurs restent « inconnus » tant qu'un mécanisme fiable n'est pas défini : une activité récente n'est pas une preuve de bon fonctionnement.

## Hors périmètre (à décider)

Rapports PDF/graphiques, multi-comptes, iOS, démarrage automatique au redémarrage du téléphone.

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
