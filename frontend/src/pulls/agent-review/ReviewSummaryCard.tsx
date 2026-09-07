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
import { Chevron } from '../../Chevron';
import MarkdownBody from '../MarkdownBody';
import { relativeTime } from '../../relativeTime';
import ReviewComposer from './ReviewComposer';
import ReviewThread from './ReviewThread';
import type { AgentReviewDto, ReviewFindingDto, ReviewRunDto } from './contracts';
import { askLabel, askQueues, messagesFor, provisional, shortSha } from './model';

/** The agent writes each limitation as one sentence; the card renders them as the list they are. */
function bullets(items: string[]): string {
  return items.map(item => `- ${item}`).join('\n');
}

function LockIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 16 16" fill="none" aria-hidden="true">
      <rect x="3.5" y="7" width="9" height="6.5" rx="1.3" stroke="currentColor" strokeWidth="1.4" />
      <path d="M5.5 7V5a2.5 2.5 0 0 1 5 0v2" stroke="currentColor" strokeWidth="1.4" />
    </svg>
  );
}

/**
 * The agent's plain-language account of the pull request, in the same card the PR description uses.
 *
 * A run still in flight shows what it has saved so far, marked provisional: a blank pane while the
 * agent works would hide the very thing the user is waiting to read.
 */
export default function ReviewSummaryCard({
  review,
  run,
  findings,
  viewerLogin,
  viewerAvatarUrl,
  busy,
  onAsk,
  onSelectFinding,
}: {
  review: AgentReviewDto;
  run: ReviewRunDto | null;
  findings: ReviewFindingDto[];
  viewerLogin: string;
  viewerAvatarUrl: string | null;
  busy: boolean;
  onAsk: (message: string, targetRevision: number) => Promise<void>;
  onSelectFinding: (finding: ReviewFindingDto) => void;
}) {
  const [threadOpen, setThreadOpen] = useState(true);
  const [composing, setComposing] = useState(false);
  const [text, setText] = useState('');
  const summary = run?.summary ?? null;
  const messages = messagesFor(review, null);
  const revision = summary?.revision ?? 1;
  const reviewed = run === null ? null : run.commits.map(commit => commit.sha);
  const limitations = summary === null ? [] : [...summary.limitations, ...(run?.coverage?.limitations ?? [])];
  const critical = summary === null
    ? []
    : summary.criticalFindingIds
      .map(id => findings.find(finding => finding.id === id))
      .filter((finding): finding is ReviewFindingDto => finding !== undefined);

  return (
    <article className="agr-card">
      <header className="agr-card__head">
        <span className="agr-agent-mark" aria-hidden="true">A</span>
        <strong>Agent</strong>
        <span className="agr-card__when">· {review.task.laneLabel}{run?.endedAt ? ` · ${relativeTime(run.endedAt)}` : ''}</span>
        <span className="agr-pill is-neutral">Private</span>
        {provisional(review) && summary !== null && (
          <span className="agr-pill is-provisional">Provisional · rev {revision}</span>
        )}
        <span className="agr-spacer" />
        {run !== null && (
          <span className="agr-card__refs">head {shortSha(run.headSha)} · base {run.baseRef}@{shortSha(run.baseSha)}</span>
        )}
      </header>
      {summary === null ? (
        <p className="agr-card__empty">
          No summary was saved for this run. The agent&apos;s messages are in Activity, but they are not a review result.
        </p>
      ) : (
        <div className="agr-card__body">
          <h2 className="agr-section">What this PR does</h2>
          <MarkdownBody body={summary.purpose} repository={review.repo} />

          <h2 className="agr-section">
            Commits <span className="agr-section__note">· {run?.commits.length ?? 0} in scope</span>
          </h2>
          <div className="agr-commits">
            {(run?.commits ?? []).map(commit => {
              const explanation = summary.commits.find(item => item.sha === commit.sha);
              return (
                <div className="agr-commit" key={commit.sha}>
                  <code className="agr-commit__sha">{shortSha(commit.sha)}</code>
                  <div className="agr-commit__text">
                    <div className="agr-commit__message">{commit.message.split('\n')[0]}</div>
                    {explanation !== undefined && <div className="agr-commit__why">{explanation.explanation}</div>}
                  </div>
                  {explanation === undefined && <span className="agr-pill is-pending">not yet read</span>}
                </div>
              );
            })}
          </div>

          <h2 className="agr-section">Combined result</h2>
          <MarkdownBody body={summary.combinedResult} repository={review.repo} />

          <h2 className="agr-section">Critical issues</h2>
          {critical.length === 0 ? (
            <p className="agr-card__none">
              {reviewed !== null && run?.coverage?.complete === true
                ? `None found. The agent explicitly recorded zero critical issues for the ${reviewed.length} ${reviewed.length === 1 ? 'commit' : 'commits'} in scope.`
                : 'None recorded yet.'}
            </p>
          ) : (
            <ul className="agr-critical">
              {critical.map(finding => (
                <li key={finding.id}>
                  <span className={`agr-severity ${(finding.severity ?? '').toLowerCase() === 'critical' ? 'is-critical' : 'is-medium'}`}>
                    {finding.severity ?? 'Critical'}
                  </span>
                  <button type="button" className="agr-link" onClick={() => onSelectFinding(finding)}>{finding.title}</button>
                  <span className="agr-critical__where">{finding.path.split('/').pop()} {finding.range}</span>
                </li>
              ))}
            </ul>
          )}

          <h2 className="agr-section">Limitations</h2>
          {limitations.length === 0 ? (
            <p className="agr-card__none">None recorded.</p>
          ) : (
            <MarkdownBody body={bullets(limitations)} repository={review.repo} />
          )}
          {(run?.coverage?.omissions.length ?? 0) > 0 && (
            <>
              <h2 className="agr-section">Not reviewed</h2>
              <MarkdownBody body={bullets(run?.coverage?.omissions ?? [])} repository={review.repo} />
            </>
          )}
        </div>
      )}
      <div className="agr-card__thread">
        <button type="button" className="agr-card__thread-head" aria-expanded={threadOpen}
          onClick={() => setThreadOpen(value => !value)}>
          <Chevron open={threadOpen} />
          <span>
            Discussion about this summary · {messages.length === 0
              ? 'no messages yet'
              : `${messages.length} ${messages.length === 1 ? 'message' : 'messages'}`}
          </span>
          <span className="agr-spacer" />
          <span className="agr-card__private"><LockIcon />Private — never published</span>
        </button>
        {threadOpen && (
          <div className="agr-card__thread-body">
            <ReviewThread review={review} messages={messages} viewerLogin={viewerLogin} viewerAvatarUrl={viewerAvatarUrl} />
            {composing ? (
              <ReviewComposer
                repository={review.repo}
                value={text}
                autoFocus
                busy={busy}
                placeholder="Ask the agent about this summary…"
                ariaLabel="Ask the agent about this summary"
                primaryLabel={askLabel(review)}
                onChange={setText}
                onSubmit={() => {
                  const body = text.trim();
                  if (body === '') return;
                  void onAsk(body, revision).then(() => {
                    setComposing(false);
                    setText('');
                  });
                }}
                onCancel={() => { setComposing(false); setText(''); }} />
            ) : (
              <div className="agr-card__ask">
                <button type="button" className="agr-action" disabled={busy || review.state === 'CLOSED'}
                  onClick={() => setComposing(true)}>Ask agent</button>
                {askQueues(review) && <span className="agr-card__busy">Agent is busy — your question will queue</span>}
              </div>
            )}
          </div>
        )}
      </div>
    </article>
  );
}
