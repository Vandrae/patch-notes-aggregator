import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api';
import { Avatar } from '../components/Avatar';
import { useDocumentTitle } from '../hooks';
import { useMe } from '../queries';

export function AccountPage() {
  useDocumentTitle('Account');
  const { data: user } = useMe();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [confirming, setConfirming] = useState(false);

  const leave = useMutation({
    mutationFn: (kind: 'signout' | 'delete') => (kind === 'signout' ? api.post('/api/auth/logout') : api.delete('/api/me')),
    onSuccess: () => {
      queryClient.clear(); // nothing from this session should linger in memory
      navigate('/login', { replace: true });
    },
  });

  if (!user) return null;

  return (
    <>
      <div className="page-head">
        <h1>Account</h1>
      </div>

      <section className="card profile" aria-labelledby="profile-heading">
        <Avatar user={user} size={64} />
        <div>
          <h2 id="profile-heading">{user.personaName}</h2>
          <p className="muted small">Steam ID {user.steamId}</p>
        </div>
        <button type="button" className="btn" disabled={leave.isPending} onClick={() => leave.mutate('signout')}>
          Sign out
        </button>
      </section>

      <section className="card danger-zone" aria-labelledby="danger-heading">
        <h2 id="danger-heading">Delete account</h2>
        <p className="muted">
          This removes your account and your watchlist from this app. It doesn't touch your Steam account in any way.
        </p>
        {!confirming ? (
          <button type="button" className="btn btn-danger-outline" onClick={() => setConfirming(true)}>
            Delete my account…
          </button>
        ) : (
          <div className="confirm" role="alertdialog" aria-labelledby="confirm-text">
            <p id="confirm-text">Are you sure? This can't be undone.</p>
            <div className="row-actions">
              <button type="button" className="btn btn-danger" disabled={leave.isPending} onClick={() => leave.mutate('delete')}>
                Yes, delete everything
              </button>
              <button type="button" className="btn" onClick={() => setConfirming(false)}>
                Cancel
              </button>
            </div>
          </div>
        )}
        {leave.isError && (
          <p className="notice notice-error" role="alert">
            That didn't work. Please try again.
          </p>
        )}
      </section>
    </>
  );
}
