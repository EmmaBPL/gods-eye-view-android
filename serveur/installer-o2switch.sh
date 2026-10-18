#!/bin/bash
# Installe ou met à jour God's Eye View sur un hébergement cPanel (o2switch).
#
# Prérequis : une application Node.js créée dans cPanel › Setup Node.js App
#   - version 24.x
#   - racine de l'application : gev
#   - URL : l'adresse choisie (servie à la racine d'un domaine ou sous-domaine)
#   - fichier de démarrage : app.cjs
#
# Usage, dans cPanel › Terminal :
#   curl -fsSL https://raw.githubusercontent.com/EmmaBPL/gods-eye-view-android/main/serveur/installer-o2switch.sh -o installer-gev.sh && bash installer-gev.sh ADRESSE
# Exemple :
#   bash installer-gev.sh globe.jedi3514.odns.fr
#
# Le script demande d'abord l'identifiant et le mot de passe (rapide), puis
# lance l'installation en arrière-plan : elle continue même si la page du
# terminal se déconnecte. Avancement : tail -5 ~/gev-install.log

set -euo pipefail

DOMAIN="${1:-}"
APP_NAME="gev"
APP="$HOME/$APP_NAME"
REPO_URL="https://github.com/bilawalsidhu/gods-eye-view.git"

say()  { printf '\n\033[1;32m==> %s\033[0m\n' "$1"; }
fail() { printf '\n\033[1;31mERREUR : %s\033[0m\n' "$1"; exit 1; }

[ -n "$DOMAIN" ] || fail "indique l'adresse, par exemple : bash installer-gev.sh globe.jedi3514.odns.fr"
DOMAIN="${DOMAIN#https://}"; DOMAIN="${DOMAIN#http://}"; DOMAIN="${DOMAIN%%/*}"

# --- 1. Environnement Node.js créé par cPanel ------------------------------
say "Recherche de l'application Node.js « $APP_NAME » créée dans cPanel"
ACTIVATE="$(ls -d "$HOME"/nodevenv/"$APP_NAME"/2[4-9]*/bin/activate 2>/dev/null | sort -V | tail -1 || true)"
[ -n "$ACTIVATE" ] || fail "application introuvable. Crée-la d'abord dans cPanel › Setup Node.js App (version 24, racine « $APP_NAME »)."
# Le script d'activation de cPanel utilise des variables non définies.
set +u
# shellcheck disable=SC1090
source "$ACTIVATE"
set -u
echo "Node.js $(node -v), npm $(npm -v)"

# --- 5. Mot de passe et invisibilité pour Google ------------------------------------
say "Protection par mot de passe"
CANDIDATES="$(find "$HOME" -maxdepth 3 -name .htaccess ! -path "$APP/*" -exec grep -l "PassengerAppRoot \"$APP\"" {} + 2>/dev/null || true)"
# Préfère le dossier qui porte le nom de l'adresse (cas d'un changement d'adresse).
HTACCESS="$(printf '%s\n' "$CANDIDATES" | grep -F "/$DOMAIN/" | head -1 || true)"
[ -n "$HTACCESS" ] || HTACCESS="$(printf '%s\n' "$CANDIDATES" | grep -v '^$' | head -1 || true)"
[ -n "$HTACCESS" ] || fail "fichier .htaccess de l'application introuvable. Vérifie l'URL de l'application dans cPanel, puis relance ce script."
echo "Fichier : $HTACCESS"

PASSWD_DIR="$HOME/.htpasswds/gev"
PASSWD_FILE="$PASSWD_DIR/passwd"
mkdir -p "$PASSWD_DIR"
# Apache doit pouvoir traverser ces dossiers pour lire le fichier (crypté).
chmod 711 "$HOME/.htpasswds" "$PASSWD_DIR"

if [ -s "$PASSWD_FILE" ]; then
  echo "Un mot de passe est déjà en place, je le réutilise (pour le changer : rm $PASSWD_FILE puis relance le script)."
else
  read -r -p "Choisis un identifiant : " GEV_USER < /dev/tty
  while true; do
    read -r -s -p "Choisis un mot de passe (au moins 12 caractères) : " GEV_PASS < /dev/tty; echo
    read -r -s -p "Retape le mot de passe : " GEV_PASS2 < /dev/tty; echo
    if [ "$GEV_PASS" != "$GEV_PASS2" ]; then echo "Les deux mots de passe sont différents, recommence."; continue; fi
    if [ "${#GEV_PASS}" -lt 12 ]; then echo "Trop court, recommence."; continue; fi
    break
  done
  HASH="$(printf '%s' "$GEV_PASS" | openssl passwd -apr1 -stdin)"
  printf '%s:%s\n' "$GEV_USER" "$HASH" > "$PASSWD_FILE"
  chmod 644 "$PASSWD_FILE"
  unset GEV_PASS GEV_PASS2
  echo "Mot de passe enregistré."
fi

if ! grep -q "ErrorDocument 401" "$HTACCESS"; then
  sed -i '/# GEV-AUTH BEGIN/,/# GEV-AUTH END/d' "$HTACCESS"
  TMPH="$(mktemp)"
  cat > "$TMPH" <<HT
# GEV-AUTH BEGIN
# Messages d'erreur servis par Apache lui-même (sinon l'appli les remplacerait
# par sa page d'accueil avec un code 200, et le mot de passe ne serait pas demandé).
ErrorDocument 401 "Acces prive"
ErrorDocument 403 "Acces refuse"
ErrorDocument 500 "Erreur serveur"
AuthType Basic
AuthName "Acces prive"
AuthUserFile "$PASSWD_FILE"
Require valid-user
# Laisse passer la vérification Let's Encrypt (création et renouvellement du certificat).
<If "%{REQUEST_URI} =~ m#^/\\.well-known/(acme-challenge|pki-validation)/#">
Require all granted
</If>
<IfModule mod_headers.c>
Header always set X-Robots-Tag "noindex, nofollow, noarchive"
</IfModule>
# GEV-AUTH END
HT
  cat "$HTACCESS" >> "$TMPH"
  cat "$TMPH" > "$HTACCESS"
  rm -f "$TMPH"
  echo "Protection ajoutée à $HTACCESS"
fi

# --- Lancement de la partie longue en arrière-plan ---------------------------
if [ "${GEV_BG:-}" != "1" ]; then
  LOG="$HOME/gev-install.log"
  GEV_BG=1 nohup bash "$0" "$DOMAIN" > "$LOG" 2>&1 < /dev/null &
  say "Installation lancée en arrière-plan"
  cat <<MSG

L'installation continue même si tu fermes cette page (10 à 20 minutes).

Pour voir où elle en est, tape :
  tail -5 ~/gev-install.log

Quand la dernière ligne indique « Terminé ! », ouvre https://$DOMAIN
MSG
  exit 0
fi

# --- 2. Code de God's Eye View ----------------------------------------------
if [ -d "$APP/.git" ]; then
  say "Mise à jour du code"
  git -C "$APP" fetch --depth 1 origin main
  git -C "$APP" reset --hard origin/main
else
  say "Téléchargement du code"
  TMP="$HOME/.gev-clone.$$"
  rm -rf "$TMP"
  git clone --depth 1 "$REPO_URL" "$TMP"
  mkdir -p "$APP"
  # Retire les fichiers d'exemple créés par cPanel (sauf node_modules, géré par cPanel).
  find "$APP" -mindepth 1 -maxdepth 1 ! -name node_modules ! -name .env ! -name tmp -exec rm -rf {} +
  cp -a "$TMP"/. "$APP"/
  rm -rf "$TMP"
fi
cd "$APP"

# --- 3. Configuration ---------------------------------------------------------
say "Configuration"
touch .env
chmod 600 .env
if grep -q '^GEV_ALLOWED_HOSTS=' .env; then
  sed -i "s|^GEV_ALLOWED_HOSTS=.*|GEV_ALLOWED_HOSTS=$DOMAIN|" .env
else
  echo "GEV_ALLOWED_HOSTS=$DOMAIN" >> .env
fi

curl -fsSL "https://raw.githubusercontent.com/EmmaBPL/gods-eye-view-android/main/serveur/app.cjs?t=$(date +%s)" -o app.cjs

say "Correctifs pour l'hébergement et les adresses"
curl -fsSL "https://raw.githubusercontent.com/EmmaBPL/gods-eye-view-android/main/serveur/patch-gev.mjs?t=$(date +%s)" -o "$HOME/.gev-patch.mjs"
node "$HOME/.gev-patch.mjs" "$APP" || echo "!! Les correctifs n'ont pas pu être appliqués ; on continue."

# --- 4. Dépendances et compilation ---------------------------------------------
say "Installation des dépendances (5 à 10 minutes)"
export PUPPETEER_SKIP_DOWNLOAD=1
npm install --include=dev --ignore-scripts --no-audit --no-fund

say "Compilation de l'application (quelques minutes)"
NODE_OPTIONS="--max-old-space-size=3072" npm run build

# --- 6. Redémarrage ------------------------------------------------------------
say "Redémarrage de l'application"
if command -v cloudlinux-selector >/dev/null 2>&1; then
  cloudlinux-selector restart --json --interpreter nodejs --app-root "$APP_NAME" >/dev/null || true
fi
mkdir -p "$APP/tmp" && touch "$APP/tmp/restart.txt"

say "Terminé !"
cat <<MSG

Ouvre https://$DOMAIN dans ton navigateur ou dans l'appli Android.
Le tout premier chargement peut prendre une trentaine de secondes.

Pour mettre à jour plus tard, relance simplement :
  bash installer-gev.sh $DOMAIN
MSG
