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
import { useEffect, useRef, useState } from 'react';
import { Reply, Send, X } from 'lucide-react';
import type { GoogleMail, GoogleStatus } from './contracts';
import { mailSender } from './mailView';
import BetterWordsButton from '../pulls/BetterWordsButton';

/** Writes a reply under the message being read, or a new email; nothing leaves before the confirmation. */
export default function GoogleMailComposer({ profile, email, reply, status, busy, act, onClose, onSent }: {
  profile: string; email: string; reply: GoogleMail | null; status: GoogleStatus; busy: boolean;
  act: (action: () => Promise<GoogleStatus>) => Promise<void>; onClose: () => void; onSent: () => void;
}) {
  const sender = reply ? mailSender(reply.from) : null;
  const [to, setTo] = useState(sender?.email ?? '');
  const [subject, setSubject] = useState(reply ? (/^re:/i.test(reply.subject) ? reply.subject : `Re: ${reply.subject}`) : '');
  const [body, setBody] = useState('');
  const [confirm, setConfirm] = useState(false);
  const [sent, setSent] = useState(false);
  const [rewriteError, setRewriteError] = useState<string | null>(null);
  const attempted = useRef(false);
  const textarea = useRef<HTMLTextAreaElement>(null);
  const box = useRef<HTMLElement>(null);
  // The composer opens below the message, and its confirmation below the text.
  useEffect(() => { box.current?.scrollIntoView({ block: 'nearest' }); }, [confirm]);
  useEffect(() => {
    if (sent && !status.operation) {
      if (!status.error) onSent();
      setSent(false);
    }
  }, [sent, status.operation, status.error, onSent]);
  const recipient = to.trim() || 'the recipients above';
  const ready = body.trim() !== '' && to.trim() !== '' && !busy && !sent;
  const discard = <button type="button" className="google-icon-btn" aria-label="Discard draft" title="Discard draft" disabled={sent} onClick={onClose}><X size={reply ? 14 : 16} /></button>;
  const editor = <section ref={box} className="agt-compose is-new-task google-compose" aria-label={reply ? 'Reply to email' : 'New email'}>
    {sender && <div className="google-compose__to"><Reply size={14} aria-hidden="true" />
      <span className="google-compose__chip"><span className={`google-avatar is-tone-${sender.tone}`} aria-hidden="true">{sender.initials}</span>{to}</span>
      <span className="google-compose__subject">{subject}</span>{discard}</div>}
    <textarea ref={textarea} className="agt-compose__input" rows={1} autoFocus={reply !== null} aria-label="Email body" placeholder={reply ? 'Write your reply…' : 'Write your message…'}
      value={body} maxLength={100000} disabled={sent} onChange={event => { setBody(event.target.value); setConfirm(false); }}
      onKeyDown={event => {
        if (event.key !== 'Enter' || event.shiftKey || event.nativeEvent.isComposing) return;
        event.preventDefault();
        if (ready) setConfirm(true);
      }} />
    {rewriteError && <p className="google-compose__error" role="alert">{rewriteError}</p>}
    {confirm ? <div className="google-compose__confirm" role="group" aria-label="Confirm send"><Send size={14} aria-hidden="true" />
      <span>Send to <strong>{recipient}</strong>? It leaves ByteQuay immediately.</span>
      <button type="button" disabled={busy} onClick={() => setConfirm(false)}>Keep editing</button>
      <button type="button" className="is-primary" disabled={busy || sent} onClick={() => {
        if (attempted.current) return;
        attempted.current = true;
        void act(async () => {
          try {
            const next = await window.bridge.sendGoogleMail({ profile, to, subject, body, replyId: reply?.id ?? '' });
            setSent(true); setConfirm(false); return next;
          } finally { attempted.current = false; }
        });
      }}><Send size={13} aria-hidden="true" />Send email</button>
    </div> : <div className="agt-compose__row">
      <BetterWordsButton text={body} textareaRef={textarea} repository="" disabled={busy || sent}
        onError={failure => setRewriteError(failure === null ? null : failure instanceof Error ? failure.message : 'Could not improve the text.')} />
      <span className="google-compose__route">{sent ? 'Sending…' : `Sends from ${email} to ${recipient}`}</span>
      <button type="button" className="agt-send" aria-label="Review email" title="Review before sending (Enter)" disabled={!ready} onClick={() => setConfirm(true)}>↑</button>
    </div>}
  </section>;
  if (reply) return editor;
  return <div className="google-compose-new">
    <div className="google-compose-new__title"><h2>New message</h2>{discard}</div>
    <div className="google-compose-new__fields">
      <div><span>From</span><span>{email}</span></div>
      <label><span>To</span><input autoFocus aria-label="Email recipients" value={to} maxLength={2000} placeholder="name@example.com" disabled={sent} onChange={event => { setTo(event.target.value); setConfirm(false); }} /></label>
      <label><span>Subject</span><input aria-label="Email subject" value={subject} maxLength={1000} placeholder="Subject" disabled={sent} onChange={event => { setSubject(event.target.value); setConfirm(false); }} /></label>
    </div>
    {editor}
  </div>;
}
