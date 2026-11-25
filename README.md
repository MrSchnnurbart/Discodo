# Discodo

**Discodo** est une application Android native (Kotlin + Jetpack Compose) qui restaure des notifications fiables et instantanées pour Discord sur les téléphones sans Google Play Services / Firebase Cloud Messaging (par exemple **GrapheneOS** sans microG, ou avec un client modifié comme **Valenced**).

L'application maintient une connexion WebSocket persistante vers le Gateway officiel de Discord (`wss://gateway.discord.gg/?v=10&encoding=json`) via un service d'arrière-plan dédié et déclenche de vraies notifications Android locales.

---

## Fonctionnalités

- **100 % autonome (zéro Google Play Services / FCM / microG)** : connexion WebSocket directe via OkHttp.
- **Messages privés (DM)** : notification prioritaire affichant la **photo de profil** de l'expéditeur à côté du message.
- **Appels vocaux DM entrants** : notification d'appel dédiée avec **vibreur d'appel** en continu et **sonnerie personnalisable** (boutons *Répondre / Ouvrir* et *Ignorer*).
- **Mentions directes** : notification prioritaire lorsqu'un message vous mentionne personnellement.
- **Mentions `@everyone`, `@here` et rôles** : prises en charge avec un canal de notification à priorité modérée et une vibration plus discrète.
- **Respect des sourdines Discord** : ignore les serveurs et salons mis en sourdine (`user_guild_settings`).
- **Ouverture directe dans votre client Discord** : toucher une notification ouvre le salon ou DM correspondant dans Valenced / Discord.
- **Historique sur 30 jours** : onglet *Historique* conservant les notifications des 30 derniers jours localement, avec bouton pour vider l'historique.
- **Sécurité locale** : le token Discord est chiffré sur l'appareil avec **Android Keystore (AES-256-GCM)** et n'est jamais journalisé.
- **Persistance & Auto-démarrage** : Foreground Service (`dataSync`), reconnexion automatique avec backoff exponentiel, reprise de session (`RESUME`), relance au démarrage du téléphone (`BOOT_COMPLETED`) et assistant d'exemption d'optimisation de batterie.
- **Mises à jour intégrées** : vérification automatique ou manuelle des nouvelles versions publiées sur GitHub Releases, avec téléchargement et installation directe depuis l'application.

---

## Installation

1. Téléchargez le fichier `.apk` le plus récent depuis la page [**Releases**](https://github.com/MrSchnnurbart/Discodo/releases).
2. Installez l'APK sur votre téléphone Android / GrapheneOS.
3. Si vous utilisiez précédemment l'ancienne version *NotiDisc* (`com.notidisc.fix`), vous pouvez la désinstaller après avoir configuré Discodo (`com.discodo.app`).

---

## Guide d'utilisation

1. **Désactiver l'optimisation de batterie** :
   Dans Discodo, appuyez sur **« Désactiver l'optimisation batterie »** afin qu'Android/GrapheneOS ne coupe pas la connexion WebSocket en veille.
2. **Enregistrer votre token Discord** :
   Collez votre token utilisateur dans le champ sécurisé **Token Discord** puis appuyez sur **Enregistrer**. Le token est immédiatement chiffré via Android Keystore.
3. **Configurer vos alertes** :
   - Activez ou désactivez selon vos besoins : **DM**, **Appels DM**, **Mentions**, **`@everyone` / `@here`**, **Mentions de rôles**, **Respecter les sourdines** et **Démarrer au boot**.
   - Dans **Sonnerie d'appel**, choisissez la sonnerie de votre choix.
4. **Démarrer le service** :
   Appuyez sur **« Démarrer le service »** et accordez la permission d'affichage des notifications (`POST_NOTIFICATIONS`). Une notification discrète indique l'état de connexion en direct.
5. **Tester** :
   Utilisez **« Tester la notification »** et **« Tester la notification d'appel »** pour vérifier le son, le vibreur et l'ouverture vers votre client Discord.
6. **Consulter l'historique** :
   L'onglet **Historique** en bas de l'écran conserve les notifications reçues pendant 30 jours ; touchez une ligne pour ouvrir la conversation ou utilisez **Supprimer** pour tout effacer.

---

## Compilation manuelle (développeurs)

Prérequis : JDK 17 et Android SDK (API 36).

```bash
git clone https://github.com/MrSchnnurbart/Discodo.git
cd Discodo
./gradlew testDebugUnitTest assembleDebug
```

L'APK est généré dans `app/build/outputs/apk/debug/app-debug.apk`.

---

## Releases automatiques via GitHub Actions

Le workflow [`.github/workflows/release.yml`](.github/workflows/release.yml) compile les tests, génère l'APK et publie automatiquement une GitHub Release à chaque push d'un tag `v*` :

```bash
git tag v1.0.0
git push origin v1.0.0
```

### Signature des releases (recommandé pour les mises à jour in-app)

Pour que l'application installée puisse se mettre à jour directement sans erreur de signature Android, configurez ces 4 secrets dans **Settings → Secrets and variables → Actions** de votre dépôt GitHub :

1. Générez un keystore (une seule fois) :
   ```bash
   keytool -genkey -v -keystore discodo-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias discodo
   base64 -w 0 discodo-release.jks
   ```
2. Ajoutez les secrets GitHub :
   - `ANDROID_KEYSTORE_BASE64` : contenu base64 du fichier `.jks`
   - `KEYSTORE_PASSWORD` : mot de passe du keystore
   - `KEY_ALIAS` : `discodo`
   - `KEY_PASSWORD` : mot de passe de la clé

*(Si ces secrets ne sont pas définis, le workflow publie automatiquement un APK debug signé avec la clé éphémère de CI.)*

---

## Licence

Ce projet est distribué sous licence **MIT** — voir le fichier [LICENSE](LICENSE).
