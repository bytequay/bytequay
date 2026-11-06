// Resolve the current installer once; the release page remains the fallback.
(async () => {
  try {
    const response = await fetch("https://api.github.com/repos/bytequay/bytequay/releases/latest", {
      credentials: "omit",
      signal: AbortSignal.timeout(5000),
    });
    if (!response.ok) return;
    const release = await response.json();
    if (release.draft || release.prerelease || !Array.isArray(release.assets)) return;
    const installer = release.assets.find(asset =>
      asset.state === "uploaded"
      && /^ByteQuay-[\d.]+-arm64\.dmg$/.test(asset.name)
      && typeof asset.browser_download_url === "string"
      && asset.browser_download_url.startsWith("https://github.com/bytequay/bytequay/releases/download/"),
    );
    if (!installer) return;
    for (const link of document.querySelectorAll("[data-macos-download]")) {
      link.href = installer.browser_download_url;
    }
  } catch {
    // Offline, rate-limited, or older browsers can still open GitHub Releases.
  }
})();
