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
import { useEffect, useId, useRef, useState, type CSSProperties } from 'react';
import { createPortal } from 'react-dom';
import Avatar from './Avatar';
import type { UserProfileDto } from './types';

const HOVER_DELAY_MS = 1_000;
const CARD_WIDTH = 320;
const CARD_HEIGHT = 220;
const profileRequests = new Map<string, Promise<UserProfileDto | null>>();

type Props = {
  login: string;
  avatarUrl?: string | null;
  size?: number;
};

function loadProfile(login: string): Promise<UserProfileDto | null> {
  const key = login.toLowerCase();
  const existing = profileRequests.get(key);
  if (existing) return existing;
  const request = window.bridge.getGitHubUserProfile(login).catch((): null => null);
  profileRequests.set(key, request);
  void request.then(profile => {
    if (profile === null) profileRequests.delete(key);
  });
  return request;
}

export function userCardPosition(rect: Pick<DOMRect, 'left' | 'top' | 'bottom'>): CSSProperties {
  const left = Math.max(12, Math.min(rect.left, window.innerWidth - CARD_WIDTH - 12));
  return window.innerHeight - rect.bottom >= CARD_HEIGHT || rect.top < CARD_HEIGHT
    ? { left, top: rect.bottom + 8 }
    : { left, bottom: window.innerHeight - rect.top + 8 };
}

export default function UserAvatar({ login, avatarUrl, size = 20 }: Props) {
  const cardId = useId();
  const trigger = useRef<HTMLButtonElement>(null);
  const showTimer = useRef<number | null>(null);
  const hideTimer = useRef<number | null>(null);
  const mounted = useRef(true);
  const [open, setOpen] = useState(false);
  const [position, setPosition] = useState<CSSProperties>({});
  const [profile, setProfile] = useState<UserProfileDto | null>(null);

  const clearTimer = (timer: typeof showTimer) => {
    if (timer.current !== null) window.clearTimeout(timer.current);
    timer.current = null;
  };
  const show = (delay: number) => {
    clearTimer(hideTimer);
    clearTimer(showTimer);
    showTimer.current = window.setTimeout(() => {
      const rect = trigger.current?.getBoundingClientRect();
      if (!rect) return;
      setPosition(userCardPosition(rect));
      setOpen(true);
      void loadProfile(login).then(value => {
        if (!mounted.current) return;
        if (value === null) setOpen(false);
        else setProfile(value);
      });
    }, delay);
  };
  const hide = (delay = 140) => {
    clearTimer(showTimer);
    clearTimer(hideTimer);
    hideTimer.current = window.setTimeout(() => setOpen(false), delay);
  };

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      clearTimer(showTimer);
      clearTimer(hideTimer);
    };
  }, []);

  return <>
    <button
      ref={trigger}
      type="button"
      className="user-avatar-trigger"
      aria-label={`Open ${login}'s GitHub profile`}
      aria-describedby={open ? cardId : undefined}
      onMouseEnter={() => show(HOVER_DELAY_MS)}
      onMouseLeave={() => hide()}
      onFocus={() => show(0)}
      onBlur={() => hide()}
      onClick={() => {
        hide(0);
        void window.bridge.openInAppBrowser(profile?.htmlUrl ?? `https://github.com/${encodeURIComponent(login)}`);
      }}
    >
      <Avatar login={login} avatarUrl={avatarUrl} size={size} />
    </button>
    {open && createPortal(
      <aside
        id={cardId}
        className="user-profile-card"
        role="dialog"
        aria-label={`${login} profile`}
        style={position}
        tabIndex={0}
        onMouseEnter={() => clearTimer(hideTimer)}
        onMouseLeave={() => hide()}
        onFocus={() => clearTimer(hideTimer)}
        onBlur={() => hide()}
      >
        {profile === null
          ? <span className="user-profile-card__loading">Loading GitHub profile…</span>
          : <>
              <header>
                <Avatar login={profile.login} avatarUrl={profile.avatarUrl} size={48} />
                <span>
                  <strong>{profile.name ?? profile.login}</strong>
                  <small>@{profile.login}</small>
                </span>
              </header>
              {profile.bio && <p>{profile.bio}</p>}
              {(profile.location || profile.company) && (
                <div className="user-profile-card__details">
                  {profile.location && <span>{profile.location}</span>}
                  {profile.company && <span>{profile.company}</span>}
                </div>
              )}
              <footer>
                <span><strong>{profile.followers.toLocaleString()}</strong> followers</span>
                <span><strong>{profile.following.toLocaleString()}</strong> following</span>
                <span><strong>{profile.publicRepos.toLocaleString()}</strong> repositories</span>
              </footer>
            </>}
      </aside>,
      document.body,
    )}
  </>;
}
