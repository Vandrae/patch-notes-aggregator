import { Link, Navigate, useSearchParams } from 'react-router-dom';
import type { CSSProperties } from 'react';
import { safeNext, steamLoginUrl } from '../api';
import { useDocumentTitle } from '../hooks';
import { useMe } from '../queries';
import { Logo } from '../components/Logo';

/** Three plain facts under the button; the colour on each is only decoration. */
const FACTS = [
  { label: 'All of Steam', value: '~190,000', note: 'games to follow, found by name', color: '#f2c14e' },
  { label: 'Beyond Steam', value: '4 games', note: 'Roblox, Minecraft, League of Legends and VALORANT', color: '#7f93ff' },
  { label: 'Just the notes', value: 'One feed', note: 'a short excerpt, and a link to the official post', color: '#58d68d' },
];

export function LoginPage() {
  useDocumentTitle('Sign in');
  const [params] = useSearchParams();
  const { data: user } = useMe();
  const next = safeNext(params.get('next'));

  if (user) return <Navigate to={next} replace />;

  return (
    <main className="login">
      <div className="login-hero">
        <span className="brand-mark brand-mark-lg">
          <Logo size={64} />
        </span>
        <h1>Never miss a patch</h1>
        <p className="lead">Follow your games and get their official patch notes in one clean feed.</p>

        {params.get('error') && (
          <p className="notice notice-error" role="alert">
            {params.get('error') === 'rate-limited'
              ? 'Too many sign-in attempts. Please wait a minute and try again.'
              : "Sign-in with Steam didn't complete. Please try again."}
          </p>
        )}

        {/* A full page navigation on purpose: the server redirects to Steam and back. */}
        <a className="btn btn-steam" href={steamLoginUrl(next)}>
          Sign in through Steam
        </a>
        <p className="fine-print">
          You sign in on Steam's own page. We only receive your public Steam name and avatar: no password, no email.{' '}
          <Link to="/privacy">Privacy</Link>
        </p>

        <ul className="login-facts" aria-label="What you get">
          {FACTS.map((fact) => (
            <li key={fact.label} style={{ '--fact': fact.color } as CSSProperties}>
              <span className="fact-label">{fact.label}</span>
              <span className="fact-value">{fact.value}</span>
              <span className="fact-note">{fact.note}</span>
            </li>
          ))}
        </ul>
      </div>
    </main>
  );
}
