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
 * A workspace is one local checkout plus which of its remotes is the
 * read-only linked upstream.
 */
export type Workspace = {
  id: string;
  name: string;
  path: string;
  defaultBranch: string;
  /** Remote the workspace pushes to; the cherry-pick target. */
  originRemote: string;
  /** Read-only cherry-pick source, or null when nothing is linked. */
  upstreamRemote: string | null;
};

// ponytail: workspaces are machine-local checkout paths, so localStorage is
// the whole store; move to the backend if they ever need to sync.
const STORE_KEY = 'bytequay.workspaces';

export function loadWorkspaces(): Workspace[] {
  try {
    const value = JSON.parse(localStorage.getItem(STORE_KEY) ?? '[]') as unknown;
    if (!Array.isArray(value)) return [];
    return value.filter((entry): entry is Workspace =>
      typeof entry === 'object' && entry !== null
      && typeof (entry as Workspace).id === 'string'
      && typeof (entry as Workspace).path === 'string');
  } catch {
    return [];
  }
}

export function saveWorkspaces(workspaces: Workspace[]) {
  localStorage.setItem(STORE_KEY, JSON.stringify(workspaces));
}

export function upsertWorkspace(workspace: Workspace): Workspace[] {
  const next = loadWorkspaces().filter(entry => entry.id !== workspace.id);
  next.push(workspace);
  saveWorkspaces(next);
  return next;
}

export function removeWorkspace(id: string): Workspace[] {
  const next = loadWorkspaces().filter(entry => entry.id !== id);
  saveWorkspaces(next);
  return next;
}

/** "owner/repo" from a GitHub remote URL, or null for non-GitHub remotes. */
export function githubSlug(url: string): string | null {
  const match = /github\.com[:/]+([^/]+)\/([^/]+?)(?:\.git)?\/?$/.exec(url.trim());
  return match ? `${match[1]}/${match[2]}` : null;
}

/** The linked upstream defaults to a remote literally named "upstream". */
export function guessUpstreamRemote(remotes: Array<{ name: string }>): string | null {
  if (remotes.some(remote => remote.name === 'upstream')) return 'upstream';
  const other = remotes.find(remote => remote.name !== 'origin');
  return other?.name ?? null;
}
