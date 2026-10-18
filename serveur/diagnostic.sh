#!/bin/bash
# Diagnostic de l'installation God's Eye View sur o2switch.
# Usage : bash diagnostic-gev.sh globe.jedi3514.odns.fr

DOMAIN="${1:-globe.jedi3514.odns.fr}"
APP="$HOME/gev"
PORT=4999

ACTIVATE="$(ls -d "$HOME"/nodevenv/gev/2[4-9]*/bin/activate 2>/dev/null | sort -V | tail -1)"
# shellcheck disable=SC1090
[ -n "$ACTIVATE" ] && source "$ACTIVATE" 2>/dev/null
cd "$APP" || { echo "dossier $APP introuvable"; exit 1; }

echo "=== 1. Node et compilation"
node -v
ls dist >/dev/null 2>&1 && echo "dist : $(ls dist | wc -l) éléments, $(ls dist/assets 2>/dev/null | wc -l) assets" || echo "dist : ABSENT"
ls -la app.cjs .env 2>&1 | awk '{print $1, $NF}'

echo "=== 2. Erreurs dans le journal d'installation"
grep -i -E "error|erreur|killed|memory" "$HOME/gev-install.log" | grep -v -i "warn" | tail -5

echo "=== 3. Certificat HTTPS"
curl -s -o /dev/null -w "code=%{http_code} certificat=%{ssl_verify_result} (0 = valide)\n" "https://$DOMAIN/"

echo "=== 4. Test direct de l'appli (sans Apache)"
node --input-type=module -e "
import { preview } from 'vite';
await preview({ root: process.cwd(), configFile: 'vite.config.js', preview: { port: $PORT, host: '127.0.0.1', strictPort: true } });
" > "$HOME/gev-diag.log" 2>&1 &
PID=$!
for _ in $(seq 1 40); do curl -s -o /dev/null "http://127.0.0.1:$PORT/" && break; sleep 1; done
curl -s "http://127.0.0.1:$PORT/" > "$HOME/.gev-index.html"
echo "page : $(wc -c < "$HOME/.gev-index.html") octets"
grep -o -E '(src|href)="/[^"]+"' "$HOME/.gev-index.html" | sed -E 's/^(src|href)="//; s/"$//' | sort -u | head -12 | while read -r P; do
  curl -s -o /dev/null -w "%{http_code} %{content_type} $P\n" "http://127.0.0.1:$PORT$P"
done
kill "$PID" 2>/dev/null
echo "--- messages du serveur de test :"
grep -v "^$" "$HOME/gev-diag.log" | tail -6

echo "=== 5. Test à travers Apache (avec ton mot de passe)"
read -r -s -p "Mot de passe God's Eye View : " PW < /dev/tty; echo
U="SYXTBEA"
curl -sk -u "$U:$PW" "https://$DOMAIN/" > "$HOME/.gev-index2.html"
echo "page : $(wc -c < "$HOME/.gev-index2.html") octets"
grep -o -E '(src|href)="/[^"]+"' "$HOME/.gev-index2.html" | sed -E 's/^(src|href)="//; s/"$//' | sort -u | head -12 | while read -r P; do
  curl -sk -u "$U:$PW" -o /dev/null -w "%{http_code} %{content_type} $P\n" "https://$DOMAIN$P"
done
unset PW
rm -f "$HOME/.gev-index.html" "$HOME/.gev-index2.html"
echo "=== Fin du diagnostic"
