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
import { useHostingPresentation } from './pulls/PullRequestWorkspace';
import { useEffect, useState } from 'react';

type Props = {
  owner: string;
  repo: string;
  size: number;
  radius?: number;
  github?: boolean;
};

/** Background/foreground pairs for the initial tile, picked by owner name. */
const TILES: [string, string][] = [
  ['light-dark(#e8f0fe, var(--dark-blue-soft))', 'var(--fg-link)'],
  ['light-dark(#eaf5ec, var(--dark-green-soft))', 'var(--fg-success)'],
  ['light-dark(#fdf0e6, var(--dark-orange-soft))', 'var(--fg-orange)'],
  ['light-dark(#f3eefe, var(--dark-purple-soft))', 'var(--fg-purple)'],
  ['light-dark(#fdeef1, var(--dark-red-soft))', 'var(--fg-danger)'],
  ['var(--surface-inset)', 'var(--fg-muted)'],
];

function ownerTile(owner: string): [string, string] {
  let hash = 0;
  for (let index = 0; index < owner.length; index++) {
    hash = (hash * 31 + owner.charCodeAt(index)) % 997;
  }
  return TILES[hash % TILES.length];
}

/**
 * A repository's owner avatar as a rounded square. The colored initial tile
 * shows while the image loads and stays if it never arrives, so the row never
 * reflows and an unreachable avatar still reads as that repository.
 */
export default function RepoAvatar({ owner, repo, size, radius = 5, github = true }: Props) {
  const [loadedSource, setLoadedSource] = useState<string | null>(null);
  const [failedSource, setFailedSource] = useState<string | null>(null);
  const { server } = useHostingPresentation();
  github = github && server?.provider !== 'GITLAB';
  const login = owner.trim().toLowerCase();
  const [image, setImage] = useState<{ owner: string; source: string } | null>(null);
  const source = github && image?.owner === login ? image.source : null;

  useEffect(() => {
    if (!github || !login || !window.bridge?.getRepositoryAvatar) return;
    let active = true;
    const show = (value: string | null) => {
      if (active && value !== null) setImage({ owner: login, source: value });
    };
    const refresh = () => { void window.bridge.getRepositoryAvatar(login, true).then(show).catch(() => {}); };
    void window.bridge.getRepositoryAvatar(login).then(show).catch(() => {}).then(() => { if (active) refresh(); });
    window.addEventListener('online', refresh);
    return () => { active = false; window.removeEventListener('online', refresh); };
  }, [github, login]);
  const loaded = source !== null && loadedSource === source && failedSource !== source;
  const name = `${owner}/${repo}`;
  const [background, color] = ownerTile(owner);

  return (
    <span
      className="repo-avatar"
      title={name}
      role="img"
      aria-label={name}
      style={{
        width: size,
        height: size,
        borderRadius: radius,
        background,
        color,
        fontSize: Math.round(size * 0.5),
      }}
    >
      {!loaded && (owner.trim().charAt(0).toUpperCase() || '?')}
      {source !== null && failedSource !== source && (
        <img
          className="repo-avatar__image"
          src={source}
          alt=""
          style={{ borderRadius: radius, opacity: loaded ? 1 : 0 }}
          onLoad={() => setLoadedSource(source)}
          onError={() => setFailedSource(source)}
        />
      )}
    </span>
  );
}

// GitHub's Octicon mark and GitLab's tanuki (Simple Icons, CC0), both on a 24-unit grid. GitHub's
// mark is monochrome, so it takes the text colour and stays readable in both themes.
const HOST_MARKS = {
  github: {
    color: 'currentColor',
    path: 'M12 .5C5.37.5 0 5.87 0 12.5a12 12 0 0 0 8.2 11.39c.6.11.82-.26.82-.58v-2.03c-3.34.73-4.04-1.61-4.04-1.61-.55-1.39-1.33-1.76-1.33-1.76-1.09-.75.08-.73.08-.73 1.2.08 1.84 1.24 1.84 1.24 1.07 1.83 2.81 1.3 3.5.99.1-.78.42-1.3.76-1.6-2.67-.3-5.47-1.33-5.47-5.93 0-1.31.47-2.38 1.24-3.22-.13-.3-.54-1.52.11-3.18 0 0 1.01-.32 3.3 1.23a11.5 11.5 0 0 1 6 0c2.29-1.55 3.3-1.23 3.3-1.23.65 1.66.24 2.88.12 3.18.77.84 1.23 1.91 1.23 3.22 0 4.61-2.81 5.62-5.48 5.92.43.37.82 1.1.82 2.22v3.29c0 .32.21.7.82.58A12 12 0 0 0 24 12.5C24 5.87 18.63.5 12 .5Z',
  },
  gitlab: {
    color: '#FC6D26',
    path: 'm23.6 9.59-.03-.09L20.3.98a.85.85 0 0 0-.34-.4.87.87 0 0 0-1 .05.87.87 0 0 0-.29.44l-2.2 6.75H7.54L5.33 1.07a.86.86 0 0 0-.29-.44.87.87 0 0 0-1-.05.86.86 0 0 0-.34.4L.43 9.5l-.03.09a6.07 6.07 0 0 0 2.01 7.01l.01.01.03.02 4.98 3.73 2.46 1.86 1.5 1.13a1 1 0 0 0 1.22 0l1.5-1.13 2.46-1.86 5.01-3.75.01-.01a6.07 6.07 0 0 0 2.01-7z',
  },
};

/** Which host a workspace lives on, as that host's mark before its avatar. */
export function HostMark({ gitlab, size = 13, className }: { gitlab: boolean; size?: number; className?: string }) {
  const { color, path } = HOST_MARKS[gitlab ? 'gitlab' : 'github'];
  return (
    <svg className={className ? `host-mark ${className}` : 'host-mark'} width={size} height={size} viewBox="0 0 24 24" fill={color} aria-label={gitlab ? 'GitLab' : 'GitHub'} role="img">
      <path d={path} />
    </svg>
  );
}

/** The last path segment of a workspace's name: `iceberg` for `apache/iceberg`. */
export function shortWorkspaceName(name: string): string {
  return name.slice(name.lastIndexOf('/') + 1) || name;
}
