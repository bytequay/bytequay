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
import { useEffect, useRef } from 'react';
import { File, FilePlus, Play, Undo2 } from 'lucide-react';
import { fileName, shortSha } from './codeModel';
import type { ReviewCodeFilesDto } from './contracts';

/** Lines of `draft` that differ from `saved` at the same index, the count the unsaved bar and gutter marks share. */
export function changedLineCount(saved: string, draft: string): number {
  const before = saved.split('\n');
  const after = draft.split('\n');
  let changed = 0;
  for (let index = 0; index < Math.max(before.length, after.length); index++) {
    if (before[index] !== after[index]) changed++;
  }
  return changed;
}

export function UnsavedBar({ saved, draft, blocked, saving, rerunLabel, onRevert, onSave, onSaveAndRerun }: {
  saved: string;
  draft: string;
  /** Why saving waits right now, or null. */
  blocked: string | null;
  saving: boolean;
  rerunLabel: string | null;
  onRevert: () => void;
  onSave: () => void;
  onSaveAndRerun: () => void;
}) {
  const lines = changedLineCount(saved, draft);
  return (
    <div className="ct-unsaved" role="status">
      <span className="ct-unsaved__dot" aria-hidden="true" />
      <strong>Unsaved edits · {lines} {lines === 1 ? 'line' : 'lines'} changed</strong>
      <span className="ct-unsaved__note">
        {blocked ?? 'Saving writes to this working copy — later agent turns see the change. Runs made before the edit stay as evidence.'}
      </span>
      <button type="button" className="ct-text-button" disabled={saving} onClick={onRevert}>Revert</button>
      <button type="button" className="ct-text-button" disabled={saving || blocked !== null} onClick={onSave}>{saving ? 'Saving…' : 'Save'}</button>
      {rerunLabel !== null && (
        <button type="button" className="ct-text-button is-primary" disabled={saving || blocked !== null} onClick={onSaveAndRerun}>
          <Play size={11} strokeWidth={2} fill="currentColor" aria-hidden="true" /> {rerunLabel}
        </button>
      )}
    </div>
  );
}

export function ConflictBanner({ path, onKeepDraft, onLoadFromDisk }: {
  path: string;
  onKeepDraft: () => void;
  onLoadFromDisk: () => void;
}) {
  return (
    <div className="ct-conflict" role="alert">
      <div className="ct-conflict__text">
        <strong>{fileName(path)} changed on disk after you started editing</strong>
        <span>Your draft is kept and nothing was overwritten. Keep your draft to save over the new version, or load the version on disk.</span>
      </div>
      <button type="button" className="ct-text-button" onClick={onKeepDraft}>Keep my draft</button>
      <button type="button" className="ct-text-button is-danger" onClick={onLoadFromDisk}>Discard mine, load from disk</button>
    </div>
  );
}

export function DiscardPopover({ files, drafts, blocked, discarding, error, onCancel, onDiscard }: {
  files: ReviewCodeFilesDto;
  drafts: readonly string[];
  blocked: string | null;
  discarding: boolean;
  error: string | null;
  onCancel: () => void;
  onDiscard: () => void;
}) {
  const host = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const close = (event: PointerEvent) => {
      if (!host.current?.parentElement?.contains(event.target as Node)) onCancel();
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || event.defaultPrevented) return;
      event.preventDefault();
      onCancel();
    };
    window.addEventListener('pointerdown', close);
    document.addEventListener('keydown', escape);
    return () => {
      window.removeEventListener('pointerdown', close);
      document.removeEventListener('keydown', escape);
    };
  }, [onCancel]);

  const changed = files.files.filter(file => file.uncommitted || file.onlyInWorkingCopy);
  const draftOnly = drafts.filter(path => !changed.some(file => file.path === path));
  const nothing = changed.length === 0 && drafts.length === 0 && !files.savedExperiment;
  return (
    <div className="ct-popover ct-discard" ref={host} role="dialog" aria-label="Discard all uncommitted changes">
      <div className="ct-discard__head">
        <strong>Discard all uncommitted changes?</strong>
        <p>The working copy returns to revision {shortSha(files.revision)}. Ignored build output stays, and run records stay as evidence.</p>
      </div>
      {nothing
        ? <div className="ct-popover__empty">This working copy has no uncommitted changes.</div>
        : (
          <ul>
            {changed.map(file => (
              <li key={file.path} className={file.onlyInWorkingCopy ? 'is-new' : 'is-edited'} title={file.path}>
                {file.onlyInWorkingCopy ? <FilePlus size={12} strokeWidth={1.8} aria-hidden="true" /> : <File size={12} strokeWidth={1.8} aria-hidden="true" />}
                <span className="ct-discard__name">{fileName(file.path)}</span>
                <span className="ct-discard__kind">{file.onlyInWorkingCopy ? 'new file' : drafts.includes(file.path) ? 'edited · unsaved' : 'edited'}</span>
              </li>
            ))}
            {draftOnly.map(path => (
              <li key={path} className="is-edited" title={path}>
                <File size={12} strokeWidth={1.8} aria-hidden="true" />
                <span className="ct-discard__name">{fileName(path)}</span>
                <span className="ct-discard__kind">edited · unsaved</span>
              </li>
            ))}
            {files.savedExperiment && (
              <li className="is-new">
                <Undo2 size={12} strokeWidth={1.8} aria-hidden="true" />
                <span className="ct-discard__name">Experiments a failed Update review kept</span>
                <span className="ct-discard__kind">saved stash</span>
              </li>
            )}
          </ul>
        )}
      {(blocked !== null || error !== null) && <div className="ct-discard__reason" role="alert">{error ?? blocked}</div>}
      <footer className="ct-popover__footer">
        <span />
        <button type="button" onClick={onCancel}>Cancel</button>
        <button type="button" className="is-danger" disabled={nothing || blocked !== null || discarding} onClick={onDiscard}>
          {discarding ? 'Discarding…' : 'Discard'}
        </button>
      </footer>
    </div>
  );
}
