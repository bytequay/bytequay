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
import type { GoogleChatState } from './contracts';
import MarkdownBody from '../pulls/MarkdownBody';
import ReviewComposer from '../pulls/agent-review/ReviewComposer';

export default function GoogleChat({ profile, email }: { profile: string; email: string }) {
  const [state, setState] = useState<GoogleChatState | null>(null);
  const [draft, setDraft] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const alive = useRef(true);
  const bottom = useRef<HTMLDivElement>(null);
  useEffect(() => {
    alive.current = true;
    let inFlight = false;
    const refresh = async () => {
      if (inFlight) return;
      inFlight = true;
      try { const next = await window.bridge.getGoogleChat(profile); if (alive.current) setState(next); }
      catch { if (alive.current) setError('Unable to read the Gemini conversation.'); }
      finally { inFlight = false; }
    };
    void refresh(); const timer = window.setInterval(() => { void refresh(); }, 700);
    return () => { alive.current = false; window.clearInterval(timer); };
  }, [profile]);
  const lastText = state?.messages.at(-1)?.text;
  useEffect(() => { bottom.current?.scrollIntoView({ block: 'nearest' }); }, [lastText, state?.approval]);
  const act = async (action: () => Promise<GoogleChatState>) => {
    setError(null); setPending(true);
    try { const next = await action(); if (alive.current) setState(next); return true; }
    catch (cause) { if (alive.current) setError(cause instanceof Error ? cause.message : 'Gemini request failed.'); return false; }
    finally { if (alive.current) setPending(false); }
  };
  return <aside className="google-chat" aria-label="Gemini conversation">
    <header><div><h2>Gemini</h2><small>{email}</small></div><button type="button" className="setw-btn" disabled={pending || state?.busy || !state?.messages.length} onClick={() => { void act(() => window.bridge.clearGoogleChat(profile)); }}>Clear conversation</button></header>
    <div className="google-chat__messages" role="log" aria-label="Conversation messages">
      {!state?.messages.length && <p className="google-screen__placeholder">Ask about urgent mail, a booking reply, or your schedule. Gemini can search live Google data beyond the items displayed here.</p>}
      {state?.messages.map((message, index) => <article key={index} className={`google-chat__${message.role}`}><strong>{message.role === 'user' ? 'You' : 'Gemini'}</strong><MarkdownBody body={message.text} repository="" /></article>)}
      {state?.activity && <p role="status">{state.activity}</p>}
      {state?.approval && <section className="google-chat__approval" aria-label="Gemini approval"><h3>{state.approval.title}</h3><pre>{state.approval.detail}</pre><div>{state.approval.options.map(option => <button type="button" className="setw-btn" disabled={pending} key={option.id} onClick={() => { const approval = state.approval; if (approval) void act(() => window.bridge.approveGoogleChat(profile, approval.id, option.id)); }}>{option.label}</button>)}<button type="button" className="setw-btn" disabled={pending} onClick={() => { void act(() => window.bridge.stopGoogleChat(profile)); }}>Stop</button></div></section>}
      {(error || state?.error) && <p className="google-screen__error" role="alert">{error || state?.error}</p>}
      <div ref={bottom} />
    </div>
    <ReviewComposer repository="" value={draft} onChange={setDraft} ariaLabel="Message Gemini" placeholder="Ask Gemini about your mail or calendar…" primaryLabel="Send to Gemini" busy={pending} waitingReason={state?.busy ? 'Gemini is responding' : null}
      secondary={state?.busy ? <button type="button" disabled={pending} onClick={() => { void act(() => window.bridge.stopGoogleChat(profile)); }}>Stop</button> : <small>Cleared when ByteQuay quits</small>}
      onCancel={() => setDraft('')} onSubmit={async () => { if (await act(() => window.bridge.promptGoogleChat(profile, draft))) setDraft(''); }} />
  </aside>;
}
