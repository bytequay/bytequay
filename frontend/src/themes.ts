export type ThemeId = 'github-light' | 'atom-one-dark' | 'warm';

export const THEMES: { id: ThemeId; label: string }[] = [
  { id: 'github-light', label: 'GitHub Light' },
  { id: 'atom-one-dark', label: 'Atom One Dark' },
  { id: 'warm', label: 'Warm' },
];

const STORAGE_KEY = 'bytequay-theme';

export function applyTheme(id: ThemeId): void {
  document.documentElement.setAttribute('data-theme', id);
  localStorage.setItem(STORAGE_KEY, id);
}

export function loadTheme(): ThemeId {
  const saved = localStorage.getItem(STORAGE_KEY) as ThemeId | null;
  return saved && THEMES.some((t) => t.id === saved) ? saved : 'github-light';
}
