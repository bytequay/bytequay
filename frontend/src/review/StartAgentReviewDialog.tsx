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
import type { PullRequestDto } from '../types';
import { WS_DIALOG_OVERLAY, WS_DIALOG_PANEL, dialogStyles } from '../workspace/dialogStyles';

type Props = {
  workspaceId: string;
  pr: PullRequestDto;
  onClose: () => void;
  onStarted: (ownerThreadId: string) => void;
};

/** Starts the same durable AgentReview used by the PR details page. */
export default function StartAgentReviewDialog({ workspaceId, pr, onClose, onStarted }: Props) {
  const [runner, setRunner] = useState<'auto' | 'api' | 'cli'>('auto');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const start = async (event: React.FormEvent) => {
    event.preventDefault();
    if (submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      const separator = pr.repo.indexOf('/');
      if (separator <= 0 || separator === pr.repo.length - 1) {
        throw new Error(`Invalid repository name: ${pr.repo}`);
      }
      const localPr = await window.bridge.getPrForRepoPull(
          pr.repo.slice(0, separator), pr.repo.slice(separator + 1), pr.number);
      const review = await window.bridge.startAgentReview(localPr.id, {
        runner: runner === 'auto' ? undefined : runner,
        workspaceId,
      });
      const ownerThreadId = review.review.owner_thread_id;
      if (ownerThreadId === null) throw new Error('The review started without an owning thread.');
      onStarted(ownerThreadId);
    }
    catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
      setSubmitting(false);
    }
  };

  return (
    <div style={WS_DIALOG_OVERLAY} onClick={onClose} role="presentation">
      <div
        style={{ ...WS_DIALOG_PANEL, width: 500 }}
        role="dialog"
        aria-modal="true"
        aria-label="Start agent review"
        onClick={event => event.stopPropagation()}
      >
        <header style={dialogStyles.header}>
          <h2 style={dialogStyles.title}>⚖ Start agent review</h2>
          <button type="button" onClick={onClose} style={dialogStyles.closeBtn} aria-label="Close">✕</button>
        </header>

        <div style={prStyle}>
          <b>#{pr.number} {pr.title}</b>
          <small>{pr.repo}</small>
        </div>
        <p style={descriptionStyle}>
          This creates a review thread in the current workspace. It uses an existing exact local
          checkout when available; otherwise it runs remote-only and does not clone or fetch.
        </p>
        {error !== null && <div style={errorStyle} role="alert">{error}</div>}

        <form onSubmit={start}>
          <fieldset style={runnerStyle}>
            <legend style={legendStyle}>Execution</legend>
            <RunnerOption value="auto" checked={runner === 'auto'} onChange={setRunner} title="Automatic" detail="Use the configured reviewer pool." />
            <RunnerOption value="api" checked={runner === 'api'} onChange={setRunner} title="API runner" detail="Use an in-process provider credential." />
            <RunnerOption value="cli" checked={runner === 'cli'} onChange={setRunner} title="CLI runner" detail="Use a configured local review CLI." />
          </fieldset>
          <footer style={dialogStyles.footer}>
            <span style={dialogStyles.footerNote}>Drafts stay local until you publish them.</span>
            <div style={dialogStyles.footerButtons}>
              <button type="button" onClick={onClose} style={dialogStyles.secondaryBtn}>Cancel</button>
              <button type="submit" disabled={submitting} style={submitting ? dialogStyles.primaryBtnDisabled : dialogStyles.primaryBtn}>
                {submitting ? 'Starting…' : 'Start review'}
              </button>
            </div>
          </footer>
        </form>
      </div>
    </div>
  );
}

function RunnerOption({ value, checked, onChange, title, detail }: {
  value: 'auto' | 'api' | 'cli';
  checked: boolean;
  onChange: (value: 'auto' | 'api' | 'cli') => void;
  title: string;
  detail: string;
}) {
  return (
    <label style={optionStyle}>
      <input type="radio" name="agent-review-runner" checked={checked} onChange={() => onChange(value)} />
      <span><b>{title}</b><small style={{ display: 'block' }}>{detail}</small></span>
    </label>
  );
}

const prStyle: React.CSSProperties = {
  display: 'flex', flexDirection: 'column', gap: 3, padding: '11px 12px',
  border: '1px solid var(--ws-card-border)', borderRadius: 9,
  background: 'rgba(124,58,237,0.04)', fontSize: 13,
};
const descriptionStyle: React.CSSProperties = {
  color: 'var(--ws-text-3)', fontSize: 12, lineHeight: 1.5, margin: '12px 0',
};
const errorStyle: React.CSSProperties = {
  padding: 9, marginBottom: 10, borderRadius: 7, color: '#b42318',
  border: '1px solid rgba(180,35,24,.25)', background: 'rgba(180,35,24,.05)', fontSize: 12,
};
const runnerStyle: React.CSSProperties = { display: 'grid', gap: 7, border: 0, padding: 0, margin: 0 };
const legendStyle: React.CSSProperties = {
  padding: 0, marginBottom: 5, color: 'var(--ws-text-3)', fontSize: 10,
  fontWeight: 700, letterSpacing: '.07em', textTransform: 'uppercase',
};
const optionStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'flex-start', gap: 9, padding: '9px 10px',
  border: '1px solid var(--ws-card-border)', borderRadius: 8, cursor: 'pointer', fontSize: 12,
};
