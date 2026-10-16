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
  owner: string;
  repo: string;
  size: number;
  radius?: number;
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
export default function RepoAvatar({ owner, repo, size, radius = 5 }: Props) {
  const [loadedSource, setLoadedSource] = useState<string | null>(null);
  const [failedSource, setFailedSource] = useState<string | null>(null);
  const login = owner.trim().toLowerCase();
  const [image, setImage] = useState<{ owner: string; source: string } | null>(null);
  const source = image?.owner === login ? image.source : null;

  useEffect(() => {
    if (!login || !window.bridge?.getRepositoryAvatar) return;
    let active = true;
    const show = (value: string | null) => {
      if (active && value !== null) setImage({ owner: login, source: value });
    };
    const refresh = () => { void window.bridge.getRepositoryAvatar(login, true).then(show).catch(() => {}); };
    void window.bridge.getRepositoryAvatar(login).then(show).catch(() => {}).then(() => { if (active) refresh(); });
    window.addEventListener('online', refresh);
    return () => { active = false; window.removeEventListener('online', refresh); };
  }, [login]);
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
