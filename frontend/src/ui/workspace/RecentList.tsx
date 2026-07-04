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
import { useEffect, useMemo, useState } from 'react';
import type { FootprintStopDto, PullRequestDto, SurfaceType } from '../../types';
import { FootprintIcon, type IconKind } from '../../footprints/FootprintIcon';
import { relativeTime } from '../../notificationDisplay';

const MAX_ROWS = 8;

function iconFor(surfaceType: SurfaceType): IconKind {
  switch (surfaceType) {
    case 'PR_KANBAN': return 'kanban';
    case 'PR':        return 'pull-request';
    case 'TASK':      return 'robot';
    case 'THREAD':    return 'message';
  }
}

function isToday(iso: string): boolean {
  const d = new Date(iso);
  const now = new Date();
  return d.getFullYear() === now.getFullYear()
    && d.getMonth() === now.getMonth()
    && d.getDate() === now.getDate();
}

/** How recently (today) the user engaged with a PR — reviewed/approved or
 *  just opened it. 0 means not engaged today. */
function engagedAt(p: PullRequestDto): number {
  return Math.max(
    p.reviewedAt !== null && isToday(p.reviewedAt) ? Date.parse(p.reviewedAt) : 0,
    p.viewedAt !== null && isToday(p.viewedAt) ? Date.parse(p.viewedAt) : 0);
}

type TodayBuckets = { workingOn: PullRequestDto[]; reviewed: PullRequestDto[]; merged: PullRequestDto[] };

/** The three "Today" PR buckets behind the summary: your authored PRs still
 *  in progress (open, not merged), PRs you engaged with today, and your
 *  authored PRs merged today. Each newest-activity first. */
export function todayBuckets(prs: PullRequestDto[]): TodayBuckets {
  return {
    workingOn: prs
      .filter(p => p.origin === 'AUTHORED' && p.mergedAt === null && p.state !== 'closed' && isToday(p.updatedAt))
      .sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt)),
    reviewed: prs
      .filter(p => p.handledAction !== 'DISMISSED' && engagedAt(p) > 0)
      .sort((a, b) => engagedAt(b) - engagedAt(a)),
    merged: prs
      .filter(p => p.origin === 'AUTHORED' && p.mergedAt !== null && isToday(p.mergedAt))
      .sort((a, b) => Date.parse(b.mergedAt as string) - Date.parse(a.mergedAt as string)),
  };
}

function bullets(list: PullRequestDto[]): string {
  if (list.length === 0) return '_None_';
  return list.map(p => `* ${p.title} [#${p.number}](${p.htmlUrl})`).join('\n');
}

/** A standup-style Markdown summary: three sections, each a bullet list of
 *  `pr_title #pr_number` with the number linking to the PR. */
export function todayMarkdown(b: TodayBuckets): string {
  return [
    '## Working on', bullets(b.workingOn), '',
    '## Reviewed', bullets(b.reviewed), '',
    '## Merged', bullets(b.merged),
  ].join('\n');
}

/**
 * The sidebar's "Recent" section, shown on the Home surface in place
 * of the workspace list: the most recently visited surfaces (PRs,
 * tasks, threads), newest first, backed by the footprints visit
 * capture, plus a compact "Today" summary (what's being worked on +
 * the latest PR reviewed today). Clicking a row resumes that surface.
 */
export function RecentList({ onResume, onOpenPr }: {
  onResume?: (stop: FootprintStopDto) => void;
  /** Open a PR from the Today summary's "Reviewed" line. */
  onOpenPr?: (owner: string, repo: string, prNumber: number) => void;
}) {
  const [stops, setStops] = useState<FootprintStopDto[]>([]);
  const [prs, setPrs] = useState<PullRequestDto[]>([]);
  const [copied, setCopied] = useState(false);

  // The list is mounted for as long as the rail shows (i.e. across every
  // non-workspace surface), so refresh on a slow poll — visits recorded
  // while the user moves around should show up without a remount.
  useEffect(() => {
    let cancelled = false;
    const refresh = () => {
      void window.bridge.getFootprints()
        .then(trail => {
          // The trail arrives oldest-first; the sidebar wants newest on top.
          if (!cancelled) setStops(trail.stops.slice().reverse().slice(0, MAX_ROWS));
        })
        .catch(() => { /* non-fatal — section renders empty */ });
      void window.bridge.fetchPrs()
        .then(list => { if (!cancelled) setPrs(list); })
        .catch(() => { /* summary line just stays hidden */ });
    };
    refresh();
    const id = window.setInterval(refresh, 20_000);
    return () => {
      cancelled = true;
      window.clearInterval(id);
    };
  }, []);

  const workingOn = stops[0] ?? null;
  const buckets = useMemo(() => todayBuckets(prs), [prs]);
  const reviewedToday = buckets.reviewed[0] ?? null;
  const hasToday = workingOn !== null || reviewedToday !== null
    || buckets.workingOn.length > 0 || buckets.merged.length > 0;

  const handleCopy = () => {
    const md = todayMarkdown(buckets);
    const done = () => { setCopied(true); window.setTimeout(() => setCopied(false), 1500); };
    navigator.clipboard.writeText(md).then(done).catch(() => {
      // Clipboard can fail on a sandbox / permissions issue — fall back.
      const ta = document.createElement('textarea');
      ta.value = md;
      document.body.appendChild(ta);
      ta.select();
      document.execCommand('copy');
      document.body.removeChild(ta);
      done();
    });
  };

  return (
    <>
      <div className="sb-section">
        <div className="sb-section-h">
          <span className="nm">Recent</span>
        </div>
        {stops.length === 0 ? (
          <p className="sb-recent__empty">Nothing visited yet today.</p>
        ) : (
          <div className="sb-recent">
            {stops.map(stop => (
              <button
                key={`${stop.surfaceType}:${stop.surfaceId}`}
                type="button"
                className="sb-recent__row"
                onClick={() => onResume?.(stop)}
                title={stop.context ?? stop.surfaceId}
              >
                <span className="sb-recent__icon" aria-hidden="true">
                  <FootprintIcon kind={iconFor(stop.surfaceType)} size={12} />
                </span>
                <span className="sb-recent__meta">
                  <span className="sb-recent__title">{stop.title ?? stop.surfaceId}</span>
                  <span className="sb-recent__sub">
                    {stop.context ?? stop.surfaceId} · {relativeTime(stop.latestVisitAt)}
                  </span>
                </span>
              </button>
            ))}
          </div>
        )}
      </div>

      {hasToday && (
        <div className="sb-section">
          <div className="sb-section-h">
            <span className="nm">Today</span>
            <div className="actions">
              <button
                type="button"
                onClick={handleCopy}
                title="Copy today's summary as Markdown"
                aria-label="Copy today's summary as Markdown"
              >
                {copied ? '✓' : '⧉'}
              </button>
            </div>
          </div>
          <div className="sb-recent">
            {workingOn !== null && (
              <button
                type="button"
                className="sb-recent__row"
                onClick={() => onResume?.(workingOn)}
                title={workingOn.context ?? workingOn.surfaceId}
              >
                <span className="sb-recent__meta">
                  <span className="sb-recent__label">Working on</span>
                  <span className="sb-recent__title">{workingOn.title ?? workingOn.surfaceId}</span>
                </span>
              </button>
            )}
            {reviewedToday !== null && (
              <button
                type="button"
                className="sb-recent__row"
                onClick={() => {
                  const slash = reviewedToday.repo.indexOf('/');
                  if (slash > 0) {
                    onOpenPr?.(
                        reviewedToday.repo.slice(0, slash),
                        reviewedToday.repo.slice(slash + 1),
                        reviewedToday.number);
                  }
                }}
                title={`${reviewedToday.repo} #${reviewedToday.number}`}
              >
                <span className="sb-recent__meta">
                  <span className="sb-recent__label">Reviewed</span>
                  <span className="sb-recent__title">{reviewedToday.title} #{reviewedToday.number}</span>
                </span>
              </button>
            )}
          </div>
        </div>
      )}
    </>
  );
}
