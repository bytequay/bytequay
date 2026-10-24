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
import { useState, type ReactNode } from 'react';
import { RefreshCw } from 'lucide-react';
import { relativeTime } from '@bytequay/ui/relativeTime';
import { plural } from '@bytequay/ui/displayText';
import MarkdownBody from '../MarkdownBody';
import ReviewComposer from './ReviewComposer';
import ReviewThread from './ReviewThread';
import { ChangedItem, WhatChanged } from './SummaryTimeline';
import type { ReviewCardFocus } from '../../agents/ReviewRequestCard';
import type { AgentReviewDto, ReviewFindingDto, ReviewRunDto } from './contracts';
import { askLabel, askQueues, isExplanationRun, latestExplanationRun, latestRun, shortSha } from './model';

type SummaryCard = { id: string; title: string; body: ReactNode; pending?: boolean; commit?: ReviewRunDto['commits'][number] };
type AskAboutCard = (message: string, targetRevision: number, cardId: string) => Promise<void>;

function bullets(items: string[]): string {
  return [...new Set(items)].map(item => `- ${item}`).join('\n');
}

/** The discussion of one card; the intention also holds questions saved before cards had ids. */
function cardMessages(review: AgentReviewDto, run: ReviewRunDto, cardId: string) {
  return review.messages.filter(message => message.findingId === null && (
    message.cardId === cardId || (message.cardId == null && cardId === `${run.id}:purpose`
      && (message.runId == null || message.runId === run.id))
  ));
}

function SummaryItem({ card, review, run, viewerLogin, viewerAvatarUrl, busy, onAsk, onSelectCommit }: {
  card: SummaryCard;
  review: AgentReviewDto;
  run: ReviewRunDto;
  viewerLogin: string;
  viewerAvatarUrl: string | null;
  busy: boolean;
  onAsk: AskAboutCard;
  onSelectCommit?: (sha: string, snapshot?: { headSha: string; baseSha: string }) => void;
}) {
  const [composing, setComposing] = useState(false);
  const [messageOpen, setMessageOpen] = useState(false);
  const [text, setText] = useState('');
  const [error, setError] = useState<string | null>(null);
  const revision = run.summary?.revision ?? 1;
  const provisional = run.state !== 'ACCEPTED' || (!isExplanationRun(run) && (run.coverage?.complete !== true || run.coverage.omissions.length > 0));
  const close = () => { setComposing(false); setText(''); setError(null); };
  const submit = async () => {
    if (text.trim() === '') return;
    setError(null);
    try { await onAsk(text.trim(), revision, card.id); close(); }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'Could not send your question.'); }
  };
  const commit = card.commit;
  const [title, ...message] = commit?.message.split('\n') ?? [];
  const messages = cardMessages(review, run, card.id);
  const ask = !composing && <button type="button" className="agr-link" disabled={busy || review.state === 'CLOSED'}
    title={askQueues(review) ? 'Agent is busy — your question will queue' : undefined} onClick={() => setComposing(true)}>Ask reviewer</button>;
  return <div className="agr-summary-card" data-review-card={card.id}>
    <ChangedItem sha={commit?.sha} byline={`Agent review · ${relativeTime(run.endedAt ?? run.createdAt)} · Private`}
      title={commit ? <>{title}{message.join('').trim() !== '' && <button type="button" className="agr-commit__more" aria-expanded={messageOpen}
        aria-label="Show commit message" onClick={() => setMessageOpen(value => !value)}>···</button>}</> : card.title}
      note={messageOpen && <pre className="agr-explanation__message">{message.join('\n').trim()}</pre>}
      onViewChanges={commit && onSelectCommit ? () => onSelectCommit(commit.sha, { headSha: run.headSha, baseSha: run.mergeBaseSha }) : undefined}
      badges={<>{card.pending && <span className="agr-pill is-pending">not yet read</span>}
        {provisional && <span className="agr-pill is-provisional">Provisional · rev {revision}</span>}</>}
      ask={messages.length === 0 && ask}>
      {card.body}
    </ChangedItem>
    <ReviewThread review={review} messages={messages} viewerLogin={viewerLogin} viewerAvatarUrl={viewerAvatarUrl} />
    {/* Once discussed, the next question continues below the last answer. */}
    {messages.length > 0 && ask && <div className="agr-changed__foot"><span className="agr-spacer" />{ask}</div>}
    {composing && <ReviewComposer repository={review.repo} value={text} autoFocus busy={busy}
      placeholder={`Ask the reviewer about ${card.title.toLowerCase()}…`}
      ariaLabel={`Ask the reviewer about ${card.title.toLowerCase()}`} primaryLabel={askLabel(review)}
      onChange={setText} onSubmit={submit} onCancel={close} />}
    {error && <span className="lrc__error" role="alert">{error}</span>}
  </div>;
}

/** The reviewer's explanation of the change, as the Summary's What changed. */
export default function ReviewSummaryCard({ review, run, findings, viewerLogin, viewerAvatarUrl, busy, onAsk, onSelectFinding, onSelectCommit, onRefresh, refreshDisabledReason, focus }: {
  review: AgentReviewDto;
  run: ReviewRunDto | null;
  findings: ReviewFindingDto[];
  viewerLogin: string;
  viewerAvatarUrl: string | null;
  busy: boolean;
  onAsk: AskAboutCard;
  onSelectCommit?: (sha: string, snapshot?: { headSha: string; baseSha: string }) => void;
  onSelectFinding: (finding: ReviewFindingDto) => void;
  onRefresh?: () => Promise<void>;
  refreshDisabledReason?: string;
  /** A link to a card selects it in the outline. */
  focus?: ReviewCardFocus | null;
}) {
  const [selected, setSelected] = useState<string | null>(null);
  const [followed, setFollowed] = useState<ReviewCardFocus | null | undefined>(null);
  if (focus !== followed && focus?.target.kind === 'card') {
    setFollowed(focus);
    setSelected(focus.target.cardId);
  }
  const [startingRefresh, setStartingRefresh] = useState(false);
  const [refreshError, setRefreshError] = useState<{ runId: string; revision: number; message: string } | null>(null);
  const currentRefreshError = refreshError?.runId === run?.id && refreshError?.revision === run?.summary?.revision ? refreshError?.message ?? null : null;
  const refreshRun = latestExplanationRun(review);
  const refreshing = startingRefresh || refreshRun?.state === 'RUNNING';
  const refreshFailure = refreshRun !== null && ['FAILED', 'INCOMPLETE', 'STOPPED'].includes(refreshRun.state)
    ? refreshRun.outcome ?? (refreshRun.state === 'STOPPED' ? 'Explanation refresh stopped. Your saved explanation is unchanged.' : 'Could not refresh the explanation. Your saved explanation is unchanged.') : null;
  const refresh = async () => {
    if (onRefresh === undefined || run?.summary == null || refreshing || busy || refreshDisabledReason !== undefined) return;
    setStartingRefresh(true);
    setRefreshError(null);
    try { await onRefresh(); }
    catch (failure) { setRefreshError({ runId: run.id, revision: run.summary.revision,
      message: failure instanceof Error ? failure.message : 'Could not refresh the explanation.' }); }
    finally { setStartingRefresh(false); }
  };
  const refreshControl = onRefresh === undefined ? undefined : <button type="button" className="agr-explanation__refresh"
    aria-label="Refresh change intention and commit summaries" aria-busy={refreshing}
    title={refreshing ? 'Refreshing change intention and commit summaries…' : refreshDisabledReason ?? 'Refresh change intention and commit summaries'}
    disabled={busy || refreshing || refreshDisabledReason !== undefined} onClick={event => { event.preventDefault(); void refresh(); }}>
    {refreshing ? <span className="agr-spinner" aria-hidden="true" /> : <RefreshCw size={14} aria-hidden="true" />}
  </button>;
  const refreshStatus = onRefresh === undefined ? undefined : refreshing
    ? <p className="agr-explanation__refresh-status" role="status">Refreshing change intention and commit summaries…</p>
    : (currentRefreshError ?? refreshFailure) !== null
      ? <p className="agr-explanation__refresh-status lrc__error" role="alert">{currentRefreshError ?? refreshFailure}</p> : undefined;
  const summary = run?.summary;
  if (run === null && review.state === 'OPEN') {
    return <p className="agr-card__empty">Your working copy is ready in Tears of time. Start review when you want an agent to review this pull request.</p>;
  }
  if (run === null || summary == null) {
    return <p className="agr-card__empty">No summary was saved for this run. Open the review in the workspace to follow the agent conversation.</p>;
  }
  const markdown = (body: string) => <MarkdownBody body={body} repository={review.repo} />;
  const critical = summary.criticalFindingIds.map(id => findings.find(finding => finding.id === id))
    .filter((finding): finding is ReviewFindingDto => finding !== undefined);
  const evidence = [...new Map(findings.filter(finding => finding.runId === run.id)
    .flatMap(finding => finding.evidence).map(item => [item.ref, item])).values()];
  const warnings = run.coverage?.warnings ?? [];
  const purpose: SummaryCard = { id: `${run.id}:purpose`, title: 'Change intention', body: <>
    {markdown(summary.purpose)}
    {warnings.length > 0 && <aside className="agr-banner is-warn agr-summary-card__warnings" aria-label="Verification warning">
      <strong className="agr-banner__chip">Verification warning</strong>
      <div className="agr-banner__text">{markdown(bullets(warnings))}</div>
    </aside>}
  </> };
  const commits: SummaryCard[] = run.commits.map(commit => {
    const explanation = summary.commits.find(item => item.sha === commit.sha);
    return { id: `${run.id}:commit:${commit.sha}`, title: `${shortSha(commit.sha)} · ${commit.message.split('\n')[0]}`,
      body: markdown(explanation?.explanation ?? 'The agent has not saved an explanation of this commit yet.'), pending: explanation === undefined, commit };
  });
  const earlier: SummaryCard[] = [
    { id: `${run.id}:combined-result`, title: 'Combined result', body: markdown(summary.combinedResult) },
    { id: `${run.id}:evidence`, title: 'Supporting evidence & tests', body: summary.evidence
      ? markdown(summary.evidence)
      : evidence.length === 0 ? markdown('No supporting evidence or test results were recorded in this report.')
        : <div>{evidence.map(item => <div className="agr-evidence__item" key={item.ref}>
          <div className="agr-evidence__head"><span className="agr-evidence__source" title={item.source}>{item.source}</span>
            <span className="agr-spacer" /><span>{item.provenance}</span></div>
          <pre className="agr-evidence__excerpt">{item.excerpt}</pre>
          <div className="agr-evidence__why">{markdown(item.explanation)}</div>
        </div>)}</div> },
    { id: `${run.id}:limitations`, title: 'Limitations', body: null },
    { id: `${run.id}:coverage`, title: 'Review coverage', body: markdown([
      run.coverage?.complete ? `The agent recorded complete review coverage for ${run.commits.length} ${run.commits.length === 1 ? 'commit' : 'commits'} and ${run.changedFiles} files.`
        : 'Complete review coverage has not been recorded.',
      'Coverage describes what was reviewed; test evidence is recorded separately.',
      ...(run.coverage?.omissions.length ? ['**Not reviewed**', bullets(run.coverage.omissions)] : []),
    ].join('\n\n')) },
    { id: `${run.id}:critical-issues`, title: 'Critical issues', body: critical.length === 0
      ? markdown(summary.criticalFindingIds.length > 0 ? 'No critical findings selected for the result.' : run.coverage?.complete
        ? `None found. The agent explicitly recorded zero critical issues for the ${run.commits.length} ${run.commits.length === 1 ? 'commit' : 'commits'} in scope.`
        : 'None recorded yet.')
      : <ul className="agr-critical">{critical.map(finding => <li key={finding.id}>
        <span className="agr-severity is-critical">{finding.severity ?? 'Critical'}</span>
        <button type="button" className="agr-link" onClick={() => onSelectFinding(finding)}>{finding.title}</button>
        <span className="agr-critical__where">{finding.path === null ? 'Whole change' : `${finding.path.split('/').pop()} ${finding.range}`}</span>
      </li>)}</ul> },
  ].filter(card => review.messages.some(message => message.findingId === null && message.cardId === card.id));
  const renderCard = (card: SummaryCard) => <SummaryItem key={card.id} card={card} review={review} run={run}
    onSelectCommit={onSelectCommit} viewerLogin={viewerLogin} viewerAvatarUrl={viewerAvatarUrl} busy={busy} onAsk={onAsk} />;
  const shown = selected !== null && commits.some(card => card.id === selected) ? selected : purpose.id;
  const last = run.commits.at(-1);
  return <WhatChanged selected={shown} onSelect={setSelected} actions={refreshControl}
    items={[{ id: purpose.id, label: 'Intention' }, ...commits.map((card, index) => ({ id: card.id, label: card.commit?.message.split('\n')[0] ?? card.title, ordinal: index + 1 }))]}
    meta={<>
      {run.id !== latestRun(review)?.id && `${isExplanationRun(run) ? 'Change explanation' : 'Original change explanation'} · head ${shortSha(run.headSha)} · `}
      updated {relativeTime(run.endedAt ?? run.createdAt)}
      {last !== undefined && <> · {plural(run.commits.length, 'commit')} through {onSelectCommit
        ? <button type="button" className="agr-link" title="Open this commit's diff in Changes"
          onClick={event => { event.preventDefault(); onSelectCommit(last.sha, { headSha: run.headSha, baseSha: run.mergeBaseSha }); }}>{shortSha(last.sha)}</button>
        : <code>{shortSha(last.sha)}</code>}</>}
    </>}>
    {refreshStatus}
    {/* Unselected items stay mounted, so a question being written survives switching to another. */}
    {[purpose, ...commits].map(card => <div key={card.id} hidden={card.id !== shown}>{renderCard(card)}</div>)}
    {earlier.length > 0 && <details className="agr-learning-history"><summary>Earlier discussions · {earlier.length}</summary>
      {earlier.map(card => renderCard(card))}
    </details>}
  </WhatChanged>;
}
