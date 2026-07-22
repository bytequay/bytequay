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
import { useEffect, useState, type CSSProperties, type ReactNode } from 'react';
import type { PullRequestDto } from '../types';
import type { AgentReviewData } from '../review/agentReviewTypes';
import { workspaceRouteHash } from '../workspace/workspaceRoutes';
import PullDetailPane from './PullDetailPane';
import type { PullRow } from './model';
import { pullRowFromDto, toDashboardPr } from './workspaceModel';

/** Keeps one mounted detail pane in place while its host becomes a zoom overlay. */
export function PullDetailHost({ zoomed, onClose, normalStyle, children }: {
  zoomed: boolean;
  onClose: () => void;
  normalStyle?: CSSProperties;
  children: ReactNode;
}) {
  useEffect(() => {
    if (!zoomed) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [onClose, zoomed]);

  const hostStyle: CSSProperties = zoomed ? {
    position: 'fixed',
    inset: 0,
    zIndex: 60,
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    minWidth: 0,
    minHeight: 0,
    padding: 32,
    background: 'rgba(20,16,30,0.42)',
  } : normalStyle ?? {};
  const panelStyle: CSSProperties = zoomed ? {
    position: 'relative',
    display: 'flex',
    width: 'min(1248px, 100%)',
    height: 'min(900px, calc(100vh - 64px))',
    minWidth: 0,
    minHeight: 0,
    overflow: 'hidden',
    border: '1px solid rgba(0,0,0,0.13)',
    borderRadius: 14,
    background: '#fff',
    boxShadow: '0 24px 70px rgba(0,0,0,0.28)',
  } : {
    position: 'relative',
    display: 'flex',
    flex: 1,
    minWidth: 0,
    minHeight: 0,
    background: '#fff',
  };

  return (
    <div
      style={hostStyle}
      role={zoomed ? 'dialog' : undefined}
      aria-modal={zoomed ? true : undefined}
      aria-label={zoomed ? 'Pull request details' : undefined}
      onClick={zoomed ? event => {
        if (event.target === event.currentTarget) onClose();
      } : undefined}
    >
      <div style={panelStyle}>{children}</div>
    </div>
  );
}

/** Resolves a workspace list row into the unified PR model, then zooms it. */
export default function WorkspacePullDetailZoom({ workspaceId, pullRequest, onClose }: {
  workspaceId: string;
  pullRequest: PullRequestDto;
  onClose: () => void;
}) {
  const [row, setRow] = useState<PullRow | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [agentStartPending, setAgentStartPending] = useState(false);

  useEffect(() => {
    const fullName = pullRequest.repo;
    void window.bridge.recordSurfaceVisit({
      surfaceType: 'PR',
      surfaceId: `${fullName}#${pullRequest.number}`,
      title: `${pullRequest.title} #${pullRequest.number}`,
      context: fullName,
    })
      .then(() => window.dispatchEvent(new Event('footprint-recorded')))
      .catch(() => { /* fire-and-forget */ });
  }, [pullRequest]);

  useEffect(() => {
    let cancelled = false;
    setRow(null);
    setError(null);
    setAgentStartPending(false);
    const [owner, repo] = pullRequest.repo.split('/', 2);
    if (owner === undefined || repo === undefined || owner === '' || repo === '') {
      setError('This pull request has no repository identity.');
      return;
    }
    void window.bridge.getPrForRepoPull(owner, repo, pullRequest.number)
      .then(async pr => {
        const review = await window.bridge.getAgentReview(pr.id).catch((): null => null);
        if (cancelled) return;
        const base = pullRowFromDto(pullRequest);
        const state = review === null && base.hasAgent ? 'done' : reviewState(review);
        setRow({
          ...base,
          id: pr.id,
          hasAgent: state !== 'none',
          dto: { ...toDashboardPr(pullRequest), id: pr.id, reviewState: state },
        });
      })
      .catch(reason => {
        if (!cancelled) setError(reason instanceof Error ? reason.message : 'Could not open this pull request.');
      });
    return () => { cancelled = true; };
  }, [pullRequest]);

  const assignAgent = () => {
    if (row === null) return;
    const previous = row;
    setAgentStartPending(true);
    setRow({ ...row, hasAgent: true, dto: { ...row.dto, reviewState: 'running' } });
    void window.bridge.startAgentReview(row.id, { workspaceId })
      .then(() => setAgentStartPending(false))
      .catch(() => {
        setAgentStartPending(false);
        setRow(current => current?.id === previous.id ? previous : current);
      });
  };

  const openAgent = () => {
    window.location.hash = workspaceRouteHash({
      kind: 'pull-request', workspaceId, number: pullRequest.number, agentColumn: true,
    });
  };

  return (
    <PullDetailHost zoomed onClose={onClose}>
      {row === null ? (
        <div style={{ flex: 1, display: 'grid', placeItems: 'center', color: error === null ? '#8b949e' : '#cf222e', fontSize: 13 }}>
          {error ?? 'Loading pull request…'}
        </div>
      ) : (
        <PullDetailPane
          key={row.id}
          row={row}
          zoomed
          onToggleZoom={onClose}
          onAssignAgent={assignAgent}
          onWorkWithAgent={agentStartPending ? undefined : openAgent}
        />
      )}
    </PullDetailHost>
  );
}

function reviewState(review: AgentReviewData | null): 'none' | 'running' | 'done' | 'stale' {
  if (review === null) return 'none';
  if (review.rounds.some(round => round.status === 'QUEUED' || round.status === 'RUNNING')) {
    return 'running';
  }
  return review.review.status === 'STALE' ? 'stale' : 'done';
}
