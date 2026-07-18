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
import { renderMarkdown } from '../markdown';
import type { MarkdownRepoContext } from '../markdown';

/** The overview composer: Write|Preview segmented control, the prototype's
 *  formatting-toolbar glyph row (decorative, as in the prototype), and the
 *  Close/Comment button row. */

const toolIconStyle = { width: 26, height: 26, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', borderRadius: 6 } as const;

function Toolbar() {
  return (
    <span style={{ display: 'inline-flex', gap: 1, color: '#59636e' }}>
      <span className="pl-hov-ic" style={{ ...toolIconStyle, fontSize: 13, fontWeight: 700 }}>H</span>
      <span className="pl-hov-ic" style={toolIconStyle}><svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round"><path d="M7 4h6a3.5 3.5 0 0 1 0 7H7z" /><path d="M7 11h7a3.5 3.5 0 0 1 0 7H7z" /></svg></span>
      <span className="pl-hov-ic" style={toolIconStyle}><svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M13 4h5" /><path d="M6 20h5" /><path d="M15 4 9 20" /></svg></span>
      <span className="pl-hov-ic" style={toolIconStyle}><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M10 7H6a2 2 0 0 0-2 2v2a2 2 0 0 0 2 2h2v3" /><path d="M20 7h-4a2 2 0 0 0-2 2v2a2 2 0 0 0 2 2h2v3" /></svg></span>
      <span className="pl-hov-ic" style={toolIconStyle}><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="m8 8-5 4 5 4" /><path d="m16 8 5 4-5 4" /></svg></span>
      <span className="pl-hov-ic" style={toolIconStyle}><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7" /><path d="M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7" /></svg></span>
      <span className="pl-hov-ic" style={toolIconStyle}><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M8 6h13" /><path d="M8 12h13" /><path d="M8 18h13" /><path d="M3 6h.01" /><path d="M3 12h.01" /><path d="M3 18h.01" /></svg></span>
      <span className="pl-hov-ic" style={toolIconStyle}><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M10 6h11" /><path d="M10 12h11" /><path d="M10 18h11" /><path d="M4 6h1v4" /><path d="M4 10h2" /><path d="M6 18H4c0-1 2-2 2-3s-1-1.5-2-1" /></svg></span>
    </span>
  );
}

export default function PullComposer({ canClose, onComment, repoCtx }: {
  canClose: boolean;
  /** Posts a PR-level comment via the same bridge path PRView's hosts use;
   *  undefined while the bundle is still loading (button disabled). */
  onComment?: (body: string) => Promise<void>;
  repoCtx: MarkdownRepoContext;
}) {
  const [seg, setSeg] = useState<'write' | 'preview'>('write');
  const [draft, setDraft] = useState('');
  const [busy, setBusy] = useState(false);
  const disabled = draft.trim().length === 0 || busy || onComment === undefined;
  const submit = () => {
    const body = draft.trim();
    if (body.length === 0 || onComment === undefined || busy) return;
    setBusy(true);
    onComment(body)
      .then(() => setDraft(''))
      .catch(() => { /* poll reconciles; keep the draft */ })
      .finally(() => setBusy(false));
  };
  return (
    <>
      <div style={{ border: '1px solid #d5dbe1', borderRadius: 10, background: '#fff', marginTop: 22, position: 'relative' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 2, padding: '8px 10px', borderBottom: '1px solid #e7e9ec', background: '#f6f8fa', borderRadius: '10px 10px 0 0' }}>
          {([['write', 'Write'], ['preview', 'Preview']] as const).map(([key, label]) => (
            <button
              key={key}
              onClick={() => setSeg(key)}
              style={{
                padding: '4px 14px',
                border: `1px solid ${seg === key ? '#d0d7de' : 'transparent'}`,
                background: seg === key ? '#fff' : 'transparent',
                borderRadius: 7,
                fontSize: 12.5,
                fontWeight: seg === key ? 600 : 500,
                color: seg === key ? '#17191c' : '#59636e',
                cursor: 'pointer',
              }}
            >
              {label}
            </button>
          ))}
          <span style={{ flex: 1 }} />
          <Toolbar />
        </div>
        {seg === 'write' ? (
          <textarea
            placeholder="Add a comment"
            value={draft}
            onChange={e => setDraft(e.target.value)}
            onKeyDown={e => {
              if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') { e.preventDefault(); submit(); }
            }}
            style={{ display: 'block', width: '100%', minHeight: 112, border: 0, outline: 'none', resize: 'vertical', padding: '13px 16px', fontSize: 13.5, lineHeight: 1.6, color: '#1f2328', background: 'transparent', borderRadius: '0 0 10px 10px' }}
          />
        ) : draft.trim().length > 0 ? (
          <div
            style={{ minHeight: 112, padding: '13px 16px', fontSize: 13.5, lineHeight: 1.65, color: '#1f2328' }}
            dangerouslySetInnerHTML={{ __html: renderMarkdown(draft, repoCtx) }}
          />
        ) : (
          <div style={{ minHeight: 112, padding: '13px 16px', fontSize: 13.5, color: '#8b949e', fontStyle: 'italic' }}>Nothing to preview</div>
        )}
      </div>
      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 14 }}>
        {canClose && (
          <button
            disabled
            title="Not wired yet"
            style={{ padding: '7px 15px', border: '1px solid #d5dbe1', background: '#fff', borderRadius: 8, fontSize: 12.5, fontWeight: 600, color: '#1f2328', cursor: 'pointer' }}
          >
            Close pull request
          </button>
        )}
        <button
          className="pl-hov-green"
          onClick={submit}
          disabled={disabled}
          style={{ display: 'inline-flex', alignItems: 'center', gap: 8, padding: '7px 16px', border: '1px solid #1a7f37', background: '#1f883d', borderRadius: 8, fontSize: 12.5, fontWeight: 600, color: '#fff', cursor: 'pointer', boxShadow: '0 1px 2px rgba(0,0,0,0.08)', opacity: disabled ? 0.6 : 1 }}
        >
          Comment<span style={{ opacity: 0.6, fontSize: 11, fontWeight: 500 }}>⌘⏎</span>
        </button>
      </div>
    </>
  );
}
