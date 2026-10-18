/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { expect, it } from 'vitest';
import { createServer, resolveConfig } from 'vite';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, basename } from 'node:path';
import { runBrowserFixture, deadline } from './fixtures/browserFixture';

it('loads the editor with separate app and IDE dependency caches in development', async () => {
  const configs = await Promise.all(['vite.renderer.config.ts', 'vite.ide.config.ts'].map(configFile => resolveConfig({ configFile }, 'serve')));
  expect(configs[0].cacheDir).not.toBe(configs[1].cacheDir);
  const directory = await mkdtemp(join(tmpdir(), 'bytequay-ide-dev-'));
  const servers = await Promise.all(configs.map(config => createServer({ configFile: config.configFile,
    cacheDir: join(directory, basename(config.cacheDir)), server: { host: '127.0.0.1', port: 0, hmr: false } })));
  try {
    await Promise.all(servers.map(server => server.listen()));
    const url = `${servers[1].resolvedUrls.local[0]}ide/index.html`;
    const result = await runBrowserFixture<{ text: string; errors: string[] }>({
      prefix: 'bytequay-ide-startup-', entry: 'src/fixtures/harness.ts', global: 'Harness', blockNetwork: false, timeoutMs: 45_000,
      preload: `const { contextBridge } = require('electron');
        contextBridge.exposeInMainWorld('ideWorkbench', {
          request: () => {}, onContext: () => () => {}, bootstrap: () => new Promise(() => {}),
        });`,
      setup: `const errors = [];
        window.webContents.on('console-message', event => { if (event.level === 'error') errors.push(event.message); });`,
      body: `await window.loadURL(${JSON.stringify(url)});
        const text = await window.webContents.executeJavaScript(\`new Promise((resolve, reject) => {
          const timer = setTimeout(() => { clearInterval(poll); reject(new Error('Editor did not mount: ' + document.body.innerText)); }, 30000);
          const poll = setInterval(() => {
            if (document.body.innerText.includes('Loading code tools…')) { clearTimeout(timer); clearInterval(poll); resolve(document.body.innerText); }
          }, 50);
        })\`);
        const results = { text, errors };`,
    });
    expect(result.text).toContain('Loading code tools…');
    expect(result.errors).toEqual([]);
  } finally {
    await Promise.all(servers.map(server => server.close()));
    await rm(directory, { recursive: true, force: true });
  }
}, deadline(90_000));
