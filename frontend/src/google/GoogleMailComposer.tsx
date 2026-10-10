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
import type { GoogleMail, GoogleStatus } from './contracts';
import ReviewComposer from '../pulls/agent-review/ReviewComposer';

export default function GoogleMailComposer({ profile, email, reply, status, busy, act, onClose }: {
  profile: string; email: string; reply: GoogleMail | null; status: GoogleStatus; busy: boolean;
  act: (action: () => Promise<GoogleStatus>) => Promise<void>; onClose: () => void;
}) {
  const [to, setTo] = useState(reply ? (reply.from.match(/<([^<>]+)>/)?.[1] ?? reply.from).trim() : '');
  const [subject, setSubject] = useState(reply ? (/^re:/i.test(reply.subject) ? reply.subject : `Re: ${reply.subject}`) : '');
  const [body, setBody] = useState('');
  const [confirm, setConfirm] = useState(false);
  const [sent, setSent] = useState(false);
  const attempted = useRef(false);
  useEffect(() => {
    if (sent && !status.operation) {
      if (!status.error) onClose();
      setSent(false);
    }
  }, [sent, status.operation, status.error, onClose]);
  return <section className="google-mail-compose" aria-label={reply ? 'Reply to email' : 'New email'}>
    <h2>{reply ? 'Reply' : 'New email'}</h2><p>From: {email}</p>
    <label>To<input aria-label="Email recipients" value={to} maxLength={2000} disabled={sent} onChange={event => { setTo(event.target.value); setConfirm(false); }} /></label>
    <label>Subject<input aria-label="Email subject" value={subject} maxLength={1000} disabled={sent} onChange={event => { setSubject(event.target.value); setConfirm(false); }} /></label>
    <ReviewComposer repository="" value={body} onChange={value => { setBody(value); setConfirm(false); }} ariaLabel="Email body" placeholder="Write your message…" primaryLabel="Review email" outgoing={`Send from ${email} to ${to || 'the recipients above'}`} busy={busy} disabledReason={!to.trim() ? 'Enter a recipient' : null} onCancel={onClose} onSubmit={() => setConfirm(true)} />
    {confirm && <div className="google-mail-compose__confirm" role="group" aria-label="Confirm send"><p>Send this email from <strong>{email}</strong> to <strong>{to}</strong>?</p>
      <button type="button" className="setw-btn-primary" disabled={busy || sent} onClick={() => {
        if (attempted.current) return;
        attempted.current = true;
        void act(async () => {
          try {
            const next = await window.bridge.sendGoogleMail({ profile, to, subject, body, replyId: reply?.id ?? '' });
            setSent(true); setConfirm(false); return next;
          } finally { attempted.current = false; }
        });
      }}>Send email</button><button type="button" className="setw-btn" disabled={busy} onClick={() => setConfirm(false)}>Keep editing</button>
    </div>}
  </section>;
}
