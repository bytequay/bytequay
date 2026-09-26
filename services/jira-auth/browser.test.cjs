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
const { app, BrowserWindow } = require('electron');
const assert = require('node:assert/strict');
const { createServer } = require('node:http');
const { mkdtemp, rm } = require('node:fs/promises');
const { randomBytes } = require('node:crypto');
const { tmpdir } = require('node:os');
const { join } = require('node:path');
const { pathToFileURL } = require('node:url');

app.on('window-all-closed', () => {});
(async () => {
  const directory = await mkdtemp(join(tmpdir(), 'bytequay-jira-browser-'));
  app.setPath('userData', join(directory, 'chromium'));
  await app.whenReady();
  const { createBroker, readConfig } = await import(pathToFileURL(join(__dirname, 'broker.mjs')));
  let broker, source, upstream, window;
  try {
    upstream = createServer((request, response) => {
      const url = new URL(request.url, 'http://localhost');
      if (url.pathname === '/authorize') {
        assert.equal(request.headers.referer, undefined, 'Do not disclose broker state URLs to the OAuth provider');
        response.writeHead(303, { Location: `${source}/oauth/callback?state=${url.searchParams.get('state')}&code=browser-accepted` });
        response.end(); return;
      }
      response.setHeader('Content-Type', 'application/json');
      if (url.pathname === '/oauth/token') response.end(JSON.stringify({ access_token: 'test-access', refresh_token: 'test-refresh', expires_in: 3600, token_type: 'bearer' }));
      else if (url.pathname === '/oauth/token/accessible-resources') response.end(JSON.stringify([{ id: '12345678-1234-1234-1234-123456789abc', url: 'https://example.atlassian.net', name: 'Example', scopes: ['read:jira-work'] }]));
      else { response.statusCode = 404; response.end('{}'); }
    });
    await new Promise(resolve => upstream.listen(0, '127.0.0.1', resolve));
    const config = readConfig({ JIRA_AUTH_ALLOW_LOCALHOST: 'true', JIRA_AUTH_PUBLIC_URL: 'http://127.0.0.1:8080',
      JIRA_AUTH_TEST_UPSTREAM: `http://127.0.0.1:${upstream.address().port}`, JIRA_AUTH_ENCRYPTION_KEY: randomBytes(32).toString('base64'),
      JIRA_AUTH_DATA_DIR: join(directory, 'connections'), ATLASSIAN_CLIENT_ID: 'test-client', ATLASSIAN_CLIENT_SECRET: 'test-secret' });
    broker = await createBroker(config);
    await new Promise(resolve => broker.server.listen(0, '127.0.0.1', resolve));
    source = config.publicOrigin = `http://127.0.0.1:${broker.server.address().port}`;
    const connection = await fetch(source + '/v1/connections', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' }).then(response => response.json());
    window = new BrowserWindow({ show: false, webPreferences: { contextIsolation: true, nodeIntegration: false, partition: 'jira-broker-test' } });
    await window.loadURL(connection.authorizationUrl);
    await window.webContents.executeJavaScript(`document.querySelector('input').value=${JSON.stringify(connection.pairingCode)}; document.querySelector('form').requestSubmit()`);
    let state = 'pending';
    for (let attempt = 0; attempt < 50 && state === 'pending'; attempt++) {
      await new Promise(resolve => setTimeout(resolve, 100));
      state = (await fetch(source + `/v1/connections/${connection.id}`, { headers: { Authorization: `Bearer ${connection.pairingToken}` } }).then(response => response.json())).state;
    }
    assert.equal(state, 'connected', 'The real browser form must reach the OAuth callback');
    assert.match(await window.webContents.executeJavaScript('document.body.innerText'), /Jira is connected/);
    console.log('Chromium pairing form → OAuth redirect → callback: PASS');
  }
  finally {
    window?.destroy();
    await broker?.close();
    if (upstream) await new Promise(resolve => upstream.close(resolve));
    await rm(directory, { recursive: true, force: true });
  }
  app.quit();
})().catch(error => { console.error(error.message); app.exit(1); });
