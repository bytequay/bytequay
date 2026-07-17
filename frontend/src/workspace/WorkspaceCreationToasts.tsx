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
import { useCallback, useEffect, useRef, useState } from 'react';
import { workspaceApi, type WorkspaceCreationDto } from './workspaceApi';

type Props = {
  onOpenWorkspace: (workspaceId: string) => void;
};

const LIVE = new Set<WorkspaceCreationDto['state']>([
  'queued', 'forking', 'cloning', 'syncing',
]);

/** Global persisted clone/sync progress from source frame 6d. */
export default function WorkspaceCreationToasts({ onOpenWorkspace }: Props) {
  const [operations, setOperations] = useState<WorkspaceCreationDto[]>([]);
  const [visibleIds, setVisibleIds] = useState<Set<string>>(new Set());
  const [hiddenIds, setHiddenIds] = useState<Set<string>>(new Set());
  const mounted = useRef(true);

  const merge = useCallback((rows: WorkspaceCreationDto[]) => {
    if (!mounted.current) return;
    setOperations(rows);
    setVisibleIds(current => {
      const next = new Set(current);
      for (const operation of rows) {
        if (LIVE.has(operation.state) && !hiddenIds.has(operation.id)) {
          next.add(operation.id);
        }
      }
      return next;
    });
  }, [hiddenIds]);

  const refresh = useCallback(async () => {
    try {
      merge(await workspaceApi.creations());
    }
    catch {
      // Creation feedback is helpful but must not destabilize the app shell.
    }
  }, [merge]);

  useEffect(() => {
    mounted.current = true;
    void refresh();
    const timer = window.setInterval(() => { void refresh(); }, 1_000);
    const started = (event: Event) => {
      const operation = (event as CustomEvent<WorkspaceCreationDto>).detail;
      setOperations(current => [
        operation,
        ...current.filter(row => row.id !== operation.id),
      ]);
      setHiddenIds(current => {
        const next = new Set(current);
        next.delete(operation.id);
        return next;
      });
      setVisibleIds(current => new Set(current).add(operation.id));
    };
    window.addEventListener('bytequay:workspace-creation-started', started);
    return () => {
      mounted.current = false;
      window.clearInterval(timer);
      window.removeEventListener('bytequay:workspace-creation-started', started);
    };
  }, [refresh]);

  const visible = operations
    .filter(operation => visibleIds.has(operation.id) && !hiddenIds.has(operation.id))
    .slice(0, 3);

  if (visible.length === 0) return null;

  const hide = (id: string) => {
    setHiddenIds(current => new Set(current).add(id));
    setVisibleIds(current => {
      const next = new Set(current);
      next.delete(id);
      return next;
    });
  };

  return (
    <aside className="wu-creation-toasts" aria-live="polite" aria-label="Workspace setup progress">
      {visible.map(operation => (
        <article
          key={operation.id}
          className={`wu-creation-toast wu-creation-toast--${operation.state}`}
        >
          <span className="wu-creation-toast__state" aria-hidden>
            {operation.state === 'ready'
              ? <CheckIcon />
              : operation.state === 'failed'
                ? <ErrorIcon />
                : <i />}
          </span>
          <span className="wu-creation-toast__copy">
            <strong>{creationTitle(operation)}</strong>
            <small>{creationDetail(operation)}</small>
          </span>
          <span className="wu-creation-toast__actions">
            {operation.state === 'failed' && (
              <button
                type="button"
                className="primary"
                onClick={() => {
                  void workspaceApi.retryCreation(operation.id).then(retried => {
                    setOperations(current => current.map(row =>
                      row.id === retried.id ? retried : row));
                  });
                }}
              >
                Retry
              </button>
            )}
            {operation.workspaceId !== null && operation.state === 'ready' && (
              <button
                type="button"
                className={operation.state === 'ready' ? 'primary' : ''}
                onClick={() => onOpenWorkspace(operation.workspaceId as string)}
              >
                Open
              </button>
            )}
            {operation.state !== 'ready' && (
              <button type="button" className="quiet" onClick={() => hide(operation.id)}>
                Hide
              </button>
            )}
          </span>
        </article>
      ))}
    </aside>
  );
}

function creationTitle(operation: WorkspaceCreationDto): string {
  const name = operation.repo;
  if (operation.operationKind === 'reclone') {
    if (operation.state === 'ready') return `${name} was safely re-cloned`;
    if (operation.state === 'failed') return `Couldn't re-clone ${name}`;
    return `Re-cloning ${name}…`;
  }
  if (operation.state === 'ready') return `${name} is ready`;
  if (operation.state === 'failed') return `Couldn't connect ${name}`;
  if (operation.state === 'syncing') return `Syncing ${name}…`;
  if (operation.state === 'forking') return `Forking ${name}…`;
  if (operation.state === 'queued') return `Preparing ${name}…`;
  return `Cloning ${name}…`;
}

function creationDetail(operation: WorkspaceCreationDto): string {
  if (operation.state === 'ready') {
    return operation.operationKind === 'reclone'
      ? 'replacement verified · previous clone retained as backup'
      : operation.stageMessage ?? 'clone + first sync complete';
  }
  if (operation.state === 'failed') {
    return operation.errorMessage ?? operation.stageMessage ?? 'Workspace setup failed';
  }
  const current = operation.stageMessage ?? operation.state;
  return `${current} ${progress(operation)}%`;
}

function progress(operation: WorkspaceCreationDto): number {
  if (operation.state === 'ready') return 100;
  if (operation.progressTotal <= 0) return 8;
  return Math.max(5, Math.min(95,
    Math.round(operation.progressCurrent / operation.progressTotal * 100)));
}

function CheckIcon() {
  return <svg viewBox="0 0 24 24"><path d="M20 6 9 17l-5-5" /></svg>;
}

function ErrorIcon() {
  return <svg viewBox="0 0 24 24"><path d="m6 6 12 12M18 6 6 18" /></svg>;
}
