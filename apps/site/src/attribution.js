// Carries where a visit came from, and the code of a friend's invitation, on to the sign-up, so
// the app can tell which places bring people who stay. It rides on the links rather than in
// storage, so nothing is kept here.
(() => {
  const here = new URLSearchParams(location.search);
  let source = here.get("utm_source");
  const campaign = here.get("utm_campaign");
  const referral = here.get("ref");
  if (!source && document.referrer) {
    try {
      const host = new URL(document.referrer).hostname;
      // Our own app and pages are not where a visit came from.
      if (!host.endsWith("workspaced.online")) source = host.replace(/^www\./, "");
    } catch {}
  }
  if (!source && !campaign && !referral) return;
  for (const link of document.querySelectorAll("a[href]")) {
    const url = new URL(link.getAttribute("href"), location.href);
    const onSite = url.origin === location.origin;
    // Only the app, and other pages here so it survives a visitor reading around first.
    if (url.hostname !== "ai.workspaced.online" && !onSite) continue;
    if (onSite && url.pathname === location.pathname) continue;
    if (source && !url.searchParams.has("utm_source")) url.searchParams.set("utm_source", source);
    if (campaign && !url.searchParams.has("utm_campaign")) url.searchParams.set("utm_campaign", campaign);
    if (referral && !url.searchParams.has("ref")) url.searchParams.set("ref", referral);
    link.href = url.href;
  }
})();
