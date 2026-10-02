import { Navigate, Route, Routes } from 'react-router-dom';
import { RequireAuth } from './components/RequireAuth';
import { Shell } from './components/Shell';
import { AccountPage } from './pages/AccountPage';
import { DiscoverPage } from './pages/DiscoverPage';
import { FeedPage } from './pages/FeedPage';
import { LoginPage } from './pages/LoginPage';
import { NotFoundPage } from './pages/NotFoundPage';
import { WatchlistPage } from './pages/WatchlistPage';

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        element={
          <RequireAuth>
            <Shell />
          </RequireAuth>
        }
      >
        <Route path="/feed" element={<FeedPage />} />
        <Route path="/discover" element={<DiscoverPage />} />
        <Route path="/watchlist" element={<WatchlistPage />} />
        <Route path="/account" element={<AccountPage />} />
      </Route>
      <Route path="/" element={<Navigate to="/feed" replace />} />
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  );
}
