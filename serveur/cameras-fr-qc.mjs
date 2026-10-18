// Ajoute à God's Eye View des caméras de circulation publiées en données
// ouvertes, absentes de l'appli d'origine :
//   - Métropole de Lyon, caméras CRITER (Licence Ouverte 2.0)
//   - Québec, caméras du MTMD / Québec 511 (CC-BY 4.0), si le flux fournit des images
// Le résultat est un fichier de caméras perso, fusionné avec le fichier par
// défaut de l'appli, que God's Eye View charge via CCTV_SOURCES_FILE.
//
// Usage : node cameras-fr-qc.mjs /chemin/vers/gev

import fs from 'node:fs';
import path from 'node:path';

const root = process.argv[2] || process.cwd();
const OUT_REL = 'config/cctv_sources.custom.json';
const OUT = path.join(root, OUT_REL);
const DEFAULT_PACK = path.join(root, 'config/cctv_sources.austin.json');
const UA = 'gods-eye-view-android (+https://github.com/EmmaBPL/gods-eye-view-android)';

const LYON_URL =
  'https://www.data.gouv.fr/api/1/datasets/r/dd826b09-7c55-4c84-8793-624651aecab2';
const QC_URL =
  'https://ws.mapserver.transports.gouv.qc.ca/swtq?service=wfs&version=2.0.0&request=GetFeature' +
  '&typeNames=ms:infos_cameras&outputFormat=geojson&srsName=EPSG:4326';

async function getJson(url) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 30_000);
  try {
    const res = await fetch(url, {
      headers: { 'User-Agent': UA, Accept: 'application/json' },
      signal: controller.signal,
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const text = await res.text();
    return JSON.parse(text);
  } finally {
    clearTimeout(timer);
  }
}

function pointOf(feature) {
  const c = feature?.geometry?.coordinates;
  if (!Array.isArray(c) || c.length < 2) return null;
  const [lon, lat] = c.map(Number);
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) return null;
  if (Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
  return { lat, lon };
}

function slug(text) {
  return String(text || '')
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '')
    .slice(0, 60);
}

async function lyonCameras() {
  const data = await getJson(LYON_URL);
  const out = [];
  for (const feature of data?.features || []) {
    const p = feature.properties || {};
    const point = pointOf(feature);
    const url = String(p.url || '');
    if (!point || !/^https:\/\/download\.data\.grandlyon\.com\//.test(url)) continue;
    const name = [p.libellelong, p.observation].filter(Boolean).join(' — ');
    out.push({
      id: `lyon-criter-${slug(p.numeromaintenance || p.identifiant || p.gid)}`,
      name: name || p.nom || 'Caméra CRITER',
      code: String(p.numeromaintenance || '').toUpperCase() || undefined,
      city: 'Lyon',
      provider: 'Métropole de Lyon — CRITER',
      sourceKind: 'municipal-webcam',
      feedType: 'image',
      url,
      snapshotUrl: url,
      lat: point.lat,
      lon: point.lon,
      fovDeg: 60,
      rangeM: 180,
      mountHeightM: 8,
      license:
        'Métropole de Lyon, caméras Web CRITER — Licence Ouverte / Open Licence 2.0 (data.gouv.fr).',
    });
  }
  return out;
}

function findImageUrl(properties) {
  const values = Object.values(properties || {}).filter(
    (v) => typeof v === 'string' && /^https?:\/\//i.test(v),
  );
  return (
    values.find((v) => /\.(jpe?g|png|webp|gif)(\?|$)/i.test(v)) ||
    values.find((v) => /image|snapshot|photo|jpg/i.test(v)) ||
    ''
  );
}

async function quebecCameras() {
  const data = await getJson(QC_URL);
  const features = data?.features || [];
  if (features.length) {
    console.log('   Exemple Québec (pour vérification) :');
    console.log('   ' + JSON.stringify(features[0].properties).slice(0, 600));
  }
  const out = [];
  for (const feature of features) {
    const p = feature.properties || {};
    const point = pointOf(feature);
    const url = findImageUrl(p);
    if (!point || !url) continue;
    const rawName =
      p.description || p.descr || p.nom || p.name || p.localisation || p.route || '';
    const idPart = p.id || p.identifiant || p.no_camera || p.numero || rawName || out.length;
    out.push({
      id: `qc-mtmd-${slug(idPart)}`,
      name: String(rawName || 'Caméra Québec 511'),
      city: String(p.region || p.municipalite || 'Québec'),
      provider: 'Gouvernement du Québec — MTMD (Québec 511)',
      sourceKind: 'state-dot',
      feedType: 'image',
      url,
      snapshotUrl: url,
      lat: point.lat,
      lon: point.lon,
      fovDeg: 60,
      rangeM: 300,
      mountHeightM: 10,
      license: 'Ministère des Transports et de la Mobilité durable du Québec — CC-BY 4.0 (Données Québec).',
    });
  }
  return out;
}

const packs = [];
try {
  packs.push(...JSON.parse(fs.readFileSync(DEFAULT_PACK, 'utf8')));
} catch {}

let added = 0;
for (const [label, load] of [
  ['Lyon (CRITER)', lyonCameras],
  ['Québec (MTMD)', quebecCameras],
]) {
  try {
    const cams = await load();
    packs.push(...cams);
    added += cams.length;
    console.log(`OK Caméras ${label} : ${cams.length}`);
  } catch (error) {
    console.warn(`!! Caméras ${label} indisponibles : ${error?.message || error}`);
  }
}

const seen = new Set();
const unique = packs.filter((cam) => {
  if (!cam?.id || seen.has(cam.id)) return false;
  seen.add(cam.id);
  return true;
});

fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, JSON.stringify(unique, null, 1));

const envFile = path.join(root, '.env');
let env = '';
try {
  env = fs.readFileSync(envFile, 'utf8');
} catch {}
if (/^CCTV_SOURCES_FILE=/m.test(env)) {
  env = env.replace(/^CCTV_SOURCES_FILE=.*$/m, `CCTV_SOURCES_FILE=${OUT_REL}`);
} else {
  env = (env && !env.endsWith('\n') ? env + '\n' : env) + `CCTV_SOURCES_FILE=${OUT_REL}\n`;
}
// Sans ce réglage, un fichier perso désactive tous les autres réseaux de
// caméras (Ontario, Londres, Toronto…) : on les garde actifs.
if (/^CCTV_FORCE_AUSTIN=/m.test(env)) {
  env = env.replace(/^CCTV_FORCE_AUSTIN=.*$/m, 'CCTV_FORCE_AUSTIN=1');
} else {
  env += 'CCTV_FORCE_AUSTIN=1\n';
}
fs.writeFileSync(envFile, env);
fs.chmodSync(envFile, 0o600);

console.log(`Caméras ajoutées : ${added} (fichier ${OUT_REL}).`);
