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
import { useAutoGrow } from '../../useAutoGrow';

const TOOLBAR = ['H', 'B', 'I', '"', '<>', '🔗', '≡', '1.'];

/**
 * The PR-level comment composer (U13f): avatar + a GitHub-styled card with
 * Write/Preview tabs, a formatting toolbar, and a footer whose hint switches
 * on `local` — during a local (task, pre-push) phase the comment stays in
 * ByteQuay; otherwise it posts to GitHub as the user.
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
  const inputRef = useAutoGrow(value);
  const submit = () => {
    if (onSubmit !== undefined && value.trim().length > 0) onSubmit();
  };
  return (
    <div className="pr-comment-composer">
      <span className="pr-avatar you s28">Y</span>
      <div className="cc-box">
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
          {tab === 'write' && (
            <span className="cc-toolbar">
              {TOOLBAR.map(g => <span key={g}>{g}</span>)}
            </span>
          )}
        </div>
        {tab === 'write' ? (
          <textarea
            ref={inputRef}
            className="cc-input"
            placeholder="Leave a comment on this PR…"
            value={value}
            onChange={e => onChange(e.target.value)}
            onKeyDown={e => {
              if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') { e.preventDefault(); submit(); }
            }}
          />
        ) : (
          <div className="cc-input" style={{ fontStyle: 'normal', color: 'var(--text-1)' }}>
            {value.trim().length > 0
              ? <MarkdownProse text={value} />
              : <span style={{ color: 'var(--text-4)', fontStyle: 'italic' }}>Nothing to preview</span>}
          </div>
        )}
        <div className="cc-footer">
          <span>Markdown supported</span>
          {local ? (
            <span className="local-note">🔒 local — won&apos;t be posted to GitHub</span>
          ) : (
            <span>Posts to GitHub{username !== undefined ? ` as @${username}` : ''}</span>
          )}
          <span className="right">
            <button type="button" className="btn sm green" onClick={submit} disabled={onSubmit === undefined}>
              Comment<span className="kbd">⌘↵</span>
            </button>
          </span>
        </div>
      </div>
    </div>
  );
}
