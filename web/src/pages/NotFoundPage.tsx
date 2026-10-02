import { Link } from 'react-router-dom';
import { useDocumentTitle } from '../hooks';

export function NotFoundPage() {
  useDocumentTitle('Not found');
  return (
    <main className="login">
      <div className="login-card card">
        <h1>Page not found</h1>
        <p className="lead">That page doesn't exist.</p>
        <Link className="btn btn-primary" to="/feed">
          Go to your feed
        </Link>
      </div>
    </main>
  );
}
