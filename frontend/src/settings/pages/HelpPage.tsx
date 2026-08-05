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
import { useEffect, useState } from 'react';
import type { IssueDto } from '../../types';
import { pasteClipboardImages } from '../../ui/shell/pasteClipboardImages';
import { PreviewImg } from '../../ui/primitives';
import SettingsPage from '../shared/SettingsPage';
import { CheckIcon, ClipboardIcon, IssueIcon } from '../shared/icons';

const REPO = 'bytequay/bytequay';

function HelpPage() {
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [images, setImages] = useState<string[]>([]);
  const [attachDiagnostics, setAttachDiagnostics] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [created, setCreated] = useState<IssueDto | null>(null);
  const [env, setEnv] = useState<Env | null>(null);
  const [copied, setCopied] = useState(false);

  useEffect(() => { void readEnv().then(setEnv); }, []);

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (title.trim() === '' || body.trim() === '') return;
    if (images.length > 0) {
      setError('Screenshot captured, but ByteQuay needs a public attachment upload endpoint before it can include images in a GitHub issue.');
      return;
    }
    setSubmitting(true);
    setError(null);
    setCreated(null);
    try {
      setCreated(await window.bridge.reportByteQuayIssue(title.trim(), composeBody()));
      setTitle('');
      setBody('');
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : String(reason));
    } finally {
      setSubmitting(false);
    }
  };

  const composeBody = () => (attachDiagnostics && env !== null
    ? `${body.trim()}\n\n---\n${diagnosticsBlock(env)}`
    : body.trim());

  /* Filing through the API needs a token with issue-write access to
   * bytequay/bytequay — a fine-grained PAT scoped to the user's own repos
   * can never have that. GitHub's own new-issue form needs no token at all,
   * so failures fall back to it with the draft prefilled. */
  const openPrefilledIssue = async () => {
    const full = composeBody();
    const base = `https://github.com/${REPO}/issues/new?title=${encodeURIComponent(title.trim())}`;
    const withBody = `${base}&body=${encodeURIComponent(full)}`;
    if (withBody.length > 6000) {
      await navigator.clipboard.writeText(full);
      setError('Report copied to clipboard — paste it into the GitHub form.');
      await window.bridge.openInAppBrowser(base);
      return;
    }
    await window.bridge.openInAppBrowser(withBody);
  };

  const copyDiagnostics = async () => {
    if (env === null) return;
    await navigator.clipboard.writeText(diagnosticsBlock(env));
    setCopied(true);
    window.setTimeout(() => setCopied(false), 1600);
  };

  return (
    <SettingsPage
      title="Help & feedback"
      subtitle="Report a ByteQuay problem without leaving the app."
      width={820}
    >
      {created !== null && (
        <div className="sv2-success" role="status">
          <CheckIcon size={15} />
          <span>Issue #{created.number} opened in <span className="sv2-mono">{REPO}</span>.</span>
          <button
            className="sv2-btn sv2-btn--sm"
            type="button"
            style={{ marginLeft: 'auto' }}
            onClick={() => { void window.bridge.openInAppBrowser(created.htmlUrl); }}
          >
            View on GitHub ↗
          </button>
        </div>
      )}

      <div className="sv2-card">
        <div className="sv2-card__head">
          <span className="sv2-card__title">Report a bug</span>
          <span className="sv2-card__hint">always files into <span className="sv2-mono">{REPO}</span></span>
        </div>
        <form className="sv2-form" onSubmit={event => { void submit(event); }}>
          <label className="sv2-field">
            <span>Short title</span>
            <input
              className="sv2-input"
              style={{ height: 34 }}
              value={title}
              maxLength={256}
              onChange={event => setTitle(event.target.value)}
              placeholder="What went wrong?"
              disabled={submitting}
              required
            />
          </label>
          <label className="sv2-field">
            <span>What happened?</span>
            <textarea
              className="sv2-textarea"
              aria-label="What happened?"
              value={body}
              maxLength={65_000}
              rows={6}
              onChange={event => setBody(event.target.value)}
              onPaste={event => pasteClipboardImages(event, images, next => { setImages(next); setError(null); })}
              placeholder="What were you doing, what did you expect, and what happened instead?"
              disabled={submitting}
              required
            />
            <small>Paste a screenshot with ⌘V.</small>
          </label>

          {images.length > 0 && (
            <div className="product-issue-form__images" aria-label="Attached screenshots">
              {images.map((src, index) => (
                <div className="product-issue-form__image" key={`${src.slice(-24)}-${index}`}>
                  <PreviewImg src={src} alt="Pasted screenshot" />
                  <button
                    type="button"
                    aria-label={`Remove screenshot ${index + 1}`}
                    onClick={() => setImages(images.filter((_, i) => i !== index))}
                  >×</button>
                </div>
              ))}
            </div>
          )}

          <button
            type="button"
            className="sv2-check"
            role="checkbox"
            aria-checked={attachDiagnostics}
            onClick={() => setAttachDiagnostics(v => !v)}
          >
            <span className={'sv2-check__box' + (attachDiagnostics ? ' sv2-check__box--on' : '')}>
              {attachDiagnostics && <CheckIcon size={10} width={3.4} />}
            </span>
            <span style={{ minWidth: 0 }}>
              <span className="sv2-check__title">Attach diagnostics</span>
              <span className="sv2-check__desc">
                App version, platform and local backend reachability. Credentials are never included.
              </span>
            </span>
          </button>

          <div className="sv2-form__foot">
            <span>{envLine(env)}</span>
            <button
              type="submit"
              className="sv2-btn sv2-btn--dark"
              disabled={submitting || title.trim() === '' || body.trim() === ''}
            >
              {submitting ? 'Submitting…' : 'Submit issue'}
            </button>
          </div>
          {error !== null && (
            <div className="sv2-error" role="alert">
              <span>{error}</span>
              <button
                className="sv2-btn sv2-btn--sm"
                type="button"
                style={{ marginLeft: 'auto' }}
                onClick={() => { void openPrefilledIssue(); }}
              >
                Open on GitHub instead ↗
              </button>
            </div>
          )}
        </form>
      </div>

      <div className="sv2-card">
        <button
          className="sv2-link-row"
          type="button"
          onClick={() => { void window.bridge.openInAppBrowser(`https://github.com/${REPO}/issues`); }}
        >
          <span className="sv2-link-row__icon"><IssueIcon size={15} /></span>
          Browse open issues
          <span className="sv2-link-row__meta">{REPO} ↗</span>
        </button>
        <button className="sv2-link-row" type="button" disabled={env === null} onClick={() => { void copyDiagnostics(); }}>
          <span className="sv2-link-row__icon"><ClipboardIcon size={15} /></span>
          Copy diagnostics to clipboard
          <span className="sv2-link-row__meta">{copied ? 'copied' : 'for pasting elsewhere'}</span>
        </button>
      </div>
    </SettingsPage>
  );
}

type Env = { version: string; platform: string; backendUp: boolean };

async function readEnv(): Promise<Env> {
  const [version, backendUp] = await Promise.all([
    attempt(() => window.bridge.getAppVersion().then(v => v.version), 'unknown'),
    attempt(() => window.bridge.fetchHello().then(() => true), false),
  ]);
  return { version, platform: navigator.platform, backendUp };
}

/** Diagnostics are a nicety — a bridge call that throws (or isn't there
 *  at all) must never stop the user filing the bug they came here for. */
async function attempt<T>(run: () => Promise<T>, fallback: T): Promise<T> {
  try {
    return await run();
  } catch {
    return fallback;
  }
}

function envLine(env: Env | null): string {
  if (env === null) return 'Reading environment…';
  return `ByteQuay ${env.version} · ${env.platform} · local backend ${env.backendUp ? 'up' : 'unreachable'}`;
}

function diagnosticsBlock(env: Env): string {
  return [
    '**Diagnostics**',
    `- ByteQuay: ${env.version}`,
    `- Platform: ${env.platform}`,
    `- User agent: ${navigator.userAgent}`,
    `- Local backend: ${env.backendUp ? 'reachable' : 'unreachable'}`,
  ].join('\n');
}

export default HelpPage;
