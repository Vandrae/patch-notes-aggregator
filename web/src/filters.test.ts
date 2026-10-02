import { describe, expect, it } from 'vitest';
import { filterQuery, isFiltering, matchesFilters, NO_FILTERS } from './filters';
import type { Game } from './types';

const game = (over: Partial<Game>): Game => ({
  id: 1,
  name: 'G',
  sourceType: 'STEAM_NEWS',
  steamAppId: 1,
  shortDescription: null,
  imageUrl: null,
  iconUrl: null,
  genres: [],
  rating: null,
  ageRating: null,
  ...over,
});

describe('filters', () => {
  it('builds the API query tail, and nothing at all when not filtering', () => {
    expect(filterQuery(NO_FILTERS)).toBe('');
    expect(filterQuery({ genres: ['RPG', 'MASSIVELY_MULTIPLAYER'], minRating: 8, ages: [] })).toBe(
      '&genre=RPG&genre=MASSIVELY_MULTIPLAYER&minRating=8',
    );
    expect(filterQuery({ genres: [], minRating: 6, ages: [] })).toBe('&minRating=6');
    expect(filterQuery({ genres: ['RPG'], minRating: 8, ages: ['TEEN', 'MATURE'] })).toBe('&genre=RPG&minRating=8&age=TEEN&age=MATURE');
  });

  it('knows whether anything is being filtered', () => {
    expect(isFiltering(NO_FILTERS)).toBe(false);
    expect(isFiltering({ genres: ['RPG'], minRating: 0, ages: [] })).toBe(true);
    expect(isFiltering({ genres: [], minRating: 7, ages: [] })).toBe(true);
    expect(isFiltering({ genres: [], minRating: 0, ages: ['TEEN'] })).toBe(true);
  });

  it('matches a game with at least one of the genres and at least the rating, like the server does', () => {
    const rpg = game({ genres: ['RPG', 'ACTION'], rating: { score: 8, label: 'Very Positive', percentPositive: 88 } });
    expect(matchesFilters(rpg, NO_FILTERS)).toBe(true);
    expect(matchesFilters(rpg, { genres: ['STRATEGY', 'ACTION'], minRating: 0, ages: [] })).toBe(true);
    expect(matchesFilters(rpg, { genres: ['STRATEGY'], minRating: 0, ages: [] })).toBe(false);
    expect(matchesFilters(rpg, { genres: [], minRating: 8, ages: [] })).toBe(true);
    expect(matchesFilters(rpg, { genres: [], minRating: 9, ages: [] })).toBe(false);
    expect(matchesFilters(rpg, { genres: ['RPG'], minRating: 9, ages: [] })).toBe(false);
  });

  it('matches a game whose ESRB age rating is one of the chosen ones, and never one without an age rating', () => {
    const mature = game({ ageRating: 'MATURE' });
    expect(matchesFilters(mature, { genres: [], minRating: 0, ages: ['TEEN', 'MATURE'] })).toBe(true);
    expect(matchesFilters(mature, { genres: [], minRating: 0, ages: ['TEEN'] })).toBe(false);
    expect(matchesFilters(game({}), { genres: [], minRating: 0, ages: ['TEEN'] })).toBe(false); // many games have no age rating
    expect(matchesFilters(game({}), NO_FILTERS)).toBe(true);
    expect(matchesFilters(game({ genres: ['RPG'], ageRating: 'MATURE' }), { genres: ['RPG'], minRating: 0, ages: ['TEEN'] })).toBe(false);
  });

  it('never matches a game without a rating or genres when that filter is on', () => {
    const bare = game({});
    expect(matchesFilters(bare, NO_FILTERS)).toBe(true);
    expect(matchesFilters(bare, { genres: ['RPG'], minRating: 0, ages: [] })).toBe(false);
    expect(matchesFilters(bare, { genres: [], minRating: 6, ages: [] })).toBe(false);
  });

  it('tolerates games from an older API answer that has no genres or rating field', () => {
    const legacy = { ...game({}), genres: undefined, rating: undefined } as unknown as Game;
    expect(matchesFilters(legacy, NO_FILTERS)).toBe(true);
    expect(matchesFilters(legacy, { genres: ['RPG'], minRating: 0, ages: [] })).toBe(false);
  });
});
