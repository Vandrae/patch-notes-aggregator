import { Link } from 'react-router-dom';
import { fullDate, safeExternalUrl, timeAgo } from '../format';
import type { FeedItem } from '../types';
import { GameIcon } from './GameIcon';

/** @param showGame false on a game's own page, where every card is that game and naming it again would be noise */
export function ArticleCard({ item, showGame = true }: { item: FeedItem; showGame?: boolean }) {
  const href = safeExternalUrl(item.url);
  return (
    <article className="card article">
      <div className="article-head">
        {showGame && <GameIcon name={item.gameName} iconUrl={item.gameIconUrl} size={32} />}
        <div className="article-meta">
          {showGame && (
            <>
              <Link className="article-game" to={`/games/${item.gameId}`}>
                {item.gameName}
              </Link>
              <span className="dot" aria-hidden="true">
                ·
              </span>
            </>
          )}
          <time dateTime={item.publishedAt} title={fullDate(item.publishedAt)}>
            {timeAgo(item.publishedAt)}
          </time>
        </div>
        <span className="badge">Patch notes</span>
      </div>
      <h3 className="article-title">
        {href ? (
          <a href={href} target="_blank" rel="noopener noreferrer">
            {item.title}
            <span className="visually-hidden"> (opens on Steam in a new tab)</span>
          </a>
        ) : (
          item.title
        )}
      </h3>
      {item.summary && <p className="article-summary">{item.summary}</p>}
      {href && (
        <a className="article-link" href={href} target="_blank" rel="noopener noreferrer" tabIndex={-1} aria-hidden="true">
          Read the full notes ↗
        </a>
      )}
    </article>
  );
}
