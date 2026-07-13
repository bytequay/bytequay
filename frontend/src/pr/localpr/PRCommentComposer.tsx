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
import Avatar from '../../Avatar';
import { MarkdownProse } from '../../threads/MarkdownProse';
import { CodeIcon } from '../../ui/TaskBrainDesignIcons';
import { useAutoGrow } from '../../useAutoGrow';

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
  const disabled = onSubmit === undefined || value.trim().length === 0;
  return (
    <div className="pr-comment-composer">
      {username === undefined
        ? <span className="pr-comment-composer__avatar avatar avatar--fallback">Y</span>
        : <Avatar login={username.replace(/^@/, '')} size={40} className="pr-comment-composer__avatar" />}
      <div className="cc-main">
        <h3 className="cc-title">Add a comment</h3>
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
              <span className="cc-toolbar" aria-label="Markdown formatting">
                <span className="cc-tool text" title="Heading">H</span>
                <span className="cc-tool text bold" title="Bold">B</span>
                <span className="cc-tool text italic" title="Italic">I</span>
                <span className="cc-tool" title="Quote"><svg viewBox="0 0 16 16"><path d="M1.75 2.75v10.5M5 4h9M5 8h9M5 12h6" /></svg></span>
                <span className="cc-tool" title="Code"><CodeIcon size={18} /></span>
                <span className="cc-tool" title="Link"><svg viewBox="0 0 16 16"><path d="m6.25 9.75 3.5-3.5M5.1 11.9l-1 .1a3 3 0 0 1 0-6h2M10.9 4.1l1-.1a3 3 0 0 1 0 6h-2" /></svg></span>
                <span className="cc-tool divider" aria-hidden="true" />
                <span className="cc-tool" title="Bulleted list"><svg viewBox="0 0 16 16"><path d="M6 4h8M6 8h8M6 12h8" /><circle cx="2.5" cy="4" r=".75" fill="currentColor" stroke="none" /><circle cx="2.5" cy="8" r=".75" fill="currentColor" stroke="none" /><circle cx="2.5" cy="12" r=".75" fill="currentColor" stroke="none" /></svg></span>
                <span className="cc-tool" title="Numbered list"><svg viewBox="0 0 16 16"><path d="M7 4h7M7 8h7M7 12h7M2 3h1v3M2 8h2l-2 2h2M2 12h2l-2 2h2" /></svg></span>
              </span>
            )}
          </div>
          {tab === 'write' ? (
            <textarea
              ref={inputRef}
              className="cc-input"
              placeholder="Add your comment here..."
              value={value}
              onChange={e => onChange(e.target.value)}
              onKeyDown={e => {
                if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') { e.preventDefault(); submit(); }
              }}
            />
          ) : (
            <div className="cc-input cc-preview">
              {value.trim().length > 0
                ? <MarkdownProse text={value} />
                : <span className="cc-empty-preview">Nothing to preview</span>}
            </div>
          )}
          <div className="cc-footer">
            <span className="cc-markdown"><b aria-hidden="true">M↓</b> Markdown is supported</span>
            {local ? (
              <span className="local-note">
                <svg viewBox="0 0 16 16" aria-hidden="true"><rect x="3.5" y="7" width="9" height="7" rx="1.5" /><path d="M5.5 7V5a2.5 2.5 0 0 1 5 0v2" /></svg>
                Local comment — won&apos;t be posted to GitHub
              </span>
            ) : (
              <span className="cc-destination">Posts to GitHub{username !== undefined ? ` as @${username.replace(/^@/, '')}` : ''}</span>
            )}
          </div>
        </div>
        <div className="cc-actions">
          <button type="button" className="btn green" onClick={submit} disabled={disabled}>Comment</button>
        </div>
      </div>
    </div>
  );
}
