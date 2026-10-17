/*!
 * Radiant PC Game Browser - PC Engine Shim
 *
 * Injected by MainActivity with WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*")),
 * so it runs in every frame (top page and iframes) before any page script.
 * The app prepends one line:  window.__RADIANT_BOOT__ = { ua, chromeMajor, chromeFull, bridge, settings, scale };
 *
 *   1. Windows 11 desktop environment (navigator, UA client hints, matchMedia, touch feature hiding)
 *   2. WebGL: S3TC / DXT1 / DXT3 / DXT5 software decoder behind emulated extensions
 *   3. Pointer Lock emulation (promise based, Escape behaves like desktop Chrome)
 *   4. Fullscreen emulation (top-layer pseudo fullscreen that also works from inside iframes)
 *   5. Audio autoplay unlock
 *   6. SharedArrayBuffer probe (polyfills the global only when threads can really work)
 *   7. Display: devicePixelRatio resolution scaling + desktop-width viewport
 *   8. Gamepad API override (standard XInput layout built from the app's native controller state)
 *   9. Input engine: controller -> synthetic desktop keyboard / mouse events
 *  10. Native bridge (WebViewCompat.addWebMessageListener object, one channel per frame)
 */
(function () {
  'use strict';

  var W = window;
  if (W.__radiantShimActive) return;
  try {
    Object.defineProperty(W, '__radiantShimActive', { value: true, configurable: false, enumerable: false });
  } catch (e) {
    return;
  }

  // =====================================================================================
  // 0. Boot configuration and helpers
  // =====================================================================================

  var BOOT = (W.__RADIANT_BOOT__ && typeof W.__RADIANT_BOOT__ === 'object') ? W.__RADIANT_BOOT__ : {};
  try { delete W.__RADIANT_BOOT__; } catch (e) { /* non-configurable on exotic hosts: ignore */ }

  const IS_TOP = (function () { try { return W.top === W; } catch (e) { return false; } })();
  const now = () => performance.now();

  const DEFAULT_BINDS = {
    0: 'Space',        // A / Cross        - jump
    1: 'KeyC',         // B / Circle       - crouch / slide
    2: 'KeyR',         // X / Square       - reload
    3: 'WheelDown',    // Y / Triangle     - next weapon
    4: 'KeyF+KeyE',    // LB / L1          - use / buy / interact (both common PC conventions)
    5: 'KeyG',         // RB / R1          - grenade
    6: 'Mouse2',       // LT / L2          - aim down sights
    7: 'Mouse0',       // RT / R2          - fire
    8: 'Tab',          // Select / Share   - scoreboard
    9: 'Menu',         // Start / Options  - Esc on tap, Enter on hold
    10: 'ShiftLeft',   // L3               - sprint
    11: 'KeyV',        // R3               - melee
    12: 'Digit1',      // D-pad up
    13: 'Digit2',      // D-pad down
    14: 'Digit3',      // D-pad left
    15: 'Digit4',      // D-pad right
    16: 'None'         // Guide (opens the app drawer natively)
  };

  const S = {
    mode: 'kbm',            // 'kbm' | 'pad' | 'hybrid'
    sens: 1,
    deadzone: 0.15,
    dpadArrows: false,
    resScale: 1,
    desktopViewport: 1280,
    autoCursor: true,
    binds: Object.assign({}, DEFAULT_BINDS)
  };

  function clampNum(v, lo, hi, d) {
    v = Number(v);
    return Number.isFinite(v) ? Math.min(hi, Math.max(lo, v)) : d;
  }

  function applySettings(src) {
    if (!src || typeof src !== 'object') return;
    if (src.mode === 'kbm' || src.mode === 'pad' || src.mode === 'hybrid') S.mode = src.mode;
    if ('sens' in src) S.sens = clampNum(src.sens, 0.05, 10, S.sens);
    if ('deadzone' in src) S.deadzone = clampNum(src.deadzone, 0, 0.9, S.deadzone);
    if ('dpadArrows' in src) S.dpadArrows = !!src.dpadArrows;
    if ('resScale' in src) S.resScale = clampNum(src.resScale, 0.25, 2, S.resScale);
    if ('desktopViewport' in src) S.desktopViewport = clampNum(src.desktopViewport, 0, 4096, S.desktopViewport) | 0;
    if ('autoCursor' in src) S.autoCursor = !!src.autoCursor;
    if (src.binds && typeof src.binds === 'object') {
      const b = Object.assign({}, DEFAULT_BINDS);
      for (const k in src.binds) {
        if (Object.prototype.hasOwnProperty.call(src.binds, k)) b[k] = String(src.binds[k]);
      }
      S.binds = b;
    }
  }
  applySettings(BOOT.settings);

  function defineGetter(obj, prop, getter) {
    if (!obj) return false;
    try {
      Object.defineProperty(obj, prop, { get: getter, configurable: true, enumerable: true });
      return true;
    } catch (e) {
      return false;
    }
  }

  function guard(name, fn) {
    try {
      fn();
    } catch (e) {
      try { console.warn('[Radiant] ' + name + ' disabled:', e); } catch (_) { /* console missing */ }
    }
  }

  // Channel to the app. Messages are queued until the injected bridge object is found.
  const Native = {
    obj: null,
    queue: [],
    post(msg) {
      let s;
      try { s = JSON.stringify(msg); } catch (e) { return; }
      if (this.obj) {
        try { this.obj.postMessage(s); } catch (e) { /* frame is going away */ }
      } else if (this.queue.length < 100) {
        this.queue.push(s);
      }
    }
  };

  // Shared state used by several sections (declared up front so every section can reach it).
  const Lock = { el: null };
  const Display = { pageScale: Number(BOOT.scale) > 0 ? Number(BOOT.scale) : 0, notify: function () {} };
  const Status = { sab: 'unknown', s3tc: 'unknown' };

  // =====================================================================================
  // 1. Windows 11 desktop environment
  // =====================================================================================

  // Which desktop browser the page should believe it is talking to.
  //   chrome / edge : Chromium on Windows 11 (matches the real engine, the safest choice)
  //   firefox       : Firefox on Windows 11 (no UA client hints, no window.chrome)
  //   android       : no spoofing at all (the real Android WebView identity)
  const IDENTITY = ['chrome', 'edge', 'firefox', 'android'].indexOf(BOOT.identity) >= 0 ? BOOT.identity : 'chrome';

  // navigator.userAgentData (UA Client Hints JS API) for the Chromium-based identities.
  function installUserAgentData(major, full) {
    const NP = Navigator.prototype;
    const lead = IDENTITY === 'edge' ? 'Microsoft Edge' : 'Google Chrome';
    const brands = Object.freeze([
      Object.freeze({ brand: lead, version: major }),
      Object.freeze({ brand: 'Chromium', version: major }),
      Object.freeze({ brand: 'Not_A Brand', version: '24' })
    ]);
    const fullVersionList = Object.freeze([
      Object.freeze({ brand: lead, version: full }),
      Object.freeze({ brand: 'Chromium', version: full }),
      Object.freeze({ brand: 'Not_A Brand', version: '24.0.0.0' })
    ]);
    const highEntropy = {
      architecture: 'x86',
      bitness: '64',
      model: '',
      platformVersion: '15.0.0',
      uaFullVersion: full,
      fullVersionList: fullVersionList,
      wow64: false,
      formFactors: Object.freeze(['Desktop'])
    };
    const uadProto = (typeof W.NavigatorUAData === 'function') ? W.NavigatorUAData.prototype : Object.prototype;
    const uad = Object.create(uadProto);
    Object.defineProperties(uad, {
      brands: { value: brands, enumerable: true },
      mobile: { value: false, enumerable: true },
      platform: { value: 'Windows', enumerable: true },
      getHighEntropyValues: {
        value: function getHighEntropyValues(hints) {
          const out = { brands: brands, mobile: false, platform: 'Windows' };
          const list = (hints && typeof hints[Symbol.iterator] === 'function') ? Array.from(hints) : [];
          for (const h of list) {
            if (Object.prototype.hasOwnProperty.call(highEntropy, h)) out[h] = highEntropy[h];
          }
          return Promise.resolve(out);
        }
      },
      toJSON: { value: function toJSON() { return { brands: brands, mobile: false, platform: 'Windows' }; } }
    });
    defineGetter(NP, 'userAgentData', () => uad);
  }

  function installDesktopEnv() {
    if (IDENTITY === 'android') return;
    const ua = (typeof BOOT.ua === 'string' && BOOT.ua) ||
      'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';
    const major = String(BOOT.chromeMajor || (ua.match(/Chrome\/(\d+)/) || [0, '131'])[1]);
    const full = String(BOOT.chromeFull || (major + '.0.0.0'));
    const NP = Navigator.prototype;
    const nativeCores = navigator.hardwareConcurrency || 8;
    const firefox = IDENTITY === 'firefox';

    defineGetter(NP, 'userAgent', () => ua);
    defineGetter(NP, 'appVersion', () => firefox ? '5.0 (Windows)' : ua.replace(/^Mozilla\//, ''));
    defineGetter(NP, 'platform', () => 'Win32');
    defineGetter(NP, 'vendor', () => firefox ? '' : 'Google Inc.');
    defineGetter(NP, 'maxTouchPoints', () => 0);
    defineGetter(NP, 'hardwareConcurrency', () => Math.max(nativeCores, 8));
    if (firefox) {
      defineGetter(NP, 'oscpu', () => 'Windows NT 10.0; Win64; x64');
      defineGetter(NP, 'buildID', () => '20181001000000');
      defineGetter(NP, 'productSub', () => '20100101');
      // Firefox exposes neither navigator.deviceMemory nor navigator.userAgentData.
      for (const p of ['deviceMemory', 'userAgentData']) {
        try { delete NP[p]; } catch (e) { /* ignore */ }
        try { delete navigator[p]; } catch (e) { /* ignore */ }
      }
      try { if (Object.prototype.hasOwnProperty.call(W, 'chrome')) delete W.chrome; } catch (e) { /* ignore */ }
    } else {
      defineGetter(NP, 'deviceMemory', () => 8);
      installUserAgentData(major, full);
      // Desktop Chrome and Edge always expose window.chrome; Android WebView does not.
      if (!W.chrome) {
        const chromeObj = {
          app: {
            isInstalled: false,
            InstallState: { DISABLED: 'disabled', INSTALLED: 'installed', NOT_INSTALLED: 'not_installed' },
            RunningState: { CANNOT_RUN: 'cannot_run', READY_TO_RUN: 'ready_to_run', RUNNING: 'running' }
          },
          runtime: {},
          loadTimes: function loadTimes() { return {}; },
          csi: function csi() { return { startE: Date.now(), onloadT: Date.now(), pageT: performance.now(), tran: 15 }; }
        };
        try {
          Object.defineProperty(W, 'chrome', { value: chromeObj, writable: true, configurable: true, enumerable: true });
        } catch (e) { /* ignore */ }
      }
    }

    // matchMedia: a desktop has a fine pointer that can hover and no coarse (touch) pointer.
    // Each pointer/hover feature is rewritten into an always-true or always-false condition so
    // compound queries such as "not (pointer: coarse)" or "(hover: hover) and (min-width: 900px)"
    // still evaluate correctly in the native engine.
    const origMatchMedia = W.matchMedia;
    if (typeof origMatchMedia === 'function') {
      const TRUE_COND = '(min-width: 0px)';
      const FALSE_COND = '((max-width: 0px) and (min-width: 1px))';
      const RE = /\(\s*(any-)?(pointer|hover)\s*(?::\s*([a-z-]+)\s*)?\)/gi;
      const desktopValue = (feature, value) => {
        const v = (value || '').toLowerCase();
        if (feature === 'pointer') return v === '' || v === 'fine';
        return v === '' || v === 'hover';
      };
      W.matchMedia = function matchMedia(query) {
        const q = String(query);
        const rewritten = q.replace(RE, (m, any, feature, value) =>
          desktopValue(feature.toLowerCase(), value) ? TRUE_COND : FALSE_COND);
        const mql = origMatchMedia.call(this, rewritten);
        if (rewritten !== q) {
          try { Object.defineProperty(mql, 'media', { value: q, configurable: true }); } catch (e) { /* ignore */ }
        }
        return mql;
      };
    }

    // Hide touch-only feature detection points ('ontouchstart' in window, window.orientation ...)
    // Chromium installs these context-dependent attributes slightly after document-start scripts run,
    // so the removal is repeated: now, in a microtask, and in the first MutationObserver callback
    // (which the parser runs before executing the page's first <script>).
    const hideTouchFeatures = () => {
      const holders = [W, W.Window && W.Window.prototype, W.Document && W.Document.prototype,
        W.Element && W.Element.prototype, W.HTMLElement && W.HTMLElement.prototype,
        W.SVGElement && W.SVGElement.prototype];
      for (const p of ['ontouchstart', 'ontouchmove', 'ontouchend', 'ontouchcancel']) {
        for (const h of holders) {
          if (!h) continue;
          try { if (Object.prototype.hasOwnProperty.call(h, p)) delete h[p]; } catch (e) { /* non-configurable */ }
        }
      }
      for (const p of ['orientation', 'onorientationchange']) {
        try { if (Object.prototype.hasOwnProperty.call(W, p)) delete W[p]; } catch (e) { /* ignore */ }
        try { if (W.Window && Object.prototype.hasOwnProperty.call(W.Window.prototype, p)) delete W.Window.prototype[p]; } catch (e) { /* ignore */ }
      }
    };
    hideTouchFeatures();
    Promise.resolve().then(hideTouchFeatures);
    try {
      let runs = 0;
      const mo = new MutationObserver(() => {
        hideTouchFeatures();
        if (++runs >= 3) mo.disconnect();
      });
      mo.observe(document, { childList: true, subtree: true });
      document.addEventListener('DOMContentLoaded', () => { hideTouchFeatures(); mo.disconnect(); }, { once: true });
    } catch (e) { /* ignore */ }
    const origCreateEvent = Document.prototype.createEvent;
    Document.prototype.createEvent = function createEvent(type) {
      if (/^touchevents?$/i.test(String(type))) {
        throw new DOMException("The provided event type ('" + type + "') is invalid.", 'NotSupportedError');
      }
      return origCreateEvent.apply(this, arguments);
    };
  }

  // =====================================================================================
  // 2. WebGL: S3TC (DXT1/3/5) emulation
  // =====================================================================================

  const S3TC_FORMATS = new Map([
    [0x83F0, { kind: 1, punch: false, srgb: false }], // COMPRESSED_RGB_S3TC_DXT1_EXT
    [0x83F1, { kind: 1, punch: true, srgb: false }],  // COMPRESSED_RGBA_S3TC_DXT1_EXT
    [0x83F2, { kind: 3, punch: false, srgb: false }], // COMPRESSED_RGBA_S3TC_DXT3_EXT
    [0x83F3, { kind: 5, punch: false, srgb: false }], // COMPRESSED_RGBA_S3TC_DXT5_EXT
    [0x8C4C, { kind: 1, punch: false, srgb: true }],  // COMPRESSED_SRGB_S3TC_DXT1_EXT
    [0x8C4D, { kind: 1, punch: true, srgb: true }],   // COMPRESSED_SRGB_ALPHA_S3TC_DXT1_EXT
    [0x8C4E, { kind: 3, punch: false, srgb: true }],  // COMPRESSED_SRGB_ALPHA_S3TC_DXT3_EXT
    [0x8C4F, { kind: 5, punch: false, srgb: true }]   // COMPRESSED_SRGB_ALPHA_S3TC_DXT5_EXT
  ]);
  const S3TC_EXT_CONSTANTS = {
    COMPRESSED_RGB_S3TC_DXT1_EXT: 0x83F0,
    COMPRESSED_RGBA_S3TC_DXT1_EXT: 0x83F1,
    COMPRESSED_RGBA_S3TC_DXT3_EXT: 0x83F2,
    COMPRESSED_RGBA_S3TC_DXT5_EXT: 0x83F3
  };
  const S3TC_SRGB_EXT_CONSTANTS = {
    COMPRESSED_SRGB_S3TC_DXT1_EXT: 0x8C4C,
    COMPRESSED_SRGB_ALPHA_S3TC_DXT1_EXT: 0x8C4D,
    COMPRESSED_SRGB_ALPHA_S3TC_DXT3_EXT: 0x8C4E,
    COMPRESSED_SRGB_ALPHA_S3TC_DXT5_EXT: 0x8C4F
  };
  const ANISO_EXT_CONSTANTS = {
    TEXTURE_MAX_ANISOTROPY_EXT: 0x84FE,
    MAX_TEXTURE_MAX_ANISOTROPY_EXT: 0x84FF
  };

  const LITTLE_ENDIAN = new Uint8Array(new Uint32Array([0x01020304]).buffer)[0] === 0x04;
  const RGB_MASK = LITTLE_ENDIAN ? 0x00FFFFFF : 0xFFFFFF00;
  const ALPHA_SHIFT = LITTLE_ENDIAN ? 24 : 0;

  function packRGBA(r, g, b, a) {
    return LITTLE_ENDIAN
      ? (((a << 24) | (b << 16) | (g << 8) | r) >>> 0)
      : (((r << 24) | (g << 16) | (b << 8) | a) >>> 0);
  }

  function s3tcImageBytes(kind, w, h) {
    return ((w + 3) >> 2) * ((h + 3) >> 2) * (kind === 1 ? 8 : 16);
  }

  // Decodes one 2D S3TC image (srcOff..) into out32 (w*h packed RGBA8 pixels).
  function decodeS3TCImage(src, srcOff, w, h, fmt, out32) {
    const kind = fmt.kind;
    const punch = fmt.punch;
    const bw = (w + 3) >> 2;
    const bh = (h + 3) >> 2;
    const pal = new Uint32Array(4);
    const alphaTab = new Uint8Array(8);
    const blockBytes = kind === 1 ? 8 : 16;
    let p = srcOff;

    for (let by = 0; by < bh; by++) {
      const y0 = by << 2;
      const rows = Math.min(4, h - y0);
      for (let bx = 0; bx < bw; bx++, p += blockBytes) {
        const x0 = bx << 2;
        const cols = Math.min(4, w - x0);

        // ----- alpha block (DXT3 explicit 4-bit, DXT5 interpolated 3-bit indices)
        let a3lo = 0, a3hi = 0, a5lo = 0, a5hi = 0;
        if (kind === 3) {
          a3lo = (src[p] | (src[p + 1] << 8) | (src[p + 2] << 16) | (src[p + 3] << 24)) >>> 0;
          a3hi = (src[p + 4] | (src[p + 5] << 8) | (src[p + 6] << 16) | (src[p + 7] << 24)) >>> 0;
        } else if (kind === 5) {
          const a0 = src[p];
          const a1 = src[p + 1];
          alphaTab[0] = a0;
          alphaTab[1] = a1;
          if (a0 > a1) {
            alphaTab[2] = ((6 * a0 + 1 * a1) / 7) | 0;
            alphaTab[3] = ((5 * a0 + 2 * a1) / 7) | 0;
            alphaTab[4] = ((4 * a0 + 3 * a1) / 7) | 0;
            alphaTab[5] = ((3 * a0 + 4 * a1) / 7) | 0;
            alphaTab[6] = ((2 * a0 + 5 * a1) / 7) | 0;
            alphaTab[7] = ((1 * a0 + 6 * a1) / 7) | 0;
          } else {
            alphaTab[2] = ((4 * a0 + 1 * a1) / 5) | 0;
            alphaTab[3] = ((3 * a0 + 2 * a1) / 5) | 0;
            alphaTab[4] = ((2 * a0 + 3 * a1) / 5) | 0;
            alphaTab[5] = ((1 * a0 + 4 * a1) / 5) | 0;
            alphaTab[6] = 0;
            alphaTab[7] = 255;
          }
          a5lo = src[p + 2] | (src[p + 3] << 8) | (src[p + 4] << 16);
          a5hi = src[p + 5] | (src[p + 6] << 8) | (src[p + 7] << 16);
        }

        // ----- color block (RGB565 endpoints + 2-bit indices)
        const c = kind === 1 ? p : p + 8;
        const c0 = src[c] | (src[c + 1] << 8);
        const c1 = src[c + 2] | (src[c + 3] << 8);
        const bits = (src[c + 4] | (src[c + 5] << 8) | (src[c + 6] << 16) | (src[c + 7] << 24)) >>> 0;

        let r0 = (c0 >> 11) & 31, g0 = (c0 >> 5) & 63, b0 = c0 & 31;
        let r1 = (c1 >> 11) & 31, g1 = (c1 >> 5) & 63, b1 = c1 & 31;
        r0 = (r0 << 3) | (r0 >> 2); g0 = (g0 << 2) | (g0 >> 4); b0 = (b0 << 3) | (b0 >> 2);
        r1 = (r1 << 3) | (r1 >> 2); g1 = (g1 << 2) | (g1 >> 4); b1 = (b1 << 3) | (b1 >> 2);

        pal[0] = packRGBA(r0, g0, b0, 255);
        pal[1] = packRGBA(r1, g1, b1, 255);
        if (kind !== 1 || c0 > c1) {
          // 4-colour mode (always used by DXT3/DXT5 colour blocks)
          pal[2] = packRGBA(((2 * r0 + r1) / 3) | 0, ((2 * g0 + g1) / 3) | 0, ((2 * b0 + b1) / 3) | 0, 255);
          pal[3] = packRGBA(((r0 + 2 * r1) / 3) | 0, ((g0 + 2 * g1) / 3) | 0, ((b0 + 2 * b1) / 3) | 0, 255);
        } else {
          // 3-colour + transparent/black mode (DXT1 only)
          pal[2] = packRGBA((r0 + r1) >> 1, (g0 + g1) >> 1, (b0 + b1) >> 1, 255);
          pal[3] = punch ? 0 : packRGBA(0, 0, 0, 255);
        }

        for (let py = 0; py < rows; py++) {
          let o = (y0 + py) * w + x0;
          for (let px = 0; px < cols; px++, o++) {
            const i = (py << 2) | px;
            let color = pal[(bits >>> (i << 1)) & 3];
            if (kind === 3) {
              const nib = i < 8 ? (a3lo >>> (i << 2)) & 15 : (a3hi >>> ((i - 8) << 2)) & 15;
              color = ((color & RGB_MASK) | ((nib * 17) << ALPHA_SHIFT)) >>> 0;
            } else if (kind === 5) {
              const ai = i < 8 ? (a5lo >>> (3 * i)) & 7 : (a5hi >>> (3 * (i - 8))) & 7;
              color = ((color & RGB_MASK) | (alphaTab[ai] << ALPHA_SHIFT)) >>> 0;
            }
            out32[o] = color;
          }
        }
      }
    }
  }

  // Decodes `depth` consecutive 2D S3TC images into one tightly packed RGBA8 buffer.
  function decodeS3TC(src, w, h, depth, fmt) {
    w = w | 0; h = h | 0; depth = Math.max(1, depth | 0);
    if (w <= 0 || h <= 0) return new Uint8Array(0);
    const slice = s3tcImageBytes(fmt.kind, w, h);
    if (!src || src.length < slice * depth) {
      try {
        console.warn('[Radiant] S3TC upload has ' + (src ? src.length : 0) + ' bytes, expected ' + (slice * depth));
      } catch (e) { /* ignore */ }
      return null;
    }
    const out = new Uint8Array(w * h * depth * 4);
    for (let z = 0; z < depth; z++) {
      decodeS3TCImage(src, z * slice, w, h, fmt, new Uint32Array(out.buffer, z * w * h * 4, w * h));
    }
    return out;
  }

  function installWebGL() {
    const targets = [];
    if (W.WebGLRenderingContext) targets.push([W.WebGLRenderingContext.prototype, false]);
    if (W.WebGL2RenderingContext) targets.push([W.WebGL2RenderingContext.prototype, true]);
    for (const [P, isGL2] of targets) patchGLPrototype(P, isGL2);
    installGLReporting();
  }

  function patchGLPrototype(P, isGL2) {
    const o = {};
    for (const k of ['getSupportedExtensions', 'getExtension', 'getParameter', 'isContextLost',
      'compressedTexImage2D', 'compressedTexSubImage2D', 'compressedTexImage3D', 'compressedTexSubImage3D',
      'texImage2D', 'texSubImage2D', 'texImage3D', 'texSubImage3D', 'texStorage2D', 'texStorage3D',
      'pixelStorei', 'bindBuffer', 'getBufferSubData', 'texParameterf', 'texParameteri', 'getTexParameter',
      'samplerParameterf', 'samplerParameteri', 'getSamplerParameter']) {
      if (typeof P[k] === 'function') o[k] = P[k];
    }
    if (!o.getSupportedExtensions || !o.getExtension || !o.getParameter) return;

    const RGBA = 0x1908, UNSIGNED_BYTE = 0x1401, RGBA8 = 0x8058, SRGB8_ALPHA8 = 0x8C43;
    const PIXEL_UNPACK_BUFFER = 0x88EC, PIXEL_UNPACK_BUFFER_BINDING = 0x88EF;
    const TEXTURE_MAX_ANISOTROPY = 0x84FE, MAX_TEXTURE_MAX_ANISOTROPY = 0x84FF;
    const COMPRESSED_TEXTURE_FORMATS = 0x86A3;
    const UNPACK_DEFAULTS = isGL2
      ? [[0x0CF5, 4], [0x9240, 0], [0x9241, 0], [0x0CF2, 0], [0x0CF3, 0], [0x0CF4, 0], [0x806E, 0], [0x806D, 0]]
      : [[0x0CF5, 4], [0x9240, 0], [0x9241, 0]];

    const ctxInfo = new WeakMap();
    function info(gl) {
      let s = ctxInfo.get(gl);
      if (!s) {
        const list = o.getSupportedExtensions.call(gl) || [];
        const force = !!BOOT.forceS3TCEmulation;
        s = {
          s3tc: !force && list.indexOf('WEBGL_compressed_texture_s3tc') >= 0,
          s3tcSrgb: !force && list.indexOf('WEBGL_compressed_texture_s3tc_srgb') >= 0,
          aniso: list.indexOf('EXT_texture_filter_anisotropic') >= 0,
          exts: {}
        };
        if (list.length > 0) ctxInfo.set(gl, s); // never cache the empty list of a lost context
      }
      return s;
    }
    function emulated(gl, fmt) {
      const s = info(gl);
      return fmt.srgb ? !s.s3tcSrgb : !s.s3tc;
    }
    function fakeExtension(consts) {
      const ext = {};
      for (const k of Object.keys(consts)) Object.defineProperty(ext, k, { value: consts[k], enumerable: true });
      return Object.freeze(ext);
    }
    function internalFormatFor(fmt) {
      return isGL2 ? (fmt.srgb ? SRGB8_ALPHA8 : RGBA8) : RGBA;
    }

    // Runs fn with default unpack state so a tightly packed RGBA8 buffer uploads exactly.
    function withPlainUnpack(gl, fn) {
      const restore = [];
      for (const [pname, def] of UNPACK_DEFAULTS) {
        const cur = Number(o.getParameter.call(gl, pname));
        if (cur !== def) {
          restore.push([pname, cur]);
          o.pixelStorei.call(gl, pname, def);
        }
      }
      let pbo = null;
      if (isGL2) {
        pbo = o.getParameter.call(gl, PIXEL_UNPACK_BUFFER_BINDING);
        if (pbo) o.bindBuffer.call(gl, PIXEL_UNPACK_BUFFER, null);
      }
      try {
        fn();
      } finally {
        if (pbo) o.bindBuffer.call(gl, PIXEL_UNPACK_BUFFER, pbo);
        for (const [pname, val] of restore) o.pixelStorei.call(gl, pname, val);
      }
    }

    // Resolves the compressed payload: ArrayBufferView (+srcOffset/srcLengthOverride) or a PBO range.
    function compressedSource(gl, args, start) {
      const a = args[start];
      if (typeof a === 'number') {
        if (!isGL2 || !o.getBufferSubData) return null;
        const size = a | 0;
        const offset = Number(args[start + 1]) || 0;
        const tmp = new Uint8Array(size);
        o.getBufferSubData.call(gl, PIXEL_UNPACK_BUFFER, offset, tmp);
        return tmp;
      }
      if (!a || !ArrayBuffer.isView(a)) return null;
      let u8 = new Uint8Array(a.buffer, a.byteOffset, a.byteLength);
      if (args.length > start + 1) {
        const el = a.BYTES_PER_ELEMENT || 1;
        const off = (Number(args[start + 1]) || 0) * el;
        const len = (Number(args[start + 2]) || 0) * el;
        u8 = u8.subarray(off, len ? off + len : u8.length);
      }
      return u8;
    }

    P.getSupportedExtensions = function getSupportedExtensions() {
      const list = o.getSupportedExtensions.call(this);
      if (!list) return list;
      for (const name of ['WEBGL_compressed_texture_s3tc', 'WEBGL_compressed_texture_s3tc_srgb',
        'EXT_texture_filter_anisotropic']) {
        if (list.indexOf(name) < 0) list.push(name);
      }
      return list;
    };

    P.getExtension = function getExtension(name) {
      if (o.isContextLost && o.isContextLost.call(this)) return o.getExtension.call(this, name);
      const n = String(name).toLowerCase().replace(/^(webkit_|moz_)/, '');
      const s = info(this);
      if (n === 'webgl_compressed_texture_s3tc' && !s.s3tc) {
        return s.exts.s3tc || (s.exts.s3tc = fakeExtension(S3TC_EXT_CONSTANTS));
      }
      if (n === 'webgl_compressed_texture_s3tc_srgb' && !s.s3tcSrgb) {
        return s.exts.s3tcSrgb || (s.exts.s3tcSrgb = fakeExtension(S3TC_SRGB_EXT_CONSTANTS));
      }
      if (n === 'ext_texture_filter_anisotropic' && !s.aniso) {
        return s.exts.aniso || (s.exts.aniso = fakeExtension(ANISO_EXT_CONSTANTS));
      }
      return o.getExtension.call(this, name);
    };

    P.getParameter = function getParameter(pname) {
      if (pname === MAX_TEXTURE_MAX_ANISOTROPY && !info(this).aniso) return 1;
      const v = o.getParameter.call(this, pname);
      if (pname === COMPRESSED_TEXTURE_FORMATS && v) {
        const s = info(this);
        const extra = [];
        if (!s.s3tc) extra.push(0x83F0, 0x83F1, 0x83F2, 0x83F3);
        if (!s.s3tcSrgb) extra.push(0x8C4C, 0x8C4D, 0x8C4E, 0x8C4F);
        if (extra.length) {
          const out = new Uint32Array(v.length + extra.length);
          out.set(v);
          out.set(extra, v.length);
          return out;
        }
      }
      return v;
    };

    // Anisotropic filtering: when the GPU lacks it the parameter becomes a harmless no-op.
    if (o.texParameterf) {
      P.texParameterf = function texParameterf(target, pname, param) {
        if (pname === TEXTURE_MAX_ANISOTROPY && !info(this).aniso) return;
        return o.texParameterf.call(this, target, pname, param);
      };
    }
    if (o.texParameteri) {
      P.texParameteri = function texParameteri(target, pname, param) {
        if (pname === TEXTURE_MAX_ANISOTROPY && !info(this).aniso) return;
        return o.texParameteri.call(this, target, pname, param);
      };
    }
    if (o.getTexParameter) {
      P.getTexParameter = function getTexParameter(target, pname) {
        if (pname === TEXTURE_MAX_ANISOTROPY && !info(this).aniso) return 1;
        return o.getTexParameter.call(this, target, pname);
      };
    }
    if (o.samplerParameterf) {
      P.samplerParameterf = function samplerParameterf(sampler, pname, param) {
        if (pname === TEXTURE_MAX_ANISOTROPY && !info(this).aniso) return;
        return o.samplerParameterf.call(this, sampler, pname, param);
      };
    }
    if (o.samplerParameteri) {
      P.samplerParameteri = function samplerParameteri(sampler, pname, param) {
        if (pname === TEXTURE_MAX_ANISOTROPY && !info(this).aniso) return;
        return o.samplerParameteri.call(this, sampler, pname, param);
      };
    }
    if (o.getSamplerParameter) {
      P.getSamplerParameter = function getSamplerParameter(sampler, pname) {
        if (pname === TEXTURE_MAX_ANISOTROPY && !info(this).aniso) return 1;
        return o.getSamplerParameter.call(this, sampler, pname);
      };
    }

    // ---- compressed uploads -> decoded RGBA8 uploads
    if (o.compressedTexImage2D && o.texImage2D) {
      P.compressedTexImage2D = function compressedTexImage2D(target, level, internalformat, width, height, border) {
        const fmt = S3TC_FORMATS.get(internalformat);
        if (!fmt || !emulated(this, fmt)) return o.compressedTexImage2D.apply(this, arguments);
        const src = compressedSource(this, arguments, 6);
        const rgba = decodeS3TC(src, width, height, 1, fmt);
        if (!rgba) return undefined;
        const gl = this;
        withPlainUnpack(gl, () => o.texImage2D.call(gl, target, level, internalFormatFor(fmt), width, height, 0,
          RGBA, UNSIGNED_BYTE, rgba));
        return undefined;
      };
    }
    if (o.compressedTexSubImage2D && o.texSubImage2D) {
      P.compressedTexSubImage2D = function compressedTexSubImage2D(target, level, xoffset, yoffset, width, height, format) {
        const fmt = S3TC_FORMATS.get(format);
        if (!fmt || !emulated(this, fmt)) return o.compressedTexSubImage2D.apply(this, arguments);
        const src = compressedSource(this, arguments, 7);
        const rgba = decodeS3TC(src, width, height, 1, fmt);
        if (!rgba) return undefined;
        const gl = this;
        withPlainUnpack(gl, () => o.texSubImage2D.call(gl, target, level, xoffset, yoffset, width, height,
          RGBA, UNSIGNED_BYTE, rgba));
        return undefined;
      };
    }
    if (o.texStorage2D) {
      P.texStorage2D = function texStorage2D(target, levels, internalformat, width, height) {
        const fmt = S3TC_FORMATS.get(internalformat);
        if (!fmt || !emulated(this, fmt)) return o.texStorage2D.apply(this, arguments);
        // Immutable storage is allocated as RGBA8; compressedTexSubImage2D is redirected above.
        return o.texStorage2D.call(this, target, levels, internalFormatFor(fmt), width, height);
      };
    }
    if (o.texStorage3D) {
      P.texStorage3D = function texStorage3D(target, levels, internalformat, width, height, depth) {
        const fmt = S3TC_FORMATS.get(internalformat);
        if (!fmt || !emulated(this, fmt)) return o.texStorage3D.apply(this, arguments);
        return o.texStorage3D.call(this, target, levels, internalFormatFor(fmt), width, height, depth);
      };
    }
    if (o.compressedTexImage3D && o.texImage3D) {
      P.compressedTexImage3D = function compressedTexImage3D(target, level, internalformat, width, height, depth, border) {
        const fmt = S3TC_FORMATS.get(internalformat);
        if (!fmt || !emulated(this, fmt)) return o.compressedTexImage3D.apply(this, arguments);
        const src = compressedSource(this, arguments, 7);
        const rgba = decodeS3TC(src, width, height, depth, fmt);
        if (!rgba) return undefined;
        const gl = this;
        withPlainUnpack(gl, () => o.texImage3D.call(gl, target, level, internalFormatFor(fmt), width, height, depth, 0,
          RGBA, UNSIGNED_BYTE, rgba));
        return undefined;
      };
    }
    if (o.compressedTexSubImage3D && o.texSubImage3D) {
      P.compressedTexSubImage3D = function compressedTexSubImage3D(target, level, xoffset, yoffset, zoffset,
        width, height, depth, format) {
        const fmt = S3TC_FORMATS.get(format);
        if (!fmt || !emulated(this, fmt)) return o.compressedTexSubImage3D.apply(this, arguments);
        const src = compressedSource(this, arguments, 9);
        const rgba = decodeS3TC(src, width, height, depth, fmt);
        if (!rgba) return undefined;
        const gl = this;
        withPlainUnpack(gl, () => o.texSubImage3D.call(gl, target, level, xoffset, yoffset, zoffset,
          width, height, depth, RGBA, UNSIGNED_BYTE, rgba));
        return undefined;
      };
    }

    Object.defineProperty(P, '__radiantInfo', { value: info, configurable: true, enumerable: false });
  }

  // Reports the first WebGL context of the frame to the app (shown in the drawer status panel).
  function installGLReporting() {
    let reported = false;
    const patch = (proto) => {
      if (!proto || typeof proto.getContext !== 'function') return;
      const orig = proto.getContext;
      proto.getContext = function getContext(type) {
        const ctx = orig.apply(this, arguments);
        if (ctx && !reported && typeof type === 'string' && /^(webgl2?|experimental-webgl)$/i.test(type)) {
          reported = true;
          setTimeout(() => {
            try {
              let renderer = '';
              try {
                const dbg = ctx.getExtension('WEBGL_debug_renderer_info');
                renderer = String(ctx.getParameter(dbg ? dbg.UNMASKED_RENDERER_WEBGL : 0x1F01) || '');
              } catch (e) { /* ignore */ }
              const P = Object.getPrototypeOf(ctx);
              const i = P.__radiantInfo ? P.__radiantInfo(ctx) : { s3tc: false };
              Status.s3tc = i.s3tc ? 'native' : 'emulated';
              Native.post({ t: 'gl', api: type.toLowerCase(), renderer: renderer.slice(0, 120), s3tc: Status.s3tc, sab: Status.sab });
            } catch (e) { /* ignore */ }
          }, 0);
        }
        return ctx;
      };
    };
    patch(W.HTMLCanvasElement && W.HTMLCanvasElement.prototype);
    patch(W.OffscreenCanvas && W.OffscreenCanvas.prototype);
  }

  // =====================================================================================
  // 3. Pointer Lock emulation
  // =====================================================================================

  function lockedElement() {
    const el = Lock.el;
    if (el && !el.isConnected) {
      setLock(null)();
      return null;
    }
    return el;
  }

  // Updates the lock and returns a function that fires pointerlockchange.
  function setLock(el) {
    const prev = Lock.el;
    if (prev === el) return function () {};
    Lock.el = el;
    try { W.__lockedElement = el; } catch (e) { /* ignore */ }
    const doc = (el && el.ownerDocument) || (prev && prev.ownerDocument) || document;
    Native.post({ t: 'lock', on: !!el });
    Input.onLockChanged();
    return function () {
      try { doc.dispatchEvent(new Event('pointerlockchange', { bubbles: true })); } catch (e) { /* ignore */ }
    };
  }

  function userExitLock() {
    if (!Lock.el) return;
    setLock(null)();
  }

  function installPointerLock() {
    const EP = Element.prototype;
    const DP = Document.prototype;

    EP.requestPointerLock = function requestPointerLock() {
      const el = this;
      return new Promise((resolve) => {
        setTimeout(() => {
          if (!el.isConnected) {
            try { (el.ownerDocument || document).dispatchEvent(new Event('pointerlockerror', { bubbles: true })); } catch (e) { /* ignore */ }
            resolve();
            return;
          }
          setLock(el)();
          resolve();
        }, 0);
      });
    };
    EP.webkitRequestPointerLock = EP.requestPointerLock;
    EP.mozRequestPointerLock = EP.requestPointerLock;

    DP.exitPointerLock = function exitPointerLock() {
      if (!Lock.el) return;
      const fire = setLock(null);
      setTimeout(fire, 0);
    };
    DP.webkitExitPointerLock = DP.exitPointerLock;
    DP.mozExitPointerLock = DP.exitPointerLock;

    const getter = function () {
      const el = lockedElement();
      if (!el) return null;
      if (this instanceof Document) return el.ownerDocument === this ? el : null;
      return (typeof el.getRootNode === 'function' && el.getRootNode() === this) ? el : null;
    };
    defineGetter(DP, 'pointerLockElement', getter);
    defineGetter(DP, 'webkitPointerLockElement', getter);
    defineGetter(DP, 'mozPointerLockElement', getter);
    if (W.ShadowRoot) defineGetter(W.ShadowRoot.prototype, 'pointerLockElement', getter);

    // Desktop Chrome: Escape releases pointer lock and is not delivered to the page.
    W.addEventListener('keydown', (e) => {
      if (e.isTrusted && e.key === 'Escape' && Lock.el) {
        e.stopImmediatePropagation();
        e.preventDefault();
        userExitLock();
      }
    }, true);
  }

  // =====================================================================================
  // 4. Fullscreen emulation
  // =====================================================================================

  function installFullscreen() {
    const EP = Element.prototype;
    const DP = Document.prototype;
    const ATTR = 'data-radiant-fullscreen';
    const FS = { el: null };

    function ensureStyle(doc) {
      if (doc.getElementById('__radiant_fullscreen_css')) return;
      const s = doc.createElement('style');
      s.id = '__radiant_fullscreen_css';
      s.textContent =
        '[' + ATTR + ']:not(html){position:fixed!important;inset:0!important;left:0!important;top:0!important;' +
        'width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;' +
        'min-width:0!important;min-height:0!important;margin:0!important;box-sizing:border-box!important;' +
        'transform:none!important;z-index:2147483647!important;object-fit:contain}' +
        'iframe[' + ATTR + ']{border:none!important;padding:0!important}' +
        '[' + ATTR + ']::backdrop{background:#000}' +
        'html[' + ATTR + '-active],html[' + ATTR + '-active]>body{overflow:hidden!important}';
      (doc.head || doc.documentElement).appendChild(s);
    }

    function fire(el) {
      setTimeout(() => {
        const target = el.isConnected ? el : el.ownerDocument;
        for (const type of ['fullscreenchange', 'webkitfullscreenchange']) {
          try { target.dispatchEvent(new Event(type, { bubbles: true, composed: true })); } catch (e) { /* ignore */ }
        }
        try { W.dispatchEvent(new Event('resize')); } catch (e) { /* ignore */ }
      }, 0);
    }

    function enter(el) {
      if (!el || !el.isConnected) return false;
      if (FS.el === el) return true;
      if (FS.el) leave(false);
      const doc = el.ownerDocument;
      FS.el = el;
      if (el !== doc.documentElement) {
        ensureStyle(doc);
        el.setAttribute(ATTR, '');
        // The top layer escapes ancestors with transform/filter/overflow, like real fullscreen.
        try {
          if (typeof el.showPopover === 'function' && !el.hasAttribute('popover')) {
            el.setAttribute('popover', 'manual');
            el.__radiantPopover = true;
            el.showPopover();
          }
        } catch (e) {
          if (el.__radiantPopover) { el.removeAttribute('popover'); delete el.__radiantPopover; }
        }
      }
      doc.documentElement.setAttribute(ATTR + '-active', '');
      if (!IS_TOP) {
        try { W.parent.postMessage({ __radiant: 'fs', on: true }, '*'); } catch (e) { /* ignore */ }
      }
      fire(el);
      return true;
    }

    function leave(notifyParent) {
      const el = FS.el;
      if (!el) return;
      FS.el = null;
      el.removeAttribute(ATTR);
      if (el.__radiantPopover) {
        try { el.hidePopover(); } catch (e) { /* ignore */ }
        el.removeAttribute('popover');
        delete el.__radiantPopover;
      }
      try { el.ownerDocument.documentElement.removeAttribute(ATTR + '-active'); } catch (e) { /* ignore */ }
      if (notifyParent && !IS_TOP) {
        try { W.parent.postMessage({ __radiant: 'fs', on: false }, '*'); } catch (e) { /* ignore */ }
      }
      fire(el);
    }

    // A child frame asked to be fullscreen: make its <iframe> element fill this document too.
    W.addEventListener('message', (e) => {
      const d = e.data;
      if (!d || typeof d !== 'object' || d.__radiant !== 'fs') return;
      e.stopImmediatePropagation();
      const frames = document.querySelectorAll('iframe,frame');
      for (const f of frames) {
        if (f.contentWindow === e.source) {
          if (d.on) enter(f);
          else if (FS.el === f) leave(true);
          break;
        }
      }
    }, true);

    EP.requestFullscreen = function requestFullscreen() {
      enter(this);
      return Promise.resolve();
    };
    EP.webkitRequestFullscreen = function webkitRequestFullscreen() { enter(this); };
    EP.webkitRequestFullScreen = EP.webkitRequestFullscreen;
    EP.mozRequestFullScreen = EP.webkitRequestFullscreen;

    DP.exitFullscreen = function exitFullscreen() {
      leave(true);
      return Promise.resolve();
    };
    DP.webkitExitFullscreen = function webkitExitFullscreen() { leave(true); };
    DP.webkitCancelFullScreen = DP.webkitExitFullscreen;
    DP.mozCancelFullScreen = DP.webkitExitFullscreen;

    const elementGetter = function () {
      const el = FS.el;
      if (!el) return null;
      if (this instanceof Document) return el.ownerDocument === this ? el : null;
      return null;
    };
    for (const p of ['fullscreenElement', 'webkitFullscreenElement', 'webkitCurrentFullScreenElement', 'mozFullScreenElement']) {
      defineGetter(DP, p, elementGetter);
    }
    for (const p of ['fullscreenEnabled', 'webkitFullscreenEnabled', 'mozFullScreenEnabled']) {
      defineGetter(DP, p, () => true);
    }
    for (const p of ['fullscreen', 'webkitIsFullScreen', 'mozFullScreen']) {
      defineGetter(DP, p, function () { return !!FS.el && FS.el.ownerDocument === this; });
    }
  }

  // =====================================================================================
  // 5. Audio autoplay unlock
  // =====================================================================================

  const AudioState = { contexts: [] };

  function resumeAudio() {
    for (let i = AudioState.contexts.length - 1; i >= 0; i--) {
      const ref = AudioState.contexts[i];
      const ctx = ref.deref ? ref.deref() : ref;
      if (!ctx || ctx.state === 'closed') {
        AudioState.contexts.splice(i, 1);
        continue;
      }
      if (ctx.state === 'suspended' && !ctx.__radiantUserSuspended) {
        try { ctx.resume().catch(() => {}); } catch (e) { /* ignore */ }
      }
    }
  }

  function installAudio() {
    for (const name of ['AudioContext', 'webkitAudioContext']) {
      const Orig = W[name];
      if (typeof Orig !== 'function' || Orig.__radiantWrapped) continue;
      const proto = Orig.prototype;
      const origSuspend = proto.suspend;
      const origResume = proto.resume;
      if (typeof origSuspend === 'function' && !origSuspend.__radiantWrapped) {
        proto.suspend = function suspend() {
          this.__radiantUserSuspended = true;
          return origSuspend.apply(this, arguments);
        };
        proto.suspend.__radiantWrapped = true;
      }
      if (typeof origResume === 'function' && !origResume.__radiantWrapped) {
        proto.resume = function resume() {
          this.__radiantUserSuspended = false;
          return origResume.apply(this, arguments);
        };
        proto.resume.__radiantWrapped = true;
      }
      const Wrapped = function AudioContext() {
        if (!new.target) throw new TypeError("Failed to construct 'AudioContext': Please use the 'new' operator");
        const ctx = Reflect.construct(Orig, arguments, new.target);
        AudioState.contexts.push(typeof WeakRef === 'function' ? new WeakRef(ctx) : ctx);
        return ctx;
      };
      Wrapped.prototype = proto;
      Object.setPrototypeOf(Wrapped, Orig);
      Object.defineProperty(Wrapped, 'name', { value: name });
      Object.defineProperty(Wrapped, '__radiantWrapped', { value: true });
      try {
        Object.defineProperty(proto, 'constructor', { value: Wrapped, writable: true, configurable: true });
      } catch (e) { /* ignore */ }
      try {
        Object.defineProperty(W, name, { value: Wrapped, writable: true, configurable: true, enumerable: false });
      } catch (e) {
        W[name] = Wrapped;
      }
    }
    const unlock = (e) => { if (e.isTrusted) resumeAudio(); };
    for (const t of ['pointerdown', 'keydown', 'touchend', 'mousedown']) {
      W.addEventListener(t, unlock, { capture: true, passive: true });
    }
  }

  // =====================================================================================
  // 6. SharedArrayBuffer probe
  // =====================================================================================

  function installSAB() {
    if (typeof W.SharedArrayBuffer === 'function') {
      Status.sab = W.crossOriginIsolated ? 'native (cross-origin isolated)' : 'native';
      return;
    }
    let Ctor = null;
    try {
      Ctor = new WebAssembly.Memory({ initial: 1, maximum: 1, shared: true }).buffer.constructor;
    } catch (e) {
      Ctor = null;
    }
    if (!Ctor) {
      Status.sab = 'unavailable (no shared WebAssembly memory)';
      return;
    }
    // Threads need shared memory to be cloneable into Workers. structuredClone runs the same
    // serializer check synchronously, so it tells us whether pthreads can actually work here.
    let transferable = false;
    try {
      structuredClone(new WebAssembly.Memory({ initial: 1, maximum: 1, shared: true }).buffer);
      transferable = true;
    } catch (e) {
      transferable = false;
    }
    if (transferable) {
      Object.defineProperty(W, 'SharedArrayBuffer', { value: Ctor, writable: true, configurable: true, enumerable: false });
      defineGetter(W, 'crossOriginIsolated', () => true);
      Status.sab = 'polyfilled (shared memory can be posted to workers)';
    } else {
      // Defining the global anyway would push libraries with a single-thread fallback onto a
      // threaded path that then fails, so it is left undefined.
      Status.sab = 'unavailable (WebView blocks posting shared memory to workers)';
    }
  }

  // A page that sends COOP+COEP (or Document-Isolation-Policy) wants SharedArrayBuffer threads.
  // Android WebView only grants them to app-allowlisted origins whose document carries
  // Document-Isolation-Policy, so the page's own headers are read and the app is asked to enable it.
  const nativeFetch = typeof W.fetch === 'function' ? W.fetch.bind(W) : null;
  function detectIsolationRequest() {
    if (W.crossOriginIsolated || !nativeFetch || !/^https?:$/.test(location.protocol)) return;
    const key = '__radiant_coi_checked';
    try {
      if (sessionStorage.getItem(key) === location.pathname) return;
      sessionStorage.setItem(key, location.pathname);
    } catch (e) { /* storage blocked: check anyway */ }
    nativeFetch(location.href, { method: 'HEAD', credentials: 'include', cache: 'no-store' }).then((r) => {
      const coop = (r.headers.get('cross-origin-opener-policy') || '').toLowerCase();
      const coep = (r.headers.get('cross-origin-embedder-policy') || '').toLowerCase();
      const dip = (r.headers.get('document-isolation-policy') || '').toLowerCase();
      if (/require-corp|credentialless/.test(coep) || /isolate/.test(dip)) {
        Native.post({ t: 'coi', top: IS_TOP, coop: coop, coep: coep, dip: dip });
      }
    }).catch(() => { /* HEAD unsupported or offline: nothing to report */ });
  }

  // =====================================================================================
  // 7. Display: devicePixelRatio scaling + desktop viewport
  // =====================================================================================

  function installDisplay() {
    let nativeGetter = null;
    for (const holder of [W, W.Window && W.Window.prototype]) {
      if (!holder) continue;
      const d = Object.getOwnPropertyDescriptor(holder, 'devicePixelRatio');
      if (d && typeof d.get === 'function') { nativeGetter = d.get; break; }
    }
    const fallback = Number(W.devicePixelRatio) || 1;
    const nativeDpr = () => {
      try { return nativeGetter ? Number(nativeGetter.call(W)) || fallback : fallback; } catch (e) { return fallback; }
    };
    const effective = () => {
      let base;
      if (Display.pageScale > 0) {
        base = Display.pageScale; // physical pixels per CSS pixel, reported by the app (density x page zoom)
      } else {
        base = nativeDpr();
        if (IS_TOP && W.visualViewport && W.visualViewport.scale > 0) base *= W.visualViewport.scale;
      }
      return Math.max(0.25, base * S.resScale);
    };
    try {
      Object.defineProperty(W, 'devicePixelRatio', {
        get: effective,
        set: function (v) {
          Object.defineProperty(W, 'devicePixelRatio', { value: v, writable: true, configurable: true, enumerable: true });
        },
        configurable: true,
        enumerable: true
      });
    } catch (e) { /* ignore */ }
    let last = effective();
    Display.notify = () => {
      const v = effective();
      if (Math.abs(v - last) < 1e-3) return;
      last = v;
      setTimeout(() => { try { W.dispatchEvent(new Event('resize')); } catch (e) { /* ignore */ } }, 0);
    };

    if (IS_TOP && S.desktopViewport > 0) installDesktopViewport(S.desktopViewport);
  }

  function installDesktopViewport(width) {
    const content = 'width=' + width;
    const fix = () => {
      const doc = document;
      const metas = doc.querySelectorAll('meta[name="viewport" i]');
      if (metas.length === 0) {
        if (doc.head) {
          const m = doc.createElement('meta');
          m.name = 'viewport';
          m.content = content;
          doc.head.insertBefore(m, doc.head.firstChild);
        }
        return;
      }
      for (const m of metas) {
        if (m.getAttribute('content') !== content) m.setAttribute('content', content);
      }
    };
    let mo = new MutationObserver(fix);
    mo.observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['content'] });
    // After load, only watch <head> so DOM-heavy game UIs do not trigger the observer every frame.
    W.addEventListener('load', () => {
      setTimeout(() => {
        fix();
        mo.disconnect();
        if (document.head) {
          mo = new MutationObserver(fix);
          mo.observe(document.head, { childList: true, subtree: true, attributes: true, attributeFilter: ['content'] });
        }
      }, 1500);
    });
  }

  function setPageScale(v) {
    v = Number(v);
    if (!(v > 0)) return;
    Display.pageScale = v;
    Display.notify();
  }

  // =====================================================================================
  // 8. Gamepad API override
  // =====================================================================================

  const GamepadAPI = { cache: null, cacheVersion: -1, actuators: [], exposed: new Set() };

  function installGamepadAPI() {
    const NP = Navigator.prototype;
    const GP = W.Gamepad ? W.Gamepad.prototype : Object.prototype;
    const GPB = W.GamepadButton ? W.GamepadButton.prototype : Object.prototype;
    const HAP = W.GamepadHapticActuator ? W.GamepadHapticActuator.prototype : Object.prototype;

    function actuator(index) {
      if (GamepadAPI.actuators[index]) return GamepadAPI.actuators[index];
      const a = Object.create(HAP);
      Object.defineProperties(a, {
        type: { value: 'dual-rumble', enumerable: true },
        effects: { value: Object.freeze(['dual-rumble']), enumerable: true },
        playEffect: {
          value: function playEffect(type, params) {
            const p = params || {};
            Native.post({ t: 'rumble', i: index, ms: Math.max(0, Math.min(5000, Number(p.duration) || 0)),
              s: Number(p.strongMagnitude) || 0, w: Number(p.weakMagnitude) || 0 });
            return Promise.resolve('complete');
          }
        },
        reset: {
          value: function reset() {
            Native.post({ t: 'rumble', i: index, ms: 0, s: 0, w: 0 });
            return Promise.resolve('complete');
          }
        }
      });
      GamepadAPI.actuators[index] = a;
      return a;
    }

    function makeButton(v, analog) {
      const b = Object.create(GPB);
      const pressed = analog ? v > 0.12 : v > 0.5;
      Object.defineProperties(b, {
        pressed: { value: pressed, enumerable: true },
        touched: { value: pressed || v > 0.01, enumerable: true },
        value: { value: v, enumerable: true }
      });
      return b;
    }

    function makePad(p) {
      const g = Object.create(GP);
      const buttons = [];
      for (let i = 0; i < 17; i++) buttons.push(makeButton(p.b[i], i === 6 || i === 7));
      Object.defineProperties(g, {
        id: { value: 'Xbox 360 Controller (XInput STANDARD GAMEPAD)', enumerable: true },
        index: { value: p.i, enumerable: true },
        connected: { value: true, enumerable: true },
        timestamp: { value: Input.padTime, enumerable: true },
        mapping: { value: 'standard', enumerable: true },
        axes: { value: Object.freeze([p.a[0], p.a[1], p.a[2], p.a[3]]), enumerable: true },
        buttons: { value: Object.freeze(buttons), enumerable: true },
        vibrationActuator: { value: actuator(p.i), enumerable: true },
        hapticActuators: { value: Object.freeze([]), enumerable: true }
      });
      return g;
    }

    function build() {
      const out = [null, null, null, null];
      if (S.mode === 'kbm') return out; // keyboard/mouse only: the controller stays invisible to the page
      for (const p of Input.pads) {
        if (p.i >= 0 && p.i < 4) out[p.i] = makePad(p);
      }
      return out;
    }

    NP.getGamepads = function getGamepads() {
      if (GamepadAPI.cacheVersion !== Input.padVersion || !GamepadAPI.cache) {
        GamepadAPI.cache = build();
        GamepadAPI.cacheVersion = Input.padVersion;
      }
      return GamepadAPI.cache.slice();
    };
    if (NP.webkitGetGamepads) NP.webkitGetGamepads = NP.getGamepads;
  }

  // Fires gamepadconnected / gamepaddisconnected so engines that wait for them see the pad.
  function syncGamepadEvents() {
    const want = new Set();
    if (S.mode !== 'kbm') {
      for (const p of Input.pads) if (p.i >= 0 && p.i < 4) want.add(p.i);
    }
    const pads = (want.size || GamepadAPI.exposed.size) ? navigator.getGamepads() : [];
    for (const i of Array.from(GamepadAPI.exposed)) {
      if (!want.has(i)) {
        GamepadAPI.exposed.delete(i);
        firePadEvent('gamepaddisconnected', i, null);
      }
    }
    for (const i of want) {
      if (!GamepadAPI.exposed.has(i)) {
        GamepadAPI.exposed.add(i);
        firePadEvent('gamepadconnected', i, pads[i]);
      }
    }
  }

  function firePadEvent(type, index, pad) {
    try {
      let gp = pad;
      if (!gp) {
        gp = Object.create(W.Gamepad ? W.Gamepad.prototype : Object.prototype);
        Object.defineProperties(gp, {
          id: { value: 'Xbox 360 Controller (XInput STANDARD GAMEPAD)', enumerable: true },
          index: { value: index, enumerable: true },
          connected: { value: false, enumerable: true },
          mapping: { value: 'standard', enumerable: true },
          timestamp: { value: now(), enumerable: true },
          axes: { value: Object.freeze([0, 0, 0, 0]), enumerable: true },
          buttons: { value: Object.freeze([]), enumerable: true }
        });
      }
      const ev = new Event(type);
      Object.defineProperty(ev, 'gamepad', { value: gp, enumerable: true });
      W.dispatchEvent(ev);
    } catch (e) { /* ignore */ }
  }

  // =====================================================================================
  // 9. Input engine
  // =====================================================================================

  const KEYMETA = (function () {
    const m = Object.create(null);
    const add = (code, key, keyCode, shifted, loc) => {
      m[code] = { key: key, keyCode: keyCode, shifted: shifted || key, loc: loc || 0 };
    };
    for (let i = 0; i < 26; i++) {
      const ch = String.fromCharCode(97 + i);
      add('Key' + ch.toUpperCase(), ch, 65 + i, ch.toUpperCase());
    }
    const digitShift = ')!@#$%^&*(';
    for (let i = 0; i < 10; i++) add('Digit' + i, String(i), 48 + i, digitShift[i]);
    for (let i = 1; i <= 12; i++) add('F' + i, 'F' + i, 111 + i);
    add('Space', ' ', 32);
    add('Enter', 'Enter', 13);
    add('Escape', 'Escape', 27);
    add('Tab', 'Tab', 9);
    add('Backspace', 'Backspace', 8);
    add('CapsLock', 'CapsLock', 20);
    add('ShiftLeft', 'Shift', 16, 'Shift', 1);
    add('ShiftRight', 'Shift', 16, 'Shift', 2);
    add('ControlLeft', 'Control', 17, 'Control', 1);
    add('ControlRight', 'Control', 17, 'Control', 2);
    add('AltLeft', 'Alt', 18, 'Alt', 1);
    add('AltRight', 'Alt', 18, 'Alt', 2);
    add('ArrowUp', 'ArrowUp', 38);
    add('ArrowDown', 'ArrowDown', 40);
    add('ArrowLeft', 'ArrowLeft', 37);
    add('ArrowRight', 'ArrowRight', 39);
    add('Insert', 'Insert', 45);
    add('Delete', 'Delete', 46);
    add('Home', 'Home', 36);
    add('End', 'End', 35);
    add('PageUp', 'PageUp', 33);
    add('PageDown', 'PageDown', 34);
    add('Backquote', '`', 192, '~');
    add('Minus', '-', 189, '_');
    add('Equal', '=', 187, '+');
    add('BracketLeft', '[', 219, '{');
    add('BracketRight', ']', 221, '}');
    add('Backslash', '\\', 220, '|');
    add('Semicolon', ';', 186, ':');
    add('Quote', "'", 222, '"');
    add('Comma', ',', 188, '<');
    add('Period', '.', 190, '>');
    add('Slash', '/', 191, '?');
    for (let i = 0; i < 10; i++) add('Numpad' + i, String(i), 96 + i, String(i), 3);
    add('NumpadEnter', 'Enter', 13, 'Enter', 3);
    add('NumpadAdd', '+', 107, '+', 3);
    add('NumpadSubtract', '-', 109, '-', 3);
    add('NumpadMultiply', '*', 106, '*', 3);
    add('NumpadDivide', '/', 111, '/', 3);
    add('NumpadDecimal', '.', 110, '.', 3);
    return m;
  })();

  const LOOK_BASE_SPEED = 1100;   // virtual mouse pixels per second at full right-stick deflection
  const TAP_HOLD_MS = 60;         // tapped keys stay down long enough for per-frame polling engines
  const MENU_HOLD_MS = 450;       // Start held this long sends Enter instead of Escape

  const Input = {
    myId: 0,
    lockOwner: 0,
    pads: [],
    padVersion: 0,
    padTime: 0,
    suppress: 0,
    cursor: false,
    mergedButtons: new Float32Array(17),
    mergedAxes: new Float32Array(4),
    prevDown: new Uint8Array(17),
    latched: new Uint8Array(17),
    active: new Array(17).fill(null),
    held: new Map(),          // code -> 'pad' | 'direct'
    pendingUps: new Map(),    // code -> timeout id
    mouseButtons: 0,
    padMouse: 0,              // mouse buttons pressed by the controller mapping
    physButtons: 0,           // buttons reported by a captured physical mouse
    loopOn: false,
    lastTs: 0,
    look: { x: 0, y: 0 },
    raw: { x: 0, y: 0 },
    rem: { x: 0, y: 0 },
    edgeSince: 0,
    menu: { downAt: 0, fired: false },
    weapon: 1,
    vpos: null,
    vposTarget: null,
    tapUntil: 0,
    wasOwner: false,
    canvas: { el: null, at: 0 },
    onLockChanged: function () {
      Input.vpos = null;
      schedule();
    }
  };

  function deepActiveElement(doc) {
    let a = doc.activeElement;
    while (a && a.shadowRoot && a.shadowRoot.activeElement) a = a.shadowRoot.activeElement;
    return a;
  }

  // The frame that receives synthetic input: the one holding the (emulated) pointer lock, otherwise
  // the innermost focused frame, otherwise the top frame.
  function isOwner() {
    if (Input.lockOwner) return Input.lockOwner === Input.myId;
    let focused = false;
    try { focused = document.hasFocus(); } catch (e) { focused = false; }
    if (!focused) return IS_TOP;
    const a = deepActiveElement(document);
    return !(a && (a.tagName === 'IFRAME' || a.tagName === 'FRAME'));
  }

  function mainCanvas() {
    const c = Input.canvas;
    const t = now();
    if (c.el && c.el.isConnected && t - c.at < 1000) return c.el;
    let best = null;
    let bestArea = 0;
    const list = document.getElementsByTagName('canvas');
    for (let i = 0; i < list.length; i++) {
      const r = list[i].getBoundingClientRect();
      const area = Math.max(0, r.width) * Math.max(0, r.height);
      if (area > bestArea) { bestArea = area; best = list[i]; }
    }
    c.el = best;
    c.at = t;
    return best;
  }

  function keyTarget() {
    const l = lockedElement();
    if (l) return l;
    const a = deepActiveElement(document);
    if (a && a !== document.body && a !== document.documentElement && a.tagName !== 'IFRAME' && a.tagName !== 'FRAME') return a;
    return mainCanvas() || document.body || document.documentElement || document;
  }

  function mouseTarget() {
    return lockedElement() || mainCanvas() || document.body || document.documentElement || document;
  }

  function mousePoint(tgt) {
    if (!Input.vpos || Input.vposTarget !== tgt) {
      let x = W.innerWidth / 2;
      let y = W.innerHeight / 2;
      if (tgt && typeof tgt.getBoundingClientRect === 'function') {
        const r = tgt.getBoundingClientRect();
        if (r.width > 0 && r.height > 0) { x = r.left + r.width / 2; y = r.top + r.height / 2; }
      }
      Input.vpos = { x: x, y: y };
      Input.vposTarget = tgt;
    }
    return Input.vpos;
  }

  function modifiers() {
    const h = Input.held;
    return {
      shiftKey: h.has('ShiftLeft') || h.has('ShiftRight'),
      ctrlKey: h.has('ControlLeft') || h.has('ControlRight'),
      altKey: h.has('AltLeft') || h.has('AltRight'),
      metaKey: false
    };
  }

  function fireKey(type, code) {
    const m = KEYMETA[code];
    if (!m) return;
    const md = modifiers();
    const key = md.shiftKey ? m.shifted : m.key;
    const press = type === 'keypress';
    const kc = press ? (key === 'Enter' ? 13 : key.charCodeAt(0)) : m.keyCode;
    const cc = press ? kc : 0;
    let ev;
    try {
      ev = new KeyboardEvent(type, {
        key: key, code: code, location: m.loc, bubbles: true, cancelable: true, composed: true, view: W,
        repeat: false, shiftKey: md.shiftKey, ctrlKey: md.ctrlKey, altKey: md.altKey, metaKey: false,
        keyCode: kc, which: kc, charCode: cc
      });
    } catch (e) {
      return;
    }
    try {
      Object.defineProperty(ev, 'keyCode', { get: () => kc });
      Object.defineProperty(ev, 'which', { get: () => kc });
      Object.defineProperty(ev, 'charCode', { get: () => cc });
    } catch (e) { /* ignore */ }
    try { keyTarget().dispatchEvent(ev); } catch (e) { /* ignore */ }
  }

  function keyDown(code, source) {
    if (!KEYMETA[code]) return;
    const pending = Input.pendingUps.get(code);
    if (pending) {
      clearTimeout(pending);
      Input.pendingUps.delete(code);
      Input.held.delete(code);
      fireKey('keyup', code);
    }
    if (Input.held.has(code)) return;
    Input.held.set(code, source || 'pad');
    fireKey('keydown', code);
    const m = KEYMETA[code];
    const md = modifiers();
    if (!md.ctrlKey && !md.altKey && (m.key.length === 1 || m.key === 'Enter')) fireKey('keypress', code);
  }

  function keyUp(code) {
    const pending = Input.pendingUps.get(code);
    if (pending) {
      clearTimeout(pending);
      Input.pendingUps.delete(code);
    }
    if (!Input.held.has(code)) return;
    Input.held.delete(code);
    fireKey('keyup', code);
  }

  function tapKey(code) {
    keyDown(code, 'pad');
    const id = setTimeout(() => {
      Input.pendingUps.delete(code);
      keyUp(code);
    }, TAP_HOLD_MS);
    Input.pendingUps.set(code, id);
  }

  function mouseInit(tgt, extra) {
    const pt = mousePoint(tgt);
    const md = modifiers();
    return Object.assign({
      bubbles: true, cancelable: true, composed: true, view: W,
      clientX: pt.x, clientY: pt.y, screenX: pt.x, screenY: pt.y,
      buttons: Input.mouseButtons,
      shiftKey: md.shiftKey, ctrlKey: md.ctrlKey, altKey: md.altKey, metaKey: false
    }, extra);
  }

  function firePointer(tgt, type, init) {
    try {
      tgt.dispatchEvent(new PointerEvent(type, Object.assign({
        pointerId: 1, pointerType: 'mouse', isPrimary: true, width: 1, height: 1,
        pressure: Input.mouseButtons ? 0.5 : 0
      }, init)));
    } catch (e) { /* ignore */ }
  }

  function fireMouse(tgt, type, init) {
    try { tgt.dispatchEvent(new MouseEvent(type, init)); } catch (e) { /* ignore */ }
  }

  function buttonBit(btn) { return btn === 0 ? 1 : (btn === 2 ? 2 : 4); }

  // silent = release without click/contextmenu (used when input is taken away mid-press)
  function mouseButton(btn, down, silent) {
    const bit = buttonBit(btn);
    const before = Input.mouseButtons;
    if (down === !!(before & bit)) return;
    Input.mouseButtons = down ? (before | bit) : (before & ~bit);
    const tgt = mouseTarget();
    if (down && btn === 0 && tgt && tgt !== document.body && typeof tgt.focus === 'function') {
      try { tgt.focus({ preventScroll: true }); } catch (e) { /* ignore */ }
    }
    const init = mouseInit(tgt, { button: btn });
    const ptype = down ? (before === 0 ? 'pointerdown' : 'pointermove')
      : (Input.mouseButtons === 0 ? 'pointerup' : 'pointermove');
    firePointer(tgt, ptype, init);
    fireMouse(tgt, down ? 'mousedown' : 'mouseup', init);
    if (!down && !silent) {
      if (btn === 0) {
        fireMouse(tgt, 'click', Object.assign({}, init, { detail: 1 }));
      } else {
        fireMouse(tgt, 'auxclick', Object.assign({}, init, { detail: 1 }));
        if (btn === 2) fireMouse(tgt, 'contextmenu', init);
      }
    }
  }

  function mouseMove(dx, dy) {
    if (!dx && !dy) return;
    const tgt = mouseTarget();
    const pt = mousePoint(tgt);
    if (!lockedElement()) {
      pt.x = Math.min(Math.max(pt.x + dx, 0), Math.max(0, W.innerWidth - 1));
      pt.y = Math.min(Math.max(pt.y + dy, 0), Math.max(0, W.innerHeight - 1));
    }
    const init = mouseInit(tgt, { movementX: dx, movementY: dy, button: 0 });
    firePointer(tgt, 'pointermove', Object.assign({}, init, { button: -1 }));
    fireMouse(tgt, 'mousemove', init);
  }

  function wheel(deltaY) {
    const tgt = mouseTarget();
    const init = mouseInit(tgt, { deltaX: 0, deltaY: deltaY, deltaZ: 0, deltaMode: 0, button: 0 });
    try { tgt.dispatchEvent(new WheelEvent('wheel', init)); } catch (e) { /* ignore */ }
  }

  // which: 'pad' releases only what the controller mapping pressed; 'all' releases everything.
  function releaseAll(which) {
    const all = which === 'all';
    for (const [code, id] of Array.from(Input.pendingUps)) {
      clearTimeout(id);
      Input.pendingUps.delete(code);
    }
    for (const [code, src] of Array.from(Input.held).reverse()) {
      if (all || src === 'pad') keyUp(code);
    }
    for (const btn of [0, 2, 1]) {
      const bit = buttonBit(btn);
      if ((Input.mouseButtons & bit) && (all || (Input.padMouse & bit))) mouseButton(btn, false, true);
    }
    Input.padMouse = 0;
    if (all) Input.physButtons = 0;
    for (let i = 0; i < 17; i++) {
      Input.active[i] = null;
      Input.latched[i] = Input.prevDown[i] ? 1 : Input.latched[i];
      Input.prevDown[i] = 0;
    }
    Input.menu.downAt = 0;
    Input.menu.fired = false;
    Input.look.x = Input.look.y = 0;
    Input.rem.x = Input.rem.y = 0;
    Input.edgeSince = 0;
  }

  function tokensFor(i) {
    if (i >= 12 && i <= 15 && S.dpadArrows) return [['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'][i - 12]];
    const b = S.binds[i] != null ? S.binds[i] : DEFAULT_BINDS[i];
    return String(b || 'None').split('+').map((s) => s.trim()).filter(Boolean);
  }

  function press(i) {
    const toks = tokensFor(i);
    Input.active[i] = toks;
    for (const t of toks) {
      switch (t) {
        case 'None': break;
        case 'Mouse0': Input.padMouse |= 1; mouseButton(0, true); break;
        case 'Mouse1': Input.padMouse |= 4; mouseButton(1, true); break;
        case 'Mouse2': Input.padMouse |= 2; mouseButton(2, true); break;
        case 'WheelUp': wheel(-100); break;
        case 'WheelDown': wheel(100); break;
        case 'Toggle12':
          Input.weapon = Input.weapon === 1 ? 2 : 1;
          tapKey('Digit' + Input.weapon);
          break;
        case 'Menu':
          Input.menu.downAt = now();
          Input.menu.fired = false;
          break;
        default: keyDown(t, 'pad');
      }
    }
  }

  function release(i, cancelled) {
    const toks = Input.active[i] || [];
    Input.active[i] = null;
    for (const t of toks) {
      switch (t) {
        case 'None': case 'WheelUp': case 'WheelDown': case 'Toggle12': break;
        case 'Mouse0': Input.padMouse &= ~1; mouseButton(0, false, cancelled); break;
        case 'Mouse1': Input.padMouse &= ~4; mouseButton(1, false, cancelled); break;
        case 'Mouse2': Input.padMouse &= ~2; mouseButton(2, false, cancelled); break;
        case 'Menu':
          if (!cancelled && Input.menu.downAt && !Input.menu.fired) {
            if (lockedElement()) userExitLock(); // desktop behaviour: Esc releases the mouse first
            else tapKey('Escape');
          }
          Input.menu.downAt = 0;
          Input.menu.fired = false;
          break;
        default: keyUp(t);
      }
    }
  }

  function setStickKey(code, down) {
    if (down) {
      if (!Input.held.has(code)) keyDown(code, 'pad');
    } else if (Input.held.get(code) === 'pad') {
      let boundElsewhere = false;
      for (let i = 0; i < 17; i++) {
        if (Input.active[i] && Input.active[i].indexOf(code) >= 0) { boundElsewhere = true; break; }
      }
      if (!boundElsewhere) keyUp(code);
    }
  }

  function kbmActive() {
    return S.mode !== 'pad' && !Input.cursor;
  }

  function schedule() {
    if (Input.loopOn) return;
    Input.loopOn = true;
    requestAnimationFrame(tick);
  }

  function tick(ts) {
    Input.loopOn = false;
    if (!isOwner()) {
      if (Input.wasOwner || Input.held.size || Input.mouseButtons) releaseAll('all');
      Input.wasOwner = false;
      Input.lastTs = 0;
      return;
    }
    Input.wasOwner = true;
    const dt = Input.lastTs ? Math.min(0.05, Math.max(0, (ts - Input.lastTs) / 1000)) : 1 / 60;
    Input.lastTs = ts;

    // Raw physical-mouse movement (captured pointer) is delivered in every mode.
    let mx = Input.raw.x;
    let my = Input.raw.y;
    Input.raw.x = Input.raw.y = 0;

    let busy = false;
    if (kbmActive()) {
      const b = Input.mergedButtons;
      const a = Input.mergedAxes;
      let anyDown = false;

      for (let i = 0; i < 17; i++) {
        const sup = (Input.suppress & (1 << i)) !== 0;
        const down = !sup && b[i] > ((i === 6 || i === 7) ? 0.3 : 0.5);
        if (Input.latched[i]) {
          if (!down) Input.latched[i] = 0;
          continue;
        }
        if (down && !Input.prevDown[i]) {
          Input.prevDown[i] = 1;
          press(i);
        } else if (!down && Input.prevDown[i]) {
          Input.prevDown[i] = 0;
          release(i, sup);
        }
        if (down) anyDown = true;
      }

      // Left stick -> WASD (8-way)
      const lx = a[0];
      const ly = a[1];
      const moveT = Math.max(0.3, S.deadzone + 0.1);
      setStickKey('KeyW', ly < -moveT);
      setStickKey('KeyS', ly > moveT);
      setStickKey('KeyA', lx < -moveT);
      setStickKey('KeyD', lx > moveT);

      // Right stick -> relative mouse with response curve and edge acceleration
      const rx = a[2];
      const ry = a[3];
      const mag = Math.hypot(rx, ry);
      const dz = S.deadzone;
      if (mag > dz) {
        const n = Math.min(1, (mag - dz) / Math.max(0.05, 1 - dz));
        let speed = Math.pow(n, 1.7) * LOOK_BASE_SPEED * S.sens;
        if (n > 0.92) {
          if (!Input.edgeSince) Input.edgeSince = ts;
          speed *= 1 + 0.6 * Math.min(1, (ts - Input.edgeSince) / 450);
        } else {
          Input.edgeSince = 0;
        }
        mx += (rx / mag) * speed * dt;
        my += (ry / mag) * speed * dt;
      } else {
        Input.edgeSince = 0;
      }

      // Touch look deltas
      mx += Input.look.x;
      my += Input.look.y;
      Input.look.x = Input.look.y = 0;

      // Start held -> Enter
      if (Input.menu.downAt && !Input.menu.fired && now() - Input.menu.downAt >= MENU_HOLD_MS) {
        Input.menu.fired = true;
        tapKey('Enter');
      }

      busy = anyDown || mag > dz || Math.hypot(lx, ly) > dz || Input.held.size > 0 || Input.mouseButtons !== 0 ||
        Input.menu.downAt !== 0;
    } else if (Input.held.size || Input.padMouse) {
      releaseAll('pad');
    }

    Input.rem.x += mx;
    Input.rem.y += my;
    const ix = Math.trunc(Input.rem.x);
    const iy = Math.trunc(Input.rem.y);
    Input.rem.x -= ix;
    Input.rem.y -= iy;
    if (ix || iy) mouseMove(ix, iy);

    if (busy || Input.pendingUps.size) schedule();
    else Input.lastTs = 0;
  }

  function onPadMessage(m) {
    const list = Array.isArray(m.p) ? m.p : [];
    const pads = [];
    for (const p of list) {
      if (!p || typeof p !== 'object') continue;
      const b = new Array(17);
      const a = new Array(4);
      for (let i = 0; i < 17; i++) b[i] = Math.max(0, Math.min(1, (Number(p.b && p.b[i]) || 0) / 1000));
      for (let i = 0; i < 4; i++) a[i] = Math.max(-1, Math.min(1, (Number(p.a && p.a[i]) || 0) / 1000));
      pads.push({ i: p.i | 0, n: String(p.n || ''), b: b, a: a });
    }
    Input.pads = pads;
    Input.padVersion++;
    Input.padTime = now();
    Input.suppress = m.s | 0;
    const wasCursor = Input.cursor;
    Input.cursor = !!m.c;

    const mb = Input.mergedButtons;
    const ma = Input.mergedAxes;
    mb.fill(0);
    ma.fill(0);
    let lMag = 0;
    let rMag = 0;
    let anyPressed = false;
    for (const p of pads) {
      for (let i = 0; i < 17; i++) {
        if (p.b[i] > mb[i]) mb[i] = p.b[i];
        if (p.b[i] > 0.5) anyPressed = true;
      }
      const l = Math.hypot(p.a[0], p.a[1]);
      if (l > lMag) { lMag = l; ma[0] = p.a[0]; ma[1] = p.a[1]; }
      const r = Math.hypot(p.a[2], p.a[3]);
      if (r > rMag) { rMag = r; ma[2] = p.a[2]; ma[3] = p.a[3]; }
    }
    if (anyPressed) resumeAudio();
    if (Input.cursor && !wasCursor) releaseAll('pad');
    syncGamepadEvents();
    schedule();
  }

  function onPhysicalMouse(m) {
    Input.raw.x += Number(m.dx) || 0;
    Input.raw.y += Number(m.dy) || 0;
    const b = (m.b | 0) & 7;
    const changed = b ^ Input.physButtons;
    Input.physButtons = b;
    for (const btn of [0, 2, 1]) {
      const bit = buttonBit(btn);
      if (changed & bit) mouseButton(btn, !!(b & bit));
    }
    const w = Number(m.w) || 0;
    if (w) wheel(w);
    schedule();
  }

  function installInput() {
    W.addEventListener('blur', () => releaseAll('all'));
    document.addEventListener('visibilitychange', () => { if (document.hidden) releaseAll('all'); });

    // While the app injects a virtual-cursor tap (a real touch event, so it carries user activation
    // and reaches cross-origin iframes), hide its touch nature: touch events are swallowed and
    // touch-type pointer events are re-dispatched as mouse-type pointer events. The trusted
    // compatibility mousedown/mouseup/click that Chromium generates are left untouched.
    const blockTouch = (e) => {
      if (e.isTrusted && now() < Input.tapUntil) e.stopImmediatePropagation();
    };
    for (const t of ['touchstart', 'touchmove', 'touchend', 'touchcancel']) {
      W.addEventListener(t, blockTouch, { capture: true, passive: true });
    }
    const remap = { pointerdown: true, pointermove: true, pointerup: true };
    const onPointer = (e) => {
      if (!e.isTrusted || e.pointerType !== 'touch' || now() >= Input.tapUntil) return;
      e.stopImmediatePropagation();
      if (!remap[e.type]) return;
      try {
        e.target.dispatchEvent(new PointerEvent(e.type, {
          bubbles: true, cancelable: true, composed: true, view: W,
          clientX: e.clientX, clientY: e.clientY, screenX: e.screenX, screenY: e.screenY,
          pointerId: 1, pointerType: 'mouse', isPrimary: true, width: 1, height: 1,
          button: e.type === 'pointermove' ? -1 : 0,
          buttons: e.type === 'pointerup' ? 0 : 1,
          pressure: e.type === 'pointerup' ? 0 : 0.5
        }));
      } catch (err) { /* ignore */ }
    };
    for (const t of ['pointerdown', 'pointermove', 'pointerup', 'pointercancel', 'pointerover', 'pointerout',
      'pointerenter', 'pointerleave', 'gotpointercapture', 'lostpointercapture']) {
      W.addEventListener(t, onPointer, true);
    }
  }

  // =====================================================================================
  // 10. Native bridge
  // =====================================================================================

  function onNativeMessage(raw) {
    let m;
    try { m = typeof raw === 'string' ? JSON.parse(raw) : raw; } catch (e) { return; }
    if (!m || typeof m !== 'object') return;
    switch (m.t) {
      case 'hello':
        Input.myId = m.id | 0;
        Input.lockOwner = m.lock | 0;
        if (m.settings) onSettings(m.settings);
        if (m.cursor != null) Input.cursor = !!m.cursor;
        if (m.scale) setPageScale(m.scale);
        if (IS_TOP) Native.post({ t: 'status', sab: Status.sab });
        schedule();
        break;
      case 'cfg':
        onSettings(m.settings);
        break;
      case 'pad':
        onPadMessage(m);
        break;
      case 'look':
        if (isOwner()) {
          Input.look.x += (Number(m.dx) || 0) * S.sens;
          Input.look.y += (Number(m.dy) || 0) * S.sens;
          schedule();
        }
        break;
      case 'mouse':
        if (isOwner()) onPhysicalMouse(m);
        break;
      case 'key':
        if (isOwner() && KEYMETA[m.code]) {
          if (m.down) keyDown(m.code, 'direct');
          else keyUp(m.code);
        }
        break;
      case 'tap':
        Input.tapUntil = now() + Math.max(0, Math.min(60000, Number(m.ms) || 0));
        break;
      case 'lockOwner':
        Input.lockOwner = m.id | 0;
        schedule();
        break;
      case 'unlock':
        if (Lock.el) userExitLock();
        break;
      case 'scale':
        setPageScale(m.v);
        break;
      default:
        break;
    }
  }

  function onSettings(settings) {
    const prevMode = S.mode;
    const prevScale = S.resScale;
    applySettings(settings);
    if (prevMode !== S.mode) {
      Input.padVersion++;
      releaseAll('pad');
      syncGamepadEvents();
    }
    if (prevScale !== S.resScale) Display.notify();
    schedule();
  }

  function connectNative() {
    const name = (typeof BOOT.bridge === 'string' && BOOT.bridge) || 'RadiantNative';
    let tries = 0;
    const attempt = () => {
      const obj = W[name];
      if (obj && typeof obj.postMessage === 'function') {
        Native.obj = obj;
        const handler = (e) => onNativeMessage(e && e.data);
        if (typeof obj.addEventListener === 'function') obj.addEventListener('message', handler);
        else obj.onmessage = handler;
        const hello = () => Native.post({ t: 'hello', top: IS_TOP, href: String(location.href).slice(0, 300) });
        hello();
        const queued = Native.queue.splice(0, Native.queue.length);
        for (const s of queued) {
          try { obj.postMessage(s); } catch (e) { /* ignore */ }
        }
        W.addEventListener('pagehide', () => Native.post({ t: 'bye' }));
        W.addEventListener('pageshow', (e) => { if (e.persisted) hello(); });
        return;
      }
      if (++tries < 60) setTimeout(attempt, 50);
    };
    attempt();
  }

  // =====================================================================================
  // Install
  // =====================================================================================

  guard('desktop environment', installDesktopEnv);
  guard('webgl', installWebGL);
  guard('pointer lock', installPointerLock);
  guard('fullscreen', installFullscreen);
  guard('audio', installAudio);
  guard('shared array buffer', installSAB);
  guard('display', installDisplay);
  guard('gamepad api', installGamepadAPI);
  guard('input', installInput);
  guard('native bridge', connectNative);
  guard('isolation probe', () => setTimeout(detectIsolationRequest, 0));

  if (BOOT.exposeInternals) {
    W.__radiantInternals = {
      S: S, Input: Input, Lock: Lock, Status: Status, Display: Display, Native: Native,
      onNativeMessage: onNativeMessage, decodeS3TC: decodeS3TC, S3TC_FORMATS: S3TC_FORMATS,
      isOwner: isOwner, IDENTITY: IDENTITY
    };
  }
})();
