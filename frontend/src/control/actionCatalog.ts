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

/** Action catalog for the Phase-9 control bar.
 *
 *  <p>The full design carries a hierarchical tag grammar
 *  ({@code #repo-byteQuay-pr-1234}), action verbs ({@code :go},
 *  {@code :open}, {@code :create}, {@code :ship}, …), an AI-
 *  interpreted FROM AI section, page-element registry, action preview,
 *  and undo. This MVP catalog is the navigation-only subset — actions
 *  carry a label, a description, and an {@code invoke} that calls back
 *  into the host with a typed dispatch payload. The host (App.tsx)
 *  routes the dispatch into its existing {@code setNav} state machine
 *  so the catalog stays decoupled from React-Router-or-whatever.
 *
 *  <p>Verb / tag grammar lands in a follow-up; this commit's job is to
 *  prove the bar surface — ⌘K opens it, query filters, Enter executes. */

export type ControlAction = {
  id: string;
  label: string;
  description: string;
  /** Keywords searched alongside the label so "memory" matches the
   *  "Open WORKSPACE.md" action even though the label doesn't
   *  literally contain the word. */
  keywords: string[];
  /** Icon glyph — purposely Unicode so the catalog can ship without a
   *  binary asset pipeline. The frontend uses these as the leading
   *  chip on each result row. */
  icon: string;
  /** Source category — drives the section heading in the suggestion
   *  list. The design's "FROM AI" suggestions go in a separate
   *  catalog when the AI wiring lands. */
  source: 'navigation' | 'create';
  /** Typed dispatch handed back to the host. The host owns the
   *  navigation state, so the catalog stays free of React imports. */
  dispatch: ControlDispatch;
};

export type ControlDispatch =
  | { kind: 'nav.home' }
  | { kind: 'nav.workspace'; section: 'home' | 'threads' | 'memory' | 'insights' | 'settings' }
  | { kind: 'nav.threads' }
  | { kind: 'nav.pull-requests' }
  | { kind: 'nav.repos' }
  | { kind: 'nav.email' }
  | { kind: 'nav.notifications' }
  | { kind: 'nav.settings'; section?: 'help' }
  | { kind: 'create.thread' };

/** Stable order — the catalog renders unfiltered queries in this
 *  order, and filtered queries sort by score then by this index for
 *  ties so the bar's "default top hit" stays predictable. */
export const ACTION_CATALOG: ControlAction[] = [
  {
    id: 'nav.workspace.home',
    label: 'Go to Workspace home',
    description: 'Open the workspace overview — active threads, tasks in flight, memory snippet.',
    keywords: ['workspace', 'home', 'overview', 'dashboard'],
    icon: '◆',
    source: 'navigation',
    dispatch: { kind: 'nav.workspace', section: 'home' },
  },
  {
    id: 'nav.workspace.memory',
    label: 'Open WORKSPACE.md',
    description: 'Workspace memory editor — distilled facts the AI loads into every thread.',
    keywords: ['memory', 'workspace.md', 'brain', 'distill'],
    icon: '◇',
    source: 'navigation',
    dispatch: { kind: 'nav.workspace', section: 'memory' },
  },
  {
    id: 'nav.workspace.insights',
    label: 'Go to Insights',
    description: 'KPI cards, spend chart, tasks shipped per repo.',
    keywords: ['insights', 'kpi', 'spend', 'stats'],
    icon: '△',
    source: 'navigation',
    dispatch: { kind: 'nav.workspace', section: 'insights' },
  },
  {
    id: 'nav.workspace.settings',
    label: 'Workspace settings',
    description: 'Repositories, AI defaults, behavior, danger zone.',
    keywords: ['settings', 'workspace', 'repos', 'ai defaults'],
    icon: '●',
    source: 'navigation',
    dispatch: { kind: 'nav.workspace', section: 'settings' },
  },
  {
    id: 'nav.threads',
    label: 'Go to Threads',
    description: 'View all running and idle threads.',
    keywords: ['threads', 'list', 'inbox'],
    icon: '▢',
    source: 'navigation',
    dispatch: { kind: 'nav.threads' },
  },
  {
    id: 'nav.pull-requests',
    label: 'Go to Pull requests',
    description: 'PRs awaiting your review + PRs you authored.',
    keywords: ['pull requests', 'prs', 'review'],
    icon: '↗',
    source: 'navigation',
    dispatch: { kind: 'nav.pull-requests' },
  },
  {
    id: 'nav.notifications',
    label: 'Go to Notifications',
    description: 'Bell — auto-fix progress, parked review panels, publish-gate audit.',
    keywords: ['notifications', 'bell', 'awaiting'],
    icon: '☉',
    source: 'navigation',
    dispatch: { kind: 'nav.notifications' },
  },
  {
    id: 'nav.repos',
    label: 'Go to Repos',
    description: 'Local clones — repository detail + diff browser.',
    keywords: ['repos', 'repositories', 'local'],
    icon: '▣',
    source: 'navigation',
    dispatch: { kind: 'nav.repos' },
  },
  {
    id: 'nav.email',
    label: 'Go to Email',
    description: 'Inbox — Gmail thread surface.',
    keywords: ['email', 'gmail', 'inbox'],
    icon: '✉',
    source: 'navigation',
    dispatch: { kind: 'nav.email' },
  },
  {
    id: 'nav.home',
    label: 'Go to Home',
    description: 'The top-level home (PR inbox + activity).',
    keywords: ['home', 'app home', 'activity'],
    icon: '⌂',
    source: 'navigation',
    dispatch: { kind: 'nav.home' },
  },
  {
    id: 'nav.settings',
    label: 'App settings',
    description: 'Account, credentials, AI review, integrations, workspace memory…',
    keywords: ['settings', 'account', 'credentials'],
    icon: '⚙',
    source: 'navigation',
    dispatch: { kind: 'nav.settings' },
  },
  {
    id: 'create.issue-report',
    label: 'Report a ByteQuay bug',
    description: 'Quickly file a product issue in the ByteQuay GitHub repository.',
    keywords: ['bug', 'issue', 'feedback', 'help', 'report'],
    icon: '⚑',
    source: 'create',
    dispatch: { kind: 'nav.settings', section: 'help' },
  },
  {
    id: 'create.thread',
    label: 'New thread',
    description: 'Start a new build / discussion thread in this workspace.',
    keywords: ['new', 'thread', 'create', 'start', 'task'],
    icon: '+',
    source: 'create',
    dispatch: { kind: 'create.thread' },
  },
];

/** Verbs the bar understands as the first token. Per the
 *  main-control-bar design, verbs are {@code :}-prefixed and
 *  selectively constrain which actions are considered.
 *
 *  <ul>
 *    <li>{@code :go} — navigation only</li>
 *    <li>{@code :open} — same as {@code :go} for now; design
 *        distinguishes "open in pane" from "navigate fully" but
 *        we don't have panes yet</li>
 *    <li>{@code :create} — creation actions only</li>
 *  </ul>
 *
 *  <p>Anything not prefixed by a known verb falls through to
 *  fuzzy substring search across the full catalog. */
const VERBS: Record<string, ControlAction['source'][]> = {
  ':go': ['navigation'],
  ':open': ['navigation'],
  ':create': ['create'],
};

/** Substring-then-keyword filter. Returns an ordered subset of the
 *  catalog; unmatched queries return everything in catalog order so
 *  the bar still shows the user something to navigate.
 *
 *  <p>A leading {@code :go} / {@code :open} / {@code :create} verb
 *  narrows the catalog by source before the substring filter runs,
 *  so {@code ":go threads"} only returns nav rows and
 *  {@code ":create"} surfaces just the creation row(s). */
export function filterCatalog(query: string): ControlAction[] {
  const raw = query.trim();
  if (raw.length === 0) return ACTION_CATALOG;
  // Verb prefix: strip the verb and constrain the catalog by source.
  let catalog = ACTION_CATALOG;
  let body = raw;
  if (body.startsWith(':')) {
    const space = body.indexOf(' ');
    const verb = (space === -1 ? body : body.slice(0, space)).toLowerCase();
    if (VERBS[verb]) {
      const sources = new Set(VERBS[verb]);
      catalog = ACTION_CATALOG.filter(a => sources.has(a.source));
      body = space === -1 ? '' : body.slice(space + 1).trim();
      // Bare verb (no body) returns the verb-narrowed catalog as-is.
      if (body.length === 0) return catalog;
    }
  }
  const q = body.toLowerCase();
  const tokens = q.split(/\s+/).filter(t => t.length > 0);
  type Scored = { action: ControlAction; score: number };
  const scored: Scored[] = [];
  for (const action of catalog) {
    const label = action.label.toLowerCase();
    const keywords = action.keywords.map(k => k.toLowerCase());
    const description = action.description.toLowerCase();
    let score = 0;
    let allMatch = true;
    for (const token of tokens) {
      // Tiered weights so the most-intentional match wins. Label
      // beats keyword beats description — so "memory" surfaces
      // "Open WORKSPACE.md" (keyword: memory) ahead of "Workspace
      // home" (description: "…memory snippet").
      if (label.includes(token)) score += 3;
      else if (keywords.some(k => k.includes(token))) score += 2;
      else if (description.includes(token)) score += 1;
      else { allMatch = false; break; }
    }
    if (allMatch) scored.push({ action, score });
  }
  scored.sort((a, b) => b.score - a.score);
  return scored.map(s => s.action);
}
