import type { ReactNode } from 'react';

type Props = {
  title: ReactNode;
  description?: ReactNode;
  /** Control(s) rendered to the right — toggle, button, input, group of buttons, etc. */
  control: ReactNode;
};

function SettingRow({ title, description, control }: Props) {
  return (
    <div className="setting-row">
      <div className="setting-row__text">
        <div className="setting-row__title">{title}</div>
        {description && <div className="setting-row__desc">{description}</div>}
      </div>
      <div className="setting-row__control">{control}</div>
    </div>
  );
}

export default SettingRow;
