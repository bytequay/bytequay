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
import { renderMarkdown, type MarkdownRepoContext } from '../markdown';
import { highlightToHtml, languageForPath } from '../highlight';

/**
 * Renders a GitHub blob permalink (e.g.
 * {@code https://github.com/owner/repo/blob/<sha>/path/to/File.java#L512})
 * the way github.com does in a comment: a header with the file path + the
 * referenced line, and the actual source line(s) fetched from that commit.
 *
 * We fetch the whole file at the sha via {@code fetchFileBlob} (the only
 * blob read the bridge exposes) and slice out the referenced lines. The
 * snippet is plain monospace — we don't syntax-highlight, since the app
 * carries no highlighter.
 */
const BLOB_RE =
  /^https?:\/\/github\.com\/([^/\s]+)\/([^/\s]+)\/blob\/([0-9a-fA-F]{7,40})\/([^\s#]+)(?:#L(\d+)(?:-L(\d+))?)?$/;

export type ParsedPermalink = {
  owner: string;
  repo: string;
  sha: string;
  path: string;
  lineStart: number | null;
  lineEnd: number | null;
};

/** Parse a GitHub blob permalink, or null when the URL isn't one. */
export function parseGithubPermalink(url: string): ParsedPermalink | null {
  const m = BLOB_RE.exec(url.trim());
  if (m === null) {
    return null;
  }
  return {
    owner: m[1],
    repo: m[2],
    sha: m[3],
    path: decodeURIComponent(m[4]),
    lineStart: m[5] ? parseInt(m[5], 10) : null,
    lineEnd: m[6] ? parseInt(m[6], 10) : null,
  };
}

export function GithubPermalinkCard({ url }: { url: string }) {
  const parsed = parseGithubPermalink(url);
  const [lines, setLines] = useState<string[] | null>(null);
  const [failed, setFailed] = useState(false);

  const lineStart = parsed?.lineStart ?? null;
  useEffect(() => {
    if (parsed === null || lineStart === null) {
      return;
    }
    let cancelled = false;
    window.bridge.fetchFileBlob(`${parsed.owner}/${parsed.repo}`, parsed.path, parsed.sha)
      .then(res => { if (!cancelled) setLines(res.lines); })
      .catch(() => { if (!cancelled) setFailed(true); });
    return () => { cancelled = true; };
    // url fully determines the parsed target.
  }, [url]); // eslint-disable-line react-hooks/exhaustive-deps

  // Not a recognised permalink (or no line anchor) — fall back to a link
  // so the reference is at least clickable.
  if (parsed === null || lineStart === null) {
    return <a href={url} target="_blank" rel="noreferrer">{url}</a>;
  }

  const { repo, sha, path } = parsed;
  const end = parsed.lineEnd ?? lineStart;
  const snippet = lines !== null ? lines.slice(lineStart - 1, end) : null;
  const locLabel = end > lineStart ? `Lines ${lineStart} to ${end}` : `Line ${lineStart}`;
  const commitUrl = `https://github.com/${parsed.owner}/${repo}/commit/${sha}`;

  return (
    <div className="gh-permalink">
      <div className="gh-permalink__head">
        <a href={url} target="_blank" rel="noreferrer" className="gh-permalink__path">
          {repo}/{path}
        </a>
        <div className="gh-permalink__loc">
          {locLabel} in{' '}
          <a href={commitUrl} target="_blank" rel="noreferrer" className="gh-permalink__sha">
            {sha.slice(0, 7)}
          </a>
        </div>
      </div>
      <div className="gh-permalink__code">
        {failed ? (
          <div className="gh-permalink__note">Couldn’t load this snippet.</div>
        ) : snippet === null ? (
          <div className="gh-permalink__note">Loading…</div>
        ) : snippet.length === 0 ? (
          <div className="gh-permalink__note">Line not found at this commit.</div>
        ) : (
          snippet.map((line, i) => (
            <div key={i} className="gh-permalink__row">
              <span className="gh-permalink__lineno">{lineStart + i}</span>
              <code
                className="gh-permalink__linetext hljs"
                dangerouslySetInnerHTML={{ __html: line === '' ? ' ' : highlightToHtml(line, languageForPath(path)) }}
              />
            </div>
          ))
        )}
      </div>
    </div>
  );
}

type Segment = { kind: 'md'; text: string } | { kind: 'permalink'; url: string };

const PERMALINK_LINE_RE =
  /^\s*(https?:\/\/github\.com\/[^/\s]+\/[^/\s]+\/blob\/[0-9a-fA-F]{7,40}\/[^\s#]+#L\d+(?:-L\d+)?)\s*$/;

/**
 * Split a comment body into markdown runs and standalone GitHub blob
 * permalinks. github.com only "cards" a permalink when it sits on its own
 * line, so we match line-by-line and leave inline links alone.
 */
export function splitBodyOnPermalinks(body: string): Segment[] {
  const segments: Segment[] = [];
  let buffer: string[] = [];
  const flush = () => {
    if (buffer.length > 0) {
      segments.push({ kind: 'md', text: buffer.join('\n') });
      buffer = [];
    }
  };
  for (const line of body.split('\n')) {
    const m = PERMALINK_LINE_RE.exec(line);
    if (m !== null) {
      flush();
      segments.push({ kind: 'permalink', url: m[1] });
    }
    else {
      buffer.push(line);
    }
  }
  flush();
  return segments;
}

/**
 * Render a comment body as markdown, but lift standalone GitHub blob
 * permalinks out into {@link GithubPermalinkCard}s. Shared by the comment
 * + review-thread renderers so a pasted permalink cards everywhere. When
 * there's no permalink this is exactly the old single-block render.
 */
export function MarkdownWithPermalinks({
  body,
  className = 'prc-comment-body',
  repoContext,
}: {
  body: string;
  className?: string;
  repoContext?: MarkdownRepoContext;
}) {
  const segments = splitBodyOnPermalinks(body);
  if (segments.length === 1 && segments[0].kind === 'md') {
    return <div className={className} dangerouslySetInnerHTML={{ __html: renderMarkdown(body, repoContext) }} />;
  }
  return (
    <>
      {segments.map((seg, i) => {
        if (seg.kind === 'permalink') {
          return <GithubPermalinkCard key={i} url={seg.url} />;
        }
        if (seg.text.trim() === '') {
          return null;
        }
        return (
          <div
            key={i}
            className={className}
            dangerouslySetInnerHTML={{ __html: renderMarkdown(seg.text, repoContext) }}
          />
        );
      })}
    </>
  );
}
