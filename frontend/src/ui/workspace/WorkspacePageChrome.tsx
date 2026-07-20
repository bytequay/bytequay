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
import type { ReactNode } from 'react';
import { RepoAv } from '../../pulls/atoms';

export function WorkspaceSwitcherCard({ name, repository, onSwitch }: {
  name: string;
  repository: string;
  onSwitch?: () => void;
}) {
  return (
    <div className="workspace-page-switcher-wrap">
      <button type="button" className="workspace-page-switcher" title={`Open ${name} Today`} onClick={onSwitch}>
        <span className="workspace-page-switcher__tile" aria-hidden>
          {repository.includes('/')
            ? <RepoAv repo={repository} size={28} />
            : name.charAt(0).toUpperCase() || 'B'}
        </span>
        <span className="workspace-page-switcher__copy">
          <span>{name}</span>
          <small>{repository}</small>
        </span>
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#8b949e"
          strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
          <path d="m7 9 5-5 5 5" />
          <path d="m7 15 5 5 5-5" />
        </svg>
      </button>
    </div>
  );
}

export function TrunkLineIcon({ size = 14 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <circle cx="6" cy="12" r="2.3" />
      <path d="M8.5 12H21" />
      <path d="m15 8.5 3.5 3.5-3.5 3.5" />
    </svg>
  );
}

export function ChevronIcon({ direction = 'right', size = 11 }: {
  direction?: 'left' | 'right' | 'down';
  size?: number;
}) {
  const path = direction === 'left' ? 'm15 18-6-6 6-6'
    : direction === 'down' ? 'm6 9 6 6 6-6'
      : 'm9 18 6-6-6-6';
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d={path} />
    </svg>
  );
}

export function CheckCircleIcon({ size = 13 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <circle cx="12" cy="12" r="9" />
      <path d="m8.5 12.5 2.5 2.5 5-5.5" />
    </svg>
  );
}

export function PullRequestBranchIcon({ size = 12 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <circle cx="6" cy="6" r="2.2" />
      <circle cx="18" cy="18" r="2.2" />
      <path d="M6 8.3V12a6 6 0 0 0 6 6h3.5" />
    </svg>
  );
}

export function SidebarRow({ icon, children, trailing, onClick, className = '', disabled = false, title }: {
  icon: ReactNode;
  children: ReactNode;
  trailing?: ReactNode;
  onClick?: () => void;
  className?: string;
  disabled?: boolean;
  title?: string;
}) {
  return (
    <button type="button" className={`workspace-page-row ${className}`.trim()} disabled={disabled}
      title={title} onClick={onClick}>
      <span aria-hidden>{icon}</span>
      <span>{children}</span>
      {trailing !== undefined && <small>{trailing}</small>}
    </button>
  );
}
