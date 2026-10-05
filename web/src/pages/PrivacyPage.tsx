import { Link } from 'react-router-dom';
import { useDocumentTitle } from '../hooks';
import { Logo } from '../components/Logo';

/** Public (no sign-in needed), because people should be able to read this before they sign in. */
export function PrivacyPage() {
  useDocumentTitle('Privacy');

  return (
    <main className="legal">
      <Link to="/" className="brand" aria-label="Patch Notes home">
        <span className="brand-mark">
          <Logo size={28} />
        </span>
        <span className="brand-name">Patch Notes</span>
      </Link>

      <h1>Privacy</h1>
      <p className="lead">The short version: you sign in with Steam, we keep your public Steam name and the games you follow, and nothing else.</p>

      <h2>What we keep</h2>
      <ul>
        <li>Your SteamID, public Steam name and avatar address, which Steam gives us when you sign in. No email address and no password: you sign in on Steam's own page and we never see your Steam password.</li>
        <li>The games you follow, and when you last signed in.</li>
      </ul>
      <p>
        Everything else on the site (games, patch notes) is public information from Steam. We keep a short excerpt of each patch
        note and a link to the original post, not the post itself.
      </p>

      <h2>What we don't do</h2>
      <ul>
        <li>No advertising, no analytics, no tracking scripts and no selling or sharing of your data.</li>
        <li>No access log of who visited what. Visitors' network addresses are kept only in the server's memory, briefly, to slow down abuse; they are not written to any log or to the database.</li>
      </ul>

      <h2>Cookies</h2>
      <p>
        Only the ones needed to work: a session cookie that keeps you signed in for up to 7 days, a security token that protects your
        actions from being forged by other sites, and two short-lived cookies used while signing in. None is used to track you.
      </p>

      <h2>Other companies involved</h2>
      <ul>
        <li>
          <a href="https://store.steampowered.com/privacy_agreement/" target="_blank" rel="noopener noreferrer">
            Steam (Valve)
          </a>{' '}
          handles your sign-in and supplies the data above. Game images are loaded straight from Steam's servers, so Steam sees
          that your browser asked for them.
        </li>
        <li>The site runs on a server from Amazon Web Services, which stores the data described above.</li>
      </ul>

      <h2>Deleting your data</h2>
      <p>
        Open <Link to="/account">Account</Link> and choose <strong>Delete my account</strong>. Your account and watchlist are
        removed straight away. It doesn't touch your Steam account in any way. Copies in backups disappear as those are replaced.
      </p>

      <h2>Questions</h2>
      <p>
        This is a personal project. Questions or requests can go to the{' '}
        <a href="https://github.com/Vandrae/patch-notes-aggregator/issues" target="_blank" rel="noopener noreferrer">
          project's issue page on GitHub
        </a>
        .
      </p>

      <p className="fine-print">Not affiliated with Valve. Steam and the Steam logo are trademarks of Valve Corporation.</p>
    </main>
  );
}
