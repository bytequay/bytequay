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

type Props = {
  owner: string;
  repo: string;
  size: number;
  radius?: number;
};

/** Background/foreground pairs for the initial tile, picked by owner name. */
const TILES: [string, string][] = [
  ['#e8f0fe', '#1a56b0'],
  ['#eaf5ec', '#1a7f37'],
  ['#fdf0e6', '#bc4c00'],
  ['#f3eefe', '#6639ba'],
  ['#fdeef1', '#cf222e'],
  ['#eceff2', '#57606a'],
];

export function ownerTile(owner: string): [string, string] {
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
  const [loaded, setLoaded] = useState(false);
  const [failed, setFailed] = useState(false);
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
      {!failed && owner.trim() !== '' && (
        <img
          className="repo-avatar__image"
          src={`https://github.com/${encodeURIComponent(owner)}.png?size=${size * 2}`}
          alt=""
          style={{ borderRadius: radius, opacity: loaded ? 1 : 0 }}
          onLoad={() => setLoaded(true)}
          onError={() => setFailed(true)}
        />
      )}
    </span>
  );
}
