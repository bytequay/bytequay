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
import { describe, expect, it } from 'vitest';
import { breadcrumbLabel } from './App';

// The label mapping takes App's internal Nav union; cast nav-likes so
// the test stays focused on the mapping without re-exporting the type.
const nav = (v: Record<string, unknown>) =>
  v as unknown as Parameters<typeof breadcrumbLabel>[0];

describe('breadcrumbLabel', () => {
  it('reads a PR opened from a task as "Task", not the repo', () => {
    expect(breadcrumbLabel(nav({ view: 'thread-detail', threadId: 't', taskId: 'k1' })))
      .toBe('Task');
  });

  it('reads a PR opened from a trunk thread as "Thread"', () => {
    expect(breadcrumbLabel(nav({ view: 'thread-detail', threadId: 't' }))).toBe('Thread');
  });

  it('reads a review-thread origin as "Review"', () => {
    expect(breadcrumbLabel(nav({ view: 'review-thread', threadId: 't' }))).toBe('Review');
  });

  it('still maps the known top-level views', () => {
    expect(breadcrumbLabel(nav({ view: 'my-prs' }))).toBe('My PRs');
    expect(breadcrumbLabel(nav({ view: 'home' }))).toBe('Home');
  });

  it('returns null for an unknown / absent origin so the caller falls back', () => {
    expect(breadcrumbLabel(undefined)).toBeNull();
    expect(breadcrumbLabel(nav({ view: 'repo', owner: 'a', repo: 'b' }))).toBeNull();
  });
});
