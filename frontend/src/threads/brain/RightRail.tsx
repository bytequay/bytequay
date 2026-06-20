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
import type { ApprovalDto, CommitDto, ContextWindowDto, TaskBrainViewData } from '../../types/brainView';
import { LinkedPRCard } from './LinkedPRCard';
import { formatTokensK, relativeShort } from './format';

type Props = {
  rail: TaskBrainViewData['rightRail'];
  nowMs: number;
  onApprove: (approval: ApprovalDto) => void;
  onMerge: () => void;
  onViewDiff: () => void;
  onViewContext: () => void;
  onPause: () => void;
  onClose: () => void;
};

function ApprovalCard({ approval, onApprove }: { approval: ApprovalDto; onApprove: () => void }) {
  return (
    <div className="approval-card">
      <div className="hd"><span className="ic" aria-hidden>⏳</span>Approval needed</div>
      <div className="stage-name">{approval.stageTitle}</div>
      <div className="kv">
        <div className="kv-row">
          <div className="kv-lbl">Why</div>
          <div className="kv-val">{approval.reasonShort}</div>
        </div>
        <div className="kv-row">
          <div className="kv-lbl">Pending fix</div>
          <div className="kv-val mono">{approval.pendingArtifact}</div>
        </div>
      </div>
      <button type="button" className="btn" onClick={onApprove}>{approval.primaryAction.label}</button>
    </div>
  );
}

function CommitsCard({ commits, nowMs, onViewDiff }: { commits: CommitDto[]; nowMs: number; onViewDiff: () => void }) {
  const latest = commits[0];
  return (
    <div>
      <div className="sec-h">Commits <span className="r">this task · {commits.length}</span></div>
      <div className="commits-card" style={{ marginTop: 7 }}>
        {latest !== undefined ? (
          <>
            <div className="msg">{latest.subject}</div>
            <div className="meta">
              <span className="sha">{latest.sha}</span>
              <span className="when">{relativeShort(latest.authoredAt, nowMs)}</span>
            </div>
          </>
        ) : (
          <div className="msg">No commits yet</div>
        )}
        <button type="button" className="diff-btn" onClick={onViewDiff}>⇄ View code diff</button>
      </div>
    </div>
  );
}

function ContextWindowCard({ ctx, onViewContext }: { ctx: ContextWindowDto; onViewContext: () => void }) {
  const pct = ctx.tokensLimit > 0 ? Math.round((ctx.tokensUsed / ctx.tokensLimit) * 100) : 0;
  const fillCls = ctx.safeBand === 'safe' ? '' : ctx.safeBand;
  return (
    <div>
      <div className="sec-h">Context window <span className="r">brain agent</span></div>
      <div className="ctx-card" style={{ marginTop: 7 }}>
        <div className="head">
          <span className={`pct ${ctx.safeBand}`}>{pct}% {ctx.safeBand}</span>
          <span className="ratio">{formatTokensK(ctx.tokensUsed)} / {formatTokensK(ctx.tokensLimit)}</span>
        </div>
        <div className="track"><div className={`fill ${fillCls}`} style={{ width: `${pct}%` }} /></div>
        <button type="button" className="view-btn" onClick={onViewContext}>
          ⬛ View full context <span className="kbd">⌘⇧I</span>
        </button>
      </div>
    </div>
  );
}

/**
 * Right rail — the actionable stack: a pending approval card (only when
 * a stage needs attention), the linked PR with its integrated merge
 * button, recent commits, the brain agent's context window meter, and
 * the pause/close controls.
 */
export function RightRail({
  rail, nowMs, onApprove, onMerge, onViewDiff, onViewContext, onPause, onClose,
}: Props) {
  return (
    <aside className="right-rail">
      {rail.approval !== null && (
        <ApprovalCard approval={rail.approval} onApprove={() => onApprove(rail.approval as ApprovalDto)} />
      )}

      {rail.linkedPr !== null && (
        <div>
          <div className="sec-h">Linked PR</div>
          <div style={{ marginTop: 7 }}>
            <LinkedPRCard pr={rail.linkedPr} onMerge={onMerge} />
          </div>
        </div>
      )}

      <CommitsCard commits={rail.recentCommits} nowMs={nowMs} onViewDiff={onViewDiff} />

      <ContextWindowCard ctx={rail.context} onViewContext={onViewContext} />

      <div className="pause-card">
        <button type="button" className="pause-btn" onClick={onPause}>⏸ Pause task</button>
        <button type="button" className="close-btn" onClick={onClose}>⏹ Close task</button>
      </div>
    </aside>
  );
}
