# Games that are not on Steam

Some of the most-watched games are not on Steam at all (Minecraft, Roblox, League of Legends, Fortnite, World of Warcraft…).
They are added by **configuration, not code**: an entry under `app.custom.games` in `application.yml` names the game and the
feed its patch notes are read from. At startup each entry is put in the catalog (searchable, followable, with its own game
page, like any other game), and from then on the same polling, storage and feed as Steam games apply to it.

```yaml
app:
  custom:
    games:
      - name: Roblox
        description: "…two plain sentences for the game's page…"
        popularity: 20000000        # only where it sorts when browsing Discover; it orders, it does not measure
        sources:
          - kind: RSS               # an RSS 2.0 or Atom feed (this one is a Discourse forum category)
            url: https://devforum.roblox.com/c/updates/release-notes/62.rss
```

Three kinds of source are understood: `RSS` (RSS 2.0 and Atom, including a Discourse forum category's `.rss`), `HELP_CENTER` (a
Zendesk help centre's public article list) and `RIOT_NEWS` (a Riot Games news page, see below). A game may have several
sources, which are combined, and one being down does not stop the others. Feeds must be https and are read politely: an
honest `User-Agent` naming this project, a 5 MB cap, a 15 second timeout, compressed transfer, and the same adaptive
schedule as Steam games (a quiet game is asked about once a day). The publisher's own link goes with every note, and only a
short plain-text excerpt is kept, as for Steam.

**Registered now:** Roblox (the Roblox developer forum's release-notes feed), Minecraft (the official release changelogs for
Java and Bedrock, from Mojang's help centre), and League of Legends and VALORANT (Riot Games' official patch notes).

**The rule.** A game is added when its publisher itself offers its patch notes publicly and in a form meant to be read by a
program, with one deliberate, narrow exception: Riot Games. The most-streamed non-Steam games were checked in October 2026:

| Game | What I found | Verdict |
|---|---|---|
| Roblox | official RSS (Discourse category) | **added** |
| Minecraft | official help-centre API with dates, text and links | **added** |
| League of Legends, VALORANT | no feed, but the news page carries its article list as data | **added**, read as little as possible (below) |
| Teamfight Tactics | its notes are on a separate site with a different layout | not added |
| Clash Royale | blog is a web page, no feed | not added |
| Star Citizen | the "RSS" address returns a web page | not added |
| World of Warcraft, Hearthstone | no feed at the addresses tried | not added |
| Fortnite, Rocket League, Tibia | behind a Cloudflare bot check | not added: that check is not something to get around |
| Genshin Impact, Mobile Legends | not investigated (mobile or launcher only) | not added |
| Pokémon and other Nintendo titles | no public patch-note feed | not added |

## Riot Games (the exception, and how it is kept small)

Riot publishes no feed. Its news pages are built with Next.js, which leaves the data a page was built from inside the page: a
list of every patch-notes article with its title, link, date and a short teaser Riot wrote. So one request to the list page
is enough, and an article page is never opened. What was checked first: Riot's `robots.txt` allows all crawlers; its
[terms of service](https://www.riotgames.com/en/terms-of-service) ban "bots and automation programs that interact with the Riot
Services" (section 7.1), which is aimed at game cheats and does not mention reading the website; and its fan-content policy
([Legal Jibber Jabber](https://www.riotgames.com/en/legal)) says nothing about automated access but asks for a notice that
the project is not endorsed by Riot, which the privacy page and the footer carry. This is a judgement call, not a permission:
Riot has not agreed to it. So it is kept as gentle as it can be and still work:

- **One page per game**, about 56 KB with compression, never an article.
- **At most twice a day per page** (`min-interval: 12h`), enforced inside the app: however often the poller asks and however
  many people follow the game, the last answer is reused and nothing is sent. A failed read is remembered for up to 30
  minutes, so a page that has changed shape is not asked for again and again.
- **Honest identification**: the `User-Agent` names this project and its address.
- **Only the teaser and a link back** are stored, as for every game.
- **It fails loudly.** The page is read by the shape of its data, not its position; if that shape disappears the read is an
  error (visible in the logs, and the game backs off), never an empty list that looks like "no new patch".
- **A test guards the gentleness**: `ShippedCustomGamesTest` fails if a Riot page's minimum gap is shortened below 12 hours.

If Riot ever asks for this to stop, remove the two entries (and the `RIOT_NEWS` kind): nothing else depends on them.

Turning other publishers' web pages into feeds is not done: it breaks whenever a site changes, and a bot check is a clear
"no". If a publisher later offers a feed, adding the game is one entry.

## Artwork

Steam games get their cover and icon from Steam. A game that is not on Steam has none: the app draws a lettered tile, and its
page has no cover. That is on purpose. The publishers' official art could not be used: Riot's press page offers only logos, and
Riot's fan-project rules ban its logos; Roblox does not allow its logo; Mojang's guidelines could not be read. Nothing is
hotlinked from a publisher either.

An entry can still name art (`image` and `icon`), which must be a file in this site's `/art/` folder (lower-case letters,
digits and dashes; `.svg`, `.png`, `.webp` or `.jpg`); startup refuses a web address or any other path, and a test checks the
file exists. No art ships today. If a publisher offers official art with a licence that fits, putting the file in
`web/public/art/` and naming it in the entry is all it takes.

What these games still lack compared to Steam games: genres, review ratings and a "View on Steam" link (their line says "Patch
notes from the publisher" instead), and their Discover position comes from the configured `popularity`, not from reviews.
