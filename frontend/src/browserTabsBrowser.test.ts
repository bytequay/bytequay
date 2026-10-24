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
import { execFile } from 'node:child_process';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { promisify } from 'node:util';
import electron from 'electron';
import { build } from 'esbuild';
import { expect, it } from 'vitest';
import { deadline } from './fixtures/browserFixture';
import { electronFixtureLifecycle } from './fixtures/electronFixtureLifecycle';

it('protects real page drafts, searches native page content and creates empty pages without loading a website', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'bytequay-browser-tabs-'));
  const timeout = deadline(30_000);
  try {
    await build({ entryPoints: [resolve('src/browserTabs.ts')], outfile: join(directory, 'browserTabs.cjs'), bundle: true,
      platform: 'node', format: 'cjs', external: ['electron'], logLevel: 'silent' });
    const script = join(directory, 'main.cjs');
    await writeFile(script, `
      const { app, BrowserWindow, dialog, session, webContents } = require('electron');
      const assert = require('node:assert/strict');
      const { BrowserTabs } = require('./browserTabs.cjs');
      ${electronFixtureLifecycle(timeout)}
      app.setPath('userData', ${JSON.stringify(join(directory, 'profile'))});
      app.whenReady().then(async () => {
        const window = new BrowserWindow({ show: false, width: 900, height: 700, webPreferences: { backgroundThrottling: false } });
        const tabs = new BrowserTabs(() => window, () => ${JSON.stringify(join(directory, 'tabs.json'))});
        const until = async (check, description) => {
          for (let attempt = 0; attempt < 200; attempt++) {
            const value = await check();
            if (value) return value;
            await new Promise(resolve => setTimeout(resolve, 25));
          }
          throw new Error('Timed out waiting for ' + description);
        };
        try {
          await window.loadURL('data:text/html,<h1>App content without search matches</h1>');
          // Native views need a shown window to finish painting; this stays transparent and click-through.
          window.setOpacity(0); window.setIgnoreMouseEvents(true); window.showInactive();
          const browserSession = session.fromPartition('persist:inapp-browser');
          browserSession.protocol.handle('https', () => new Response(
            '<!doctype html><title>Draft page</title><h1>Needle</h1><p>Another Needle</p><textarea id="draft"></textarea>',
            { headers: { 'content-type': 'text/html' } }));
          const contentsCount = webContents.getAllWebContents().length;
          tabs.newPage();
          const first = tabs.getState().activeTabId;
          tabs.newPage();
          const id = tabs.getState().activeTabId;
          assert.notEqual(id, first);
          assert.equal(tabs.getState().tabs.length, 2);
          assert.ok(tabs.getState().tabs.every(tab => tab.url === '' && !tab.loading));
          assert.equal(webContents.getAllWebContents().length, contentsCount);
          await tabs.navigate(id, 'https://fixture.test/draft');
          tabs.mount(id, { x: 0, y: 80, width: 900, height: 600 });
          const contents = webContents.getAllWebContents().find(entry => entry.getURL() === 'https://fixture.test/draft');
          assert.ok(contents);
          const messages = [];
          window.webContents.send = (channel, ...args) => { messages.push({ channel, args }); };
          const modifier = process.platform === 'darwin' ? 'meta' : 'control';
          contents.focus();
          contents.sendInputEvent({ type: 'keyDown', keyCode: 'F', modifiers: [modifier] });
          contents.sendInputEvent({ type: 'keyUp', keyCode: 'F', modifiers: [modifier] });
          await until(() => messages.some(message => message.channel === 'inapp:find' && message.args[0] === id), 'the native Find shortcut');
          const find = async options => {
            const start = messages.length;
            tabs.find(id, 'Needle', options);
            return until(() => messages.slice(start).find(message => message.channel === 'find:result'
              && message.args[0].tabId === id && message.args[0].matches === 2 && message.args[0].activeMatchOrdinal > 0)?.args[0], 'native search results');
          };
          const firstMatch = await find({ forward: true, findNext: true });
          const nextMatch = await find({ forward: true, findNext: false });
          assert.notEqual(nextMatch.activeMatchOrdinal, firstMatch.activeMatchOrdinal);
          await tabs.navigate(id, 'https://fixture.test/next');
          await find({ forward: true, findNext: false });
          await tabs.navigate(id, 'https://fixture.test/draft');
          tabs.stopFind(id);

          await contents.executeJavaScript(
            "document.querySelector('#draft').value = 'Keep this unfinished draft'; "
            + "window.addEventListener('beforeunload', event => { event.preventDefault(); event.returnValue = ''; }); true", true);
          let answer = 0;
          let prompts = 0;
          dialog.showMessageBoxSync = (_window, options) => {
            assert.deepEqual(options.buttons, ['Stay', 'Leave']);
            prompts++;
            return answer;
          };
          tabs.close(id);
          await until(() => prompts === 1, 'the unsaved-work warning');
          assert.equal(contents.isDestroyed(), false);
          assert.equal(tabs.getState().activeTabId, id);
          assert.equal(await contents.executeJavaScript("document.querySelector('#draft').value"), 'Keep this unfinished draft');
          await tabs.navigate(id, 'https://fixture.test/other');
          assert.equal(prompts, 2);
          const retained = tabs.getState().tabs.find(tab => tab.id === id);
          assert.equal(retained.url, 'https://fixture.test/draft');
          assert.equal(retained.loading, false);
          assert.equal(retained.error, null);
          assert.equal(await contents.executeJavaScript("document.querySelector('#draft').value"), 'Keep this unfinished draft');
          answer = 1;
          tabs.close(id);
          await until(() => contents.isDestroyed() && !tabs.getState().tabs.some(tab => tab.id === id), 'confirmed page closure');
          assert.equal(prompts, 3);
          assert.equal(tabs.getState().activeTabId, null);
          assert.equal(tabs.getState().tabs[0].id, first);
          process.stdout.write(JSON.stringify({ drafts: true, find: true, newPages: true }) + '\\n');
          tabs.releaseWindow();
          app.exit(0);
        }
        catch (error) { console.error(error); app.exit(1); }
      });
    `);
    const environment = { ...process.env };
    delete environment.ELECTRON_RUN_AS_NODE;
    const executable = electron as unknown as string;
    const command = process.platform === 'linux' && !process.env.DISPLAY ? 'xvfb-run' : executable;
    const args = command === executable ? [script] : ['-a', executable, script];
    const result = await promisify(execFile)(command, args, { env: environment, timeout, killSignal: 'SIGKILL' });
    expect(JSON.parse(result.stdout.trim())).toEqual({ drafts: true, find: true, newPages: true });
  }
  finally { await rm(directory, { recursive: true, force: true }); }
}, deadline(40_000));
