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
import type { RepoActivityItemDto, RepoMetaDto } from './types';
import Avatar from './Avatar';
import LogoLoading from './LogoLoading';
import { formatRelative } from './prBuckets';

type Props = {
  owner: string;
  repo: string;
};

/**
 * Right-pane overview shown on the repo detail page when no PR is
 * selected. Replaces the old HelpPanel with a GitHub-style hero card,
 * About + language bar, and a Recent activity feed — per
 * docs/design/pr-dashboard/repo-prs.png. Two parallel fetches
 * (/meta and /activity) on mount; each renders independently so a
 * slow events fetch doesn't block the hero.
 */
function RepoOverviewPanel({ owner, repo }: Props) {
  const [meta, setMeta] = useState<RepoMetaDto | null>(null);
  const [activity, setActivity] = useState<RepoActivityItemDto[] | null>(null);
  const [metaError, setMetaError] = useState<string | null>(null);
  const [activityError, setActivityError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setMeta(null);
    setMetaError(null);
    window.bridge.getRepoMeta(owner, repo)
      .then(d => { if (!cancelled) setMeta(d); })
      .catch(e => { if (!cancelled) setMetaError(e instanceof Error ? e.message : String(e)); });
    return () => { cancelled = true; };
  }, [owner, repo]);

  useEffect(() => {
    let cancelled = false;
    setActivity(null);
    setActivityError(null);
    window.bridge.getRepoActivity(owner, repo)
      .then(d => { if (!cancelled) setActivity(d); })
      .catch(e => { if (!cancelled) setActivityError(e instanceof Error ? e.message : String(e)); });
    return () => { cancelled = true; };
  }, [owner, repo]);

  const fullName = `${owner}/${repo}`;
  const githubUrl = meta?.htmlUrl ?? `https://github.com/${fullName}`;

  return (
    <div className="repo-overview">
      <div className="repo-overview__watched">
        <div className="repo-overview__watched-text">
          <span aria-hidden="true">📡</span>
          You're watching this repo. ByteQuay polls it every 60s for new
          PRs and CI updates.
        </div>
      </div>

      {/* Hero card. Renders the moment /meta resolves; placeholder
          spinner takes the same vertical room so the layout doesn't
          jump when the data lands. */}
      {meta ? (
        <RepoHero meta={meta} fullName={fullName} githubUrl={githubUrl} owner={owner} />
      ) : metaError ? (
        <FriendlyError what="repo info" raw={metaError} />
      ) : (
        <div className="repo-overview__hero-loading">
          <LogoLoading size={48} label={`Loading ${fullName}`} />
        </div>
      )}

      {meta && <RepoAbout meta={meta} />}

      <RepoActivity items={activity} error={activityError} githubUrl={githubUrl} />
    </div>
  );
}

/** Wraps a raw IPC/HTTP error string into a humane message. The raw
 *  GitHub rate-limit response is a giant JSON blob that's useless to
 *  the user — we strip it down to "rate limit hit, try again later"
 *  and keep the original behind a details disclosure for debugging. */
function FriendlyError({ what, raw }: { what: string; raw: string }) {
  const isRateLimit = /API rate limit exceeded|secondary rate limit/i.test(raw);
  if (isRateLimit) {
    return (
      <div className="repo-overview__error">
        <strong>GitHub rate limit reached.</strong> ByteQuay can't load
        the {what} right now — GitHub caps authenticated requests at
        5,000/hour and we've burnt through them. Try again in a few
        minutes; the limit resets on a rolling window.
      </div>
    );
  }
  return (
    <div className="repo-overview__error">
      Couldn't load {what}: {raw}
    </div>
  );
}

function RepoHero({ meta, fullName, githubUrl, owner }: {
  meta: RepoMetaDto;
  fullName: string;
  githubUrl: string;
  owner: string;
}) {
  const fmt = (n: number) => n.toLocaleString();
  return (
    <section className="repo-hero">
      <div className="repo-hero__head">
        <Avatar login={owner} size={28} className="repo-hero__avatar" />
        <h1 className="repo-hero__name">{fullName}</h1>
        <a
          className="repo-hero__github-link"
          href="#"
          onClick={(e) => { e.preventDefault(); void window.bridge.openExternal(githubUrl); }}
        >
          View on GitHub ↗
        </a>
      </div>
      {meta.description && (
        <p className="repo-hero__tagline">{meta.description}</p>
      )}
      <div className="repo-hero__stats">
        <span className="repo-hero__stat">
          <span aria-hidden="true">★</span> <strong>{fmt(meta.stargazersCount)}</strong> stars
        </span>
        <span className="repo-hero__stat">
          <strong>{fmt(meta.forksCount)}</strong> forks
        </span>
        <span className="repo-hero__stat">
          <strong>{fmt(meta.watchersCount)}</strong> watching
        </span>
      </div>
      {meta.topics.length > 0 && (
        <div className="repo-hero__topics">
          {meta.topics.map(t => (
            <span key={t} className="repo-hero__topic">{t}</span>
          ))}
        </div>
      )}
    </section>
  );
}

function RepoAbout({ meta }: { meta: RepoMetaDto }) {
  const langs = useMemo(() => {
    const total = Object.values(meta.languages).reduce((s, n) => s + n, 0);
    if (total === 0) return [] as Array<{ name: string; pct: number }>;
    return Object.entries(meta.languages)
      .map(([name, bytes]) => ({ name, pct: (bytes / total) * 100 }))
      .sort((a, b) => b.pct - a.pct);
  }, [meta.languages]);

  return (
    <section className="repo-about">
      <h2 className="repo-about__title">About</h2>
      {meta.description ? (
        <p className="repo-about__desc">{meta.description}</p>
      ) : (
        <p className="repo-about__desc repo-about__desc--empty">No description provided.</p>
      )}
      <div className="repo-about__chips">
        {meta.license && (
          <span className="repo-about__chip">⚖ {meta.license}</span>
        )}
        {meta.defaultBranch && (
          <span className="repo-about__chip">⎇ {meta.defaultBranch}</span>
        )}
      </div>

      {langs.length > 0 && (
        <>
          <h3 className="repo-about__subtitle">Languages</h3>
          <div className="repo-about__lang-bar" aria-hidden="true">
            {langs.map((l, i) => (
              <span
                key={l.name}
                className={`repo-about__lang-seg repo-about__lang-seg--c${(i % 8) + 1}`}
                style={{ width: `${l.pct}%` }}
                title={`${l.name} ${l.pct.toFixed(1)}%`}
              />
            ))}
          </div>
          <div className="repo-about__lang-legend">
            {langs.slice(0, 6).map((l, i) => (
              <span key={l.name} className="repo-about__lang-item">
                <span className={`repo-about__lang-dot repo-about__lang-dot--c${(i % 8) + 1}`} aria-hidden="true" />
                {l.name} {l.pct.toFixed(1)}%
              </span>
            ))}
          </div>
        </>
      )}
    </section>
  );
}

function RepoActivity({ items, error, githubUrl }: {
  items: RepoActivityItemDto[] | null;
  error: string | null;
  githubUrl: string;
}) {
  return (
    <section className="repo-activity">
      <h2 className="repo-activity__title">Recent activity</h2>
      {error ? (
        <FriendlyError what="activity" raw={error} />
      ) : items === null ? (
        <div className="repo-activity__loading">
          <LogoLoading size={36} label="Loading activity" />
        </div>
      ) : items.length === 0 ? (
        <div className="repo-activity__empty">No recent activity.</div>
      ) : (
        <ul className="repo-activity__list">
          {items.map((it, i) => (
            <li key={i} className="repo-activity__item">
              <span className={`repo-activity__icon repo-activity__icon--${activityIconClass(it.type)}`} aria-hidden="true">
                {activityIcon(it.type)}
              </span>
              <div className="repo-activity__body">
                <a
                  className="repo-activity__title-link"
                  href="#"
                  onClick={(e) => { e.preventDefault(); void window.bridge.openExternal(it.htmlUrl); }}
                >
                  {it.title}
                </a>
                <div className="repo-activity__meta">
                  {it.actor && <span>@{it.actor}</span>}
                  {it.actor && it.createdAt && <span> · </span>}
                  {it.createdAt && <span>{formatRelative(it.createdAt)}</span>}
                </div>
              </div>
            </li>
          ))}
        </ul>
      )}
      <div className="repo-activity__see-more">
        <a
          href="#"
          onClick={(e) => { e.preventDefault(); void window.bridge.openExternal(githubUrl); }}
        >
          See full activity on GitHub ↗
        </a>
      </div>
    </section>
  );
}

function activityIcon(type: string): string {
  switch (type) {
    case 'PushEvent': return '⤴';
    case 'PullRequestEvent': return '⬢';
    case 'PullRequestReviewEvent': return '◎';
    case 'IssuesEvent': return '◌';
    case 'IssueCommentEvent': return '💬';
    case 'ReleaseEvent': return '🚀';
    case 'CreateEvent': return '+';
    case 'DeleteEvent': return '−';
    default: return '·';
  }
}

function activityIconClass(type: string): string {
  switch (type) {
    case 'PushEvent': return 'push';
    case 'PullRequestEvent': return 'pr';
    case 'PullRequestReviewEvent': return 'review';
    case 'IssuesEvent':
    case 'IssueCommentEvent': return 'issue';
    case 'ReleaseEvent': return 'release';
    default: return 'other';
  }
}

export default RepoOverviewPanel;
