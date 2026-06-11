/*
 * Polyfills for running the browser/WKWebView manga parsers inside a bare
 * GraalVM "js" Context, which provides only core ECMAScript — no WHATWG URL,
 * URLSearchParams, atob/btoa, or TextEncoder/TextDecoder.
 *
 * This file is evaluated BEFORE parsers.bundle.js on the JVM/desktop path only.
 * The web (browser) and iOS (WKWebView) paths already have these natively, so
 * the polyfills live here (host side) rather than in the shared parser JS.
 *
 * Each is guarded by a typeof check so a future GraalVM that ships these
 * natively is never clobbered.
 */
(function (g) {
  'use strict';

  // ---- URL ---------------------------------------------------------------
  // base.js toAbsoluteUrl/toRelativeUrl rely on `new URL(url[, base])` and
  // .href/.hostname/.pathname/.search/.hash/.origin. Several family parsers
  // also use new URL(href).hostname. A bare engine has no URL, so toRelativeUrl
  // silently returned absolute urls and toAbsoluteUrl threw on relative inputs.
  if (typeof g.URL === 'undefined') {
    var parseAbsolute = function (url) {
      var m = /^([a-zA-Z][a-zA-Z0-9+.\-]*:)\/\/([^/?#]*)([^?#]*)(\?[^#]*)?(#.*)?/.exec(url);
      if (!m) {
        var n = /^([^?#]*)(\?[^#]*)?(#.*)?/.exec(url) || ['', '', '', ''];
        return { protocol: '', host: '', hostname: '', port: '', pathname: n[1] || '', search: n[2] || '', hash: n[3] || '', origin: '' };
      }
      var host = m[2] || '';
      var hostname = host, port = '';
      var ci = host.lastIndexOf(':');
      if (ci > host.lastIndexOf(']')) { hostname = host.slice(0, ci); port = host.slice(ci + 1); }
      return {
        protocol: m[1], host: host, hostname: hostname, port: port,
        pathname: m[3] || '/', search: m[4] || '', hash: m[5] || '',
        origin: m[1] + '//' + host
      };
    };
    var resolve = function (url, base) {
      url = String(url == null ? '' : url).trim();
      var hasScheme = /^[a-zA-Z][a-zA-Z0-9+.\-]*:/.test(url);
      if (!hasScheme && base != null) {
        var b = parseAbsolute(String(base).trim());
        if (url.indexOf('//') === 0) {
          url = b.protocol + url;
        } else if (url.charAt(0) === '/') {
          url = b.origin + url;
        } else if (url.charAt(0) === '#') {
          url = b.origin + b.pathname + b.search + url;
        } else if (url.charAt(0) === '?') {
          url = b.origin + b.pathname + url;
        } else {
          var dir = b.pathname.replace(/[^/]*$/, '');
          var parts = (dir + url).split('/');
          var out = [];
          for (var i = 0; i < parts.length; i++) {
            var p = parts[i];
            if (p === '..') { out.pop(); }
            else if (p === '.') { /* skip */ }
            else { out.push(p); }
          }
          url = b.origin + '/' + out.join('/').replace(/^\/+/, '');
        }
      }
      return parseAbsolute(url);
    };
    var URLImpl = function (url, base) {
      var p = resolve(url, base);
      this.protocol = p.protocol; this.host = p.host; this.hostname = p.hostname;
      this.port = p.port; this.pathname = p.pathname; this.search = p.search;
      this.hash = p.hash; this.origin = p.origin;
      this.searchParams = new g.URLSearchParams(p.search);
    };
    Object.defineProperty(URLImpl.prototype, 'href', {
      get: function () { return this.origin + this.pathname + this.search + this.hash; },
      enumerable: true
    });
    URLImpl.prototype.toString = function () { return this.href; };
    g.URL = URLImpl;
  }

  // ---- URLSearchParams ---------------------------------------------------
  // madara.js / mangadventure.js / fmreader.js build query strings with this.
  if (typeof g.URLSearchParams === 'undefined') {
    var USP = function (init) {
      this._p = [];
      if (typeof init === 'string') {
        var s = init.replace(/^\?/, '');
        if (s) {
          var kvs = s.split('&');
          for (var i = 0; i < kvs.length; i++) {
            var kv = kvs[i]; if (!kv) continue;
            var eq = kv.indexOf('=');
            var k = eq < 0 ? kv : kv.slice(0, eq);
            var v = eq < 0 ? '' : kv.slice(eq + 1);
            this._p.push([decodeURIComponent(k.replace(/\+/g, ' ')), decodeURIComponent(v.replace(/\+/g, ' '))]);
          }
        }
      } else if (init && typeof init === 'object') {
        for (var key in init) {
          if (Object.prototype.hasOwnProperty.call(init, key)) this._p.push([String(key), String(init[key])]);
        }
      }
    };
    USP.prototype.append = function (k, v) { this._p.push([String(k), String(v)]); };
    USP.prototype.set = function (k, v) {
      var found = false;
      for (var i = 0; i < this._p.length; i++) {
        if (this._p[i][0] === String(k)) {
          if (!found) { this._p[i][1] = String(v); found = true; }
          else { this._p.splice(i, 1); i--; }
        }
      }
      if (!found) this._p.push([String(k), String(v)]);
    };
    USP.prototype.get = function (k) {
      for (var i = 0; i < this._p.length; i++) if (this._p[i][0] === String(k)) return this._p[i][1];
      return null;
    };
    USP.prototype.has = function (k) { return this.get(k) !== null; };
    USP.prototype['delete'] = function (k) {
      this._p = this._p.filter(function (e) { return e[0] !== String(k); });
    };
    USP.prototype.forEach = function (cb, thisArg) {
      for (var i = 0; i < this._p.length; i++) cb.call(thisArg, this._p[i][1], this._p[i][0], this);
    };
    USP.prototype.toString = function () {
      var out = [];
      for (var i = 0; i < this._p.length; i++) {
        out.push(encodeURIComponent(this._p[i][0]) + '=' + encodeURIComponent(this._p[i][1]));
      }
      return out.join('&');
    };
    g.URLSearchParams = USP;
  }

  // ---- atob / btoa (latin1 base64) --------------------------------------
  // likemanga.js / fmreader.js decode base64 obfuscated payloads.
  if (typeof g.atob === 'undefined' || typeof g.btoa === 'undefined') {
    var B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
    if (typeof g.btoa === 'undefined') {
      g.btoa = function (input) {
        var str = String(input), out = '', i = 0;
        while (i < str.length) {
          var c1 = str.charCodeAt(i++), c2 = str.charCodeAt(i++), c3 = str.charCodeAt(i++);
          var e1 = c1 >> 2, e2 = ((c1 & 3) << 4) | (c2 >> 4), e3 = ((c2 & 15) << 2) | (c3 >> 6), e4 = c3 & 63;
          if (isNaN(c2)) { e3 = e4 = 64; } else if (isNaN(c3)) { e4 = 64; }
          out += B64.charAt(e1) + B64.charAt(e2) + (e3 === 64 ? '=' : B64.charAt(e3)) + (e4 === 64 ? '=' : B64.charAt(e4));
        }
        return out;
      };
    }
    if (typeof g.atob === 'undefined') {
      g.atob = function (input) {
        var str = String(input).replace(/[^A-Za-z0-9+/=]/g, ''), out = '', i = 0;
        while (i < str.length) {
          var e1 = B64.indexOf(str.charAt(i++)), e2 = B64.indexOf(str.charAt(i++));
          var e3 = B64.indexOf(str.charAt(i++)), e4 = B64.indexOf(str.charAt(i++));
          var c1 = (e1 << 2) | (e2 >> 4), c2 = ((e2 & 15) << 4) | (e3 >> 2), c3 = ((e3 & 3) << 6) | e4;
          out += String.fromCharCode(c1);
          if (e3 !== 64 && e3 >= 0) out += String.fromCharCode(c2);
          if (e4 !== 64 && e4 >= 0) out += String.fromCharCode(c3);
        }
        return out;
      };
    }
  }

  // ---- TextEncoder / TextDecoder (UTF-8) --------------------------------
  // likemanga.js uses new TextDecoder('utf-8').decode(bytes) on atob output;
  // mangotheme.js / fmreader.js use TextEncoder for key derivation.
  if (typeof g.TextEncoder === 'undefined') {
    g.TextEncoder = function () {};
    g.TextEncoder.prototype.encode = function (str) {
      str = String(str);
      var bytes = [];
      for (var i = 0; i < str.length; i++) {
        var c = str.charCodeAt(i);
        if (c < 0x80) { bytes.push(c); }
        else if (c < 0x800) { bytes.push(0xc0 | (c >> 6), 0x80 | (c & 0x3f)); }
        else if (c >= 0xd800 && c <= 0xdbff && i + 1 < str.length) {
          var c2 = str.charCodeAt(i + 1);
          var cp = 0x10000 + ((c & 0x3ff) << 10) + (c2 & 0x3ff); i++;
          bytes.push(0xf0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3f), 0x80 | ((cp >> 6) & 0x3f), 0x80 | (cp & 0x3f));
        } else { bytes.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f)); }
      }
      return new Uint8Array(bytes);
    };
  }
  if (typeof g.TextDecoder === 'undefined') {
    g.TextDecoder = function () {};
    g.TextDecoder.prototype.decode = function (buf) {
      var bytes = buf instanceof Uint8Array ? buf : new Uint8Array(buf || []);
      var out = '', i = 0;
      while (i < bytes.length) {
        var b = bytes[i++];
        if (b < 0x80) { out += String.fromCharCode(b); }
        else if (b >= 0xc0 && b < 0xe0) { out += String.fromCharCode(((b & 0x1f) << 6) | (bytes[i++] & 0x3f)); }
        else if (b >= 0xe0 && b < 0xf0) { out += String.fromCharCode(((b & 0x0f) << 12) | ((bytes[i++] & 0x3f) << 6) | (bytes[i++] & 0x3f)); }
        else {
          var cp = ((b & 0x07) << 18) | ((bytes[i++] & 0x3f) << 12) | ((bytes[i++] & 0x3f) << 6) | (bytes[i++] & 0x3f);
          cp -= 0x10000;
          out += String.fromCharCode(0xd800 + (cp >> 10), 0xdc00 + (cp & 0x3ff));
        }
      }
      return out;
    };
  }
})(typeof globalThis !== 'undefined' ? globalThis : this);
