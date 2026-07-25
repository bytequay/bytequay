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

/**
 * The public renderer routes introduced by workspace unification. They are
 * intentionally independent from App's older in-memory Nav union so the
 * migration can keep compatibility routes until their last caller is gone.
 */
export type WorkspaceRoute =
  | { kind: 'home' }
  | { kind: 'reviews' }
  | { kind: 'workspaces' }
  | { kind: 'workspace'; workspaceId: string }
  | { kind: 'trunks'; workspaceId: string; trunkId?: string }
  | {
      kind: 'pull-request';
      workspaceId: string;
      number?: number;
      prId?: string;
      agentColumn?: boolean;
    }
  | { kind: 'issue'; workspaceId: string; number?: number }
  | { kind: 'session'; workspaceId: string; sessionId?: string }
  | { kind: 'backlog'; workspaceId: string; key?: string }
  | { kind: 'branches'; workspaceId: string; name?: string }
  | { kind: 'commits'; workspaceId: string }
  | { kind: 'ci-harness'; workspaceId: string; watchId?: string }
  | { kind: 'memory'; workspaceId: string }
  | { kind: 'insights'; workspaceId: string }
  | { kind: 'notifications'; workspaceId: string }
  | { kind: 'settings'; workspaceId: string; section?: string }
  | { kind: 'legacy-repo'; owner?: string; repo?: string; page?: 'pulls' | 'issues' | 'branches' };

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
export function parseWorkspaceRoute(value: string): WorkspaceRoute {
  const raw = value.trim().replace(/^#/, '');
  const queryAt = raw.indexOf('?');
  const logical = (queryAt < 0 ? raw : raw.slice(0, queryAt)).replace(/^\/+|\/+$/g, '');
  const query = new URLSearchParams(queryAt < 0 ? '' : raw.slice(queryAt + 1));
  if (logical === '' || logical === 'home') return { kind: 'home' };
  if (logical === 'reviews') return { kind: 'reviews' };
  if (logical === 'workspaces') return { kind: 'workspaces' };

  const parts = logical.split('/');
  if (parts[0] === 'repos') return { kind: 'legacy-repo' };
  if ((parts[0] === 'repo' || parts[0] === 'repository' || parts[0] === 'local-repo')
      && parts.length >= 3) {
    const owner = decode(parts[1]);
    const repo = decode(parts[2]);
    if (owner !== null && repo !== null) {
      const page = parts[3] === 'issues' || parts[3] === 'branches' ? parts[3] : 'pulls';
      return { kind: 'legacy-repo', owner, repo, page };
    }
  }

  if (parts[0] !== 'workspace' || parts.length < 2) return { kind: 'home' };
  const workspaceId = decode(parts[1]);
  if (workspaceId === null) return { kind: 'home' };
  if (parts.length === 2) return { kind: 'workspace', workspaceId };

  switch (parts[2]) {
    case 'trunks': {
      const trunkId = parts[3] === undefined ? undefined : decode(parts[3]) ?? undefined;
      return { kind: 'trunks', workspaceId, trunkId };
    }
    case 'prs': {
      const number = parts[3] === undefined ? null : positiveNumber(parts[3]);
      const prId = query.get('prId') || undefined;
      const agentColumn = query.get('agent') === '1' ? true : undefined;
      return {
        kind: 'pull-request',
        workspaceId,
        ...(number === null ? {} : { number }),
        ...(prId === undefined ? {} : { prId }),
        ...(agentColumn === undefined ? {} : { agentColumn }),
      };
    }
    case 'issues': {
      if (parts[3] === undefined) return { kind: 'issue', workspaceId };
      const number = positiveNumber(parts[3]);
      return number === null ? { kind: 'issue', workspaceId }
        : { kind: 'issue', workspaceId, number };
    }
    case 'sessions': {
      const sessionId = parts[3] === undefined ? undefined : decode(parts[3]) ?? undefined;
      return { kind: 'session', workspaceId, sessionId };
    }
    case 'backlog':
      return {
        kind: 'backlog',
        workspaceId,
        key: parts[3] === undefined ? undefined : decode(parts[3]) ?? undefined,
      };
    case 'branches':
      return {
        kind: 'branches',
        workspaceId,
        name: parts[3] === undefined ? undefined : decode(parts[3]) ?? undefined,
      };
    case 'commits': return { kind: 'commits', workspaceId };
    case 'ci-harness':
      return {
        kind: 'ci-harness',
        workspaceId,
        watchId: parts[3] === undefined ? undefined : decode(parts[3]) ?? undefined,
      };
    case 'memory': return { kind: 'memory', workspaceId };
    case 'insights': return { kind: 'insights', workspaceId };
    case 'notifications': return { kind: 'notifications', workspaceId };
    case 'settings':
      return {
        kind: 'settings',
        workspaceId,
        section: parts[3] === undefined ? undefined : decode(parts[3]) ?? undefined,
      };
    default: return { kind: 'workspace', workspaceId };
  }
}

const encoded = (value: string): string => encodeURIComponent(value);

export function workspaceRouteHash(route: WorkspaceRoute): string {
  switch (route.kind) {
    case 'home': return '#/home';
    case 'reviews': return '#/reviews';
    case 'workspaces': return '#/workspaces';
    case 'workspace': return `#/workspace/${encoded(route.workspaceId)}`;
    case 'trunks':
      return `#/workspace/${encoded(route.workspaceId)}/trunks${
        route.trunkId === undefined ? '' : `/${encoded(route.trunkId)}`}`;
    case 'pull-request': {
      const query = [];
      if (route.prId !== undefined) query.push(`prId=${encoded(route.prId)}`);
      if (route.agentColumn === true) query.push('agent=1');
      return `#/workspace/${encoded(route.workspaceId)}/prs${
        route.number === undefined ? '' : `/${route.number}`}${
        query.length === 0 ? '' : `?${query.join('&')}`}`;
    }
    case 'issue':
      return `#/workspace/${encoded(route.workspaceId)}/issues${
        route.number === undefined ? '' : `/${route.number}`}`;
    case 'session':
      return `#/workspace/${encoded(route.workspaceId)}/sessions${
        route.sessionId === undefined ? '' : `/${encoded(route.sessionId)}`}`;
    case 'backlog':
      return `#/workspace/${encoded(route.workspaceId)}/backlog${
        route.key === undefined ? '' : `/${encoded(route.key)}`}`;
    case 'branches':
      return `#/workspace/${encoded(route.workspaceId)}/branches${
        route.name === undefined ? '' : `/${encoded(route.name)}`}`;
    case 'commits': return `#/workspace/${encoded(route.workspaceId)}/commits`;
    case 'ci-harness':
      return `#/workspace/${encoded(route.workspaceId)}/ci-harness${
        route.watchId === undefined ? '' : `/${encoded(route.watchId)}`}`;
    case 'memory': return `#/workspace/${encoded(route.workspaceId)}/memory`;
    case 'insights': return `#/workspace/${encoded(route.workspaceId)}/insights`;
    case 'notifications': return `#/workspace/${encoded(route.workspaceId)}/notifications`;
    case 'settings':
      return `#/workspace/${encoded(route.workspaceId)}/settings${
        route.section === undefined ? '' : `/${encoded(route.section)}`}`;
    case 'legacy-repo':
      if (route.owner === undefined || route.repo === undefined) return '#/repos';
      return `#/repository/${encoded(route.owner)}/${encoded(route.repo)}/${route.page ?? 'pulls'}`;
  }
}
