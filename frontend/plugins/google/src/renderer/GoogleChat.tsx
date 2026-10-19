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
import { useGoogleHost } from './host';
import { useEffect, useRef, useState } from 'react';
import { ArrowUpRight, CalendarDays, Eraser, KeyRound, LoaderCircle, Lock, Mail, MailWarning, Reply, Search, ShieldQuestionMark, Sparkles, Square, X } from 'lucide-react';
import type { GeminiSetupStatus, GoogleChatState } from '../contracts';
import Composer from '@bytequay/ui/Composer';

/** What the next question is about; it travels as part of the message so the conversation shows what was sent. */
export type ChatContext = { kind: 'mail' | 'event'; label: string; detail: string };

const SUGGESTIONS = [
  { icon: MailWarning, text: 'What in my inbox needs a reply today?' },
  { icon: CalendarDays, text: 'What does my week look like?' },
  { icon: Reply, text: 'Draft a reply to my latest unread email' },
];

export default function GoogleChat({ profile, email, context, onContext }: { profile: string; email: string; context: ChatContext | null; onContext: (context: ChatContext | null) => void }) {
  const { bridge, settingsUrl, Markdown } = useGoogleHost();
  const [state, setState] = useState<GoogleChatState | null>(null);
  const [draft, setDraft] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [setup, setSetup] = useState<GeminiSetupStatus | null>(null);
  const [setupError, setSetupError] = useState<string | null>(null);
  const alive = useRef(true);
  const bottom = useRef<HTMLDivElement>(null);
  const input = useRef<HTMLTextAreaElement>(null);
  useEffect(() => {
    let active = true;
    const refresh = () => { void bridge.getGeminiSetupStatus().then(next => {
      if (active) { setSetup(next); setSetupError(null); }
    }).catch(() => {
      if (active) { setSetup(null); setSetupError('Unable to check Gemini setup. Open settings to check Keychain access.'); }
    }); };
    refresh();
    window.addEventListener('focus', refresh);
    return () => { active = false; window.removeEventListener('focus', refresh); };
  }, [bridge]);
  useEffect(() => {
    alive.current = true;
    let inFlight = false;
    const refresh = async () => {
      if (inFlight) return;
      inFlight = true;
      try { const next = await bridge.getGoogleChat(profile); if (alive.current) setState(next); }
      catch { if (alive.current) setError('Unable to read the Gemini conversation.'); }
      finally { inFlight = false; }
    };
    void refresh(); const timer = window.setInterval(() => { void refresh(); }, 700);
    return () => { alive.current = false; window.clearInterval(timer); };
  }, [profile, bridge]);
  const lastText = state?.messages.at(-1)?.text;
  useEffect(() => { bottom.current?.scrollIntoView({ block: 'nearest' }); }, [lastText, state?.approval, state?.busy]);
  useEffect(() => { if (context) input.current?.focus(); }, [context]);
  const act = async (action: () => Promise<GoogleChatState>) => {
    setError(null); setPending(true);
    try { const next = await action(); if (alive.current) setState(next); return true; }
    catch (cause) { if (alive.current) setError(cause instanceof Error ? cause.message : 'Gemini request failed.'); return false; }
    finally { if (alive.current) setPending(false); }
  };
  const busy = Boolean(state?.busy);
  const ready = Boolean(setup?.configured && setup.installed);
  const ask = async (text: string) => {
    if (!text.trim() || busy || pending) return;
    if (await act(() => bridge.promptGoogleChat(profile, context ? `${context.detail}\n\n${text}` : text))) { setDraft(''); onContext(null); }
  };
  const stop = () => { void act(() => bridge.stopGoogleChat(profile)); };
  const messages = state?.messages ?? [];
  const failure = error || state?.error;
  return <aside className="google-chat" aria-label="Gemini conversation">
    <header><span className="google-chat__mark"><Sparkles size={14} aria-hidden="true" /></span>
      <div><strong>Gemini</strong><small>Searches live mail &amp; calendar for {email}</small></div>
      {messages.length > 0 && !busy && <button type="button" className="google-quiet-btn" aria-label="Clear conversation" title="Clear conversation" disabled={pending}
        onClick={() => { void act(() => bridge.clearGoogleChat(profile)); }}><Eraser size={13} aria-hidden="true" />Clear</button>}</header>
    <div className="google-chat__messages" role="log" aria-label="Conversation messages">
      {busy || messages.length > 0 ? null : !setup && !setupError ? <p className="google-chat__activity" role="status">Checking Gemini setup…</p> : !ready ? <div className="google-chat__setup">
        <span className="google-chat__key"><KeyRound size={20} aria-hidden="true" /></span>
        <strong>{setup?.configured ? 'Install Gemini CLI' : 'Connect Gemini'}</strong>
        <p role={setupError ? 'alert' : 'status'}>{setupError || (setup?.configured ? 'Install Gemini CLI to use the assistant.'
          : 'Add a Gemini API key to ask about your mail and calendar. The key stays in your Keychain; conversations clear when ByteQuay quits.')}</p>
        <a className="google-primary-btn" href={settingsUrl}><KeyRound size={13} aria-hidden="true" />{setup && !setup.configured ? 'Add API key' : 'Open Gemini settings'}</a>
      </div> : <div className="google-chat__intro">
        <p>Ask about urgent mail, a booking reply, or your schedule. Gemini can search live Google data beyond the items shown here, and asks before it changes anything.</p>
        {SUGGESTIONS.map(({ icon: Icon, text }) => <button type="button" key={text} disabled={pending} onClick={() => { void ask(text); }}>
          <Icon size={15} aria-hidden="true" /><span>{text}</span><ArrowUpRight size={13} aria-hidden="true" /></button>)}
      </div>}
      {messages.map((message, index) => message.role === 'user' ? <div key={index} className="google-chat__user">{message.text}</div>
        : message.role === 'tool' ? <div key={index} className="google-chat__step"><Search size={13} aria-hidden="true" /><span>{message.text}</span></div>
          : <article key={index} className="google-chat__assistant"><span className="google-chat__mark is-small"><Sparkles size={12} aria-hidden="true" /></span><Markdown body={message.text} /></article>)}
      {state?.approval && <section className="google-chat__approval" aria-label="Gemini approval">
        <h3><ShieldQuestionMark size={14} aria-hidden="true" /><span>{state.approval.title}</span></h3><pre>{state.approval.detail}</pre>
        <div>{state.approval.options.map((option, index) => <button type="button" className={index === 0 ? 'google-primary-btn' : 'setw-btn'} disabled={pending} key={option.id}
          onClick={() => { const approval = state.approval; if (approval) void act(() => bridge.approveGoogleChat(profile, approval.id, option.id)); }}>{option.label}</button>)}
        <button type="button" className="setw-btn is-danger" disabled={pending} onClick={stop}>Stop</button></div></section>}
      {busy && !state?.approval && <p className="google-chat__activity" role="status"><LoaderCircle className="pr-task-spinner" size={12} aria-hidden="true" />{state?.activity}</p>}
      {failure && <p className="google-screen__error" role="alert">{failure}</p>}
      <div ref={bottom} />
    </div>
    {(ready || busy) && <div className="google-chat__compose">
      <Composer className="is-new-task" inputRef={input} aria-label="Message Gemini" placeholder="Ask Gemini about your mail or calendar…"
        value={draft} maxLength={20000} onChange={event => setDraft(event.target.value)} busy={pending}
        onSubmit={() => { void ask(draft); }} submitEnabled={Boolean(draft.trim()) && !busy && !pending}
        submitLabel="Send to Gemini" submitTitle="Send to Gemini (Enter)"
        header={context && <div className="google-chat__context"><span>{context.kind === 'event' ? <CalendarDays size={12} aria-hidden="true" /> : <Mail size={12} aria-hidden="true" />}<span>{context.label}</span>
          <button type="button" aria-label="Remove context" title="Remove context" onClick={() => onContext(null)}><X size={11} /></button></span></div>}>
        <span className="google-chat__private"><Lock size={12} aria-hidden="true" />Private</span>
        {!busy && <small>· Cleared when ByteQuay quits</small>}
        <span className="google-spacer" />
        {busy && <><LoaderCircle className="pr-task-spinner" size={14} aria-label="Gemini is responding" /><button type="button" className="google-chat__stop" disabled={pending} onClick={stop}><Square size={11} aria-hidden="true" />Stop</button></>}
      </Composer>
    </div>}
  </aside>;
}
