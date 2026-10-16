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
import { githubLink, type GoogleMail } from './contracts';

export const MAIL_TONES = 6;

export type MailSender = { name: string; email: string; initials: string; tone: number };
export type MailSource = 'github' | 'calendar' | 'google' | null;
export type MailSubject = { title: string; repository: string; number: string; kind: 'PR' | 'Issue' | null };
export type MailBlock = { text: string } | { items: string[]; ordered: boolean; start?: number }
  | { file: string; lines: { sign: string; text: string; tone: 'add' | 'del' | 'ctx' }[] };
export type GithubNotification = { actor: string; action: string; blocks: MailBlock[]; reason: string; link: string | null; messageId: string };

/** `Name <address>` as Gmail reports it; a bare address names itself. */
export function mailSender(from: string): MailSender {
  const match = from.match(/^\s*"?([^"<]*?)"?\s*<([^<>]+)>\s*$/);
  const email = (match?.[2] ?? from).trim();
  const name = match?.[1].trim() || email;
  const words = name.replace(/\[bot\]/, '').split(/[\s-]+/).filter(Boolean);
  const initials = (words.length > 1 ? words[0][0] + words[1][0] : (words[0] ?? '?').slice(0, 2)).toUpperCase();
  let hash = 0;
  for (const char of name) hash = (hash * 31 + char.charCodeAt(0)) >>> 0;
  return { name, email, initials, tone: hash % MAIL_TONES };
}

export function mailSource(email: string): MailSource {
  const host = email.slice(email.lastIndexOf('@') + 1).toLowerCase();
  if (host === 'github.com' || host.endsWith('.github.com')) return 'github';
  if (email.toLowerCase().startsWith('calendar-notification@google.')) return 'calendar';
  return host === 'google.com' || host.endsWith('.google.com') ? 'google' : null;
}

/** GitHub writes `Re: [owner/repo] Title (PR #1)`; the list shows the title and keeps the rest as labels. */
export function mailSubject(subject: string): MailSubject {
  const repository = subject.match(/\[([^\]\s]+\/[^\]\s]+)\]/)?.[1] ?? '';
  const reference = repository ? subject.match(/\((PR|Issue) #(\d+)\)\s*$/) : null;
  const bare = subject.replace(/^(re:\s*)+/i, '').trim();
  const title = repository ? bare.replace(/\[[^\]\s]+\/[^\]\s]+\]\s*/, '').replace(/\s*\((?:PR|Issue) #\d+\)$/, '') : bare;
  return { title: title || '(No subject)', repository, number: reference?.[2] ?? '', kind: (reference?.[1] as 'PR' | 'Issue' | undefined) ?? null };
}

export function mailDay(date: string, now = new Date()): string {
  const sent = new Date(date);
  if (Number.isNaN(sent.getTime())) return 'Earlier';
  const days = Math.round((new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
    - new Date(sent.getFullYear(), sent.getMonth(), sent.getDate()).getTime()) / 86_400_000);
  return days === 0 ? 'Today' : days === 1 ? 'Yesterday' : sent.toLocaleDateString([], { weekday: 'short', month: 'short', day: 'numeric' });
}

export function mailTime(date: string): string {
  const sent = new Date(date);
  return Number.isNaN(sent.getTime()) ? date : sent.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
}

/** Consecutive messages of one day, in the order Gmail returned them. */
export function mailGroups(mail: GoogleMail[], now = new Date()): { label: string; items: GoogleMail[] }[] {
  const groups: { label: string; items: GoogleMail[] }[] = [];
  for (const item of mail) {
    const label = mailDay(item.date, now);
    if (groups.at(-1)?.label === label) groups.at(-1)?.items.push(item);
    else groups.push({ label, items: [item] });
  }
  return groups;
}

const paragraphs = (text: string) => text.replace(/\r\n?/g, '\n').split(/\n\s*\n/).map(part => part.replace(/^\n+|\s+$/g, '')).filter(part => part.trim());

export function mailParagraphs(mail: GoogleMail): string[] {
  return paragraphs(mail.body || mail.snippet);
}

function quoted(block: string, file: string): MailBlock {
  return { file, lines: block.split('\n').map(line => {
    const first = line.startsWith('>');
    const rest = first ? line.slice(line[1] === ' ' ? 2 : 1) : line;
    const sign = rest[0] === '+' || rest[0] === '-' ? rest[0] : '';
    return { sign: sign || (first ? '>' : ''), text: sign ? rest.slice(1) : rest, tone: sign === '+' ? 'add' : sign === '-' ? 'del' : 'ctx' };
  }) };
}

function commentBlocks(text: string): MailBlock[] {
  const blocks: MailBlock[] = [];
  for (const line of text.split('\n')) {
    const item = line.match(/^\s*(?:(\d+)[.)]|[-*+])[ \t]+(.+)$/);
    const previous = blocks.at(-1);
    if (item) {
      const ordered = item[1] !== undefined;
      if (previous && 'items' in previous && previous.ordered === ordered) previous.items.push(item[2]);
      else blocks.push({ items: [item[2]], ordered, ...(ordered ? { start: Number(item[1]) } : {}) });
    }
    else if (previous && 'items' in previous) previous.items[previous.items.length - 1] += `\n${line.trim()}`;
    else if (previous && 'text' in previous) previous.text += `\n${line}`;
    else blocks.push({ text: line });
  }
  return blocks;
}

/** GitHub's plain-text notification: actor, quoted diff, comment, then the thread-link signature. */
export function githubNotification(mail: GoogleMail): GithubNotification {
  const [content, signature = ''] = (mail.body || mail.snippet).replace(/\r\n?/g, '\n').split(/\n-- ?\n/);
  const parts = paragraphs(content);
  const lead = parts[0]?.match(/^@(\S+) (.+?)\.?$/) ?? parts[0]?.match(/^(\S+) (left a comment)\b.*$/);
  const blocks: MailBlock[] = [];
  let file = '';
  const body = lead ? parts.slice(1) : parts;
  for (let index = 0; index < body.length; index++) {
    let part = body[index];
    const path = part.match(/^In (\S+):$/);
    if (path) { file = path[1]; continue; }
    if (part.startsWith('>')) {
      // GitHub prefixes only the first diff line with >; blank lines do not end the diff.
      while (index + 1 < body.length && /^[+-][ \t]{2,}/m.test(body[index + 1])
        && body[index + 1].split('\n').every(line => /^(?:[+-][ \t]|[ \t])/.test(line))) part += `\n\n${body[++index]}`;
      blocks.push(quoted(part, file));
    }
    else blocks.push(...commentBlocks(part));
    file = '';
  }
  const subject = mailSubject(mail.subject);
  const fallback = subject.number ? `https://github.com/${subject.repository}/${subject.kind === 'PR' ? 'pull' : 'issues'}/${subject.number}` : '';
  return {
    actor: lead ? `${lead[2] === 'left a comment' ? '' : '@'}${lead[1]}` : mailSender(mail.from).name,
    action: lead?.[2] ?? '',
    blocks,
    reason: signature.match(/^You are receiving this because .+$/m)?.[0] ?? '',
    link: githubLink(signature.match(/https:\/\/github\.com\/\S+/)?.[0] ?? fallback),
    messageId: signature.match(/^Message ID: (.+)$/m)?.[1] ?? '',
  };
}

/** GitHub's merge notice is the only state a notification states outright. */
export function mergedNotice(mail: GoogleMail): boolean {
  return /^Merged #\d+ into /.test((mail.body || mail.snippet).trimStart());
}
