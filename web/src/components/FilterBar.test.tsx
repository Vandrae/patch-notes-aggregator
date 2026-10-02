import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NO_FILTERS, type Filters } from '../filters';
import { FILTER_OPTIONS, jsonResponse, renderApp, stubApi } from '../test-utils';
import { FilterBar } from './FilterBar';

describe('FilterBar', () => {
  afterEach(() => vi.unstubAllGlobals());

  const setup = (filters: Filters = NO_FILTERS) => {
    stubApi({ 'GET /api/catalog/filters': () => jsonResponse(FILTER_OPTIONS) });
    const onChange = vi.fn();
    renderApp(<FilterBar filters={filters} onChange={onChange} />);
    return onChange;
  };

  it('offers every genre as a toggle and the ratings as "or better" choices', async () => {
    setup();

    const genres = await screen.findByRole('group', { name: /filter by genre/i });
    expect(genres).toHaveTextContent('Massively Multiplayer');
    expect(screen.getByRole('button', { name: 'RPG' })).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByRole('option', { name: 'Any rating' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'Very Positive or better' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'Overwhelmingly Positive' })).toBeInTheDocument(); // nothing is better than the top
  });

  it('adds and removes genres independently, keeping the rating', async () => {
    const onChange = setup({ genres: ['RPG'], minRating: 8, ages: [] });

    expect(await screen.findByRole('button', { name: 'RPG' })).toHaveAttribute('aria-pressed', 'true');
    await userEvent.click(screen.getByRole('button', { name: 'Action' }));
    expect(onChange).toHaveBeenLastCalledWith({ genres: ['RPG', 'ACTION'], minRating: 8, ages: [] });

    await userEvent.click(screen.getByRole('button', { name: 'RPG' }));
    expect(onChange).toHaveBeenLastCalledWith({ genres: [], minRating: 8, ages: [] });
  });

  it('offers the ESRB age ratings as toggles that combine with the other filters', async () => {
    const onChange = setup({ genres: ['RPG'], minRating: 8, ages: ['TEEN'] });

    const group = await screen.findByRole('group', { name: /filter by age rating/i });
    expect(group).toHaveTextContent('Mature 17+');
    expect(screen.getByRole('button', { name: 'Teen' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Everyone' })).toHaveAttribute('aria-pressed', 'false');

    await userEvent.click(screen.getByRole('button', { name: 'Mature 17+' }));
    expect(onChange).toHaveBeenLastCalledWith({ genres: ['RPG'], minRating: 8, ages: ['TEEN', 'MATURE'] });
    await userEvent.click(screen.getByRole('button', { name: 'Teen' }));
    expect(onChange).toHaveBeenLastCalledWith({ genres: ['RPG'], minRating: 8, ages: [] });
  });

  it('clears the age rating along with everything else', async () => {
    const onChange = setup({ genres: [], minRating: 0, ages: ['MATURE'] });

    await userEvent.click(await screen.findByRole('button', { name: /clear filters/i }));

    expect(onChange).toHaveBeenLastCalledWith(NO_FILTERS);
  });

  it('changes the rating, keeping the genres', async () => {
    const onChange = setup({ genres: ['ACTION'], minRating: 0, ages: [] });

    await userEvent.selectOptions(await screen.findByRole('combobox', { name: /rating/i }), 'Very Positive or better');

    expect(onChange).toHaveBeenLastCalledWith({ genres: ['ACTION'], minRating: 8, ages: [] });
  });

  it('offers "Clear filters" only while filtering, and clears both', async () => {
    const onChange = setup({ genres: ['ACTION'], minRating: 6, ages: [] });

    await userEvent.click(await screen.findByRole('button', { name: /clear filters/i }));

    expect(onChange).toHaveBeenLastCalledWith(NO_FILTERS);
  });

  it('has no clear button when nothing is filtered', async () => {
    setup();
    await screen.findByRole('group', { name: /filter by genre/i });
    expect(screen.queryByRole('button', { name: /clear filters/i })).toBeNull();
  });

  it('renders nothing when the option lists cannot be loaded, rather than a broken control', async () => {
    stubApi({ 'GET /api/catalog/filters': () => jsonResponse({}, 500) });
    const { container } = renderApp(<FilterBar filters={NO_FILTERS} onChange={vi.fn()} />);

    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(container.querySelector('.filter-bar')).toBeNull();
  });
});
