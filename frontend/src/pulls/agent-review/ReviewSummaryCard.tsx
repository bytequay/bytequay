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
import { Fragment, useState, type ReactNode } from 'react';
import MarkdownBody from '../MarkdownBody';
import LocalCard, { LocalCardMessage, ReviewerAvatar, SummaryCardTitle } from '../local-review/LocalCard';
import ReviewComposer from './ReviewComposer';
import ReviewThread from './ReviewThread';
import { CommitEvent, TimelineEvent } from './SummaryTimeline';
import type { AgentReviewDto, ReviewFindingDto, ReviewRunDto } from './contracts';
import { askLabel, askQueues, provisional, shortSha } from './model';

type SummaryCard = { id: string; title: string; body: ReactNode; pending?: boolean; kind?: 'purpose' | 'commit' | 'history'; commit?: ReviewRunDto['commits'][number] };
type AskAboutCard = (message: string, targetRevision: number, cardId: string) => Promise<void>;

function bullets(items: string[]): string {
  return [...new Set(items)].map(item => `- ${item}`).join('\n');
}

function SummarySectionCard({ card, review, run, viewerLogin, viewerAvatarUrl, busy, onAsk }: {
  card: SummaryCard;
  review: AgentReviewDto;
  run: ReviewRunDto;
  viewerLogin: string;
  viewerAvatarUrl: string | null;
  busy: boolean;
  onAsk: AskAboutCard;
}) {
  const [composing, setComposing] = useState(false);
  const [text, setText] = useState('');
  const [error, setError] = useState<string | null>(null);
  const revision = run.summary?.revision ?? 1;
  const messages = review.messages.filter(message => message.findingId === null && (
    message.cardId === card.id || (message.cardId == null && card.id === `${run.id}:purpose`
      && (message.runId == null || message.runId === run.id))
  ));
  const close = () => { setComposing(false); setText(''); setError(null); };
  const submit = async () => {
    if (text.trim() === '') return;
    setError(null);
    try { await onAsk(text.trim(), revision, card.id); close(); }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'Could not send your question.'); }
  };
  return <article className="agr-summary-card" data-review-card={card.id}>
    <LocalCard tone={card.kind === 'purpose' ? 'purpose' : card.kind === 'commit' ? 'commit' : 'local'} chip="Private"
      title={card.kind === 'purpose' ? <SummaryCardTitle /> : card.commit ? <SummaryCardTitle sha={card.commit.sha} />
        : <span className="lrc__title">{card.title}</span>}
      controls={<footer className={`lrc__foot${composing ? ' is-composing' : ''}`}>
        {composing ? <ReviewComposer repository={review.repo} value={text} autoFocus busy={busy}
          placeholder={`Ask the reviewer about ${card.title.toLowerCase()}…`}
          ariaLabel={`Ask the reviewer about ${card.title.toLowerCase()}`} primaryLabel={askLabel(review)}
          onChange={setText} onSubmit={submit} onCancel={close} />
          : <><button type="button" className="lrc__ask" disabled={busy || review.state === 'CLOSED'}
            onClick={() => setComposing(true)}>Ask reviewer</button>
          {askQueues(review) && <span className="pr-local-review__note">Agent is busy — your question will queue</span>}</>}
        {error && <span className="lrc__error" role="alert">{error}</span>}
      </footer>}>
      {card.body !== null && <LocalCardMessage timestamp={run.endedAt ?? run.createdAt} author="Agent review" avatar={<ReviewerAvatar />}
        tag={<span className="lrc-message__meta">{review.task.laneLabel}</span>}
        menu={<>{card.pending && <span className="agr-pill is-pending">not yet read</span>}
          {provisional(review) && <span className="agr-pill is-provisional">Provisional · rev {revision}</span>}
          <span className="agr-card__refs">head {shortSha(run.headSha)}</span></>}>
        {card.body}
      </LocalCardMessage>}
      <ReviewThread review={review} messages={messages} viewerLogin={viewerLogin} viewerAvatarUrl={viewerAvatarUrl} />
    </LocalCard>
  </article>;
}

export default function ReviewSummaryCard({ review, run, findings, viewerLogin, viewerAvatarUrl, busy, onAsk, onSelectFinding, onSelectCommit }: {
  review: AgentReviewDto;
  run: ReviewRunDto | null;
  findings: ReviewFindingDto[];
  viewerLogin: string;
  viewerAvatarUrl: string | null;
  busy: boolean;
  onAsk: AskAboutCard;
  onSelectCommit?: (sha: string, snapshot?: { headSha: string; baseSha: string }) => void;
  onSelectFinding: (finding: ReviewFindingDto) => void;
}) {
  const summary = run?.summary;
  if (run === null || summary == null) {
    return <p className="agr-card__empty">No summary was saved for this run. Open the review in the workspace to follow the agent conversation.</p>;
  }
  const markdown = (body: string) => <MarkdownBody body={body} repository={review.repo} />;
  const critical = summary.criticalFindingIds.map(id => findings.find(finding => finding.id === id))
    .filter((finding): finding is ReviewFindingDto => finding !== undefined);
  const evidence = [...new Map(findings.filter(finding => finding.runId === run.id)
    .flatMap(finding => finding.evidence).map(item => [item.ref, item])).values()];
  const warnings = run.coverage?.warnings ?? [];
  const purpose: SummaryCard = { id: `${run.id}:purpose`, title: 'Change intention', kind: 'purpose', body: <>
    {markdown(summary.purpose)}
    {warnings.length > 0 && <aside className="agr-banner is-warn agr-summary-card__warnings" aria-label="Verification warning">
      <strong className="agr-banner__chip">Verification warning</strong>
      <div className="agr-banner__text">{markdown(bullets(warnings))}</div>
    </aside>}
  </> };
  const commits: SummaryCard[] = run.commits.map(commit => {
    const explanation = summary.commits.find(item => item.sha === commit.sha);
    return { id: `${run.id}:commit:${commit.sha}`, title: `${shortSha(commit.sha)} · ${commit.message.split('\n')[0]}`,
      body: markdown(explanation?.explanation ?? 'The agent has not saved an explanation of this commit yet.'), pending: explanation === undefined, kind: 'commit', commit };
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
      ? markdown(run.coverage?.complete
        ? `None found. The agent explicitly recorded zero critical issues for the ${run.commits.length} ${run.commits.length === 1 ? 'commit' : 'commits'} in scope.`
        : 'None recorded yet.')
      : <ul className="agr-critical">{critical.map(finding => <li key={finding.id}>
        <span className="agr-severity is-critical">{finding.severity ?? 'Critical'}</span>
        <button type="button" className="agr-link" onClick={() => onSelectFinding(finding)}>{finding.title}</button>
        <span className="agr-critical__where">{finding.path === null ? 'Whole change' : `${finding.path.split('/').pop()} ${finding.range}`}</span>
      </li>)}</ul> },
  ].filter(card => review.messages.some(message => message.findingId === null && message.cardId === card.id));
  const renderCard = (card: SummaryCard) => <SummarySectionCard key={card.id} card={card} review={review} run={run}
    viewerLogin={viewerLogin} viewerAvatarUrl={viewerAvatarUrl} busy={busy} onAsk={onAsk} />;
  return <div className="agr-timeline">
    <TimelineEvent avatar={<ReviewerAvatar />}>
      <strong>Agent review</strong> described the change intention · {run.commits.length} {run.commits.length === 1 ? 'commit' : 'commits'} in scope
      {' '}· base {run.baseRef}@{shortSha(run.baseSha)}
    </TimelineEvent>
    {renderCard(purpose)}
    {run.commits.map((commit, index) => {
      const [title, ...body] = commit.message.split('\n');
      return <Fragment key={commit.sha}>
        <CommitEvent ordinal={index + 1} sha={commit.sha} title={title} body={body.join('\n').trim()}
          onViewChanges={onSelectCommit && (() => onSelectCommit(commit.sha, { headSha: run.headSha, baseSha: run.mergeBaseSha }))} />
        {renderCard(commits[index])}
      </Fragment>;
    })}
    {earlier.length > 0 && <details className="agr-learning-history"><summary>Earlier discussions · {earlier.length}</summary>
      {earlier.map(renderCard)}
    </details>}
  </div>;
}
