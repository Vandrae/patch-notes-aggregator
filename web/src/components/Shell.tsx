import { useCallback, useState } from 'react';
import { Link, NavLink, Outlet, useLocation } from 'react-router-dom';
import { steamLoginUrl } from '../api';
import { AnnounceContext } from '../hooks';
import { useMe } from '../queries';
import { Avatar } from './Avatar';
import { Logo } from './Logo';

/**
 * The frame around the pages: header, navigation, and a polite live region for screen-reader announcements. A guest sees
 * the catalog and a "Sign in" button; the feed, watchlist and account tabs appear once somebody has signed in.
 */
export function Shell() {
  const { data: user, isPending } = useMe();
  const location = useLocation();
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
          <Link to={user ? '/feed' : '/discover'} className="brand" aria-label="Patch Notes home">
            <span className="brand-mark">
              <Logo size={28} />
            </span>
            <span className="brand-name">Patch Notes</span>
          </Link>
          {user ? (
            <Link to="/account" className="user-chip" aria-label={`Account: ${user.personaName}`}>
              <Avatar user={user} size={28} />
              <span className="user-name">{user.personaName}</span>
            </Link>
          ) : (
            !isPending && (
              <a className="btn btn-primary btn-sm" href={steamLoginUrl(location.pathname + location.search)}>
                Sign in with Steam
              </a>
            )
          )}
        </div>
        <nav className="tabs" aria-label="Main">
          {user && <NavLink to="/feed">Feed</NavLink>}
          <NavLink to="/discover">Discover</NavLink>
          {user && <NavLink to="/watchlist">Watchlist</NavLink>}
        </nav>
      </header>
      <main id="main" className="page" tabIndex={-1}>
        <Outlet />
      </main>
      <footer className="footer">
        Patch notes come from each game's official posts, on Steam or the publisher's own site. Powered by{' '}
        <a href="https://store.steampowered.com/" target="_blank" rel="noopener noreferrer">
          Steam
        </a>
        . Not affiliated with Valve, Riot Games, Roblox or Mojang. <Link to="/privacy">Privacy</Link>
      </footer>
      <div className="visually-hidden" role="status" aria-live="polite">
        {announcement}
      </div>
    </AnnounceContext.Provider>
  );
}
