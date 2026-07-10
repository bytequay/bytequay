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

type Props = {
  /** Filesystem path of the working copy. Used to find the matching
   *  tracked repo (owner/repo) and from there the cached owner
   *  avatar served by the backend. */
  workingDir: string | null | undefined;
  /** Square edge in px — defaults to 18 for inline use. */
  size?: number;
  /** Optional fallback when the path doesn't match any tracked
   *  repo or the meta lookup fails; rendered as a coloured letter
   *  glyph so the slot doesn't go blank. */
  fallbackGradient?: string;
};

type Resolved = {
  url: string | null;
  initial: string;
  ownerRepo: string;
};

/**
 * Resolves a thread's working directory to its GitHub owner avatar
 * and renders the image. The lookup runs in two hops:
 *
 *  1. {@code listLocalRepos} maps clone paths → (owner, repo).
 *  2. {@code getRepoMeta(owner, repo)} returns the cached owner
 *     avatar URL.
 *
 * Both calls are memoised in module-level maps so a threads page
 * with N tiles re-uses results across tiles without N round-trips
 * to the backend. While the avatar is loading or unavailable, a
 * coloured letter glyph stands in so the slot never goes blank.
 */
export default function RepoAvatar({ workingDir, size = 18, fallbackGradient }: Props) {
  const [resolved, setResolved] = useState<Resolved | null>(() =>
    workingDir ? readCache(workingDir) : null);

  useEffect(() => {
    if (!workingDir) {
      setResolved(null);
      return;
    }
    let cancelled = false;
    void resolveAvatar(workingDir).then(r => {
      if (!cancelled) setResolved(r);
    });
    return () => { cancelled = true; };
  }, [workingDir]);

  const initial = resolved?.initial ?? deriveInitial(workingDir);
  const title = resolved?.ownerRepo ?? workingDir ?? '';

  const fallback = (
    <span
      style={{
        ...glyphStyle,
        width: size,
        height: size,
        fontSize: Math.max(8, Math.round(size * 0.55)),
        background: fallbackGradient ?? gradientFor(workingDir ?? initial),
      }}
      title={title}
      aria-label={title}
    >
      {initial}
    </span>
  );

  if (!resolved?.url) return fallback;
  return (
    <img
      src={resolved.url}
      alt={title}
      title={title}
      width={size}
      height={size}
      style={{ ...imgStyle, width: size, height: size }}
      // The cache survived but the URL turned out to be 404 / blocked —
      // wipe it so the next mount tries a fresh fetch instead of
      // re-using a known-bad URL, then show the letter fallback.
      onError={() => {
        avatarCache.set(resolved.ownerRepo, null);
        setResolved({ ...resolved, url: null });
      }}
    />
  );
}

// ────────────────────────────────────────────────────────────────────
// Cache + resolution
// ────────────────────────────────────────────────────────────────────

/** localClonePath → owner/repo. Filled once per process from the
 *  first {@code listLocalRepos} call; subsequent lookups go straight
 *  through. Acceptable staleness: if the user adds a repo mapping
 *  after the cache fills, the avatar stays blank until next reload —
 *  cheap to invalidate later if it becomes a problem. */
const pathToRepoCache = new Map<string, { owner: string; repo: string } | null>();
let pathCachePromise: Promise<void> | null = null;

/** owner/repo → avatar URL. Null means "we looked, no avatar". */
const avatarCache = new Map<string, string | null>();
const inflightAvatar = new Map<string, Promise<string | null>>();

function normalisePath(p: string): string {
  return p.replace(/\/+$/, '');
}

async function ensurePathCache(): Promise<void> {
  if (pathCachePromise) return pathCachePromise;
  pathCachePromise = (async () => {
    try {
      const list = await window.bridge.listLocalRepos();
      for (const r of list) {
        if (r.localClonePath) {
          pathToRepoCache.set(normalisePath(r.localClonePath),
            { owner: r.owner, repo: r.repo });
        }
      }
    }
    catch {
      // Leave the cache empty — every subsequent lookup will return
      // null and the components fall back to the letter glyph.
    }
  })();
  return pathCachePromise;
}

async function fetchAvatar(owner: string, repo: string): Promise<string | null> {
  const key = `${owner}/${repo}`;
  if (avatarCache.has(key)) return avatarCache.get(key) ?? null;
  const existing = inflightAvatar.get(key);
  if (existing) return existing;
  const p = (async () => {
    try {
      const meta = await window.bridge.getRepoMeta(owner, repo);
      const url = meta.ownerAvatarUrl ?? null;
      avatarCache.set(key, url);
      return url;
    }
    catch {
      avatarCache.set(key, null);
      return null;
    }
    finally {
      inflightAvatar.delete(key);
    }
  })();
  inflightAvatar.set(key, p);
  return p;
}

function readCache(workingDir: string): Resolved | null {
  const ref = pathToRepoCache.get(normalisePath(workingDir));
  if (!ref) return null;
  const url = avatarCache.get(`${ref.owner}/${ref.repo}`);
  return {
    url: url ?? null,
    initial: deriveInitial(ref.repo),
    ownerRepo: `${ref.owner}/${ref.repo}`,
  };
}

/** Resolve a task/thread {@code workingDir} (repo clone path) to its
 *  {owner, repo}, or null when no local-repo mapping matches. Reuses the
 *  same path cache the avatar lookup fills, so callers that need to
 *  deep-link into the in-app PR page don't duplicate the resolution. */
export async function resolveRepoRef(
  workingDir: string | null | undefined,
): Promise<{ owner: string; repo: string } | null> {
  if (!workingDir) return null;
  await ensurePathCache();
  return pathToRepoCache.get(normalisePath(workingDir)) ?? null;
}

/** Resolve a bare repo name (no owner, e.g. from a workspace card's
 *  repo chip) to its cached GitHub owner-avatar URL, by scanning the
 *  tracked local-repo list for a name match. Distinct from
 *  {@code resolveRepoRef}, which keys off a working-directory path.
 *  Null when no tracked repo matches or it has no avatar. */
export async function resolveAvatarByRepoName(repoName: string): Promise<string | null> {
  await ensurePathCache();
  for (const ref of pathToRepoCache.values()) {
    if (ref && ref.repo === repoName) {
      return fetchAvatar(ref.owner, ref.repo);
    }
  }
  return null;
}

async function resolveAvatar(workingDir: string): Promise<Resolved> {
  await ensurePathCache();
  const ref = pathToRepoCache.get(normalisePath(workingDir));
  if (!ref) {
    return { url: null, initial: deriveInitial(workingDir), ownerRepo: '' };
  }
  const url = await fetchAvatar(ref.owner, ref.repo);
  return {
    url,
    initial: deriveInitial(ref.repo),
    ownerRepo: `${ref.owner}/${ref.repo}`,
  };
}

function deriveInitial(s: string | null | undefined): string {
  if (!s) return '?';
  const trimmed = s.replace(/\/+$/, '');
  const last = trimmed.includes('/') ? trimmed.slice(trimmed.lastIndexOf('/') + 1) : trimmed;
  const ch = last.match(/[A-Za-z0-9]/)?.[0];
  return (ch ?? '?').toUpperCase();
}

/** Deterministic gradient for the letter fallback so repeated
 *  renders of the same path always pick the same colour. */
function gradientFor(seed: string): string {
  const palette = [
    'linear-gradient(135deg, #0ea5e9, #075985)',
    'linear-gradient(135deg, #14b8a6, #0f766e)',
    'linear-gradient(135deg, #6366f1, #3730a3)',
    'linear-gradient(135deg, #f97316, #c2410c)',
    'linear-gradient(135deg, #ec4899, #9d174d)',
    'linear-gradient(135deg, #84cc16, #4d7c0f)',
  ];
  let h = 0;
  for (let i = 0; i < seed.length; i++) {
    h = ((h << 5) - h + seed.charCodeAt(i)) | 0;
  }
  return palette[Math.abs(h) % palette.length];
}

// ────────────────────────────────────────────────────────────────────
// Styles
// ────────────────────────────────────────────────────────────────────

const imgStyle: React.CSSProperties = {
  borderRadius: 4,
  flexShrink: 0,
  objectFit: 'cover',
  display: 'inline-block',
};
const glyphStyle: React.CSSProperties = {
  borderRadius: 4,
  color: '#fff',
  fontWeight: 700,
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  flexShrink: 0,
  lineHeight: 1,
};
