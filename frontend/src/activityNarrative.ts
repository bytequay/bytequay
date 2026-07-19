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
import type { RecentEventDto } from './types';

/** A piece of activity-narrative text. {@link url} marks the segment as a
 *  clickable link (the renderer wires it up via {@code openUrl}); plain
 *  segments render as static text. Used to make repo / PR / issue
 *  mentions in the home-page activity feeds tappable. */
export type NarrativeSegment = { text: string; url?: string; emphasized?: boolean };

/** Collapses runs of consecutive PushEvents, and separately runs of
 *  consecutive PullRequestReviewCommentEvents, to the same repo (and same
 *  PR, when the payload carries one) into a single row that carries a
 *  {@code pushCount} / {@code commentCount}. Events are assumed
 *  newest-first; the merged row keeps the newest timestamp, sums commits,
 *  and inherits the group's PR title. Events of other types, or matching
 *  events separated by other activity, pass through unmerged. */
export function groupRecentEvents(events: RecentEventDto[]): RecentEventDto[] {
  // A submitted review surfaces as a PullRequestReviewEvent plus one
  // PullRequestReviewCommentEvent per inline comment — the same action seen
  // twice. When both are present for a PR, keep only "reviewed PR X" and drop
  // the redundant "commented on PR X N times" row.
  const reviewedPrs = new Set<string>();
  for (const e of events) {
    if (e.type === 'PullRequestReviewEvent' && typeof e.prNumber === 'number') {
      reviewedPrs.add(`${e.repo}#${e.prNumber}`);
    }
  }

  const out: RecentEventDto[] = [];
  for (const e of events) {
    if (e.type === 'PullRequestReviewCommentEvent' && typeof e.prNumber === 'number'
        && reviewedPrs.has(`${e.repo}#${e.prNumber}`)) {
      continue;
    }
    const prev = out[out.length - 1];
    if (e.type === 'PushEvent' && prev && prev.type === 'PushEvent'
        && prev.repo === e.repo && prev.prNumber === e.prNumber && prev.ref === e.ref) {
      out[out.length - 1] = {
        ...prev,
        commitCount: prev.commitCount + e.commitCount,
        pushCount: (prev.pushCount ?? 1) + 1,
        prTitle: prev.prTitle ?? e.prTitle,
      };
    }
    else if (e.type === 'PullRequestReviewEvent' && prev && prev.type === e.type
        && prev.repo === e.repo && prev.prNumber === e.prNumber) {
      // Multiple reviews on the same PR (e.g. approve + comment submitted
      // separately) collapse to one "reviewed PR X" row.
      out[out.length - 1] = { ...prev, prTitle: prev.prTitle ?? e.prTitle };
    }
    else if (e.type === 'PullRequestReviewCommentEvent' && prev && prev.type === e.type
        && prev.repo === e.repo && prev.prNumber === e.prNumber) {
      out[out.length - 1] = {
        ...prev,
        commentCount: (prev.commentCount ?? 1) + 1,
        prTitle: prev.prTitle ?? e.prTitle,
      };
    }
    else {
      out.push(e);
    }
  }
  return out;
}

export function repoUrl(repo: string): string { return `https://github.com/${repo}`; }
export function prUrl(repo: string, number: number): string { return `https://github.com/${repo}/pull/${number}`; }
export function issueUrl(repo: string, number: number): string { return `https://github.com/${repo}/issues/${number}`; }
export function commitsUrl(repo: string): string { return `https://github.com/${repo}/commits`; }

/** Turns a recent event into a sequence of text + link segments. Only
 *  the actionable anchors are linked — PR numbers, issue numbers, and
 *  commits-page references — so the repo name renders as plain text.
 *  Watch / Fork / Create / unknown events have no anchor of their own
 *  and become entirely plain text. */
export function followingNarrativeSegments(e: RecentEventDto): NarrativeSegment[] {
  // Repo segment is plain text — the user explicitly does not want the
  // repo to be a click target in this surface.
  const repoSeg: NarrativeSegment = { text: e.repo };
  const hasNumber = typeof e.prNumber === 'number' && e.prNumber > 0;
  const prSeg = (): NarrativeSegment | null => hasNumber
    ? { text: `#${e.prNumber}`, url: prUrl(e.repo, e.prNumber), emphasized: true }
    : null;
  const prObject = (): NarrativeSegment | null => {
    if (!e.prTitle && !hasNumber) return null;
    return {
      text: e.prTitle ?? `PR #${e.prNumber}`,
      url: hasNumber ? prUrl(e.repo, e.prNumber) : undefined,
      emphasized: true,
    };
  };
  const issueSeg = (): NarrativeSegment | null => hasNumber
    ? { text: `#${e.prNumber}`, url: issueUrl(e.repo, e.prNumber), emphasized: true }
    : null;

  switch (e.type) {
    case 'PullRequestEvent': {
      const verb = e.action === 'opened' ? 'opened' : e.action === 'closed' ? 'closed' : 'updated';
      const pr = prObject();
      return pr
        ? [{ text: `${verb} ` }, pr, { text: ' in ' }, repoSeg]
        : [{ text: verb + ' ' }, { text: 'a PR', emphasized: true }, { text: ' in ' }, repoSeg];
    }
    case 'PullRequestReviewEvent': {
      const pr = prObject();
      const state = e.reviewState?.toLowerCase().replace('_', ' ');
      const outcome = state ? [{ text: ` · ${state}` }] : [];
      return pr
        ? [{ text: 'reviewed ' }, pr, ...outcome, { text: ' in ' }, repoSeg]
        : [{ text: 'reviewed ' }, { text: 'a PR', emphasized: true }, ...outcome, { text: ' in ' }, repoSeg];
    }
    case 'PullRequestReviewCommentEvent': {
      const pr = prObject();
      // Merged rows (multiple consecutive comments on the same PR) count
      // comments — "commented on PR #4043 3 times".
      const countPhrase = e.commentCount && e.commentCount > 1 ? ` ${e.commentCount} times` : '';
      return pr
        ? [{ text: 'commented on ' }, pr, { text: `${countPhrase} in ` }, repoSeg]
        : [{ text: 'commented on ' }, { text: `a PR${countPhrase}`, emphasized: true }, { text: ' in ' }, repoSeg];
    }
    case 'PushEvent': {
      const n = e.commitCount || 1;
      // Merged rows (multiple consecutive pushes) count push events —
      // "pushed 4 times"; a single row counts commits — "pushed 1 commit".
      const countPhrase = e.pushCount && e.pushCount > 1
        ? `${e.pushCount} times`
        : `${n} commit${n !== 1 ? 's' : ''}`;
      const pr = prSeg();
      if (pr) {
        return [{ text: `pushed ${countPhrase} to PR ` }, pr, { text: ' in ' }, repoSeg];
      }
      const branch = branchName(e.ref);
      // No PR: link the count phrase to the repo's commits page since the
      // event payload doesn't carry per-commit SHAs. Repo name is plain text.
      return [
        { text: 'pushed ' },
        { text: countPhrase, url: commitsUrl(e.repo), emphasized: true },
        { text: branch ? ` to ${branch} in ` : ' to ' },
        repoSeg,
      ];
    }
    case 'IssueCommentEvent': {
      const iss = issueSeg();
      return iss
        ? [{ text: 'commented on issue ' }, iss, { text: ' in ' }, repoSeg]
        : [{ text: 'commented on ' }, { text: 'an issue', emphasized: true }, { text: ' in ' }, repoSeg];
    }
    case 'IssuesEvent': {
      const verb = e.action === 'opened' ? 'opened' : 'closed';
      const iss = issueSeg();
      return iss
        ? [{ text: `${verb} issue ` }, iss, { text: ' in ' }, repoSeg]
        : [{ text: verb + ' ' }, { text: 'an issue', emphasized: true }, { text: ' in ' }, repoSeg];
    }
    case 'CreateEvent': {
      if (e.refType === 'repository') {
        return [{ text: 'created repository ' }, { ...repoSeg, emphasized: true }];
      }
      const branch = e.ref ? `branch ${e.ref}` : 'a branch';
      return [{ text: 'created ' }, { text: branch, emphasized: true }, { text: ' in ' }, repoSeg];
    }
    case 'WatchEvent':
      return [{ text: 'starred ' }, { ...repoSeg, emphasized: true }];
    case 'ForkEvent':
      return [{ text: 'forked ' }, { ...repoSeg, emphasized: true }];
    case 'DeleteEvent': {
      const branch = e.ref ? `branch ${e.ref}` : 'a branch or tag';
      return [{ text: 'deleted ' }, { text: branch, emphasized: true }, { text: ' from ' }, repoSeg];
    }
    case 'ReleaseEvent':
      return [{ text: 'published ' }, { text: 'a release', emphasized: true }, { text: ' in ' }, repoSeg];
    case 'MemberEvent':
      return [{ text: 'updated ' }, { text: 'collaborator access', emphasized: true }, { text: ' in ' }, repoSeg];
    case 'PublicEvent':
      return [{ text: 'made ' }, { ...repoSeg, emphasized: true }, { text: ' public' }];
    case 'CommitCommentEvent':
      return [{ text: 'commented on ' }, { text: 'a commit', emphasized: true }, { text: ' in ' }, repoSeg];
    case 'GollumEvent':
      return [{ text: 'updated ' }, { text: 'wiki pages', emphasized: true }, { text: ' in ' }, repoSeg];
    default: {
      const eventName = e.type.replace(/Event$/, '').replace(/([a-z])([A-Z])/g, '$1 $2').toLowerCase();
      return [{ text: 'recorded ' }, { text: `${eventName} activity`, emphasized: true }, { text: ' in ' }, repoSeg];
    }
  }
}

function branchName(ref?: string | null): string | null {
  if (!ref) return null;
  return ref.replace(/^refs\/heads\//, '');
}
