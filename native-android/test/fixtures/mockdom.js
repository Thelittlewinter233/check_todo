/*
 * mockdom.js — Nashorn 用的浏览器环境 mock（仅测试，不进 APK）。
 *
 * 目的：让生产代码里「注入 WebView 的脚本」在 JVM 里真实执行。
 * 覆盖面（与三个生产脚本实际触达的 API 对齐，不多不少）：
 *   - listFramesScript：document.location/title/querySelectorAll('iframe,frame')、
 *     el.getAttribute('name')/el.id/el.contentDocument（跨域为 null）
 *   - frameInfoScript：document.defaultView.frames[i].document、querySelectorAll('table')、
 *     body.textContent、跨域 window 的 document 访问抛 SecurityError
 *   - dumpScript / fetchProxyScript：window.__checkTodoSink（Java ChunkAssembler 的 JS 垫片）、
 *     window.__checkTodoAbort、fetch（MiniPromise 微缩版 thenable——Nashorn 无 Promise）
 */

// ------------------------------------------------------------------
// 微缩 Promise：Nashorn 没有内建 Promise，fetch 代理脚本的 then/catch 链
// 只需要「解析即回调 + thenable 链接 + catch 旁路」这三个语义。
// ------------------------------------------------------------------
function MiniPromise() {
    this._isMini = true;
    this._done = false;
    this._ok = false;
    this._val = null;
    this._err = null;
    this._cbs = [];
    this._errs = [];
}
MiniPromise.prototype._flushOne = function (np, f, arg, isOk) {
    if (!isOk) {
        // 拒绝穿透：无 onRejected 处理器时，派生 promise 必须 reject（不能当值 resolve）。
        if (f === null || f === undefined) { np.reject(arg); return; }
        var rejected;
        try { rejected = f(arg); } catch (e) { np.reject(e); return; }
        np._settleFrom(rejected);
        return;
    }
    if (f === null || f === undefined) { np.resolve(arg); return; }
    var out;
    try { out = f(arg); } catch (e) { np.reject(e); return; }
    np._settleFrom(out);
};
MiniPromise.prototype._settleFrom = function (out) {
    var np = this;
    if (out && out._isMini) {
        out.then(function (v) { np.resolve(v); }, function (e) { np.reject(e); });
    } else {
        np.resolve(out);
    }
};
MiniPromise.prototype.resolve = function (v) {
    if (this._done) return;
    this._done = true; this._ok = true; this._val = v;
    for (var i = 0; i < this._cbs.length; i++) this._flushOne(this._cbs[i][0], this._cbs[i][1], v, true);
    this._cbs = []; this._errs = [];
};
MiniPromise.prototype.reject = function (e) {
    if (this._done) return;
    this._done = true; this._ok = false; this._err = e;
    for (var i = 0; i < this._errs.length; i++) this._flushOne(this._errs[i][0], this._errs[i][1], e, false);
    this._cbs = []; this._errs = [];
};
MiniPromise.prototype.then = function (fn, bad) {
    var np = new MiniPromise();
    if (this._done) {
        if (this._ok) this._flushOne(np, fn, this._val, true);
        else this._flushOne(np, bad, this._err, false);
    } else {
        this._cbs.push([np, fn || null]);
        this._errs.push([np, bad || null]);
    }
    return np;
};
MiniPromise.prototype['catch'] = function (bad) { return this.then(null, bad); };

// ------------------------------------------------------------------
// DOM mock
// ------------------------------------------------------------------
function MockElement(attrs, id) {
    this._attrs = attrs || {};
    if (id) this._attrs['id'] = id;
}
MockElement.prototype.getAttribute = function (name) {
    return (name in this._attrs) ? String(this._attrs[name]) : null;
};

/**
 * spec 字段：
 *   url / title / tables / bodyText / outerHTML
 *   frames: [{name, id, url, title, tables, bodyText, outerHTML, cross, frames:[...]}]
 * cross=true 的帧：contentDocument 为 null（与真实浏览器一致），其 window.document 抛 SecurityError。
 */
function makeDoc(spec) {
    var doc = {};
    doc.location = { href: spec.url || '' };
    doc.title = spec.title || '';
    var tables = spec.tables || 0;
    var bodyText = spec.bodyText || '';
    doc.body = { textContent: bodyText };
    doc.documentElement = { textContent: bodyText, outerHTML: spec.outerHTML || '' };

    var win = { document: doc };
    doc.defaultView = win;
    win.frames = [];

    var frameSpecs = spec.frames || [];
    var elements = [];
    for (var i = 0; i < frameSpecs.length; i++) {
        var f = frameSpecs[i];
        var el = new MockElement({ name: f.name || '' }, f.id || '');
        if (f.cross) {
            el.contentDocument = null;
            var blocker = {};
            Object.defineProperty(blocker, 'document', {
                get: function () { throw new Error('SecurityError: Blocked a frame with origin.'); }
            });
            win.frames.push(blocker);
        } else {
            var sub = makeDoc(f);
            el.contentDocument = sub;
            win.frames.push(sub.defaultView);
        }
        elements.push(el);
    }

    doc.querySelectorAll = function (selector) {
        if (selector === 'table') {
            var arr = [];
            for (var t = 0; t < tables; t++) arr.push({ tagName: 'TABLE' });
            return arr;
        }
        if (selector === 'iframe,frame') {
            var out = [];
            for (var k = 0; k < elements.length; k++) out.push(elements[k]);
            return out;
        }
        return [];
    };
    return doc;
}

// ------------------------------------------------------------------
// sink 垫片：把 Java 侧 ChunkAssembler（经 addJavascriptInterface 注入的等价物）
// 暴露成生产脚本期待的 window.__checkTodoSink 形态。
// ------------------------------------------------------------------
function makeSink(javaSink) {
    return {
        begin: function (id, url) { javaSink.begin(String(id), String(url)); },
        chunk: function (id, seq, total, text) {
            return javaSink.chunk(String(id), Number(seq), Number(total), String(text));
        },
        done: function (id, status, length) { javaSink.done(String(id), String(status), Number(length)); },
        fail: function (id, reason) { javaSink.fail(String(id), String(reason)); }
    };
}

// mock fetch：由各场景安装（window.fetch 形态）。默认不存在，脚本引用全局 fetch。
function mockFetchOk(url, text, status) {
    return function (u, opts) {
        var p = new MiniPromise();
        p.resolve({
            ok: (status === undefined || status === 200),
            status: (status === undefined ? 200 : status),
            text: function () {
                var tp = new MiniPromise();
                tp.resolve(text);
                return tp;
            }
        });
        return p;
    };
}
function mockFetchReject(reason) {
    return function (u, opts) {
        var p = new MiniPromise();
        p.reject(new Error(reason));
        return p;
    };
}
