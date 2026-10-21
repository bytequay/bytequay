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

/** Every page of the settings window, in sidebar order. */
export type SettingsSection =
  | 'general'
  | 'appearance'
  | 'browser'
  | 'models'
  | 'skills'
  | 'workflow'
  | 'features'
  | 'github'
  | 'jira'
  | 'google'
  | 'lab'
  | 'ws-repository'
  | 'ws-agents'
  | 'ws-token'
  | 'ws-advanced';

export type SettingsGroup = 'Personal' | 'Agents' | 'Integrations' | 'Workspace';

export type SettingsEntry = {
  section: SettingsSection;
  group: SettingsGroup;
  label: string;
  /** The path segment after the group, e.g. `models` in `#/settings/agents/models`. */
  slug: string;
  /** Row titles this page renders, so the sidebar search can match a setting the user remembers by name. */
  rows: string[];
};

const GROUP_SLUGS: Record<SettingsGroup, string> = {
  Personal: 'personal',
  Agents: 'agents',
  Integrations: 'integrations',
  Workspace: 'workspace',
};

export const SETTINGS_ENTRIES: SettingsEntry[] = [
  { section: 'workflow', group: 'Agents', label: 'Workflow', slug: 'workflow', rows: ['Automation', 'Auto-Commit', 'Auto-Draft PR', 'CI fix', 'Auto-Review fix', 'Auto-merge', 'Auto-merge delay', 'CI repair agent'] },
  { section: 'features', group: 'Agents', label: 'Features', slug: 'features', rows: ['Today', 'Enable daily agenda', 'Daily agenda', 'Make it at', 'Working days',
    "Use my calendar's busy times", 'Assistant', 'Enable Assistant', 'Assistant can start tasks', 'Assistant can answer tasks',
    'Assistant can change Epics and work items', 'Assistant can acknowledge and snooze items'] },
  {
    section: 'general',
    group: 'Personal',
    label: 'General',
    slug: 'general',
    rows: ['Setup guide', 'Background refresh', 'Quiet hours', 'Keep detailed task runtime', 'Keep what you touched', 'Clear history', 'Recent'],
  },
  { section: 'appearance', group: 'Personal', label: 'Appearance', slug: 'appearance', rows: ['Theme'] },
  { section: 'browser', group: 'Personal', label: 'Browser', slug: 'browser', rows: ['Full screen behavior', 'Zoom'] },
  {
    section: 'models',
    group: 'Agents',
    label: 'Models',
    slug: 'models',
    rows: ['Development', 'Review', 'Providers', 'API keys', 'Add API key'],
  },
  { section: 'skills', group: 'Agents', label: 'Skills', slug: 'skills', rows: ['Your instructions', 'Add skill'] },
  {
    section: 'github',
    group: 'Integrations',
    label: 'GitHub',
    slug: 'github',
    rows: ['Account', 'PR details kept ready', 'Notification summaries'],
  },
  { section: 'google', group: 'Integrations', label: 'Google Apps', slug: 'google', rows: ['Gmail', 'Calendar', 'Gemini', 'API key', 'Conversation', 'Accounts', 'Add account', 'Sign in', 'Connector', 'Agent access'] },
  { section: 'jira', group: 'Integrations', label: 'Jira', slug: 'jira', rows: ['Accounts', 'Add account', 'Reconnect', 'Sites'] },
  { section: 'lab', group: 'Integrations', label: 'Lab', slug: 'lab', rows: ['Show the Lab', 'Open Lab'] },
  {
    section: 'ws-repository',
    group: 'Workspace',
    label: 'Repository',
    slug: 'repository',
    rows: ['Linked upstream', 'Related workspaces', 'Repo line', 'Default target branch'],
  },
  {
    section: 'ws-agents',
    group: 'Workspace',
    label: 'Model overrides',
    slug: 'agents',
    rows: ['Development', 'Review', 'Providers'],
  },
  { section: 'ws-token', group: 'Workspace', label: 'Token', slug: 'token', rows: ['GitHub token'] },
  {
    section: 'ws-advanced',
    group: 'Workspace',
    label: 'Advanced',
    slug: 'advanced',
    rows: ['Unwatch workspace', 'Delete local data'],
  },
];

export const SETTINGS_GROUPS: SettingsGroup[] = ['Personal', 'Agents', 'Integrations', 'Workspace'];

export function isWorkspaceSection(section: SettingsSection): boolean {
  return section.startsWith('ws-');
}

export function entryFor(section: SettingsSection): SettingsEntry {
  const entry = SETTINGS_ENTRIES.find(candidate => candidate.section === section);
  if (entry === undefined) throw new Error(`unknown settings section: ${section}`);
  return entry;
}

/**
 * Builds the hash for one page. A workspace page carries the workspace it
 * edits, so a link can send someone to another workspace's settings without
 * first switching the app to it.
 */
export function settingsRoute(section: SettingsSection, workspaceId?: string | null): string {
  const entry = entryFor(section);
  if (!isWorkspaceSection(section)) {
    return `#/settings/${GROUP_SLUGS[entry.group]}/${entry.slug}`;
  }
  return workspaceId
    ? `#/settings/workspace/${encodeURIComponent(workspaceId)}/${entry.slug}`
    : `#/settings/workspace/${entry.slug}`;
}

/**
 * Reads a settings hash. Returns null for anything that is not a settings
 * route, and a null section for a bare `#/settings` so the caller can restore
 * the last-open page instead of guessing one.
 */
export function settingsLocationFromRoute(hash: string): { section: SettingsSection | null; workspaceId: string | null } | null {
  const match = /^#\/settings(?:\/(.*))?$/.exec(hash.split('?')[0]);
  if (match === null) return null;
  const rest = (match[1] ?? '').split('/').filter(part => part.length > 0).map(decodeURIComponent);
  if (rest.length === 0) return { section: null, workspaceId: null };

  if (rest[0] === GROUP_SLUGS.Workspace) {
    // `workspace/<id>/<slug>` names a workspace; `workspace/<slug>` means the currently selected one.
    const slug = rest.length >= 3 ? rest[2] : rest[1];
    const workspaceId = rest.length >= 3 ? rest[1] : null;
    const entry = SETTINGS_ENTRIES.find(candidate => candidate.group === 'Workspace' && candidate.slug === slug);
    return entry === undefined ? { section: null, workspaceId: null } : { section: entry.section, workspaceId };
  }

  const entry = SETTINGS_ENTRIES.find(
    candidate => GROUP_SLUGS[candidate.group] === rest[0] && candidate.slug === rest[1],
  );
  return entry === undefined ? { section: null, workspaceId: null } : { section: entry.section, workspaceId: null };
}

/** Which page settings reopens on. Personal, this Mac, not worth a backend row. */
const LAST_SECTION_KEY = 'bytequay.settings.section';

export function readLastSection(): SettingsSection {
  try {
    const stored = window.localStorage.getItem(LAST_SECTION_KEY);
    if (stored !== null && SETTINGS_ENTRIES.some(entry => entry.section === stored)) {
      return stored as SettingsSection;
    }
  }
  catch { /* a private window has no storage; the default is as good an answer */ }
  return 'models';
}

export function rememberSection(section: SettingsSection): void {
  try { window.localStorage.setItem(LAST_SECTION_KEY, section); }
  catch { /* storage is a convenience here, never a requirement */ }
}

/** Sidebar filtering: a page matches when its own label matches, or when one of its rows does. */
export function matchesSearch(entry: SettingsEntry, query: string): boolean {
  const trimmed = query.trim().toLowerCase();
  if (trimmed === '') return true;
  return entry.label.toLowerCase().includes(trimmed)
    || entry.group.toLowerCase().includes(trimmed)
    || entry.rows.some(row => row.toLowerCase().includes(trimmed));
}
