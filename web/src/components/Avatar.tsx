import { useState } from 'react';
import { safeExternalUrl } from '../format';
import type { User } from '../types';

/** The Steam avatar, or the first letter of the name if there isn't one (or it fails to load). */
export function Avatar({ user, size = 32 }: { user: User; size?: number }) {
  const [broken, setBroken] = useState(false);
  const src = user.avatarUrl ? safeExternalUrl(user.avatarUrl) : undefined;
  if (src && !broken) {
    return (
      <img
        className="avatar"
        src={src}
        alt=""
        width={size}
        height={size}
        referrerPolicy="no-referrer"
        onError={() => setBroken(true)}
      />
    );
  }
  return (
    <span className="avatar avatar-fallback" style={{ width: size, height: size, fontSize: size * 0.45 }} aria-hidden="true">
      {(user.personaName[0] ?? '?').toUpperCase()}
    </span>
  );
}
