/**
 * Top-level Notifications view, reachable from the Notifications button in
 * the global topbar. This is intentionally not a Settings sub-page — the
 * settings shell is for *configuration*, not for the day-to-day inbox of
 * "what changed since you last looked."
 *
 * Phase A placeholder. The real feed (mentions, review-requests, CI signals,
 * and team digests) lands once Teams + AI revamp ship.
 */
function NotificationsScreen() {
  return (
    <section className="notifications-screen">
      <header className="notifications-screen__head">
        <h1 className="notifications-screen__title">Notifications</h1>
        <p className="notifications-screen__subtitle">
          Mentions, review requests, and team activity will land here.
        </p>
      </header>
      <div className="settings-stub">
        <div className="settings-stub__title">Coming soon</div>
        <div>
          You'll see a feed of @-mentions, blocking-PR alerts, and per-team digests.
          Quiet hours and per-team mute toggles ship under <em>Settings → Notifications</em>
          once the feed is live.
        </div>
      </div>
    </section>
  );
}

export default NotificationsScreen;
