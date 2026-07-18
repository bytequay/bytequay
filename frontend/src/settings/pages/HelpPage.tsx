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
import type { IssueDto, ProductIssueMonitorStatusDto } from '../../types';
import { pasteClipboardImages } from '../../ui/shell/pasteClipboardImages';
import SettingCard from '../shared/SettingCard';

function HelpPage() {
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [images, setImages] = useState<string[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [created, setCreated] = useState<IssueDto | null>(null);
  const [monitor, setMonitor] = useState<ProductIssueMonitorStatusDto | null>(null);
  const [updatingMonitor, setUpdatingMonitor] = useState(false);

  useEffect(() => {
    let cancelled = false;
    void window.bridge.getByteQuayIssueMonitor()
      .then(status => { if (!cancelled) setMonitor(status); })
      .catch(() => { /* reporting still works when the maintainer probe fails */ });
    return () => { cancelled = true; };
  }, []);

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!title.trim() || !body.trim()) return;
    if (images.length > 0) {
      setError('Screenshot captured, but ByteQuay needs a public attachment upload endpoint before it can include images in a GitHub issue.');
      return;
    }
    setSubmitting(true);
    setError(null);
    setCreated(null);
    try {
      const issue = await window.bridge.reportByteQuayIssue(title.trim(), body.trim());
      setCreated(issue);
      setTitle('');
      setBody('');
    }
    catch (reason) {
      setError(reason instanceof Error ? reason.message : String(reason));
    }
    finally {
      setSubmitting(false);
    }
  };

  const toggleMonitor = async () => {
    if (!monitor) return;
    setUpdatingMonitor(true);
    setError(null);
    try {
      setMonitor(await window.bridge.setByteQuayIssueMonitor(!monitor.enabled));
    }
    catch (reason) {
      setError(reason instanceof Error ? reason.message : String(reason));
    }
    finally {
      setUpdatingMonitor(false);
    }
  };

  return (
    <>
      <div className="settings-shell-page__head">
        <div>
          <h2 className="settings-shell-page__title">Help &amp; feedback</h2>
          <div className="settings-shell-page__subtitle">
            Report a ByteQuay problem without leaving the app.
          </div>
        </div>
      </div>

      <SettingCard
        title="Report a bug"
        hint={<>This always creates an issue in <code>bytequay/bytequay</code>. The repository does not need to be watched.</>}
      >
        <form className="product-issue-form" onSubmit={event => { void submit(event); }}>
          <label className="product-issue-form__field">
            <span>Short title</span>
            <input
              value={title}
              maxLength={256}
              onChange={event => setTitle(event.target.value)}
              placeholder="What went wrong?"
              disabled={submitting}
              required
            />
          </label>
          <label className="product-issue-form__field">
            <span>What happened?</span>
            <textarea
              aria-label="What happened?"
              value={body}
              maxLength={65_000}
              onChange={event => setBody(event.target.value)}
              onPaste={event => pasteClipboardImages(event, images, next => {
                setImages(next);
                setError(null);
              })}
              placeholder="What were you doing, what did you expect, and what happened instead?"
              rows={7}
              disabled={submitting}
              required
            />
            <small>Paste a screenshot here with ⌘V or Ctrl+V.</small>
          </label>
          {images.length > 0 && (
            <div className="product-issue-form__images" aria-label="Attached screenshots">
              {images.map((src, index) => (
                <div className="product-issue-form__image" key={`${src.slice(-24)}-${index}`}>
                  <img src={src} alt="Pasted screenshot" />
                  <button
                    type="button"
                    aria-label={`Remove screenshot ${index + 1}`}
                    onClick={() => setImages(images.filter((_, imageIndex) => imageIndex !== index))}
                  >×</button>
                </div>
              ))}
            </div>
          )}
          <div className="product-issue-form__actions">
            <button
              type="submit"
              className="button button--primary"
              disabled={submitting || !title.trim() || !body.trim()}
            >
              {submitting ? 'Submitting…' : 'Submit issue'}
            </button>
          </div>
          {created && (
            <p className="product-issue-form__success" role="status">
              Issue #{created.number} created.{' '}
              <button type="button" onClick={() => { void window.bridge.openInAppBrowser(created.htmlUrl); }}>
                View on GitHub
              </button>
            </p>
          )}
          {error && <p className="product-issue-form__error" role="alert">{error}</p>}
        </form>
      </SettingCard>

      {monitor && (monitor.eligible || monitor.enabled) && (
        <SettingCard
          title="Maintainer issue intake"
          hint="Poll new ByteQuay issues, plan them through the Agent Scheduler, and auto-merge only high-confidence, low-risk, small fixes."
          action={
            <button
              type="button"
              className={`ai-skills__toggle${monitor.enabled ? '' : ' ai-skills__toggle--off'}`}
              aria-label="Toggle maintainer issue intake"
              aria-pressed={monitor.enabled}
              disabled={updatingMonitor || !monitor.eligible}
              onClick={() => { void toggleMonitor(); }}
            />
          }
        >
          <p className="product-issue-monitor__status">
            {monitor.enabled ? 'Monitoring is on.' : 'Monitoring is off.'}
            {monitor.reason && ` ${monitor.reason}`}
          </p>
        </SettingCard>
      )}
    </>
  );
}

export default HelpPage;
