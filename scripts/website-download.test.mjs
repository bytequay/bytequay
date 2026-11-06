import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import test from 'node:test';

const source = readFileSync(new URL('../website/dist/download.js', import.meta.url), 'utf8');
const fallback = 'https://github.com/bytequay/bytequay/releases/latest';
const url = 'https://github.com/bytequay/bytequay/releases/download/v1.2.3/ByteQuay-1.2.3-arm64.dmg';
const asset = { name: 'ByteQuay-1.2.3-arm64.dmg', state: 'uploaded', browser_download_url: url };
const release = { draft: false, prerelease: false, assets: [asset] };

async function resolve(fetch) {
  const links = [{ href: fallback }, { href: fallback }];
  await runInNewContext(source, { fetch, AbortSignal, document: { querySelectorAll: () => links } });
  return links.map(link => link.href);
}

test('both buttons download the uploaded Apple silicon DMG from the latest stable release', async () => {
  const links = await resolve(async endpoint => {
    assert.equal(endpoint, 'https://api.github.com/repos/bytequay/bytequay/releases/latest');
    return { ok: true, json: async () => ({ ...release, assets: [
      { ...asset, name: 'ByteQuay-1.2.3-x64.dmg' },
      { ...asset, name: 'ByteQuay-darwin-arm64-1.2.3.zip' }, asset,
    ] }) };
  });
  assert.deepEqual(links, [url, url]);
});

test('unavailable, unfinished, prerelease, or unrelated assets keep working release-page links', async () => {
  for (const data of [
    {}, { ...release, draft: true }, { ...release, prerelease: true },
    { ...release, assets: [] },
    { ...release, assets: [{ ...asset, state: 'new' }] },
    { ...release, assets: [{ ...asset, browser_download_url: 'https://example.com/app.dmg' }] },
  ]) {
    assert.deepEqual(await resolve(async () => ({ ok: true, json: async () => data })), [fallback, fallback]);
  }
  assert.deepEqual(await resolve(async () => ({ ok: false })), [fallback, fallback]);
  assert.deepEqual(await resolve(async () => { throw new Error('Offline'); }), [fallback, fallback]);
});
