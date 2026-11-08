/* Resolve the theme before first paint so there is no flash. Kept as a
   classic external script (not an inline one) so the page can run under a
   Content-Security-Policy that forbids inline script. */
(function () {
  try {
    var pref = localStorage.getItem('pfa_theme') || 'system';
    var dark = pref === 'dark' || (pref === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches);
    document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light');
    var meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute('content', dark ? '#141A22' : '#F2F3EE');
  } catch (e) { /* storage unavailable */ }
})();
