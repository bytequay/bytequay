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
import { useEffect, useState } from 'react';
import type { ManagedClonePlanDto, ManagedRepoWriteMode } from '../types';
import { workspaceApi, type WorkspaceCreationDto } from '../workspace/workspaceApi';

type Props = {
  owner: string;
  repo: string;
  onClose: () => void;
  onStarted: (operation: WorkspaceCreationDto) => void;
};

function AddRepoModal({ owner, repo, onClose, onStarted }: Props) {
  const [plan, setPlan] = useState<ManagedClonePlanDto | null>(null);
  const [writeMode, setWriteMode] = useState<ManagedRepoWriteMode>('FORK');
  const [loading, setLoading] = useState(true);
  const [cloning, setCloning] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    void window.bridge.getManagedClonePlan(owner, repo)
      .then(p => {
        if (cancelled) return;
        setPlan(p);
        setWriteMode(p.defaultWriteMode);
      })
      .catch(e => {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => { cancelled = true; };
  }, [owner, repo]);

  const selectedAvailable = plan != null
      && (writeMode === 'FORK' ? plan.forkAvailable : plan.directAvailable);

  const submitClone = async () => {
    if (!selectedAvailable || cloning) return;
    setError(null);
    setCloning(true);
    try {
      const operation = await workspaceApi.createWorkspace(owner, repo, writeMode);
      onStarted(operation);
      window.dispatchEvent(new CustomEvent(
        'bytequay:workspace-creation-started',
        { detail: operation },
      ));
      onClose();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setCloning(false);
    }
  };

  return (
    <div className="add-repo-modal-backdrop" onClick={cloning ? undefined : onClose}>
      <div className="add-repo-modal" onClick={(e) => e.stopPropagation()}>
        <header className="add-repo-modal__head">
          <h2 className="add-repo-modal__title">
            Add <code>{owner}/{repo}</code>
          </h2>
          <button
            type="button"
            className="add-repo-modal__close"
            onClick={onClose}
            aria-label="Close"
            disabled={cloning}
          >
            x
          </button>
        </header>

        {loading && (
          <div className="add-repo-modal__loading">Checking repository access...</div>
        )}

        {!loading && plan && (
          <div className="add-repo-modal__form">
            <div className="add-repo-modal__choices">
              <WriteModeChoice
                title="Use my fork"
                checked={writeMode === 'FORK'}
                disabled={!plan.forkAvailable || cloning}
                onSelect={() => setWriteMode('FORK')}
                detail={plan.forkAvailable
                  ? `Push branches to ${plan.viewerLogin}/${repo}; open PRs against ${owner}/${repo}. ByteQuay will create the fork if needed.`
                  : 'This repo is already owned by the signed-in user.'}
              />
              <WriteModeChoice
                title="Write directly"
                checked={writeMode === 'DIRECT'}
                disabled={!plan.directAvailable || cloning}
                onSelect={() => setWriteMode('DIRECT')}
                detail={plan.directAvailable
                  ? `Push branches directly to ${owner}/${repo}.`
                  : `No write permission for ${owner}/${repo}.`}
              />
            </div>

            <div className="add-repo-modal__managed-path">
              <span className="add-repo-modal__label">Managed clone</span>
              <code>{plan.destination}</code>
            </div>

            {error && <div className="add-repo-modal__error">{error}</div>}

            <footer className="add-repo-modal__actions">
              <button
                type="button"
                className="button button--secondary button--sm"
                onClick={onClose}
                disabled={cloning}
              >
                Cancel
              </button>
              <button
                type="button"
                className="button button--primary button--sm"
                onClick={() => { void submitClone(); }}
                disabled={cloning || !selectedAvailable}
              >
                {cloning ? 'Starting…' : 'Clone into ByteQuay'}
              </button>
            </footer>
          </div>
        )}

        {!loading && !plan && error && (
          <div className="add-repo-modal__form">
            <div className="add-repo-modal__error">{error}</div>
            <footer className="add-repo-modal__actions">
              <button
                type="button"
                className="button button--secondary button--sm"
                onClick={onClose}
              >
                Close
              </button>
            </footer>
          </div>
        )}
      </div>
    </div>
  );
}

function WriteModeChoice({
  title,
  detail,
  checked,
  disabled,
  onSelect,
}: {
  title: string;
  detail: string;
  checked: boolean;
  disabled: boolean;
  onSelect: () => void;
}) {
  return (
    <button
      type="button"
      className={`add-repo-choice${checked ? ' add-repo-choice--active' : ''}`}
      onClick={onSelect}
      disabled={disabled}
    >
      <div className="add-repo-choice__title">
        <input type="radio" checked={checked} readOnly tabIndex={-1} />
        <span>{title}</span>
      </div>
      <div className="add-repo-choice__sub">{detail}</div>
    </button>
  );
}

export default AddRepoModal;
