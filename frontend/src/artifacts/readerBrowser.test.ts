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
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { build } from 'vite';
import { expect, it } from 'vitest';
import { deadline, runBrowserFixture } from '../fixtures/browserFixture';

const source = 'src/main/java/org/example/engine/memory/contexts/VeryLongMemoryContextImplementation.java:123';
const html = (title: string, body: string) => `<!doctype html><link rel="stylesheet" href="theme.css"><main><h1>${title}</h1>${body}</main>
<script>window.isolated = false; try { parent.document.body; } catch { window.isolated = true; }</script>`;
const documents = {
  old: html('First edition', '<h2>Original section</h2>'),
  first: html('First chapter', `<a href="#last">Jump to the last section</a><h2>Comparison</h2>
    <div class="cards"><div class="card"><h3>Connector</h3><span class="cite">${source}</span><p><code>${'LongIdentifier'.repeat(8)}</code></p></div>
    <div class="card"><h3>Engine</h3><span class="cite">${source}</span></div></div>
    <pre>${'longCodeLine();'.repeat(60)}</pre>
    <table><tr><th>Source</th><th>Meaning</th></tr><tr><td>${source}</td><td>Allocation</td></tr></table>
    <h2>Details</h2><div style="height:700px">Read further down.</div><h2 id="last">Details</h2>
    <button id="expand" onclick="document.getElementById('extra').hidden=false">Show example</button><div id="extra" hidden style="height:500px">More to read.</div>`),
  second: html('Second chapter', '<h2>Next steps</h2><input aria-label="Your notes" value="Keep this"><div style="height:800px">The second page stays below the first.</div>'),
};

it('wraps long content and reads isolated pages continuously with a section index and independent versions', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'bytequay-artifact-reader-'));
  try {
    await build({ configFile: false, logLevel: 'silent', build: { minify: false, outDir: directory, emptyOutDir: false,
      lib: { entry: resolve('src/artifactView.ts'), formats: ['cjs'], fileName: () => 'artifactView.cjs' }, rollupOptions: { external: ['electron', /^node:/] } } });
    const result = await runBrowserFixture<Record<string, unknown>>({
      prefix: 'bytequay-reader-', entry: 'src/artifacts/fixtures/artifactsBrowser.tsx', global: 'ArtifactTests', width: 1000, height: 800,
      beforeReady: `const { ArtifactView } = require(${JSON.stringify(join(directory, 'artifactView.cjs'))});`,
      setup: `const documents = ${JSON.stringify(documents)};
        const target = { workspaceId: 'workspace', artifactId: 'book', pageId: 'first', pages: [
          { id: 'old', key: 'p1', version: 1, title: 'First edition', commit: 'abc1234' },
          { id: 'first', key: 'p1', version: 2, title: 'First chapter', commit: 'def5678' },
          { id: 'second', key: 'p2', version: 1, title: 'Second chapter', commit: 'def5678' }] };
        let failSecond = true;
        const reader = new ArtifactView(() => window, async target => {
          if (target.pageId === 'second' && failSecond) { failSecond = false; throw new Error('Temporarily unavailable'); }
          return documents[target.pageId];
        });
`,
      body: `
        const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
        const until = async check => { for (let i = 0; i < ${deadline(200)}; i++) { const value = await check(); if (value) return value; await sleep(30); } throw new Error('Reader did not settle'); };
        window.setOpacity(0); window.setIgnoreMouseEvents(true); window.showInactive();
        await reader.show(target, { x: 0, y: 0, width: 900, height: 760 });
        const contents = reader.view.webContents;
        const book = script => contents.executeJavaScript(script);
        const frame = id => contents.mainFrame.frames.find(frame => frame.url.endsWith('/pages/' + id + '/'));
        const page = (id, script) => frame(id).executeJavaScript(script);
        // Pages fill the reader's content width, which is narrower than the window when scroll bars always show, as on CI.
        const fitted = width => until(async () => await book('innerWidth') === width
          && await page('first', 'innerWidth') === await book('document.documentElement.clientWidth'));
        await until(() => frame('second'));
        const failedPage = await until(() => page('second', 'document.querySelector("[role=alert]")?.textContent'));
        await page('second', 'document.querySelector("[data-artifact-retry]").click()');
        await until(() => book('document.querySelectorAll(".reader-index-heading").length === 6'));
        const index = await book('[...document.querySelectorAll("#reader-index button")].map(button => button.textContent)');
        const isolated = await page('first', 'window.isolated && typeof window.bridge === "undefined" && typeof require === "undefined"');
        const noParent = await page('second', 'window.isolated');
        await page('first', 'location.href = "bytequay-artifact://book/workspace/pages/second/"; true');
        await sleep(50);
        const refusedSiblingNavigation = !!frame('first');
        const layouts = [];
        for (const width of [900, 500, 280]) {
          await reader.show(target, { x: 0, y: 0, width, height: 760 });
          await fitted(width);
          layouts.push(await page('first', \`(() => {
            const contained = [...document.querySelectorAll('.cite')].every(cite => {
              const card = cite.closest('.card').getBoundingClientRect();
              return [...cite.getClientRects()].every(rect => rect.left >= card.left && rect.right <= card.right);
            });
            const pre = document.querySelector('pre');
            return document.documentElement.scrollWidth <= innerWidth && contained && pre.scrollWidth > pre.clientWidth;
          })()\`));
        }
        await reader.show(target, { x: 0, y: 0, width: 900, height: 760 });
        await fitted(900);
        if (process.env.BYTEQUAY_ARTIFACT_READER_CAPTURE) {
          await book('scrollTo(0,0); document.getElementById("index-toggle").click()');
          for (const theme of ['light', 'dark']) {
            nativeTheme.themeSource = theme;
            await sleep(150);
            require('node:fs').writeFileSync(process.env.BYTEQUAY_ARTIFACT_READER_CAPTURE + '-' + theme + '.png', (await contents.capturePage()).toPNG());
          }
          await book('document.getElementById("index-toggle").click()');
          nativeTheme.themeSource = 'light';
        }
        await book('document.getElementById("index-toggle").click(); [...document.querySelectorAll(".reader-index-heading")].filter(button => button.textContent === "Details")[1].click()');
        const sectionJump = await until(() => book('scrollY > 700 && document.querySelector("[aria-current]")?.textContent === "Details"'));
        await book('scrollTo(0,0)');
        await page('first', 'document.querySelector("a").click()');
        const anchorJump = await until(() => book('scrollY > 700'));
        const before = await book('document.querySelector("iframe").offsetHeight');
        await page('first', 'document.getElementById("expand").click()');
        const grew = await until(async () => (await book('document.querySelector("iframe").offsetHeight')) > before + 400);
        await book('scrollTo(0, document.querySelectorAll(".reader-chapter")[1].offsetTop - 44)');
        const nextPage = await until(() => book('document.getElementById("reader-position").textContent === "2 of 2" && document.querySelector("[aria-current]")?.textContent === "2. Second chapter"'));
        await page('second', 'document.querySelector("input").value = "Still here"');
        await book('document.getElementById("index-toggle").click(); document.querySelector(".reader-index-page").click()');
        const keptInteraction = await page('first', '!document.getElementById("extra").hidden');
        await book('const select = document.querySelector("select"); select.value = "old"; select.dispatchEvent(new Event("change"));');
        await until(() => frame('old'));
        await until(() => book('document.querySelector(".reader-index-heading")?.textContent === "Original section"'));
        const keptSecond = await page('second', 'document.querySelector("input").value');
        const history = await page('old', 'document.querySelector("h1").textContent');
        // A transcript jump uses the same reader and does not reset the other chapter.
        await reader.show({ ...target, pageId: 'first' }, { x: 0, y: 0, width: 900, height: 760 });
        await until(() => frame('first'));
        const sameReader = contents === reader.view.webContents;
        const stillKept = await page('second', 'document.querySelector("input").value');
        const results = { index, isolated, noParent, failedPage, refusedSiblingNavigation, layouts, sectionJump, anchorJump, grew, nextPage, keptInteraction, keptSecond, history, sameReader, stillKept };
        reader.releaseWindow();`,
      timeoutMs: 40_000,
    });
    expect(result).toEqual({
      index: ['1. First chapter', 'Comparison', 'Connector', 'Engine', 'Details', 'Details', '2. Second chapter', 'Next steps'],
      isolated: true, noParent: true, failedPage: 'This page could not be opened.', refusedSiblingNavigation: true,
      layouts: [true, true, true], sectionJump: true, anchorJump: true, grew: true, nextPage: true,
      keptInteraction: true, keptSecond: 'Still here', history: 'First edition', sameReader: true, stillKept: 'Still here',
    });
  }
  finally { await rm(directory, { recursive: true, force: true }); }
}, deadline(60_000));
