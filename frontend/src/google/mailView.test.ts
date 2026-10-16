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
import type { GoogleMail } from './contracts';
import { githubNotification, mailGroups, mailSender, mailSource, mailSubject, mergedNotice } from './mailView';

const mail = (fields: Partial<GoogleMail>): GoogleMail => ({ id: 'one', threadId: 'thread', subject: '', from: '', to: '', date: '', snippet: '', body: '', unread: false, ...fields });

describe('mail presentation', () => {
  it('names the sender and keeps a bare address readable', () => {
    expect(mailSender('"Luca Stone" <notifications@github.com>')).toMatchObject({ name: 'Luca Stone', email: 'notifications@github.com', initials: 'LS' });
    expect(mailSender('notable-changes-workflow-ci[bot] <notifications@github.com>').initials).toBe('NC');
    expect(mailSender('maya@example.com')).toMatchObject({ name: 'maya@example.com', email: 'maya@example.com', initials: 'MA' });
    expect(mailSource('notifications@github.com')).toBe('github');
    expect(mailSource('calendar-notification@google.com')).toBe('calendar');
    expect(mailSource('no-reply@accounts.google.com')).toBe('google');
    expect(mailSource('maya@notgoogle.com')).toBeNull();
  });

  it('moves the repository and pull request out of a GitHub subject', () => {
    expect(mailSubject('Re: [trinodb/trino] Fix flaky test (PR #31416)')).toEqual({ title: 'Fix flaky test', repository: 'trinodb/trino', number: '31416', kind: 'PR' });
    expect(mailSubject('[a/b] Crash on start (Issue #7)')).toMatchObject({ title: 'Crash on start', kind: 'Issue', number: '7' });
    expect(mailSubject('Lunch (PR #1)')).toEqual({ title: 'Lunch (PR #1)', repository: '', number: '', kind: null });
    expect(mailSubject('')).toMatchObject({ title: '(No subject)' });
  });

  it('groups consecutive messages by local day', () => {
    const now = new Date(2026, 9, 10, 18);
    const at = (day: number) => mail({ date: new Date(2026, 9, day, 9).toString() });
    expect(mailGroups([at(10), at(10), at(9), at(3), mail({ date: 'not a date' })], now).map(group => [group.label, group.items.length]))
      .toEqual([['Today', 2], ['Yesterday', 1], [expect.stringContaining('3'), 1], ['Earlier', 1]]);
  });

  it('reads a GitHub review comment as actor, quoted diff, comment and thread link', () => {
    const notification = githubNotification(mail({
      subject: 'Re: [trinodb/trino] Fix flaky test (PR #31416)', from: 'Co1a <notifications@github.com>',
      body: '@Max-Cheng commented on this pull request.\n\n\n\nIn testing/Kerberos.java:\n\n> +    return """\n-        echo "INIT"\n         #!/bin/bash\n\nSame issue as #30979.\n\n-- \n'
        + 'Reply to this email directly or view it on GitHub:\nhttps://github.com/trinodb/trino/pull/31416#discussion_r1\nYou are receiving this because you are subscribed to this thread.\n\nMessage ID: <trinodb/trino/pull/31416/review/1@github.com>',
    }));
    expect(notification).toMatchObject({ actor: '@Max-Cheng', action: 'commented on this pull request',
      link: 'https://github.com/trinodb/trino/pull/31416#discussion_r1', messageId: '<trinodb/trino/pull/31416/review/1@github.com>',
      reason: 'You are receiving this because you are subscribed to this thread.' });
    expect(notification.blocks).toEqual([
      { file: 'testing/Kerberos.java', lines: [{ sign: '+', text: '    return """', tone: 'add' }, { sign: '-', text: '        echo "INIT"', tone: 'del' }, { sign: '', text: '         #!/bin/bash', tone: 'ctx' }] },
      { text: 'Same issue as #30979.' },
    ]);
  });

  it('falls back to the sender and the subject link, and never links outside GitHub', () => {
    const merged = mail({ subject: 'Re: [a/b] Update (PR #4)', from: 'Luca Stone <notifications@github.com>', body: 'Merged #4 into master.' });
    expect(githubNotification(merged)).toMatchObject({ actor: 'Luca Stone', action: '', link: 'https://github.com/a/b/pull/4', blocks: [{ text: 'Merged #4 into master.' }] });
    expect(mergedNotice(merged)).toBe(true);
    expect(githubNotification(mail({ subject: 'Hello', body: 'Hi\n-- \nhttps://github.com.evil.test/x' })).link).toBeNull();
  });

  it('keeps a diff together across blank lines and distinguishes comment lists from code', () => {
    const notification = githubNotification(mail({ body: '@maya commented on this pull request.\r\n\r\nIn CalendarUtils.java:\r\n\r\n'
      + '>     }\r\n\r\n-    public static long convert(long millis)\r\n+    // Compute once per caller\r\n+    public static long convert(long millis, long switchMillis)\r\n\r\n'
      + 'Thanks for the fix.\r\n\r\n - Interface change: see #4958.\r\n   Every caller computes the switch.\r\n - Unchecked: the zone may differ.\r\n - State per coercer.\r\n\r\n'
      + '#4958 keeps the signature.\r\n-Callers do not change.\r\n-The trade-off is a test.\r\n\r\n3. Run tests.\r\n4. Review results.' }));
    expect(notification.blocks).toEqual([
      { file: 'CalendarUtils.java', lines: [
        { sign: '>', text: '    }', tone: 'ctx' }, { sign: '', text: '', tone: 'ctx' },
        { sign: '-', text: '    public static long convert(long millis)', tone: 'del' },
        { sign: '+', text: '    // Compute once per caller', tone: 'add' },
        { sign: '+', text: '    public static long convert(long millis, long switchMillis)', tone: 'add' },
      ] },
      { text: 'Thanks for the fix.' },
      { ordered: false, items: ['Interface change: see #4958.\nEvery caller computes the switch.', 'Unchecked: the zone may differ.', 'State per coercer.'] },
      { text: '#4958 keeps the signature.\n-Callers do not change.\n-The trade-off is a test.' },
      { ordered: true, start: 3, items: ['Run tests.', 'Review results.'] },
    ]);
    expect(githubNotification(mail({ body: '> quoted code\n\n- A real list\n+ Another item' })).blocks).toEqual([
      { file: '', lines: [{ sign: '>', text: 'quoted code', tone: 'ctx' }] },
      { ordered: false, items: ['A real list', 'Another item'] },
    ]);
  });
});
