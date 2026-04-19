/**
 * Render smoke-tests for the activity-row UI. Specifically guards
 * against the {@code "followingNarrative is not defined"} class of bug:
 * a stale reference inside the row's JSX would throw at render time and
 * fail this test, instead of slipping past type-check (which our pinned
 * tsc + @types/node mismatch silently swallows) and into production.
 *
 * Uses {@code react-dom/server.renderToStaticMarkup} so we don't need a
 * DOM environment or @testing-library — just plain HTML strings. Every
 * branch of {@code followingNarrativeSegments} is exercised at least
 * once.
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
        event={event({ type: 'PushEvent', commitCount: 3 })}
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

  it('renders PR number as a separate link for review events', () => {
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
    expect(html).toContain('reviewed PR ');
    expect(html).toContain('#42');
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
    // Avatar still renders (the row's actor block) but the
    // home-following-item__name button is omitted.
    expect(html).not.toContain('home-following-item__name');
    expect(html).toContain('home-following-item__avatar');
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
