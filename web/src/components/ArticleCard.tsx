import { fullDate, safeExternalUrl, timeAgo } from '../format';
import type { FeedItem } from '../types';
import { GameIcon } from './GameIcon';

export function ArticleCard({ item }: { item: FeedItem }) {
  const href = safeExternalUrl(item.url);
  return (
    <article className="card article">
      <div className="article-head">
        <GameIcon name={item.gameName} iconUrl={item.gameIconUrl} size={32} />
        <div className="article-meta">
          <span className="article-game">{item.gameName}</span>
          <span className="dot" aria-hidden="true">
            ·
          </span>
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
