type Props = {
  on: boolean;
  onChange: (on: boolean) => void;
  disabled?: boolean;
  ariaLabel?: string;
};

function Toggle({ on, onChange, disabled, ariaLabel }: Props) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={on}
      aria-label={ariaLabel}
      disabled={disabled}
      className={`toggle${on ? ' toggle--on' : ''}`}
      onClick={() => onChange(!on)}
    />
  );
}

export default Toggle;
