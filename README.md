# God's Eye View pour Android (Pixel 8 Pro)

Appli Android qui affiche [God's Eye View](https://github.com/bilawalsidhu/gods-eye-view) en plein écran.

God's Eye View a besoin d'un serveur Node.js pour aller chercher les données en direct (avions, bateaux, satellites, caméras, météo…). Ce serveur est hébergé à part (par exemple sur o2switch, protégé par mot de passe). L'appli s'y connecte.

## Installer l'APK

1. Télécharge le dernier `GodsEyeView-….apk` depuis la page **Releases** : <https://github.com/EmmaBPL/gods-eye-view-android/releases/latest>
2. Ouvre le fichier. Si Android le demande, autorise l'installation d'applis inconnues pour ton navigateur.

## Premier lancement

1. Entre l'adresse de ton serveur (par exemple `https://monserveur.exemple.fr`).
2. Entre l'identifiant et le mot de passe du serveur. Ils sont mémorisés sur le téléphone.
3. Accepte le micro (commande vocale) et la position (GPS) quand l'appli les demande.

## Utilisation

- **Retour** revient en arrière dans l'appli. Sur la page d'accueil, il ouvre un menu : Quitter, Recharger, Changer l'adresse du serveur.
- En cas de problème de connexion, l'écran d'erreur permet de réessayer, de changer d'adresse ou d'effacer l'identifiant mémorisé.
- Les liens externes s'ouvrent dans ton navigateur.

## Notes techniques

- L'APK est compilé automatiquement par GitHub Actions à chaque modification (`.github/workflows/build-apk.yml`).
- La clé de signature (`app/release.keystore`) est dans le dépôt pour que chaque nouvelle version s'installe par-dessus l'ancienne. Elle ne protège rien d'autre que ce lanceur.
- L'appli n'accorde le micro et la position qu'à l'adresse du serveur enregistrée.
