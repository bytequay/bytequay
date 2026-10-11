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
import { needsAction, type AppNotice, type NoticeSnapshot } from './contracts';

/** Session-only delivery state. Resolving a notice never changes its task or Home attention. */
export class NoticeStore {
  private initialized = false;
  private readonly seen = new Set<string>();
  private readonly notices = new Map<string, AppNotice>();
  private readonly popups = new Set<string>();
  private readonly settling = new Map<string, { since: number; popup: boolean }>();

  update(incoming: AppNotice[], now = Date.now()): void {
    const current = new Set(incoming.map(notice => notice.id));
    for (const [id, notice] of this.notices) {
      if ((!current.has(id) && needsAction(notice)) || (!needsAction(notice) && now - Date.parse(notice.occurredAt) > 3_600_000)) {
        this.notices.delete(id); this.popups.delete(id);
      }
    }
    for (const id of this.settling.keys()) if (!current.has(id)) this.settling.delete(id);
    for (const notice of incoming) {
      const known = this.seen.has(notice.id);
      if (!needsAction(notice) && now - Date.parse(notice.occurredAt) > 3_600_000) { this.remember(notice.id); continue; }
      if (!known && !needsAction(notice) && !this.initialized) { this.remember(notice.id); continue; }
      // Let ordinary turn finalization release its worktree before calling it blocked.
      if (notice.kind === 'RECOVERY' && !known) {
        const pending = this.settling.get(notice.id);
        if (pending === undefined) { this.settling.set(notice.id, { since: now, popup: this.initialized }); continue; }
        if (now - pending.since < 10_000) continue;
      }
      if (!known || needsAction(notice) || this.notices.has(notice.id)) {
        for (const [id, prior] of this.notices) {
          if (id !== notice.id && prior.taskId === notice.taskId) { this.notices.delete(id); this.popups.delete(id); }
        }
        this.notices.set(notice.id, notice);
        if (!known && this.initialized && (this.settling.get(notice.id)?.popup ?? true)) this.popups.add(notice.id);
      }
      this.remember(notice.id);
    }
    this.initialized = true;
    const recent = [...this.notices.values()].filter(notice => !needsAction(notice)).sort(newest);
    for (const notice of recent.slice(50)) { this.notices.delete(notice.id); this.popups.delete(notice.id); }
  }

  dismiss(id: string): void { this.popups.delete(id); }

  snapshot(active: boolean, maxHeight = 600): NoticeSnapshot {
    const notices = [...this.notices.values()].sort((a, b) => Number(needsAction(b)) - Number(needsAction(a)) || newest(a, b));
    return { notices, popupIds: notices.filter(notice => this.popups.has(notice.id)).map(notice => notice.id), active, maxHeight };
  }

  private remember(id: string): void {
    this.seen.add(id);
    if (this.seen.size > 4096) {
      const removable = [...this.seen].find(key => !this.notices.has(key));
      if (removable !== undefined) this.seen.delete(removable);
    }
  }
}
const newest = (a: AppNotice, b: AppNotice): number => Date.parse(b.occurredAt) - Date.parse(a.occurredAt) || a.id.localeCompare(b.id);
