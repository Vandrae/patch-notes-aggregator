# The catalog, search and filters

How the app knows about every game on Steam, how search ranks them, and where covers, ratings and genres come from.

**The catalog:** on first start (or whenever it holds fewer than 1,000 games) the app imports every game from Steam's
`IStoreService/GetAppList` in the background, about 15 seconds for the whole list, then refreshes nightly with only what
changed. This needs `STEAM_API_KEY`; without one, only the starter game (Dragonwilds) is searchable and Discover says so.
Search ignores case, accents, punctuation and trademark symbols ("half life 2" finds *Half-Life 2: Episode One™*). Results
are ranked by one blended score, `relevance bonus + log10(1 + popularity)`, where the bonus is 3.0 for the exact name, 1.5 for a
name starting with your text and 0 for one that merely contains it. Popularity is on a log scale, so an exact match beats a
prefix match of similar popularity ("Portal" before "Portal 2"), but a game about 100x more popular can overtake an exact
match: searching "war" puts WARDOGS, War Thunder and Warframe above an obscure game that happens to be called "WAR!". With
no query, Discover lists the whole catalog most popular first. Each result shows Steam's cover
image, a short description, and a "View on Steam" link with the Steam logo. Steam has many different games with the same name
(three are called "Deadlock"), so popularity and that link are how you tell them apart.

**Covers, icons, descriptions, genres, ratings and popularity** come from Steam's store API (`IStoreBrowseService/GetItems`, 200 games per request) in
a second background job that runs after the catalog import. Popularity is `reviews + 10 × peak players on Steam's most-played
chart`: reviews cover almost every game, and the chart covers hugely played games that have few or no reviews yet (Valve's
Deadlock has none). It is a ranking heuristic, not a statistic. Steam throttles that endpoint hard (measured: about one request
per 3 seconds, after which it answers HTTP 429), so the job is **one paced worker** that waits and retries the *same* batch when
throttled, and fetches the most useful games first (the most-played chart, then recently updated games). The first run over the
whole catalog therefore takes about **50 minutes** in the background; search works throughout and improves as it goes (Discover
shows progress). After that only new, changed or stale (30 days) games are refreshed.

**Genre, rating and age filters** (Discover and the feed) use three more things from that same request, so they cost no extra calls:
the game's top store tags, of which Steam's ten standard genres (Action, Adventure, Casual, Indie, Massively Multiplayer,
Racing, RPG, Simulation, Sports, Strategy) are kept, and Steam's own review level (1 Overwhelmingly Negative … 9 Overwhelmingly
Positive; 0 = no reviews), and the ESRB age rating Steam shows (Everyone, Everyone 10+, Teen, Mature 17+, Adults Only 18+).
Many games have no ESRB rating at all (free-to-play and Valve titles, for one), so they never pass an age filter. Pick any number
of genres and any number of age ratings (a game matches if it has *at least one* of each) and a "this review rating or better"
level; the three combine. On the feed the filter chooses *games*, so it only ever narrows the notes of games you already watch.
All of it lives in the URL (`?genre=RPG&genre=ACTION&rating=8&age=MATURE`), so a filtered view survives a reload. A game whose details haven't
been fetched yet has no genre, rating or age rating, so it's left out while a filter is on; Discover says so while the first run is going.
