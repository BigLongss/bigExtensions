# bigExtensions

Manga sources for [Mihon](https://mihon.app) and forks.

## Install

Open **More → Settings → Browse → Extension repos** and add:

```
https://raw.githubusercontent.com/BigLongss/bigExtensions/repo/index.json
```

The app will list every source below. Updates arrive through the same screen.

If you added the old repository under `Biglongs1`, remove that URL and add the
new one above to receive updates.

Forks lag behind on repo support, so if the app rejects the URL, check that it is
up to date. Yokai, for instance, only accepts this format from 1.10.0 onwards.

## Sources

| Source | Language | Notes |
| --- | --- | --- |
| AstraToons | pt-BR | |
| Asura Scans | en | |
| Atsumaru | en | |
| Aura Toons | pt-BR | |
| Comix | en | |
| CorujaToon | pt-BR | Login available in extension settings or WebView |
| Imperio da Britannia | pt-BR | |
| Ink Scan | pt-BR | Requires an account; login through WebView |
| Kagane | en | |
| KuroMangas | pt-BR | Requires an account |
| LoversToon | pt-BR | |
| MangaBall | en | |
| MangaDot | en | |
| MangaFire | en | |
| MangaLivre.blog | pt-BR | |
| MangaLivre.org | pt-BR | |
| MangaStop | pt-BR | |
| Mangás Brasuka | pt-BR | |
| MangoToons | pt-BR | |
| Nexus Mangás | pt-BR | |
| NoxManga | pt-BR | |
| OniSaga | en | |
| Sakura Mangás | pt-BR | |
| Vegitoons | pt-BR | |
| Yomu Comics | pt-BR | Requires an account |

KuroMangas and Yomu Comics require credentials under the extension settings.
For Ink Scan, open the source in WebView, sign in and complete the site's CAPTCHA,
then return to the app and refresh. The extension reuses and refreshes that session.
CorujaToon accepts your email or username and password in its settings, or a WebView login.

## Requests

Want a source that is not here? [Open a request](https://github.com/BigLongss/bigExtensions/issues/new?template=source_request.yml).

Before asking, check that the site is not already covered by [keiyoushi](https://github.com/keiyoushi/extensions), which is a far larger repo and the better home for most sources. This one exists for sources I maintain closely, so requests are accepted based on how reliably I can keep them alive, not on how popular the site is.

A source is unlikely to be accepted when it:

- requires a paid account to read anything
- hosts content that is not legally distributable
- is a mirror of a site already listed here

## Reporting a broken source

Sites change often, and a source that worked yesterday can break without notice. [Report it here](https://github.com/BigLongss/bigExtensions/issues/new?template=broken_source.yml) with the extension version and what exactly stopped working, since browsing, search and reading tend to break independently of each other.

## Contributing

Pull requests are welcome, whether it is a fix for a broken source or a new one.

1. Fork the repo and branch off `main`.
2. Make the change and bump `versionCode` in the source's `build.gradle.kts`, otherwise the app will not offer the update.
3. Build and run it against a device before opening the PR.
4. Open the PR and fill in the checklist.

Sources live in `src/<lang>/<name>` and follow the same conventions as [keiyoushi/extensions-source](https://github.com/keiyoushi/extensions-source), so its [CONTRIBUTING](https://github.com/keiyoushi/extensions-source/blob/main/CONTRIBUTING.md) applies here too and is worth reading before writing any code.

If a source is broken and you know why but do not want to write the fix, say so in the issue. A pointer to the request that changed is usually most of the work.

## Support

Issues are the best place for anything about a source, since it stays searchable for whoever hits the same problem later. For everything else, reach me here:

<p align="left">
  <a href="https://discord.com/users/1254123038656430189">
    <img alt="Discord" src="https://img.shields.io/badge/Discord-biglongs-5865F2?style=for-the-badge&logo=discord&logoColor=white">
  </a>
  <a href="https://t.me/donttry999">
    <img alt="Telegram" src="https://img.shields.io/badge/Telegram-%40donttry999-26A5E4?style=for-the-badge&logo=telegram&logoColor=white">
  </a>
</p>

## Credits

Build infrastructure comes from [keiyoushi/extensions-source](https://github.com/keiyoushi/extensions-source), under Apache 2.0.
