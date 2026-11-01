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
import type { ArtifactReaderPage, ArtifactReaderTarget } from './contracts';
// eslint-disable-next-line import/no-unresolved -- Vite supplies the reader stylesheet as text.
import style from './reader.css?raw';

/** Only the app's reader owns navigation. Saved scripts run in separate, opaque-origin frames. */
export function artifactReader(target: ArtifactReaderTarget): string {
  const data = JSON.stringify({ pages: target.pages, pageId: target.pageId }).replaceAll('<', '\\u003c');
  return `<!doctype html><html><head><meta charset="utf-8"><link rel="stylesheet" href="theme.css"><style>${style}</style>
    <title>Artifact reader</title></head><body>
    <header class="reader-bar"><button id="index-toggle" aria-expanded="false" aria-controls="reader-index">Index</button>
    <span id="reader-title"></span><span id="reader-position" aria-live="polite"></span></header>
    <nav id="reader-index" aria-label="Table of contents" hidden></nav><div id="reader-pages"></div>
    <script>(${startReader.toString()})(${data})</script></body></html>`;
}

/** Append after the saved document so its own layout and interactive scripts stay intact. */
export function artifactReaderPage(html: string, pageId: string): string {
  return `${html}<style>html,body{height:auto!important;min-height:0!important}html{overflow:hidden}body{display:flow-root}</style>
    <script>(${reportPage.toString()})(${JSON.stringify(pageId).replaceAll('<', '\\u003c')})</script>`;
}

type Heading = { text: string; level: number; top: number };

// These functions are serialized into sandboxed documents; keep their runtime dependencies inside them.
function reportPage(pageId: string): void {
  let scheduled = false;
  const report = () => {
    scheduled = false;
    const body = document.body;
    const box = body.getBoundingClientRect();
    const headings = [...body.querySelectorAll<HTMLElement>('h1,h2,h3')].filter(heading => heading.getClientRects().length > 0)
      .map(heading => ({ text: (heading.textContent ?? '').trim(), level: Number(heading.tagName.slice(1)), top: heading.getBoundingClientRect().top + scrollY }));
    parent.postMessage({ type: 'artifact-layout', pageId, height: Math.ceil(box.top + scrollY + Math.max(box.height, body.scrollHeight)), headings }, '*');
  };
  // Chromium suspends animation frames in offscreen iframes; their size and index are still needed before scrolling to them.
  const schedule = () => { if (!scheduled) { scheduled = true; setTimeout(report, 0); } };
  new ResizeObserver(schedule).observe(document.body);
  new MutationObserver(schedule).observe(document.body, { childList: true, subtree: true, characterData: true, attributes: true });
  addEventListener('resize', schedule);
  addEventListener('load', schedule);
  document.fonts.ready.then(schedule);
  document.addEventListener('click', event => {
    if (event.target instanceof Element && event.target.closest('[data-artifact-retry]')) {
      parent.postMessage({ type: 'artifact-retry', pageId }, '*');
      return;
    }
    const link = event.target instanceof Element ? event.target.closest('a[href^="#"]') : null;
    if (!link) return;
    event.preventDefault();
    let id: string;
    try { id = decodeURIComponent(link.getAttribute('href')?.slice(1) ?? ''); } catch { return; }
    const anchor = id === '' ? document.body : document.getElementById(id) ?? document.getElementsByName(id)[0];
    if (anchor) parent.postMessage({ type: 'artifact-anchor', pageId, top: anchor.getBoundingClientRect().top + scrollY }, '*');
  });
  // Tabbing to an interactive control in a tall frame must move the outer book, too.
  document.addEventListener('focusin', event => {
    if (event.target instanceof HTMLElement) parent.postMessage({ type: 'artifact-focus', pageId, top: event.target.getBoundingClientRect().top + scrollY }, '*');
  });
  addEventListener('message', (event: MessageEvent<unknown>) => {
    if (event.source !== parent || !event.data || typeof event.data !== 'object') return;
    const message = event.data as Record<string, unknown>;
    if (message.type !== 'artifact-heading' || typeof message.top !== 'number') return;
    const heading = [...document.querySelectorAll<HTMLElement>('h1,h2,h3')].find(heading => Math.abs(heading.getBoundingClientRect().top + scrollY - Number(message.top)) < 1);
    if (heading) { heading.tabIndex = -1; heading.focus({ preventScroll: true }); }
  });
  report();
}

function startReader({ pages, pageId }: { pages: ArtifactReaderPage[]; pageId: string }): void {
  const root = document.getElementById('reader-pages') as HTMLElement;
  const index = document.getElementById('reader-index') as HTMLElement;
  const toggle = document.getElementById('index-toggle') as HTMLElement;
  const title = document.getElementById('reader-title') as HTMLElement;
  const position = document.getElementById('reader-position') as HTMLElement;
  const toolbar = document.querySelector('header') as HTMLElement;
  const groups = [...new Set(pages.map(page => page.key))];
  let pending: string | null = pageId;
  let ready: string | null = null;
  const chapters = groups.map((key, number) => {
    const versions = pages.filter(page => page.key === key).sort((a, b) => a.version - b.version);
    const selected = versions.find(page => page.id === pageId) ?? versions[versions.length - 1];
    const section = document.createElement('section');
    section.className = 'reader-chapter';
    const bar = document.createElement('div');
    bar.className = 'reader-chapter-bar';
    const label = document.createElement('span');
    label.textContent = `Page ${number + 1}`;
    const commit = document.createElement('span');
    commit.className = 'reader-commit';
    const select = document.createElement('select');
    select.setAttribute('aria-label', `Versions of ${selected.title}`);
    for (const page of versions) {
      const option = document.createElement('option');
      option.value = page.id;
      option.textContent = `v${page.version}`;
      select.append(option);
    }
    const frame = document.createElement('iframe');
    frame.setAttribute('sandbox', 'allow-scripts');
    frame.style.height = `${innerHeight}px`;
    const chapter = { section, frame, selected, versions, commit, select, headings: [] as Heading[], links: [] as HTMLButtonElement[], loaded: false };
    select.addEventListener('change', () => choose(select.value));
    bar.append(label, commit, select);
    section.append(bar, frame);
    root.append(section);
    return chapter;
  });
  type Chapter = typeof chapters[number];
  const closeIndex = () => { index.hidden = true; toggle.setAttribute('aria-expanded', 'false'); };
  toggle.addEventListener('click', () => { index.hidden = !index.hidden; toggle.setAttribute('aria-expanded', String(!index.hidden)); });
  document.addEventListener('keydown', event => { if (event.key === 'Escape' && !index.hidden) { closeIndex(); toggle.focus(); } });
  document.addEventListener('click', event => { if (event.target instanceof Node && !index.contains(event.target) && !toggle.contains(event.target)) closeIndex(); });
  const topOf = (element: Element) => element.getBoundingClientRect().top + scrollY;
  const jump = (chapter: Chapter, top = 0) => {
    scrollTo({ top: Math.max(0, topOf(chapter.frame) + top - toolbar.clientHeight - 16), behavior: 'instant' });
  };
  const complete = () => {
    if (ready !== null) {
      const selected = ready;
      ready = null;
      // Native captures must see the new frame's painted pixels, not the document it just replaced.
      requestAnimationFrame(() => requestAnimationFrame(() => window.dispatchEvent(new CustomEvent('artifact-ready', { detail: selected }))));
    }
  };
  const load = (chapter: Chapter, page: ArtifactReaderPage) => {
    chapter.selected = page;
    chapter.loaded = false;
    chapter.headings = [];
    chapter.select.value = page.id;
    chapter.select.setAttribute('aria-label', `Versions of ${page.title}`);
    chapter.commit.textContent = page.commit.slice(0, 7);
    chapter.commit.title = `Written from commit ${page.commit}`;
    chapter.frame.title = page.title;
    chapter.frame.src = `pages/${encodeURIComponent(page.id)}/`;
  };
  const choose = (id: string) => {
    const chapter = chapters.find(chapter => chapter.versions.some(page => page.id === id));
    if (!chapter) return;
    const page = chapter.versions.find(page => page.id === id);
    if (!page) return;
    pending = id;
    ready = id;
    if (chapter.selected.id !== id) {
      load(chapter, page);
      buildIndex();
    }
    else if (chapter.loaded) { jump(chapter); pending = null; complete(); }
    closeIndex();
  };
  window.addEventListener('artifact-select', event => {
    if (event instanceof CustomEvent && typeof event.detail === 'string') choose(event.detail);
  });
  const buildIndex = () => {
    index.replaceChildren();
    for (const [number, chapter] of chapters.entries()) {
      const group = document.createElement('div');
      const pageLink = document.createElement('button');
      pageLink.className = 'reader-index-page';
      pageLink.textContent = `${number + 1}. ${chapter.selected.title}`;
      pageLink.addEventListener('click', () => { pending = null; jump(chapter); closeIndex(); chapter.frame.focus({ preventScroll: true }); });
      group.append(pageLink);
      chapter.links = [pageLink];
      for (const heading of chapter.headings) {
        const button = document.createElement('button');
        button.className = `reader-index-heading is-h${heading.level}`;
        button.textContent = heading.text;
        button.addEventListener('click', () => {
          pending = null;
          jump(chapter, heading.top);
          closeIndex();
          chapter.frame.contentWindow?.postMessage({ type: 'artifact-heading', top: heading.top }, '*');
        });
        group.append(button);
        chapter.links.push(button);
      }
      index.append(group);
    }
    updatePosition();
  };
  const updatePosition = () => {
    const line = scrollY + toolbar.clientHeight + 32;
    const active = chapters.findLast(chapter => topOf(chapter.section) <= line) ?? chapters[0];
    if (title.textContent !== active.selected.title) title.textContent = active.selected.title;
    const progress = `${chapters.indexOf(active) + 1} of ${chapters.length}`;
    if (position.textContent !== progress) position.textContent = progress;
    if (pending === null && location.hash.slice(1) !== active.selected.id) location.hash = active.selected.id;
    for (const chapter of chapters) {
      const frameTop = topOf(chapter.frame);
      const heading = chapter.headings.findLastIndex(entry => frameTop + entry.top <= line);
      chapter.links.forEach((link, number) => {
        if (chapter === active && number === heading + 1) link.setAttribute('aria-current', 'location');
        else link.removeAttribute('aria-current');
      });
    }
  };
  let scrolling = false;
  addEventListener('scroll', () => {
    if (!scrolling) { scrolling = true; requestAnimationFrame(() => { scrolling = false; updatePosition(); }); }
  }, { passive: true });
  for (const name of ['wheel', 'touchstart', 'pointerdown', 'keydown']) addEventListener(name, () => { pending = null; }, { passive: true });
  addEventListener('message', (event: MessageEvent<unknown>) => {
    const chapter = chapters.find(chapter => chapter.frame.contentWindow === event.source);
    if (!chapter || !event.data || typeof event.data !== 'object') return;
    const message = event.data as Record<string, unknown>;
    if (message.pageId !== chapter.selected.id) return;
    if (message.type === 'artifact-retry') { load(chapter, chapter.selected); return; }
    if ((message.type === 'artifact-anchor' || message.type === 'artifact-focus') && typeof message.top === 'number' && Number.isFinite(message.top)) {
      const top = topOf(chapter.frame) + message.top;
      if (message.type === 'artifact-anchor' || top < scrollY + toolbar.clientHeight || top > scrollY + innerHeight - 48) jump(chapter, message.top);
      return;
    }
    if (message.type !== 'artifact-layout' || typeof message.height !== 'number' || !Number.isFinite(message.height) || !Array.isArray(message.headings)) return;
    const headings = message.headings.filter((heading: unknown): heading is Heading => {
      if (heading === null || typeof heading !== 'object') return false;
      const value = heading as Record<string, unknown>;
      return typeof value.text === 'string' && typeof value.level === 'number' && [1, 2, 3].includes(value.level)
        && typeof value.top === 'number' && Number.isFinite(value.top);
    })
      .filter(heading => heading.text !== '' && !(heading.level === 1 && heading.text === chapter.selected.title));
    const oldHeight = chapter.frame.offsetHeight;
    const above = topOf(chapter.frame) + oldHeight < scrollY + toolbar.clientHeight;
    chapter.frame.style.height = `${Math.max(1, message.height)}px`;
    if (above) scrollBy(0, chapter.frame.offsetHeight - oldHeight);
    chapter.loaded = true;
    if (JSON.stringify(headings) !== JSON.stringify(chapter.headings)) { chapter.headings = headings; buildIndex(); }
    const selected = chapters.find(chapter => chapter.selected.id === pending);
    if (selected) { jump(selected); if (chapters.every(chapter => chapter.loaded)) pending = null; }
    if (chapter.selected.id === ready) complete();
    updatePosition();
  });
  chapters.forEach(chapter => load(chapter, chapter.selected));
  buildIndex();
}
