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
import { taskLabel } from '../../threads/taskLabel';

const MAX_ROWS = 4;
// ponytail: a flat cap per bucket rather than a "+N more" overflow affordance —
// bump this (or add overflow UI) if a bucket routinely needs more room.
const TODAY_BUCKET_MAX = 5;

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

/** One "Today" bucket's rows — every PR in the bucket (capped), with the
 *  group's eyebrow label shown once on the first row rather than repeated
 *  on every row. */
export function TodayGroupRows({ label, prs, onOpen }: {
  label: string;
  prs: PullRequestDto[];
  onOpen: (pr: PullRequestDto) => void;
}) {
  return (
    <>
      {prs.slice(0, TODAY_BUCKET_MAX).map((pr, i) => (
        <button
          key={`${label}-${pr.id}`}
          type="button"
          className="sb-recent__row"
          onClick={() => onOpen(pr)}
          title={`${pr.repo} #${pr.number}`}
        >
          <span className="sb-recent__meta">
            {i === 0 && <span className="sb-recent__label">{label}</span>}
            <span className="sb-recent__title">{pr.title} #{pr.number}</span>
          </span>
        </button>
      ))}
    </>
  );
}

/**
 * `navToSurfaceVisit` (footprints/surfaceVisit.ts) captures TASK/THREAD
 * visits with a generic placeholder title ("Task"/"Thread") — the nav
 * layer only carries ids, not names, at the moment a visit fires. This
 * fills in the real name at *read* time instead: for each TASK/THREAD
 * stop, look up its current name via the same calls the detail pages
 * already use, and swap it in. A lookup failure (task/thread since
 * deleted, offline) is non-fatal — that row just keeps its placeholder.
 */
export async function enrichTitles(stops: FootprintStopDto[]): Promise<FootprintStopDto[]> {
  const taskThreadIds = new Set<string>();
  for (const stop of stops) {
    const slash = stop.surfaceId.indexOf('/');
    if (stop.surfaceType === 'TASK' && slash > 0) {
      taskThreadIds.add(stop.surfaceId.slice(0, slash));
    }
  }

  const tasksByThread = new Map<string, Awaited<ReturnType<typeof window.bridge.listTasksForThread>>>();
  await Promise.all([...taskThreadIds].map(async threadId => {
    try {
      tasksByThread.set(threadId, await window.bridge.listTasksForThread(threadId));
    }
    catch { /* non-fatal — this thread's task rows keep their placeholder */ }
  }));

  return stops.map(stop => {
    if (stop.surfaceType === 'TASK') {
      const slash = stop.surfaceId.indexOf('/');
      if (slash <= 0) return stop;
      const threadId = stop.surfaceId.slice(0, slash);
      const taskId = stop.surfaceId.slice(slash + 1);
      const task = tasksByThread.get(threadId)?.find(t => t.id === taskId);
      return task === undefined ? stop : { ...stop, title: taskLabel(task) };
    }
    return stop;
  });
}

/**
 * The sidebar's "Continue" section, shown on the Home surface in place
 * of the workspace list: the most recently visited PRs and tasks,
 * newest first, backed by the footprints visit
 * capture, plus a compact "Today" summary (what's being worked on +
 * the latest PR reviewed today). Clicking a row resumes that surface.
 */
export function RecentList({ onResume }: {
  onResume?: (stop: FootprintStopDto) => void;
  /** Open a PR from the Today summary's "Reviewed" line. */
  onOpenPr?: (owner: string, repo: string, prNumber: number) => void;
}) {
  const [stops, setStops] = useState<FootprintStopDto[]>([]);
  const [prs, setPrs] = useState<PullRequestDto[]>([]);

  // The list is mounted for as long as the rail shows (i.e. across every
  // non-workspace surface), so refresh on a slow poll — visits recorded
  // while the user moves around should show up without a remount.
  useEffect(() => {
    let cancelled = false;
    const refresh = () => {
      void window.bridge.getFootprints()
        .then(trail => trail.stops.filter(stop =>
          stop.surfaceType === 'PR'
          || stop.surfaceType === 'TASK'
          || stop.surfaceType === 'THREAD'))
        // The trail arrives oldest-first; the sidebar wants newest on top.
        .then(stops => stops.slice().reverse().slice(0, MAX_ROWS))
        .then(enrichTitles)
        .then(enriched => { if (!cancelled) setStops(enriched); })
        .catch(() => { /* non-fatal — section renders empty */ });
      void window.bridge.fetchPrs()
        .then(list => { if (!cancelled) setPrs(list); })
        .catch(() => { /* summary line just stays hidden */ });
    };
    refresh();
    const id = window.setInterval(refresh, 20_000);
    // A visit was just recorded by the nav layer — pick it up immediately
    // instead of waiting for the poll (this list stays mounted across
    // navigations, so the newly visited surface would otherwise lag).
    window.addEventListener('footprint-recorded', refresh);
    return () => {
      cancelled = true;
      window.clearInterval(id);
      window.removeEventListener('footprint-recorded', refresh);
    };
  }, []);

  // A PR's stored footprint title is whatever the visit that recorded it
  // knew — the kanban stores "PR title #num", but opening the same PR via
  // the repo view (e.g. resuming from this list) re-records with only a
  // generic "owner/repo #num". Prefer the real dashboard title at read time
  // so the row keeps its name across entry points.
  const prTitles = useMemo(() => {
    const m = new Map<string, string>();
    for (const p of prs) m.set(`${p.repo}#${p.number}`, `${p.title} #${p.number}`);
    return m;
  }, [prs]);
  const rowTitle = (stop: FootprintStopDto): string =>
    (stop.surfaceType === 'PR' ? prTitles.get(stop.surfaceId) : undefined)
    ?? stop.title ?? stop.surfaceId;

  return (
    <div className="sb-section sb-section--recent">
      <div className="sb-section-h">
        <span className="nm">Recent</span>
      </div>
      {stops.length === 0 ? (
        <p className="sb-recent__empty">Nothing recent yet.</p>
      ) : (
        <div className="sb-recent">
          {stops.map(stop => (
            <div
              key={`${stop.surfaceType}:${stop.surfaceId}`}
              role="button"
              tabIndex={0}
              className="sb-recent__row"
              onClick={() => onResume?.(stop)}
              onKeyDown={event => {
                if (event.key === 'Enter' || event.key === ' ') {
                  event.preventDefault();
                  onResume?.(stop);
                }
              }}
              title={stop.context ?? stop.surfaceId}
            >
              <span className="sb-recent__icon" aria-hidden="true">
                <RecentSurfaceIcon kind={stop.surfaceType} />
              </span>
              <span className="sb-recent__meta">
                <span className="sb-recent__title">{rowTitle(stop)}</span>
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function RecentSurfaceIcon({ kind }: { kind: SurfaceType }) {
  const common = {
    width: 13,
    height: 13,
    viewBox: '0 0 24 24',
    fill: 'none',
    stroke: 'currentColor',
    strokeWidth: 1.8,
    strokeLinecap: 'round' as const,
    strokeLinejoin: 'round' as const,
  };
  if (kind === 'TASK') {
    return (
      <svg {...common}>
        <circle cx="18" cy="18" r="2.6" />
        <circle cx="6" cy="6" r="2.6" />
        <path d="M13 6h3a2 2 0 0 1 2 2v7" />
        <path d="M6 9v12" />
      </svg>
    );
  }
  if (kind === 'THREAD') {
    return (
      <svg {...common}>
        <circle cx="6" cy="6" r="2.4" />
        <circle cx="6" cy="18" r="2.4" />
        <circle cx="18" cy="12" r="2.4" />
        <path d="M8.3 7.2 15.7 11M8.3 16.8 15.7 13" />
      </svg>
    );
  }
  if (kind === 'PR') {
    return <svg {...common}><circle cx="12" cy="12" r="8.5" /></svg>;
  }
  return (
    <svg {...common}>
      <rect x="3" y="3" width="7" height="7" rx="1.6" />
      <rect x="14" y="3" width="7" height="7" rx="1.6" />
      <rect x="3" y="14" width="7" height="7" rx="1.6" />
      <rect x="14" y="14" width="7" height="7" rx="1.6" />
    </svg>
  );
}
