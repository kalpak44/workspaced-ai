# Public site

`apps/site` is workspaced.online: a landing page, customer support, the privacy policy and the terms of service.
Plain HTML with one Tailwind stylesheet, so search engines index the full text of every page. The only script is
the privacy notice's.

```sh
npm ci
npm run build   # src/ → dist/, stylesheet compiled and minified
npm run check   # fails on a link to a local page that does not exist
```

Every page carries its own copy of the header, the footer and the privacy notice, so a change to any of them is a
change to every page. The notice shows on every page until a visitor agrees, which is remembered under
`workspaced.privacyNotice` in local storage. A new page
also needs a line in `src/sitemap.xml`. nginx serves `/support` from `support.html`, redirects `/support.html` to
`/support` and `www.workspaced.online` to the apex, so each page has one URL.

`privacy.html` and `terms.html` describe how the product behaves — no backups, pausing and deletion when the balance
runs out, the reactivation fee, the user's own OpenRouter key, the sub-processors. Changing any of those means
updating them and their "Last updated" date.
