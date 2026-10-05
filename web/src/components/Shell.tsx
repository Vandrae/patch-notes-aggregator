import { useCallback, useState } from 'react';
import { Link, NavLink, Outlet } from 'react-router-dom';
import { AnnounceContext } from '../hooks';
import { useMe } from '../queries';
import { Avatar } from './Avatar';
import { Logo } from './Logo';

/** The signed-in frame: header, navigation, and a polite live region for screen-reader announcements. */
export function Shell() {
  const { data: user } = useMe();
  const [announcement, setAnnouncement] = useState('');
  // clear first so repeating the same message is announced again
  const announce = useCallback((message: string) => {
    setAnnouncement('');
    setTimeout(() => setAnnouncement(message), 50);
  }, []);

  return (
    <AnnounceContext.Provider value={announce}>
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <header className="topbar">
        <div className="topbar-inner">
          <Link to="/feed" className="brand" aria-label="Patch Notes home">
            <span className="brand-mark">
              <Logo size={28} />
            </span>
            <span className="brand-name">Patch Notes</span>
          </Link>
          {user && (
            <Link to="/account" className="user-chip" aria-label={`Account: ${user.personaName}`}>
              <Avatar user={user} size={28} />
              <span className="user-name">{user.personaName}</span>
            </Link>
          )}
        </div>
        <nav className="tabs" aria-label="Main">
          <NavLink to="/feed">Feed</NavLink>
          <NavLink to="/discover">Discover</NavLink>
          <NavLink to="/watchlist">Watchlist</NavLink>
        </nav>
      </header>
      <main id="main" className="page" tabIndex={-1}>
        <Outlet />
      </main>
      <footer className="footer">
        Patch notes come from each game's official posts on Steam. Powered by{' '}
        <a href="https://store.steampowered.com/" target="_blank" rel="noopener noreferrer">
          Steam
        </a>
        . Not affiliated with Valve. <Link to="/privacy">Privacy</Link>
      </footer>
      <div className="visually-hidden" role="status" aria-live="polite">
        {announcement}
      </div>
    </AnnounceContext.Provider>
  );
}
