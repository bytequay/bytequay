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
import { MarkdownProse } from '../../threads/MarkdownProse';
import type { LocalPRBundle, PRViewMode } from '../../types/localPr';
import { isLocalStatus } from '../../types/localPr';
import { statusBadgeMeta } from './prViewMeta';
import { PRTimeline } from './PRTimeline';
import { PRChecksCard } from './PRChecksCard';
import { PRActionBar } from './PRActionBar';
import { PRCommentComposer } from './PRCommentComposer';

/**
 * The unified PR renderer (decision #49). ONE component, ONE `mode` prop —
 * `mode="local"` renders the private local phase, `mode="remote"` the pushed
 * phase. The visual template is identical; `mode` only switches the badge, the
 * timeline dimming, the checks emphasis, and the composer/action affordances.
 * Layout follows decision #54: fixed header + scrollable body of
 * Description → Timeline → Action bar → Checks → Composer.
 *
 * Presentational: the host resolves the {@link LocalPRBundle} (via a bridge
 * hook) and supplies the user-gated callbacks. Push and merge are never
 * auto-invoked here — they open the host's dialogs.
 */
export function PRView({
  mode, bundle, commentValue, onCommentChange, username,
  onAddComment, onPush, onAskAgent, onMerge, onMergeAnyway, onReviewChanges,
}: {
  mode: PRViewMode;
  bundle: LocalPRBundle;
  commentValue: string;
  onCommentChange: (v: string) => void;
  username?: string;
  onAddComment?: () => void;
  onPush?: () => void;
  onAskAgent?: () => void;
  onMerge?: () => void;
  onMergeAnyway?: () => void;
  /** Opens the full-page changed-files + diff review. Omitted when there's
   *  nothing to review yet. */
  onReviewChanges?: () => void;
}) {
  const { pr, timeline, checks, comments } = bundle;
  const badge = statusBadgeMeta(pr.status);
  const local = isLocalStatus(pr.status);

  const openComments = comments.filter(c => c.resolvedAt === null && c.strippedOnPushAt === null).length;
  const localChecks = checks.filter(c => c.kind === 'local');
  const remoteChecks = checks.filter(c => c.kind === 'remote');
  const localChecksPassed = localChecks.length > 0 && localChecks.every(c => c.status === 'passed' || c.status === 'neutral');
  const prNumLabel = pr.remotePrNumber !== null ? `#${pr.remotePrNumber}` : '#local';

  return (
    <div className="pr-view">
      <div className="pr-header">
        <div className="pr-title-row">
          <span className="pr-title">{pr.title}</span>
          <span className="pr-num">{prNumLabel}</span>
        </div>
        <div className="pr-title-row">
          <span className={`pr-status-badge ${badge.cls}`}>
            {badge.pulsing && <span className="d" />}
            {badge.lock && <span className="lock">🔒</span>}
            {badge.label}
          </span>
        </div>
        <div className="pr-branch-row">
          <span className="pr-branch">{pr.baseBranch}</span>
          <span className="pr-branch-arrow">←</span>
          <span className="pr-branch">{pr.branchName}</span>
        </div>
      </div>

      <div className="pr-body-scroll">
        {onReviewChanges !== undefined && (
          <button type="button" className="pr-review-btn" onClick={onReviewChanges}>
            <span className="ic" aria-hidden>◧</span>
            Review changed files &amp; diff
            <span className="arrow" aria-hidden>→</span>
          </button>
        )}
        <div className="pr-section-h">Description</div>
        <div className={pr.status === 'local-drafted' ? 'pr-description drafting' : 'pr-description'}>
          {pr.description.trim().length > 0
            ? <MarkdownProse text={pr.description} />
            : <span style={{ color: 'var(--text-4)' }}>No description yet.</span>}
        </div>

        <div className="pr-section-h">Timeline · {timeline.length} event{timeline.length === 1 ? '' : 's'}</div>
        <PRTimeline events={timeline} mode={mode} />

        <PRActionBar
          pr={pr}
          openComments={openComments}
          localChecksPassed={localChecksPassed}
          onPush={onPush}
          onAskAgent={onAskAgent}
          onMerge={onMerge}
          onMergeAnyway={onMergeAnyway}
        />

        <div className="pr-section-h">Checks</div>
        <PRChecksCard
          kind="local"
          title="Validation scripts"
          checks={localChecks}
          dim={mode === 'remote'}
        />
        <PRChecksCard
          kind="remote"
          title="GitHub Actions"
          checks={remoteChecks}
          dim={mode === 'local'}
        />

        <div className="pr-section-h">Add a comment</div>
        <PRCommentComposer
          local={local}
          username={username}
          value={commentValue}
          onChange={onCommentChange}
          onSubmit={onAddComment}
        />
      </div>
    </div>
  );
}
