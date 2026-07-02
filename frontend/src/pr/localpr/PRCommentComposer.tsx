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
import { useState } from 'react';
import { MarkdownProse } from '../../threads/MarkdownProse';

const TOOLBAR = ['H', 'B', 'I', '"', '<>', '🔗', '≡', '1.'];

/**
 * The PR-level comment composer (decision #56) at the bottom of the PR view.
 * Write / Preview tabs + a formatting toolbar + a footer whose hint switches
 * on `local`: during a local phase the comment stays in ByteQuay
 * (🔒 won't post to GitHub); during a remote phase it posts as the user.
 */
export function PRCommentComposer({
  local, username, value, onChange, onSubmit,
}: {
  local: boolean;
  username?: string;
  value: string;
  onChange: (v: string) => void;
  onSubmit?: () => void;
}) {
  const [tab, setTab] = useState<'write' | 'preview'>('write');
  const submit = () => {
    if (onSubmit !== undefined && value.trim().length > 0) onSubmit();
  };
  return (
    <div className="pr-comment-composer">
      <div className="cc-tabs">
        <button
          type="button"
          className={tab === 'write' ? 'cc-tab active' : 'cc-tab'}
          onClick={() => setTab('write')}
        >Write</button>
        <button
          type="button"
          className={tab === 'preview' ? 'cc-tab active' : 'cc-tab'}
          onClick={() => setTab('preview')}
        >Preview</button>
      </div>
      {tab === 'write' ? (
        <>
          <div className="cc-toolbar">
            {TOOLBAR.map(g => <span key={g}>{g}</span>)}
          </div>
          <textarea
            className="cc-input"
            placeholder="Leave a comment on this PR…"
            value={value}
            onChange={e => onChange(e.target.value)}
            onKeyDown={e => {
              if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') { e.preventDefault(); submit(); }
            }}
          />
        </>
      ) : (
        <div className="cc-input" style={{ fontStyle: 'normal', color: 'var(--text-1)' }}>
          {value.trim().length > 0
            ? <MarkdownProse text={value} />
            : <span style={{ color: 'var(--text-4)', fontStyle: 'italic' }}>Nothing to preview</span>}
        </div>
      )}
      <div className="cc-footer">
        {local ? (
          <span className="local-note">🔒 local — won&apos;t be posted to GitHub</span>
        ) : (
          <span className="local-note" style={{ color: 'var(--text-3)' }}>
            Comment posts to GitHub{username !== undefined ? ` as @${username}` : ''}
          </span>
        )}
        <span className="grow" style={{ flex: 1 }} />
        <button type="button" className="comment-btn" onClick={submit} disabled={onSubmit === undefined}>
          Comment<span className="kbd">⌘↵</span>
        </button>
      </div>
    </div>
  );
}
