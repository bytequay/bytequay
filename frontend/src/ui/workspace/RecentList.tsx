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
import type { FootprintStopDto, PullRequestDto, SurfaceType, WorkUnitTaskDto } from '../../types';
import { relativeTime } from '../../relativeTime';
import { taskLabel } from '../../threads/taskLabel';
import { isToday } from '../../format';

const MAX_ROWS = 4;
// ponytail: a flat cap per bucket rather than a "+N more" overflow affordance —
// bump this (or add overflow UI) if a bucket routinely needs more room.
const TODAY_BUCKET_MAX = 5;

type RecentStop = FootprintStopDto & {
  recentRepo?: string;
  recentNumber?: number | null;
};

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
export async function enrichTitles(stops: FootprintStopDto[]): Promise<RecentStop[]> {
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
      return task === undefined ? stop : {
        ...stop,
        title: taskLabel(task),
        recentRepo: taskRepo(task),
        recentNumber: task.prNumber ?? linkedPrNumber(task.linkedPrRef),
      };
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
  const [stops, setStops] = useState<RecentStop[]>([]);
  const [prs, setPrs] = useState<PullRequestDto[]>([]);

  // The list is mounted for as long as the rail shows (i.e. across every
  // non-workspace surface), so refresh on a slow poll — visits recorded
  // while the user moves around should show up without a remount.
  useEffect(() => {
    let cancelled = false;
    const refresh = () => {
      const recentStops = window.bridge.getFootprints()
        .then(trail => trail.stops.filter(stop =>
          stop.surfaceType === 'PR'
          || stop.surfaceType === 'TASK'
          || stop.surfaceType === 'THREAD'))
        // The trail arrives oldest-first; the sidebar wants newest on top.
        .then(stops => stops.slice().reverse().slice(0, MAX_ROWS))
        .then(enrichTitles);
      const dashboardPrs = window.bridge.fetchPrs().catch((): PullRequestDto[] => []);
      void Promise.all([recentStops, dashboardPrs])
        .then(async ([enriched, listed]) => {
          const known = new Set(listed.map(pr => `${pr.repo}#${pr.number}`));
          const missing = enriched
            .map(stop => stop.surfaceType === 'PR' ? parsePrRef(stop.surfaceId) : null)
            .filter((ref): ref is NonNullable<typeof ref> => ref !== null && !known.has(ref.full));
          const fetched = typeof window.bridge.getRepoPull === 'function'
            ? await Promise.all(missing.map(ref => window.bridge
              .getRepoPull(ref.owner, ref.repo, ref.number)
              .catch((): PullRequestDto | null => null)))
            : [];
          if (!cancelled) {
            setStops(enriched);
            setPrs([...listed, ...fetched.filter((pr): pr is PullRequestDto => pr !== null)]);
          }
        })
        .catch(() => { /* non-fatal — section renders empty */ });
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
    for (const p of prs) m.set(`${p.repo}#${p.number}`, p.title);
    return m;
  }, [prs]);
  const prsByRef = useMemo(() => new Map(prs.map(pr => [`${pr.repo}#${pr.number}`, pr])), [prs]);
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
            <button
              key={`${stop.surfaceType}:${stop.surfaceId}`}
              type="button"
              className="sb-recent__row"
              onClick={() => onResume?.(stop)}
              title={stop.context ?? stop.surfaceId}
            >
              <span className={`sb-recent__icon sb-recent__icon--${recentState(stop, prsByRef)}`} aria-hidden="true">
                <RecentSurfaceIcon
                  kind={stop.surfaceType}
                  merged={recentState(stop, prsByRef) === 'merged'}
                />
              </span>
              <span className="sb-recent__meta">
                <span className="sb-recent__title">{rowTitle(stop)}</span>
                <span className="sb-recent__sub">{recentSubline(stop)}</span>
              </span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

function RecentSurfaceIcon({ kind, merged }: { kind: SurfaceType; merged: boolean }) {
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
  if (kind === 'TASK' || kind === 'THREAD') {
    return (
      <svg {...common}>
        <rect x="4.5" y="4.5" width="15" height="15" rx="2" />
        <path d="m8.4 12.3 2.4 2.4 4.8-5.2" />
      </svg>
    );
  }
  if (kind === 'PR') {
    return merged ? (
      <svg {...common}>
        <circle cx="6" cy="5.5" r="2.3" /><circle cx="6" cy="18.5" r="2.3" /><circle cx="18" cy="12" r="2.3" />
        <path d="M6 7.8v8.4M6 8a7.6 7.6 0 0 0 7.6 4h2.1" />
      </svg>
    ) : (
      <svg {...common}>
        <circle cx="6" cy="5.5" r="2.3" /><circle cx="6" cy="18.5" r="2.3" /><circle cx="18" cy="18.5" r="2.3" />
        <path d="M6 7.8v8.4M11.3 5.5H15a3 3 0 0 1 3 3v7.7" />
      </svg>
    );
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

function linkedPrNumber(ref: string | null): number | null {
  if (!ref) return null;
  const number = Number(ref.slice(ref.lastIndexOf('#') + 1));
  return Number.isInteger(number) && number > 0 ? number : null;
}

function parsePrRef(ref: string): { owner: string; repo: string; number: number; full: string } | null {
  const hash = ref.lastIndexOf('#');
  const slash = ref.indexOf('/');
  const number = Number(ref.slice(hash + 1));
  if (slash < 1 || hash <= slash || !Number.isInteger(number) || number < 1) return null;
  return {
    owner: ref.slice(0, slash),
    repo: ref.slice(slash + 1, hash),
    number,
    full: ref,
  };
}

function taskRepo(task: WorkUnitTaskDto): string {
  if (task.linkedPrRef) return task.linkedPrRef.split('#')[0];
  const path = task.workingDir?.replace(/\/$/, '');
  return path?.slice(path.lastIndexOf('/') + 1) || 'task';
}

function recentState(stop: RecentStop, prs: Map<string, PullRequestDto>): 'open' | 'merged' | 'task' {
  if (stop.surfaceType !== 'PR') return 'task';
  return prs.get(stop.surfaceId)?.mergedAt ? 'merged' : 'open';
}

function recentSubline(stop: RecentStop): string {
  const time = relativeTime(stop.latestVisitAt).replace(' ago', '');
  if (stop.surfaceType === 'PR') {
    const hash = stop.surfaceId.lastIndexOf('#');
    const repo = hash > 0 ? stop.surfaceId.slice(0, hash) : stop.context ?? 'pull request';
    const number = hash > 0 ? stop.surfaceId.slice(hash) : '';
    return [repo, number, time].filter(Boolean).join(' · ');
  }
  return [stop.recentRepo ?? 'task', stop.recentNumber ? `#${stop.recentNumber}` : 'task', time].join(' · ');
}
