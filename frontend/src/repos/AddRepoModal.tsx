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
import type { LocalRepoStatusDto } from '../types';

type Props = {
  owner: string;
  repo: string;
  onClose: () => void;
  onMapped: (status: LocalRepoStatusDto) => void;
};

type Mode = 'choose' | 'locate' | 'clone' | 'cloning';

/**
 * Add-repo modal — two stacked options for mapping a watched repo to a
 * local working copy: locate an existing folder on disk, or clone
 * fresh from the GitHub URL. Mirrors the design in
 * docs/mockups/local-repo-design.md (`bytequay_add_repo_modal_v1`).
 *
 * The same modal is opened by every entry point (UNMAPPED card on the
 * Repos page, future "Map clone…" CTAs elsewhere). Cancel closes
 * without writing anything; success calls onMapped with the refreshed
 * status row so the parent doesn't need to re-list.
 */
function AddRepoModal({ owner, repo, onClose, onMapped }: Props) {
  const [mode, setMode] = useState<Mode>('choose');
  const [error, setError] = useState<string | null>(null);
  const [destination, setDestination] = useState<string>('');
  const [locatedPath, setLocatedPath] = useState<string | null>(null);

  // Pre-fetch the default clone destination as soon as the user picks
  // the Clone-fresh option so the field is populated by the time they
  // see it. Cheap call; we don't bother caching across opens.
  useEffect(() => {
    if (mode !== 'clone' || destination !== '') return;
    let cancelled = false;
    void window.bridge.defaultClonePath(owner, repo)
      .then(p => { if (!cancelled) setDestination(p); })
      .catch(() => { /* fallback to empty; user can still type */ });
    return () => { cancelled = true; };
  }, [mode, owner, repo, destination]);

  const browseLocate = async () => {
    setError(null);
    try {
      const picked = await window.bridge.pickFolder({
        title: `Locate ${owner}/${repo} on disk`,
      });
      if (picked) setLocatedPath(picked);
    } catch (e) {
      // Most common cause of a silent failure here is a stale
      // preload script — the user updated ByteQuay but the
      // Electron app wasn't restarted, so `bridge.pickFolder` is
      // unavailable in this renderer. Surface the message so it's
      // not just a click-with-nothing-happening.
      setError(folderPickerErrorMessage(e));
    }
  };

  const browseClone = async () => {
    setError(null);
    try {
      const picked = await window.bridge.pickFolder({
        title: `Choose clone destination for ${owner}/${repo}`,
        defaultPath: destination || undefined,
      });
      if (picked) setDestination(picked);
    } catch (e) {
      setError(folderPickerErrorMessage(e));
    }
  };

  const submitLocate = async () => {
    if (!locatedPath) return;
    setError(null);
    try {
      const status = await window.bridge.locateRepo(owner, repo, locatedPath);
      onMapped(status);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const submitClone = async () => {
    if (!destination.trim()) return;
    setError(null);
    setMode('cloning');
    try {
      const status = await window.bridge.cloneRepo(owner, repo, destination.trim());
      onMapped(status);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setMode('clone');
    }
  };

  return (
    <div className="add-repo-modal-backdrop" onClick={onClose}>
      <div className="add-repo-modal" onClick={(e) => e.stopPropagation()}>
        <header className="add-repo-modal__head">
          <h2 className="add-repo-modal__title">
            Map a local clone for <code>{owner}/{repo}</code>
          </h2>
          <button
            type="button"
            className="add-repo-modal__close"
            onClick={onClose}
            aria-label="Close"
            disabled={mode === 'cloning'}
          >
            ✕
          </button>
        </header>

        {mode === 'choose' && (
          <div className="add-repo-modal__choices">
            <button
              type="button"
              className="add-repo-choice"
              onClick={() => setMode('locate')}
            >
              <div className="add-repo-choice__title">Locate existing folder</div>
              <div className="add-repo-choice__sub">
                Already cloned? Pick the folder and ByteQuay will verify
                its <code>origin</code> matches.
              </div>
            </button>
            <button
              type="button"
              className="add-repo-choice"
              onClick={() => setMode('clone')}
            >
              <div className="add-repo-choice__title">Clone fresh</div>
              <div className="add-repo-choice__sub">
                Run <code>git clone</code> against the GitHub URL into
                a destination of your choice.
              </div>
            </button>
          </div>
        )}

        {mode === 'locate' && (
          <div className="add-repo-modal__form">
            <label className="add-repo-modal__label">Folder</label>
            <div className="add-repo-modal__row">
              <input
                type="text"
                className="add-repo-modal__input"
                value={locatedPath ?? ''}
                onChange={(e) => setLocatedPath(e.target.value || null)}
                placeholder="/Users/you/code/airbyte"
              />
              <button
                type="button"
                className="button button--secondary button--sm"
                onClick={browseLocate}
              >
                Browse…
              </button>
            </div>
            <p className="add-repo-modal__hint">
              ByteQuay will check <code>git config --get remote.origin.url</code>{' '}
              and refuse to map a folder whose origin doesn't match
              <code> {owner}/{repo}</code>.
            </p>
            {error && <div className="add-repo-modal__error">{error}</div>}
            <footer className="add-repo-modal__actions">
              <button
                type="button"
                className="button button--secondary button--sm"
                onClick={() => setMode('choose')}
              >
                Back
              </button>
              <button
                type="button"
                className="button button--primary button--sm"
                onClick={() => { void submitLocate(); }}
                disabled={!locatedPath}
              >
                Map this folder
              </button>
            </footer>
          </div>
        )}

        {(mode === 'clone' || mode === 'cloning') && (
          <div className="add-repo-modal__form">
            <label className="add-repo-modal__label">Destination</label>
            <div className="add-repo-modal__row">
              <input
                type="text"
                className="add-repo-modal__input"
                value={destination}
                onChange={(e) => setDestination(e.target.value)}
                disabled={mode === 'cloning'}
              />
              <button
                type="button"
                className="button button--secondary button--sm"
                onClick={browseClone}
                disabled={mode === 'cloning'}
              >
                Change…
              </button>
            </div>
            <p className="add-repo-modal__hint">
              ByteQuay will run{' '}
              <code>git clone https://github.com/{owner}/{repo}.git</code>{' '}
              into this folder. Big repos can take a few minutes — the
              modal will stay open while it runs.
            </p>
            {error && <div className="add-repo-modal__error">{error}</div>}
            <footer className="add-repo-modal__actions">
              <button
                type="button"
                className="button button--secondary button--sm"
                onClick={() => setMode('choose')}
                disabled={mode === 'cloning'}
              >
                Back
              </button>
              <button
                type="button"
                className="button button--primary button--sm"
                onClick={() => { void submitClone(); }}
                disabled={mode === 'cloning' || !destination.trim()}
              >
                {mode === 'cloning' ? 'Cloning…' : 'Clone'}
              </button>
            </footer>
          </div>
        )}
      </div>
    </div>
  );
}

function folderPickerErrorMessage(e: unknown): string {
  const raw = e instanceof Error ? e.message : String(e);
  // The renderer sees a TypeError when bridge.pickFolder is missing
  // entirely (preload didn't expose it yet, or the app needs a
  // restart). Translate to something actionable.
  if (raw.includes('is not a function')) {
    return 'Folder picker unavailable — restart ByteQuay so it picks up the latest preload script, then try again.';
  }
  return `Couldn't open folder picker: ${raw}`;
}

export default AddRepoModal;
