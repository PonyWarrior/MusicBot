// Runs before the bundle and before the first paint. The server has already written the saved
// theme into <html data-theme>; on a first run this follows the OS instead.
//
// It is a file of its own rather than a script in index.html because the server's content
// security policy allows scripts from this origin only, not inline ones.
(function () {
  var root = document.documentElement;
  if (!root.getAttribute('data-theme')) {
    root.setAttribute(
      'data-theme',
      window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches
        ? 'dark'
        : 'light',
    );
  }
})();
