importScripts('helper.js');
self.onmessage = () => {
  const out = { helper: self.__helper, href: self.location.href, ua: navigator.userAgent, shim: !!self.__radiantShimActive };
  try {
    const c = new OffscreenCanvas(64, 64);
    const gl = c.getContext('webgl2') || c.getContext('webgl');
    out.gl = !!gl;
    out.s3tc = !!(gl && gl.getExtension('WEBGL_compressed_texture_s3tc'));
    out.listed = !!(gl && gl.getSupportedExtensions().indexOf('WEBGL_compressed_texture_s3tc') >= 0);
  } catch (e) { out.error = String(e); }
  self.postMessage(out);
};
