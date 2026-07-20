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
import { useState } from 'react';
import type { CSSProperties } from 'react';
import CurrentUserAvatar from '../CurrentUserAvatar';

/**
 * Shared presentational atoms for the redesigned PR screens, ported from the
 * DC prototypes in docs/mockups/design/pr-redesign/. Values (gradients,
 * radii, sizes) are copied verbatim from the prototypes' logic classes —
 * treat them as design constants, not tunables.
 */

const AV_PALETTE: [string, string][] = [
  ['linear-gradient(135deg,#93c5fd,#2563eb)', '#fff'],
  ['linear-gradient(135deg,#fda4af,#e11d48)', '#fff'],
  ['linear-gradient(135deg,#6ee7b7,#059669)', '#fff'],
  ['linear-gradient(135deg,#c4b5fd,#7c3aed)', '#fff'],
  ['linear-gradient(135deg,#f9a8d4,#db2777)', '#fff'],
  ['linear-gradient(135deg,#fcd34d,#d97706)', '#78350f'],
];

export function avInit(name: string): string {
  return name.replace(/[^a-zA-Z]/g, '').slice(0, 2).toUpperCase() || 'U';
}

function avFallbackStyle(name: string, size: number, square: boolean): CSSProperties {
  let h = 0;
  for (let i = 0; i < name.length; i++) h = (h + name.charCodeAt(i) * 7) % AV_PALETTE.length;
  const [bg, fg] = AV_PALETTE[h];
  return {
    width: size,
    height: size,
    borderRadius: square ? 6 : '50%',
    background: bg,
    color: fg,
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
    fontSize: Math.round(size * 0.36),
    fontWeight: 800,
    flexShrink: 0,
  };
}

/** GitHub bot logins carry a literal "[bot]" suffix, but the avatar CDN
 *  expects the account handle without it. */
function avatarHandle(login: string): string {
  return login.replace(/\[bot]$/i, '');
}

/**
 * GitHub user/bot avatar at the prototype's exact geometry: circle = human,
 * 6px-radius rounded square = bot. Falls back to the deterministic
 * initials-gradient badge from the prototype when loading fails.
 */
export function Av({ login, size, square = false, src }: {
  login: string;
  size: number;
  square?: boolean;
  src?: string;
}) {
  const avatarSrc = src
    ?? `https://avatars.githubusercontent.com/${encodeURIComponent(avatarHandle(login))}?s=${size * 2}`;
  const [failedSrc, setFailedSrc] = useState<string | null>(null);
  if (failedSrc === avatarSrc || login.trim().length === 0) {
    return <span style={avFallbackStyle(login, size, square)}>{avInit(login)}</span>;
  }
  return (
    <img
      src={avatarSrc}
      alt={login}
      width={size}
      height={size}
      style={{ width: size, height: size, borderRadius: square ? 6 : '50%', flexShrink: 0, objectFit: 'cover' }}
      onError={() => setFailedSrc(avatarSrc)}
    />
  );
}

/** A missing task-PR author is the signed-in GitHub user, not an unknown user. */
export function PullAuthorAv({ login, size, square = false }: {
  login: string;
  size: number;
  square?: boolean;
}) {
  return login.trim() === ''
    ? <CurrentUserAvatar size={size} />
    : <Av login={login} size={size} square={square} />;
}

/** Repository rows show the owner's current GitHub avatar. GitHub's raw
 * username CDN path can retain the account's old/default image, while this
 * profile image route redirects to the current numeric avatar URL. */
export function RepoAv({ repo, size }: { repo: string; size: number }) {
  const owner = repo.split('/')[0]?.trim() ?? '';
  const src = owner === ''
    ? undefined
    : `https://github.com/${encodeURIComponent(owner)}.png?size=${size * 2}`;
  return <Av login={owner} size={size} square src={src} />;
}

const LABEL_COLORS: Record<string, [string, string]> = {
  'cla-signed': ['rgba(196,88,80,0.13)', '#a04a3f'],
  'ui': ['rgba(23,134,110,0.12)', '#0f766e'],
  'jdbc': ['rgba(9,105,218,0.12)', '#0969da'],
  'docs': ['rgba(191,57,137,0.10)', '#bf3989'],
  'release-notes': ['rgba(31,136,61,0.13)', '#1a7f37'],
  'mongodb': ['rgba(31,136,61,0.13)', '#1a7f37'],
  'release-blocker': ['rgba(31,136,61,0.13)', '#1a7f37'],
  'redshift': ['rgba(31,136,61,0.13)', '#1a7f37'],
  'syntax-needs-review': ['rgba(207,34,46,0.13)', '#cf222e'],
  'stale': ['rgba(110,119,129,0.13)', '#57606a'],
  'stale-ignore': ['rgba(212,167,44,0.18)', '#9a6700'],
};

/**
 * [background, foreground] for a label chip. When GitHub supplies the label's
 * real hex color it wins (tinted background, darkened text — GitHub's own
 * chip recipe); the prototype's named map is the fallback for unsynced rows.
 */
export function labelChipColors(label: string, hex?: string | null): [string, string] {
  if (hex !== undefined && hex !== null && /^[0-9a-fA-F]{6}$/.test(hex)) {
    const r = parseInt(hex.slice(0, 2), 16);
    const g = parseInt(hex.slice(2, 4), 16);
    const b = parseInt(hex.slice(4, 6), 16);
    const dark = (v: number) => Math.round(v * 0.55);
    return [`rgba(${r},${g},${b},0.14)`, `rgb(${dark(r)},${dark(g)},${dark(b)})`];
  }
  return LABEL_COLORS[label] ?? ['rgba(170,84,68,0.11)', '#96473c'];
}

/** The five add/del ratio squares shown on diff-card headers. */
export function sqsFor(add: number, del: number): string[] {
  const GREEN = '#2da44e';
  const RED = '#cf222e';
  const NEUTRAL = '#e1e5e9';
  const total = add + del;
  if (total === 0) return [NEUTRAL, NEUTRAL, NEUTRAL, NEUTRAL, NEUTRAL];
  const units = Math.min(5, total);
  let g = Math.round((units * add) / total);
  let r = units - g;
  if (add > 0 && g === 0) {
    g = 1;
    r = units - 1;
  }
  if (del > 0 && r === 0) {
    r = 1;
    g = units - 1;
  }
  const out: string[] = [];
  for (let i = 0; i < g; i++) out.push(GREEN);
  for (let i = 0; i < r; i++) out.push(RED);
  while (out.length < 5) out.push(NEUTRAL);
  return out;
}

export function shortCount(n: number): string {
  return n >= 1000 ? `${Math.round(n / 100) / 10}k` : String(n);
}

/* ── Inline SVG icons, exact paths from the prototypes ── */

export function PrOpenIcon({ size = 16, strokeWidth = 2 }: { size?: number; strokeWidth?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round">
      <circle cx="6" cy="5.5" r="2.4" />
      <circle cx="6" cy="18.5" r="2.4" />
      <circle cx="18" cy="18.5" r="2.4" />
      <path d="M6 8v8" />
      <path d="M11.5 5.5H15a3 3 0 0 1 3 3V16" />
    </svg>
  );
}

export function PrMergedIcon({ size = 16, strokeWidth = 2 }: { size?: number; strokeWidth?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round">
      <circle cx="6" cy="5.5" r="2.4" />
      <circle cx="6" cy="18.5" r="2.4" />
      <circle cx="18" cy="12" r="2.4" />
      <path d="M6 8v8" />
      <path d="M6 8a7.5 7.5 0 0 0 7.5 4H15" />
    </svg>
  );
}

export function IssueIcon({ size = 16 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <circle cx="12" cy="12" r="8.5" />
      <circle cx="12" cy="12" r="1.6" fill="currentColor" stroke="none" />
    </svg>
  );
}

/** The purple "agent review assigned" robot outline. */
export function RobotIcon({ size = 16 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <rect x="5" y="9" width="14" height="10" rx="2.5" />
      <path d="M12 9V5.5" />
      <circle cx="12" cy="4" r="1.4" />
      <path d="M9 13.5v1.6" />
      <path d="M15 13.5v1.6" />
      <path d="M2.5 12.5v3" />
      <path d="M21.5 12.5v3" />
    </svg>
  );
}

export function CiFailIcon({ size = 13 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round">
      <path d="M6 6l12 12" />
      <path d="M18 6 6 18" />
    </svg>
  );
}

export function CiPassIcon({ size = 14 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round">
      <path d="M20 6 9 17l-5-5" />
    </svg>
  );
}

export function CommentBubbleIcon({ size = 14 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d="M21 11.5a8.4 8.4 0 0 1-9 8.4 8.6 8.6 0 0 1-3.3-.7L3 21l1.8-5.7a8.4 8.4 0 1 1 16.2-3.8z" />
    </svg>
  );
}

/** Sidebar-fold glyph used by the pane toggle (right panel variant). */
export function PaneToggleIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
      <rect x="3" y="4" width="18" height="16" rx="2.2" />
      <path d="M15 4v16" />
    </svg>
  );
}
