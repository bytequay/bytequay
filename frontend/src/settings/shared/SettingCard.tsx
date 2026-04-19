import type { ReactNode } from 'react';

type Props = {
  title?: string;
  hint?: ReactNode;
  /** Optional control rendered to the right of the title (e.g. an action button). */
  action?: ReactNode;
  children?: ReactNode;
};

function SettingCard({ title, hint, action, children }: Props) {
  return (
    <section className="setting-card">
      {(title || action) && (
        <header className="setting-card__head">
          {title && <h3 className="setting-card__title">{title}</h3>}
          {action}
        </header>
      )}
      {hint && <p className="setting-card__hint">{hint}</p>}
      {children}
    </section>
  );
}

export default SettingCard;
