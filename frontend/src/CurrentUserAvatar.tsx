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
import { useEffect, useState, type CSSProperties } from 'react';
import { getCached, setCached } from './dataCache';
import type { UserProfileDto } from './types';

type Props = {
  size: number;
  className?: string;
};

/** The signed-in GitHub account's real avatar, shared by workspace surfaces. */
export default function CurrentUserAvatar({ size, className }: Props) {
  const [profile, setProfile] = useState<UserProfileDto | null>(
    () => getCached<UserProfileDto>('home:profile') ?? null,
  );
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    const bridge = typeof window === 'undefined' ? undefined : window.bridge;
    if (bridge?.getUserProfile === undefined) return;
    let cancelled = false;
    void bridge.getUserProfile()
      .then(value => {
        if (cancelled) return;
        setCached('home:profile', value);
        setProfile(value);
      })
      .catch(() => {});
    return () => { cancelled = true; };
  }, []);

  const url = profile?.avatarUrl.trim()
    || (profile ? `https://github.com/${encodeURIComponent(profile.login)}.png?size=${size * 2}` : '');
  const style = { ...avatarStyle, width: size, height: size, flexBasis: size };
  if (url !== '' && !failed) {
    return <img
      className={className}
      src={url}
      alt={profile?.login ?? 'GitHub user'}
      title={profile?.login}
      style={style}
      onError={() => setFailed(true)}
    />;
  }
  return (
    <span className={className} style={{ ...style, ...fallbackStyle }} role="img" aria-label="GitHub user">
      <svg width="60%" height="60%" viewBox="0 0 24 24" fill="none" stroke="currentColor"
        strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
        <circle cx="12" cy="8" r="3.5" />
        <path d="M5.5 20a6.5 6.5 0 0 1 13 0" />
      </svg>
    </span>
  );
}

const avatarStyle: CSSProperties = {
  display: 'inline-flex',
  flexGrow: 0,
  flexShrink: 0,
  borderRadius: '50%',
  objectFit: 'cover',
};

const fallbackStyle: CSSProperties = {
  alignItems: 'center',
  justifyContent: 'center',
  background: '#eef1f4',
  color: '#57606a',
};
