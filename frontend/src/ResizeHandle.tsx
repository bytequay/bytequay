import type { MouseEvent as ReactMouseEvent } from 'react';

type Props = {
  /** Called on every mousemove while dragging, with the viewport clientX. */
  onResize: (clientX: number) => void;
  className?: string;
  ariaLabel?: string;
};

/**
 * A 5px-wide invisible drag target between two panels. Global mousemove
 * listeners are used so the drag keeps going if the user's pointer leaves
 * the handle. We also swap the body cursor + disable user-select for the
 * duration so text doesn't highlight while dragging.
 */
function ResizeHandle({ onResize, className, ariaLabel }: Props) {
  const handleMouseDown = (e: ReactMouseEvent) => {
    e.preventDefault();
    const prevCursor = document.body.style.cursor;
    const prevUserSelect = document.body.style.userSelect;
    const onMove = (ev: MouseEvent) => onResize(ev.clientX);
    const onUp = () => {
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
      document.body.style.cursor = prevCursor;
      document.body.style.userSelect = prevUserSelect;
    };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
  };

  return (
    <div
      className={`resize-handle${className ? ' ' + className : ''}`}
      role="separator"
      aria-orientation="vertical"
      aria-label={ariaLabel ?? 'Resize panel'}
      onMouseDown={handleMouseDown}
    />
  );
}

export default ResizeHandle;
