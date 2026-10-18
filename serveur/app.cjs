'use strict';
// Démarre God's Eye View sous Passenger / cPanel (o2switch).
// Les fichiers compilés (dist/) sont servis directement ici ; le reste
// (API de données, page d'accueil) passe par le serveur « preview » de Vite.
const path = require('node:path');
const fs = require('node:fs');

process.chdir(__dirname);

const DIST = path.join(__dirname, 'dist');
const LOG = path.join(__dirname, 'gev-requests.log');
let logged = 0;
function log(line) {
  if (logged >= 300) return;
  logged += 1;
  try {
    fs.appendFileSync(LOG, `${new Date().toISOString()} ${line}\n`);
  } catch {}
}

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.geojson': 'application/geo+json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.map': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.avif': 'image/avif',
  '.ico': 'image/x-icon',
  '.ktx2': 'image/ktx2',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.otf': 'font/otf',
  '.wasm': 'application/wasm',
  '.glb': 'model/gltf-binary',
  '.gltf': 'model/gltf+json',
  '.xml': 'application/xml',
  '.txt': 'text/plain; charset=utf-8',
  '.csv': 'text/csv; charset=utf-8',
  '.mp4': 'video/mp4',
  '.webm': 'video/webm',
  '.mp3': 'audio/mpeg',
  '.wav': 'audio/wav',
  '.pbf': 'application/x-protobuf',
};

function staticPlugin() {
  return {
    name: 'gev-passenger-static',
    configurePreviewServer(server) {
      const headers = (server.config.preview && server.config.preview.headers) || {};
      log(`démarrage, dist=${DIST} existe=${fs.existsSync(DIST)}`);
      server.middlewares.use((req, res, next) => {
        log(`${req.method} ${req.url}`);
        if (req.method !== 'GET' && req.method !== 'HEAD') return next();
        let pathname;
        try {
          pathname = decodeURIComponent(new URL(req.url, 'http://localhost').pathname);
        } catch {
          return next();
        }
        if (pathname.startsWith('/api/') || pathname === '/' || pathname.endsWith('.html')) return next();
        const file = path.join(DIST, pathname);
        if (!file.startsWith(DIST + path.sep)) return next();
        let stat;
        try {
          stat = fs.statSync(file);
        } catch {
          return next();
        }
        if (!stat.isFile()) return next();
        for (const [key, value] of Object.entries(headers)) res.setHeader(key, value);
        res.setHeader('Content-Type', TYPES[path.extname(file).toLowerCase()] || 'application/octet-stream');
        res.setHeader('Content-Length', stat.size);
        res.setHeader(
          'Cache-Control',
          pathname.startsWith('/assets/') ? 'public, max-age=31536000, immutable' : 'no-cache',
        );
        res.statusCode = 200;
        if (req.method === 'HEAD') return res.end();
        fs.createReadStream(file)
          .on('error', (error) => {
            log(`erreur lecture ${file}: ${error.message}`);
            res.destroy(error);
          })
          .pipe(res);
      });
    },
  };
}

import('vite')
  .then(({ preview }) =>
    preview({
      root: __dirname,
      configFile: path.join(__dirname, 'vite.config.js'),
      plugins: [staticPlugin()],
      preview: { port: Number(process.env.PORT) || 4173, host: '127.0.0.1' },
    }),
  )
  .then(() => log("God's Eye View est prêt."))
  .catch((error) => {
    log(`ERREUR au démarrage : ${error && error.stack ? error.stack : error}`);
    console.error(error);
    process.exit(1);
  });
