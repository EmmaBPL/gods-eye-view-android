// Correctifs appliqués à God's Eye View après chaque téléchargement du code,
// avant la compilation. Chaque correctif remplace un passage précis ; si le
// passage n'existe plus (le projet a changé), il est ignoré avec un avertissement
// au lieu de casser l'installation.
//
// Usage : node patch-gev.mjs /chemin/vers/gev

import fs from 'node:fs';
import path from 'node:path';

const root = process.argv[2] || process.cwd();

const patches = [
  {
    name: 'Adresses avec numéro : Nominatim avant Photon',
    file: 'src/search/defaults.js',
    find: `            createPhotonGeocoder({ fetchImpl, endpoint: endpoints.photon }),
            // Last resort: the local Nominatim route, which answers with no key when
            // neither of the two above did. It speaks the same result shape, so it
            // rides the existing Google adapter rather than needing its own.
            createGoogleGeocoder({
              request(query, { bias, signal }) {
                const params = new URLSearchParams({ q: query });
                if (bias) params.set('bounds', bias);
                return fetchImpl(
                  \`\${endpoints.nominatim || '/api/geocode'}?\${params}\`,
                  {
                    signal,
                  },
                );
              },
            }),`,
    replace: `            // GEV-PATCH: Photon ne trouve souvent que la rue (ou la ville) pour
            // une adresse avec numéro, ce qui cadre la caméra beaucoup trop haut.
            // Nominatim trouve l'adresse exacte : il passe en premier quand la
            // recherche contient un chiffre et des lettres.
            (() => {
              const photon = createPhotonGeocoder({
                fetchImpl,
                endpoint: endpoints.photon,
              });
              const nominatim = createGoogleGeocoder({
                request(query, { bias, signal }) {
                  const params = new URLSearchParams({ q: query });
                  if (bias) params.set('bounds', bias);
                  return fetchImpl(
                    \`\${endpoints.nominatim || '/api/geocode'}?\${params}\`,
                    {
                      signal,
                    },
                  );
                },
              });
              const looksLikeAddress = (text) =>
                /\\d/.test(text) && /\\p{L}{3,}/u.test(text);
              return {
                async geocode(query, options = {}) {
                  const order = looksLikeAddress(String(query || ''))
                    ? [nominatim, photon]
                    : [photon, nominatim];
                  let answered = true;
                  for (const provider of order) {
                    let outcome;
                    try {
                      outcome = await provider.geocode(query, options);
                    } catch (error) {
                      options.signal?.throwIfAborted?.();
                      outcome = { place: null, answered: false };
                    }
                    if (outcome?.place) return outcome;
                    answered = answered && Boolean(outcome?.answered);
                  }
                  return { place: null, answered };
                },
              };
            })(),`,
  },
  {
    name: 'Photon : un résultat avec numéro de rue est une adresse précise',
    file: 'src/keylessGeocoder.js',
    find: `  if (AREA_OSM_KEYS.has(key)) return ['natural_feature'];`,
    replace: `  // GEV-PATCH: une adresse avec numéro se cadre de près, jamais comme une rue.
  if (String(properties?.housenumber || '').trim()) return [];
  if (AREA_OSM_KEYS.has(key)) return ['natural_feature'];`,
  },
  {
    name: 'Recherche Nominatim en français',
    file: 'server/providers/regional/place.js',
    find: `        'accept-language': 'en',`,
    replace: `        'accept-language': process.env.GEV_SEARCH_LANGUAGE || 'fr,en',`,
  },
  {
    name: 'Voix : rechercher les vols par compagnie aérienne',
    file: 'server/providers/openai/toolDescriptions.js',
    find: 'Every layer has id, lat, lon. Fields — ${voiceQueryFieldHint()}.`,',
    replace:
      'Every layer has id, lat, lon. Fields — ${voiceQueryFieldHint()}.' +
      ' AIRLINES (GEV-PATCH): flights carry no airline name; an airline is its ICAO callsign prefix, so filter {field:"callsign",op:"contains",value:"<PREFIX>"} on the flights layer with scope anywhere unless a place is named, and never filter callsign by the plain airline name.' +
      ' Prefixes: Air Canada ACA, Air Canada Rouge ROU, Air Canada Express JZA, Air Transat TSC, WestJet WJA, Porter POE, Flair FLE, Air Inuit AIE, Air France AFR, Transavia France TVF, easyJet EZY, Ryanair RYR, Volotea VOE, Corsair CRL, Air Caraïbes FWI, Lufthansa DLH, British Airways BAW, KLM KLM, Swiss SWR, Iberia IBE, ITA ITY, TAP TAP, Delta DAL, United UAL, American AAL, Emirates UAE, Qatar QTR, Turkish THY.' +
      ' Any other airline: use its three-letter ICAO code. The map cannot hide other flights: say so, give the count and the first callsigns, and offer to track or fly to one.`,',
  },
  {
    name: 'Caméras : toucher l’image pour l’agrandir',
    file: 'index.html',
    find: '  <script type="module" src="/src/main.js"></script>',
    replace:
      '  <script src="/gev-extras.js" defer></script>\n' +
      '  <script type="module" src="/src/main.js"></script>',
  },
];

let applied = 0;
let skipped = 0;
for (const patch of patches) {
  const file = path.join(root, patch.file);
  let text;
  try {
    text = fs.readFileSync(file, 'utf8');
  } catch {
    console.warn(`!! Correctif ignoré (fichier absent) : ${patch.name}`);
    skipped += 1;
    continue;
  }
  if (text.includes(patch.replace)) {
    console.log(`== Déjà appliqué : ${patch.name}`);
    continue;
  }
  const count = text.split(patch.find).length - 1;
  if (count !== 1) {
    console.warn(
      `!! Correctif ignoré (code modifié par l'auteur) : ${patch.name}`,
    );
    skipped += 1;
    continue;
  }
  fs.writeFileSync(file, text.replace(patch.find, patch.replace));
  console.log(`OK Correctif appliqué : ${patch.name}`);
  applied += 1;
}
console.log(`Correctifs : ${applied} appliqué(s), ${skipped} ignoré(s).`);
