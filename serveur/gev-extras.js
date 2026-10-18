// Ajouts God's Eye View (installés par patch-gev.mjs).
// Toucher l'image d'une caméra (panneau CCTV) l'ouvre en plein écran ;
// toucher à nouveau, ou le bouton ✕, la referme. L'image suit la caméra en
// direct (photo rafraîchie ou vidéo).
(function () {
  'use strict';
  if (window.__gevExtras) return;
  window.__gevExtras = true;

  var style = document.createElement('style');
  style.textContent =
    '#cctv-frame-wrap{cursor:zoom-in}' +
    '.gev-cctv-zoom{position:fixed;inset:0;z-index:2147483000;background:rgba(0,0,0,.94);' +
    'display:flex;align-items:center;justify-content:center;cursor:zoom-out;touch-action:pinch-zoom}' +
    '.gev-cctv-zoom img,.gev-cctv-zoom canvas{width:100vw;height:100vh;object-fit:contain}' +
    '.gev-cctv-zoom-close{position:absolute;top:max(12px,env(safe-area-inset-top));right:12px;' +
    'width:44px;height:44px;border-radius:22px;border:1px solid #7cffb2;background:#05080d;' +
    'color:#7cffb2;font:20px monospace;cursor:pointer}' +
    '.gev-cctv-zoom-label{position:absolute;left:12px;right:68px;top:max(18px,env(safe-area-inset-top));' +
    'color:#7cffb2;font:12px monospace;letter-spacing:.08em;text-transform:uppercase;' +
    'white-space:nowrap;overflow:hidden;text-overflow:ellipsis}';
  document.head.appendChild(style);

  var overlay = null;
  var timer = 0;
  var raf = 0;

  function close() {
    if (!overlay) return;
    clearInterval(timer);
    cancelAnimationFrame(raf);
    overlay.remove();
    overlay = null;
  }

  function open() {
    var img = document.getElementById('cctv-frame');
    var video = document.getElementById('cctv-video');
    var useVideo = video && !video.hidden && video.width > 1;
    if (!useVideo && !(img && img.getAttribute('src'))) return;

    overlay = document.createElement('div');
    overlay.className = 'gev-cctv-zoom';
    overlay.setAttribute('role', 'dialog');

    var label = document.createElement('div');
    label.className = 'gev-cctv-zoom-label';
    var select = document.getElementById('cctv-camera-select');
    var option = select && select.options[select.selectedIndex];
    label.textContent = option ? option.textContent : 'CCTV';

    var closeBtn = document.createElement('button');
    closeBtn.className = 'gev-cctv-zoom-close';
    closeBtn.type = 'button';
    closeBtn.textContent = '✕';
    closeBtn.setAttribute('aria-label', 'Fermer');

    if (useVideo) {
      var big = document.createElement('canvas');
      var ctx = big.getContext('2d');
      var paint = function () {
        if (!overlay) return;
        if (video.width > 1 && video.height > 1) {
          if (big.width !== video.width || big.height !== video.height) {
            big.width = video.width;
            big.height = video.height;
          }
          try {
            ctx.drawImage(video, 0, 0);
          } catch (e) {}
        }
        raf = requestAnimationFrame(paint);
      };
      overlay.appendChild(big);
      paint();
    } else {
      var copy = document.createElement('img');
      copy.alt = label.textContent;
      copy.src = img.currentSrc || img.src;
      overlay.appendChild(copy);
      timer = setInterval(function () {
        var next = img.currentSrc || img.src;
        if (next && next !== copy.src) copy.src = next;
      }, 500);
    }

    overlay.appendChild(label);
    overlay.appendChild(closeBtn);
    overlay.addEventListener('click', close);
    document.body.appendChild(overlay);
  }

  document.addEventListener(
    'click',
    function (event) {
      var wrap = event.target && event.target.closest && event.target.closest('#cctv-frame-wrap');
      if (wrap && !overlay) open();
    },
    true,
  );
  document.addEventListener('keydown', function (event) {
    if (event.key === 'Escape') close();
  });
  window.addEventListener('popstate', close);
})();
