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

import { Ban, Bookmark, Check, ChevronDown, ChevronRight, CircleAlert, Code, ExternalLink, FileText, Flag, GitMerge, ListChecks, Pause, Search, Sparkles, User, X, Zap } from 'lucide-react';

const ICONS = { doc: FileText, lesson: Bookmark, link: ExternalLink, merge: GitMerge, check: Check, pause: Pause, alert: CircleAlert,
  search: Search, review: ListChecks, bolt: Zap, close: X, down: ChevronDown, next: ChevronRight, spark: Sparkles, user: User, code: Code, flag: Flag, off: Ban };
export type IconName = keyof typeof ICONS;

export function Icon({ name, size = 16, className }: { name: IconName; size?: number; className?: string }) {
  const Glyph = ICONS[name];
  return <Glyph size={size} className={className} strokeWidth={1.8} aria-hidden="true" />;
}

/** The small disclosure chevron the design rotates 90° when a row opens. */
export function Chevron({ open, size = 12, className }: { open: boolean; size?: number; className?: string }) {
  return <svg className={className} width={size} height={size} viewBox="0 0 16 16" fill="none" aria-hidden="true"
    style={{ transform: `rotate(${open ? 90 : 0}deg)`, transition: 'transform .15s' }}>
    <path d="M6 4l4 4-4 4" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
  </svg>;
}

export function Spinner({ size = 14 }: { size?: number }) {
  return <span className="rcp-spin" aria-hidden="true" style={{ width: size, height: size }} />;
}
