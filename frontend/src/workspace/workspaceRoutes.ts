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
import type { SettingsSection } from '../settings/types';
import type {
  ProviderFilter as ThreadsProviderFilter,
  RepoFilter as ThreadsRepoFilter,
  StatusFilter as ThreadsStatusFilter,
} from '../threads/ThreadsLeftRail';
import type { WorkspaceSection } from './WorkspaceShell';

export type Nav =
  | { view: 'home' }
  | {
      view: 'pulls';
      initialPr?: { repo: string; number: number };
      initialReviewAction?: 'quick' | 'watch';
    }
  | {
      view: 'repo';
      owner: string;
      repo: string;
      prNumber?: number;
      initialTab?: 'pulls' | 'issues';
      diffCommitSha?: string;
      openDiff?: boolean;
      back?: Nav;
    }
  | { view: 'email' }
  | { view: 'thread-create'; initialGroupId?: string }
  | { view: 'thread-detail'; threadId: string; taskId?: string }
  | { view: 'task-brain'; threadId: string; taskId: string; initialPrSubTab?: 'changes' }
  | { view: 'stage-detail'; threadId: string; taskId: string; stageId: string }
  | { view: 'syncs'; jobId?: string }
  | { view: 'review-thread'; threadId: string; back?: Nav }
  | { view: 'notifications' }
  | { view: 'repos' }
  | { view: 'repository'; owner: string; repo: string }
  | { view: 'local-repo'; owner: string; repo: string; initialBranch?: string }
  | { view: 'settings'; section?: SettingsSection }
  | {
      view: 'workspace';
      section?: WorkspaceSection;
      prNumber?: number;
      prId?: string;
      agentColumn?: boolean;
      issueNumber?: number;
      sessionId?: string;
      backlogKey?: string;
      branchName?: string;
      settingsSection?: string;
      threadsFilter?: ThreadsStatusFilter;
      threadsProvider?: ThreadsProviderFilter;
      threadsGroupId?: string;
      threadsRepo?: ThreadsRepoFilter;
    }
  | { view: 'workspaces-landing' };

export type WorkspaceNavigation = { nav: Nav; workspaceId: string | null };

const navigation = (nav: Nav, workspaceId: string | null = null): WorkspaceNavigation => ({
  nav,
  workspaceId,
});

const decode = (value: string): string | null => {
  try {
    const decoded = decodeURIComponent(value);
    return decoded.length > 0 ? decoded : null;
  }
  catch {
    return null;
  }
};

const positiveNumber = (value: string): number | null => {
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null;
};

/** Parse both "#/…" and plain logical paths. Invalid paths fall home. */
export function parseWorkspaceRoute(value: string): WorkspaceNavigation {
  const raw = value.trim().replace(/^#/, '');
  const queryAt = raw.indexOf('?');
  const logical = (queryAt < 0 ? raw : raw.slice(0, queryAt)).replace(/^\/+|\/+$/g, '');
  const query = new URLSearchParams(queryAt < 0 ? '' : raw.slice(queryAt + 1));
  if (logical === '' || logical === 'home') return navigation({ view: 'home' });
  if (logical === 'reviews') return navigation({ view: 'pulls' });
  if (logical === 'workspaces') return navigation({ view: 'workspaces-landing' });

  const parts = logical.split('/');
  if (parts[0] === 'repos') return navigation({ view: 'workspaces-landing' });
  if ((parts[0] === 'repo' || parts[0] === 'repository' || parts[0] === 'local-repo')
      && parts.length >= 3) {
    const owner = decode(parts[1]);
    const repo = decode(parts[2]);
    if (owner !== null && repo !== null) {
      const page = parts[3] === 'issues' || parts[3] === 'branches' ? parts[3] : 'pulls';
      return navigation(page === 'branches'
        ? { view: 'local-repo', owner, repo }
        : { view: 'repo', owner, repo, initialTab: page });
    }
  }

  if (parts[0] !== 'workspace' || parts.length < 2) return navigation({ view: 'home' });
  const workspaceId = decode(parts[1]);
  if (workspaceId === null) return navigation({ view: 'home' });
  if (parts.length === 2) {
    return navigation({ view: 'workspace', section: 'today' }, workspaceId);
  }

  switch (parts[2]) {
    case 'trunks': {
      const trunkId = parts[3] === undefined ? undefined : decode(parts[3]) ?? undefined;
      return navigation(trunkId === undefined
        ? { view: 'workspace', section: 'trunks' }
        : { view: 'thread-detail', threadId: trunkId }, workspaceId);
    }
    case 'prs': {
      const number = parts[3] === undefined ? null : positiveNumber(parts[3]);
      const prId = query.get('prId') || undefined;
      const agentColumn = query.get('agent') === '1' ? true : undefined;
      return navigation({
        view: 'workspace',
        section: 'pull-requests',
        ...(number === null ? {} : { prNumber: number }),
        ...(prId === undefined ? {} : { prId }),
        ...(agentColumn === undefined ? {} : { agentColumn }),
      }, workspaceId);
    }
    case 'issues': {
      const number = parts[3] === undefined ? null : positiveNumber(parts[3]);
      return navigation({
        view: 'workspace',
        section: 'issues',
        ...(number === null ? {} : { issueNumber: number }),
      }, workspaceId);
    }
    case 'sessions':
      return navigation({
        view: 'workspace',
        section: 'sessions',
        sessionId: parts[3] === undefined ? undefined : decode(parts[3]) ?? undefined,
      }, workspaceId);
    case 'backlog':
      return navigation({
        view: 'workspace',
        section: 'backlog',
        backlogKey: parts[3] === undefined ? undefined : decode(parts[3]) ?? undefined,
      }, workspaceId);
    case 'branches':
      return navigation({
        view: 'workspace',
        section: 'branches',
        branchName: parts[3] === undefined ? undefined : decode(parts[3]) ?? undefined,
      }, workspaceId);
    case 'commits': return navigation({ view: 'workspace', section: 'commits' }, workspaceId);
    case 'syncs':
      return navigation({
        view: 'syncs',
        jobId: parts[3] === undefined ? undefined : decode(parts[3]) ?? undefined,
      }, workspaceId);
    case 'memory': return navigation({ view: 'workspace', section: 'memory' }, workspaceId);
    case 'insights': return navigation({ view: 'workspace', section: 'insights' }, workspaceId);
    case 'notifications':
      return navigation({ view: 'workspace', section: 'notifications' }, workspaceId);
    case 'settings':
      return navigation({
        view: 'workspace',
        section: 'settings',
        settingsSection: parts[3] === undefined ? 'agents' : decode(parts[3]) ?? 'agents',
      }, workspaceId);
    default: return navigation({ view: 'workspace', section: 'today' }, workspaceId);
  }
}

const encoded = (value: string): string => encodeURIComponent(value);

function workspaceSectionHash(nav: Extract<Nav, { view: 'workspace' }>, workspaceId: string): string {
  const base = `#/workspace/${encoded(workspaceId)}`;
  const section = nav.section === 'home' ? 'today'
    : nav.section === 'threads' ? 'trunks'
      : nav.section ?? 'today';
  switch (section) {
    case 'today': return base;
    case 'trunks': return `${base}/trunks`;
    case 'pull-requests': {
      const query = [];
      if (nav.prId !== undefined) query.push(`prId=${encoded(nav.prId)}`);
      if (nav.agentColumn === true) query.push('agent=1');
      return `${base}/prs${nav.prNumber === undefined ? '' : `/${nav.prNumber}`}${
        query.length === 0 ? '' : `?${query.join('&')}`}`;
    }
    case 'issues': return `${base}/issues${
      nav.issueNumber === undefined ? '' : `/${nav.issueNumber}`}`;
    case 'sessions': return `${base}/sessions${
      nav.sessionId === undefined ? '' : `/${encoded(nav.sessionId)}`}`;
    case 'backlog': return `${base}/backlog${
      nav.backlogKey === undefined ? '' : `/${encoded(nav.backlogKey)}`}`;
    case 'branches': return `${base}/branches${
      nav.branchName === undefined ? '' : `/${encoded(nav.branchName)}`}`;
    case 'commits': return `${base}/commits`;
    case 'memory': return `${base}/memory`;
    case 'insights': return `${base}/insights`;
    case 'notifications': return `${base}/notifications`;
    case 'settings': return `${base}/settings${
      nav.settingsSection === undefined ? '' : `/${encoded(nav.settingsSection)}`}`;
  }
}

export function workspaceRouteHash(nav: Nav, workspaceId: string): string;
export function workspaceRouteHash(nav: Nav, workspaceId: string | null): string | null;
export function workspaceRouteHash(nav: Nav, workspaceId: string | null): string | null {
  switch (nav.view) {
    case 'home': return '#/home';
    case 'pulls': return '#/reviews';
    case 'workspaces-landing': return '#/workspaces';
    case 'workspace':
      return workspaceId === null ? '#/workspaces' : workspaceSectionHash(nav, workspaceId);
    case 'thread-detail':
    case 'task-brain':
    case 'stage-detail':
      return workspaceId === null ? null
        : `#/workspace/${encoded(workspaceId)}/trunks/${encoded(nav.threadId)}`;
    case 'syncs':
      return workspaceId === null ? null
        : `#/workspace/${encoded(workspaceId)}/syncs${
          nav.jobId === undefined ? '' : `/${encoded(nav.jobId)}`}`;
    case 'repos': return '#/repos';
    case 'repo':
      return `#/repository/${encoded(nav.owner)}/${encoded(nav.repo)}/${nav.initialTab ?? 'pulls'}`;
    case 'repository':
      return `#/repository/${encoded(nav.owner)}/${encoded(nav.repo)}/pulls`;
    case 'local-repo':
      return `#/repository/${encoded(nav.owner)}/${encoded(nav.repo)}/branches`;
    default: return null;
  }
}
