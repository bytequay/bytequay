type Props = {
  title: string;
  description?: string;
};

/**
 * Placeholder rendered for sidebar sections whose features ship in a later
 * phase (Notifications / Integrations / Help, plus Teams until Phase C).
 * Keeps the sidebar shape stable and signals direction without misleading
 * users that the feature already works.
 */
function ComingSoon({ title, description }: Props) {
  return (
    <div className="settings-stub">
      <div className="settings-stub__title">{title}</div>
      <div>{description ?? 'This area is on the roadmap. We\'ll let you know when it lands.'}</div>
    </div>
  );
}

export default ComingSoon;
