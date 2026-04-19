import { useState } from 'react';

type Props = {
  login: string;
  size?: number;
  className?: string;
};

/**
 * GitHub profile picture with a colored-initial fallback.
 *
 * If the image load fails — common for actors whose `login` isn't a real
 * GitHub handle (bot markers like "dependabot[bot]", display names from
 * commit authors like "Nazarii Gudzovatyi", etc.) — we render a placeholder
 * with the first character. It's important that the fallback *still occupies
 * the same 24×24 (or whatever size) box*: if we hid the image with
 * `display: none`, the surrounding CSS Grid/Flex would re-place the sibling
 * into the avatar slot and its text would wrap at 3 chars per line.
 */
function Avatar({ login, size = 20, className }: Props) {
  const [failed, setFailed] = useState(false);
  const classes = ['avatar', className].filter(Boolean).join(' ');
  const hasLogin = login.trim().length > 0;

  if (failed || !hasLogin) {
    const initial = hasLogin ? login.trim().charAt(0).toUpperCase() : '?';
    return (
      <span
        className={classes + ' avatar--fallback'}
        style={{
          width: size,
          height: size,
          fontSize: Math.max(10, Math.round(size * 0.5)),
        }}
        aria-label={hasLogin ? login : 'unknown user'}
        role="img"
      >
        {initial}
      </span>
    );
  }

  return (
    <img
      className={classes}
      src={`https://github.com/${encodeURIComponent(login)}.png?size=${size * 2}`}
      alt={login}
      width={size}
      height={size}
      onError={() => setFailed(true)}
    />
  );
}

export default Avatar;
