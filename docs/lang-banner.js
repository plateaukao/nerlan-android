/* Injects the EN | 中文 switcher into the nav. The counterpart URL keeps the
   same filename and hash; zh-tw pages live in a zh-tw/ subdirectory that
   mirrors the English pages one-to-one. */
(function () {
  var path = window.location.pathname;
  var hash = window.location.hash || '';
  var isZhTw = /\/zh-tw\//.test(path);
  var filename = path.split('/').pop() || 'index.html';
  if (!filename || filename.indexOf('.') === -1) filename = 'index.html';

  var enHref = (isZhTw ? '../' : '') + filename + hash;
  var zhHref = (isZhTw ? '' : 'zh-tw/') + filename + hash;

  function insert() {
    var container = document.querySelector('.site-nav .container');
    if (!container || container.querySelector('.site-nav__lang')) return;
    var el = document.createElement('div');
    el.className = 'site-nav__lang';
    el.innerHTML =
      '<a href="' + enHref + '" class="' + (isZhTw ? '' : 'current') + '" hreflang="en">EN</a>' +
      '<span class="site-nav__lang-sep">|</span>' +
      '<a href="' + zhHref + '" class="' + (isZhTw ? 'current' : '') + '" hreflang="zh-Hant-TW" lang="zh-Hant-TW">中文</a>';
    var toggle = container.querySelector('.nav-toggle');
    if (toggle) container.insertBefore(el, toggle);
    else container.appendChild(el);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', insert);
  } else {
    insert();
  }
})();
