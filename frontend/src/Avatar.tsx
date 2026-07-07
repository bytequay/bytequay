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
  login: string;
  size?: number;
  className?: string;
};

/** GitHub's REST/GraphQL `login` for a bot actor comes back with a literal
 *  `[bot]` suffix (e.g. "coderabbitai[bot]", "github-actions[bot]") — that
 *  suffix is GitHub's own marker that the actor is a Bot, not part of the
 *  account's real handle, so the `github.com/{login}.png` avatar shorthand
 *  404s on it (Coderabbit's actual avatar lives at
 *  github.com/coderabbitai.png). Stripped only for the image URL; `alt`
 *  and the fallback initial still use the original login. */
function avatarHandle(login: string): string {
  return login.replace(/\[bot]$/i, '');
}

/**
 * GitHub profile picture with a colored-initial fallback.
 *
 * If the image load fails — common for actors whose `login` isn't a real
 * GitHub handle (display names from commit authors like "Nazarii
 * Gudzovatyi", etc.) — we render a placeholder with the first character.
 * It's important that the fallback *still occupies the same 24×24 (or
 * whatever size) box*: if we hid the image with `display: none`, the
 * surrounding CSS Grid/Flex would re-place the sibling into the avatar
 * slot and its text would wrap at 3 chars per line.
 */
function Avatar({ login, size = 20, className }: Props) {
  const [failed, setFailed] = useState(false);
  const classes = ['avatar', className].filter(Boolean).join(' ');
  const hasLogin = login.trim().length > 0;

  if (failed || !hasLogin) {
    const initial = hasLogin ? login.trim().charAt(0).toUpperCase() : '?';
    return (
      <span
        className={classes + ' avatar--fallback'}
        style={{
          width: size,
          height: size,
          fontSize: Math.max(10, Math.round(size * 0.5)),
        }}
        aria-label={hasLogin ? login : 'unknown user'}
        role="img"
      >
        {initial}
      </span>
    );
  }

  return (
    <img
      className={classes}
      src={`https://github.com/${encodeURIComponent(avatarHandle(login))}.png?size=${size * 2}`}
      alt={login}
      width={size}
      height={size}
      onError={() => setFailed(true)}
    />
  );
}

export default Avatar;
