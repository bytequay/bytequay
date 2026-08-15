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
import Avatar from './Avatar';
import { getCached, setCached } from './dataCache';
import type { UserProfileDto } from './types';

type Props = {
  size: number;
  className?: string;
};

/** The signed-in GitHub account's avatar, shared by Home and PR surfaces. */
export default function CurrentUserAvatar({ size, className }: Props) {
  const [profile, setProfile] = useState<UserProfileDto | null>(
    () => getCached<UserProfileDto>('home:profile') ?? null,
  );
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

  if (profile) {
    return <Avatar login={profile.login} avatarUrl={profile.avatarUrl} size={size} className={className} />;
  }
  return (
    <span
      className={`avatar avatar--fallback ${className ?? ''}`}
      style={{ width: size, height: size, fontSize: Math.max(10, Math.round(size * 0.5)) }}
      role="img"
      aria-label="GitHub user"
    >
      <svg width="60%" height="60%" viewBox="0 0 24 24" fill="none" stroke="currentColor"
        strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
        <circle cx="12" cy="8" r="3.5" />
        <path d="M5.5 20a6.5 6.5 0 0 1 13 0" />
      </svg>
    </span>
  );
}
