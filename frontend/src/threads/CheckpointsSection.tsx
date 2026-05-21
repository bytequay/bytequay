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
import { useEffect, useState } from 'react';
import type { ThreadCheckpointDto } from '../types';
import { threadTokenLabel } from './threadDisplay';
import { useCheckpoints } from './useCheckpoints';

type Props = {
  threadId: string;
  /** Bridge to the parent's SSE handler — we register a refetch
   *  trigger that fires on TurnDone so newly-generated checkpoints
   *  show up without a manual reload. Same pattern as
   *  {@link useConvIndex}'s onUpstreamEvent. */
  sseRef?: React.MutableRefObject<((name: string) => void) | null>;
};

/**
 * Sidebar Checkpoints rail. Renders the active Overall card (purple
 * gradient) on top, then per-segment cards in descending seq order.
 * The "+ save" button manually fires the scheduler's generate path so
 * the user can pin a summary at a natural breakpoint without waiting
 * for the token threshold to trip.
 *
 * <p>Click-through detail drawer (full Markdown body, model + cost
 * metadata, "Continue this work" cross-app shortcut) is deferred —
 * the list-only first cut lets us validate the cadence before we
 * commit to the drawer's shape.
 */
export function CheckpointsSection({ threadId, sseRef }: Props) {
  const cp = useCheckpoints(threadId);

  // Register the refetch trigger with the parent's SSE handler.
  useEffect(() => {
    if (!sseRef) return;
    sseRef.current = cp.onUpstreamEvent;
    return () => { sseRef.current = null; };
  }, [sseRef, cp.onUpstreamEvent]);

  const overall = cp.rows.find(r => r.isOverall) ?? null;
  const segments = cp.rows.filter(r => !r.isOverall);

  // "Summariser disabled" banner — the scheduler crossed the
  // threshold but the background call threw. The most common cause
  // is an unset Anthropic API key, so we treat that text specially
  // and link to the settings page.
  const schedErr = cp.schedulerError;
  const isMissingKey = schedErr !== null && /api key not configured/i.test(schedErr);

  return (
    <div style={listStyle}>
      {schedErr !== null && (
        <div style={isMissingKey ? warnBannerStyle : errorBannerStyle}>
          {isMissingKey ? (
            <>
              Summariser disabled — configure an Anthropic API key in
              <em> Settings → AI review </em>to enable checkpoint summaries.
            </>
          ) : (
            <>Last summarise attempt failed: {schedErr}</>
          )}
        </div>
      )}
      {cp.error !== null && cp.rows.length === 0 && (
        <div style={errorStyle}>{cp.error}</div>
      )}
      {cp.rows.length === 0 && !cp.loading && cp.error === null && schedErr === null && (
        <div style={emptyStyle}>
          No summaries yet — auto-saved at ~25k tokens, or hit
          <em> + save </em>below.
        </div>
      )}
      {overall && (
        <CheckpointCard cp={overall} variant="overall" />
      )}
      {segments.map(seg => (
        <CheckpointCard key={seg.id} cp={seg} variant="segment" />
      ))}
      <div style={footerStyle}>
        <button
          type="button"
          onClick={() => { void cp.generate(); }}
          disabled={cp.generating}
          style={generateBtnStyle}
          title="Force-generate a summary of everything since the last segment"
        >
          {cp.generating ? '✶ summarising…' : '+ save checkpoint'}
        </button>
      </div>
      {cp.generateError !== null && (
        <div style={errorStyle}>{cp.generateError}</div>
      )}
    </div>
  );
}

function CheckpointCard({
  cp, variant,
}: {
  cp: ThreadCheckpointDto;
  variant: 'overall' | 'segment';
}) {
  const [expanded, setExpanded] = useState(false);
  const ago = relativeAgo(cp.generatedAt);
  const tokenLabel = threadTokenLabel(cp.tokensCovered);
  const isOverall = variant === 'overall';
  const titleParts = isOverall
    ? `Overall · turns ${cp.firstMsgSeq}–${cp.lastMsgSeq}`
    : `cp-${cp.seq} · turns ${cp.firstMsgSeq}–${cp.lastMsgSeq}`;
  const cardStyle = isOverall ? cardOverallStyle : cardSegmentStyle;
  return (
    <div style={cardStyle}>
      <div style={cardHeaderStyle}>
        <span style={isOverall ? titleOverallStyle : titleStyle}>{titleParts}</span>
        <span style={metaStyle}>{tokenLabel} · {ago}</span>
      </div>
      {cp.bulletTitles.length > 0 && (
        <ul style={bulletListStyle}>
          {cp.bulletTitles.map((b, i) => (
            <li key={i} style={bulletStyle}>{b}</li>
          ))}
        </ul>
      )}
      {expanded && cp.summaryMd && (
        <pre style={summaryStyle}>{cp.summaryMd}</pre>
      )}
      {cp.summaryMd && (
        <button
          type="button"
          onClick={() => setExpanded(v => !v)}
          style={expandBtnStyle}
        >
          {expanded ? 'hide details' : 'show details'}
        </button>
      )}
    </div>
  );
}

function relativeAgo(iso: string): string {
  const then = Date.parse(iso);
  if (Number.isNaN(then)) return '';
  const diffSec = Math.max(1, Math.round((Date.now() - then) / 1000));
  if (diffSec < 60) return `${diffSec}s ago`;
  const m = Math.round(diffSec / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h}h ago`;
  const d = Math.round(h / 24);
  return `${d}d ago`;
}

const listStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
};

const cardSegmentStyle: React.CSSProperties = {
  border: '1px solid var(--border)',
  background: 'var(--bg-elevated)',
  borderRadius: 6,
  padding: '6px 8px',
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
};

const cardOverallStyle: React.CSSProperties = {
  ...cardSegmentStyle,
  // Soft accent wash + thicker left border to mark it as the rollup
  // — matches the design's "purple gradient" guidance without
  // committing to a literal gradient before we have the rest of the
  // tinted-card system.
  background: 'var(--accent-a10)',
  borderColor: 'var(--accent-a40)',
  borderLeftWidth: 3,
  borderLeftColor: 'var(--accent)',
};

const cardHeaderStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'baseline',
  justifyContent: 'space-between',
  gap: 6,
};

const titleStyle: React.CSSProperties = {
  fontSize: 11,
  fontWeight: 600,
  color: 'var(--text-1)',
};

const titleOverallStyle: React.CSSProperties = {
  ...titleStyle,
  color: 'var(--accent-dark)',
  letterSpacing: '0.02em',
};

const metaStyle: React.CSSProperties = {
  fontSize: 10,
  color: 'var(--text-4)',
  whiteSpace: 'nowrap',
};

const bulletListStyle: React.CSSProperties = {
  margin: 0,
  padding: '0 0 0 14px',
  fontSize: 11,
  color: 'var(--text-2)',
  lineHeight: 1.4,
  listStyle: 'disc',
};

const bulletStyle: React.CSSProperties = {
  marginBottom: 1,
};

const summaryStyle: React.CSSProperties = {
  margin: 0,
  padding: '6px 8px',
  background: 'var(--bg-card)',
  border: '1px solid var(--border)',
  borderRadius: 4,
  fontSize: 11,
  lineHeight: 1.45,
  color: 'var(--text-2)',
  whiteSpace: 'pre-wrap',
  overflowWrap: 'anywhere',
  fontFamily: 'inherit',
};

const expandBtnStyle: React.CSSProperties = {
  alignSelf: 'flex-start',
  border: 'none',
  background: 'transparent',
  padding: 0,
  color: 'var(--accent-dark)',
  fontSize: 10,
  cursor: 'pointer',
  textDecoration: 'underline',
};

const footerStyle: React.CSSProperties = {
  display: 'flex',
  marginTop: 4,
};

const generateBtnStyle: React.CSSProperties = {
  flex: 1,
  padding: '5px 8px',
  fontSize: 11,
  border: '1px dashed var(--border)',
  background: 'transparent',
  borderRadius: 4,
  color: 'var(--text-2)',
  cursor: 'pointer',
};

const errorStyle: React.CSSProperties = {
  padding: '4px 6px',
  fontSize: 10.5,
  color: '#b91c1c',
  fontStyle: 'italic',
};

const emptyStyle: React.CSSProperties = {
  padding: '4px 2px',
  fontSize: 11,
  color: 'var(--text-3)',
  lineHeight: 1.4,
};

const warnBannerStyle: React.CSSProperties = {
  padding: '6px 8px',
  fontSize: 11,
  lineHeight: 1.45,
  color: '#9a6700',
  background: 'rgba(255, 197, 0, 0.10)',
  border: '1px solid rgba(255, 197, 0, 0.45)',
  borderRadius: 4,
};

const errorBannerStyle: React.CSSProperties = {
  padding: '6px 8px',
  fontSize: 11,
  lineHeight: 1.45,
  color: '#b91c1c',
  background: 'rgba(185, 28, 28, 0.06)',
  border: '1px solid rgba(185, 28, 28, 0.35)',
  borderRadius: 4,
};
