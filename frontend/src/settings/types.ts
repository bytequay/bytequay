// Discriminator for the active page in the new settings shell. Mirrored
// in App.tsx's Nav union so deep links land on the right section.
//
// Note: 'notifications' is *not* a settings page. Its entry point lives in
// the global topbar (top-level NotificationsScreen) — settings is for
// configuration, the topbar item is the day-to-day feed.
export type SettingsSection =
  | 'account'
  | 'appearance'
  | 'github-token'
  | 'teams'
  | 'ai-review'
  | 'watched-repos'
  | 'integrations'
  | 'help';
