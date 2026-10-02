import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { ApiError } from '../api';
import { useMe } from '../queries';
import { ErrorState } from './States';

/** Renders its children only for a signed-in user; everyone else is sent to the sign-in page and brought back after. */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { data: user, error, isPending, refetch } = useMe();
  const location = useLocation();

  if (isPending) {
    return (
      <div className="splash" role="status" aria-label="Loading">
        <div className="spinner" aria-hidden="true" />
      </div>
    );
  }
  if (error instanceof ApiError && error.status === 401) {
    const next = encodeURIComponent(location.pathname + location.search);
    return <Navigate to={`/login?next=${next}`} replace />;
  }
  if (error || !user) {
    return (
      <div className="page">
        <ErrorState message="We couldn't reach the server." onRetry={() => void refetch()} />
      </div>
    );
  }
  return <>{children}</>;
}
