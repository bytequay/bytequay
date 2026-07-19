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
import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import ActivityRow, { NarrativeText } from './ActivityRow';
import { followingNarrativeSegments } from './activityNarrative';
import type { RecentEventDto } from './types';

function event(overrides: Partial<RecentEventDto> = {}): RecentEventDto {
  return {
    type: 'PullRequestEvent',
    repo: 'trinodb/trino',
    createdAt: '2026-04-29T12:00:00Z',
    commitCount: 0,
    action: 'opened',
    prTitle: null,
    prNumber: 1234,
    refType: null,
    actorLogin: 'octocat',
    ...overrides,
  };
}

const noop = () => { /* test-only handler */ };

describe('ActivityRow', () => {
  it('renders without throwing for every event type', () => {
    const types: RecentEventDto['type'][] = [
      'PullRequestEvent',
      'PullRequestReviewEvent',
      'PullRequestReviewCommentEvent',
      'PushEvent',
      'IssueCommentEvent',
      'IssuesEvent',
      'CreateEvent',
      'WatchEvent',
      'ForkEvent',
      'GollumEvent',
    ];
    for (const type of types) {
      const html = renderToStaticMarkup(
        <ActivityRow
          event={event({ type })}
          actor={{ login: 'octocat', profileUrl: 'https://github.com/octocat' }}
          showActorName={true}
          formatTime={(iso) => iso}
          onOpenUrl={noop}
        />,
      );
      expect(html).toContain('home-following-item');
    }
  });

  it('PushEvent links the "N commits" phrase to the commits page (repo is plain text)', () => {
    const html = renderToStaticMarkup(
      <ActivityRow
        event={event({ type: 'PushEvent', commitCount: 3, prNumber: 0 })}
        actor={{ login: 'octocat', profileUrl: 'https://github.com/octocat' }}
        showActorName={true}
        formatTime={(iso) => iso}
        onOpenUrl={noop}
      />,
    );
    expect(html).toContain('https://github.com/trinodb/trino/commits');
    expect(html).toContain('home-following-item__link');
    expect(html).toContain('pushed ');
    expect(html).toContain('3 commits');
    // Repo URL alone should NOT appear as a link target — only the
    // commits subpath should.
    expect(html).not.toContain('title="https://github.com/trinodb/trino"');
  });

  it('renders a linked PR fallback for review events without a title', () => {
    const html = renderToStaticMarkup(
      <ActivityRow
        event={event({ type: 'PullRequestReviewEvent', prNumber: 42 })}
        actor={null}
        showActorName={false}
        formatTime={() => 'just now'}
        onOpenUrl={noop}
      />,
    );
    expect(html).toContain('https://github.com/trinodb/trino/pull/42');
    expect(html).toContain('Reviewed ');
    expect(html).toContain('PR #42');
  });

  it('renders the PR title in the sentence and keeps the number in the detail line', () => {
    const html = renderToStaticMarkup(
      <ActivityRow
        event={event({
          type: 'PullRequestReviewEvent',
          prNumber: 30384,
          prTitle: 'Improve exchange source memory accounting',
        })}
        actor={{ login: 'octocat', profileUrl: 'https://github.com/octocat' }}
        showActorName={true}
        formatTime={() => '19m'}
        onOpenUrl={noop}
      />,
    );
    expect(html).toContain('reviewed ');
    expect(html).toContain('Improve exchange source memory accounting');
    expect(html).toContain('Review submitted on PR #30384');
  });

  it('renders issues path (not pull) for IssuesEvent', () => {
    const html = renderToStaticMarkup(
      <ActivityRow
        event={event({ type: 'IssuesEvent', action: 'opened', prNumber: 99 })}
        actor={null}
        showActorName={false}
        formatTime={() => 'just now'}
        onOpenUrl={noop}
      />,
    );
    expect(html).toContain('https://github.com/trinodb/trino/issues/99');
    expect(html).not.toContain('/pull/99');
  });

  it('hides the actor name when showActorName=false (own-activity card)', () => {
    const ownEvent = event({ type: 'PushEvent', actorLogin: 'me' });
    const html = renderToStaticMarkup(
      <ActivityRow
        event={ownEvent}
        actor={{ login: 'me', profileUrl: 'https://github.com/me' }}
        showActorName={false}
        formatTime={() => '1m ago'}
        onOpenUrl={noop}
      />,
    );
    expect(html).not.toContain('home-following-item__name');
    expect(html).toContain('home-activity-icon--commit');
    expect(html).not.toContain('home-following-item__avatar');
  });

  it('uses the actor avatar URL supplied by the GitHub event payload', () => {
    const html = renderToStaticMarkup(
      <ActivityRow
        event={event({ actorAvatarUrl: 'https://avatars.githubusercontent.com/u/1?v=4' })}
        actor={{
          login: 'octocat',
          profileUrl: 'https://github.com/octocat',
          avatarUrl: 'https://avatars.githubusercontent.com/u/1?v=4',
        }}
        showActorName={true}
        formatTime={() => '2h'}
        onOpenUrl={noop}
      />,
    );
    expect(html).toContain('https://avatars.githubusercontent.com/u/1?v=4');
  });

  it('renders a concrete second-line payload', () => {
    const html = renderToStaticMarkup(
      <ActivityRow
        event={event({ type: 'PushEvent', detail: 'fix: persist inbox acknowledgement' })}
        actor={null}
        showActorName={false}
        formatTime={() => '2h'}
        onOpenUrl={noop}
      />,
    );
    expect(html).toContain('home-following-item__detail');
    expect(html).toContain('fix: persist inbox acknowledgement');
  });
});

describe('NarrativeText', () => {
  it('renders linked segments as <button> and plain segments as <span>', () => {
    // PullRequestEvent has the PR linked + the repo as plain text — a
    // good case for asserting both branches render correctly.
    const html = renderToStaticMarkup(
      <NarrativeText
        segments={followingNarrativeSegments(event({ type: 'PullRequestEvent', prNumber: 7 }))}
        onLinkClick={noop}
      />,
    );
    expect(html).toContain('<button');
    expect(html).toContain('https://github.com/trinodb/trino/pull/7');
    // Plain segments stay inside <span>; the repo segment is plain text now.
    expect(html).toContain('<span>trinodb/trino</span>');
  });

  it('Watch / Fork / Create events render with no <button> (no anchor target)', () => {
    const html = renderToStaticMarkup(
      <NarrativeText
        segments={followingNarrativeSegments(event({ type: 'WatchEvent' }))}
        onLinkClick={noop}
      />,
    );
    expect(html).toContain('<span>starred </span>');
    expect(html).not.toContain('<button');
  });
});
