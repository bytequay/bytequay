import { useEffect, useRef, useState } from 'react';
import type { PullRequestDto } from './types';

type Props = {
  pr: PullRequestDto;
  onBack: () => void;
};

type SignInNotice = { kind: 'blocked'; provider: string } | { kind: 'tips' };

function ReviewScreen({ pr, onBack }: Props) {
  const slotRef = useRef<HTMLDivElement>(null);
  const [notice, setNotice] = useState<SignInNotice | null>(null);
  const [dismissedTips, setDismissedTips] = useState(false);
  const [expanded, setExpanded] = useState(false);

  useEffect(() => {
    const unsubBlocked = window.bridge.onReviewAuthBlocked(({ provider }) => {
      setNotice({ kind: 'blocked', provider });
    });
    const unsubTips = window.bridge.onReviewSignInPage(() => {
      // "blocked" is more specific, don't clobber it with the generic tips.
      // If the user dismissed the tips banner, respect that until the next
      // time they hit a blocked SSO flow.
      setNotice((prev) => {
        if (prev && prev.kind === 'blocked') return prev;
        if (dismissedTips) return prev;
        return { kind: 'tips' };
      });
    });
    return () => {
      unsubBlocked();
      unsubTips();
    };
  }, [dismissedTips]);

  const dismissNotice = () => {
    if (notice?.kind === 'tips') setDismissedTips(true);
    setNotice(null);
    setExpanded(false);
  };

  useEffect(() => {
    const el = slotRef.current;
    if (!el) return;

    const readBounds = () => {
      const r = el.getBoundingClientRect();
      return { x: r.x, y: r.y, width: r.width, height: r.height };
    };

    void window.bridge.mountReview(pr.repo, pr.number, readBounds()).catch(() => {
      /* non-fatal */
    });

    // ResizeObserver catches the common case (window resized → slot changes size).
    // We also listen for window resize as a belt-and-braces for position-only shifts.
    const push = () => {
      void window.bridge.setReviewBounds(readBounds()).catch(() => { /* best-effort */ });
    };
    const ro = new ResizeObserver(push);
    ro.observe(el);
    window.addEventListener('resize', push);

    return () => {
      ro.disconnect();
      window.removeEventListener('resize', push);
      void window.bridge.unmountReview().catch(() => { /* best-effort */ });
    };
  }, [pr.repo, pr.number]);

  return (
    <div className="review-screen">
      <div className="review-toolbar">
        <button className="button button--secondary" onClick={onBack} type="button">
          ← Back to details
        </button>
        <div className="review-toolbar__title">
          <span className="review-toolbar__repo">{pr.repo}</span>
          <span className="review-toolbar__num">#{pr.number}</span>
          <span className="review-toolbar__pr-title">{pr.title}</span>
        </div>
      </div>
      {notice && (
        <div className="review-auth-banner" role="alert">
          <div className="review-auth-banner__summary">
            <span className="review-auth-banner__icon" aria-hidden="true">⚠</span>
            <span className="review-auth-banner__text">
              {notice.kind === 'blocked'
                ? `${notice.provider} sign-in won't work here — use your GitHub username and password.`
                : "Passkey and third-party sign-in won't work here — use your GitHub username and password (plus 2FA if enabled)."}
            </span>
            <button
              className="review-auth-banner__action"
              type="button"
              onClick={() => void window.bridge.resetReviewSignIn(pr.repo, pr.number)}
              title="Clear cookies for this embedded browser and reload the GitHub sign-in page. Use this if GitHub keeps sending you to a passkey prompt instead of the password form."
            >
              Sign in fresh
            </button>
            <button
              className="review-auth-banner__link"
              type="button"
              onClick={() => setExpanded((v) => !v)}
            >
              {expanded ? 'Less' : 'Why?'}
            </button>
            <button
              className="review-auth-banner__close"
              type="button"
              onClick={dismissNotice}
              aria-label="Dismiss"
            >
              ✕
            </button>
          </div>
          {expanded && (
            <div className="review-auth-banner__details">
              macOS only lets approved browsers (Safari, Chrome, Firefox, Edge) drive
              Touch ID / Face ID for WebAuthn, so GitHub&rsquo;s passkey prompt hangs
              here. Google, Microsoft, and Apple SSO also refuse to sign in from
              embedded browsers. Once you sign in with password + 2FA once, GitHub
              remembers this window and you won&rsquo;t see this again.
            </div>
          )}
        </div>
      )}
      <div className="review-slot" ref={slotRef} />
    </div>
  );
}

export default ReviewScreen;
