// A file rather than inline, so the Content-Security-Policy can refuse every inline script.

// Storage can be blocked (private windows, site data off); the notice then shows on
// every visit and still closes for this one.
(() => {
  const key = "workspaced.privacyNotice";
  const notice = document.getElementById("privacy-notice");
  try { if (localStorage.getItem(key) === "dismissed") return; } catch {}
  notice.hidden = false;
  document.getElementById("privacy-notice-dismiss").addEventListener("click", () => {
    notice.hidden = true;
    try { localStorage.setItem(key, "dismissed"); } catch {}
  });
})();
