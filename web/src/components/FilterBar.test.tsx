import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
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

  /** The controls live in a panel that starts closed: this is what a person does to reach them. */
  const open = async () => {
    await userEvent.click(await screen.findByRole('button', { name: /^filters/i }));
  };

  describe('the Filters button', () => {
    it('starts closed, so the chips and the menu are not on screen until it is opened', async () => {
      setup();

      const button = await screen.findByRole('button', { name: /^filters/i });
      expect(button).toHaveAttribute('aria-expanded', 'false');
      expect(screen.queryByRole('group', { name: /filter by genre/i })).toBeNull();
      expect(screen.queryByRole('combobox', { name: /rating/i })).toBeNull();
    });

    it('opens and closes the panel, telling assistive technology which', async () => {
      setup();

      await open();
      expect(screen.getByRole('button', { name: /^filters/i })).toHaveAttribute('aria-expanded', 'true');
      expect(screen.getByRole('group', { name: /filter by genre/i })).toBeInTheDocument();

      await userEvent.click(screen.getByRole('button', { name: /^filters/i }));
      expect(screen.getByRole('button', { name: /^filters/i })).toHaveAttribute('aria-expanded', 'false');
      expect(screen.queryByRole('group', { name: /filter by genre/i })).toBeNull();
    });

    it('closes on Escape and puts the focus back on the button', async () => {
      setup();
      await open();
      await userEvent.click(screen.getByRole('button', { name: 'RPG' })); // focus is now inside the panel

      await userEvent.keyboard('{Escape}');

      const button = screen.getByRole('button', { name: /^filters/i });
      expect(button).toHaveAttribute('aria-expanded', 'false');
      expect(button).toHaveFocus();
    });

    it('shows no count and no chips when nothing is filtered', async () => {
      setup();

      const button = await screen.findByRole('button', { name: /^filters/i });
      expect(button).toHaveAccessibleName('Filters');
      expect(screen.queryByRole('button', { name: /remove filter/i })).toBeNull();
    });

    it('counts what is switched on, including the rating', async () => {
      setup({ genres: ['RPG', 'ACTION'], minRating: 8, ages: ['TEEN'] });

      expect(await screen.findByRole('button', { name: /^filters/i })).toHaveAccessibleName('Filters, 4 active');
    });

    it('lists each active filter as a chip while closed, and removing one leaves the others', async () => {
      const onChange = setup({ genres: ['RPG'], minRating: 8, ages: ['TEEN'] });

      expect(await screen.findByRole('button', { name: 'Remove filter RPG' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Remove filter Teen' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Remove filter Very Positive or better' })).toBeInTheDocument();

      await userEvent.click(screen.getByRole('button', { name: 'Remove filter Teen' }));
      expect(onChange).toHaveBeenLastCalledWith({ genres: ['RPG'], minRating: 8, ages: [] });
      await userEvent.click(screen.getByRole('button', { name: 'Remove filter Very Positive or better' }));
      expect(onChange).toHaveBeenLastCalledWith({ genres: ['RPG'], minRating: 0, ages: ['TEEN'] });
    });

    it('swaps the chips for the panel when opened, so nothing is shown twice', async () => {
      setup({ genres: ['RPG'], minRating: 0, ages: [] });
      await screen.findByRole('button', { name: 'Remove filter RPG' });

      await open();

      expect(screen.queryByRole('button', { name: /remove filter/i })).toBeNull();
      expect(screen.getByRole('button', { name: 'RPG' })).toHaveAttribute('aria-pressed', 'true');
    });

    it('keeps the panel open while choices are made (the parent re-renders with each)', async () => {
      stubApi({ 'GET /api/catalog/filters': () => jsonResponse(FILTER_OPTIONS) });
      function Harness() {
        const [filters, setFilters] = useState<Filters>(NO_FILTERS);
        return <FilterBar filters={filters} onChange={setFilters} />;
      }
      renderApp(<Harness />);
      await open();

      await userEvent.click(screen.getByRole('button', { name: 'RPG' }));
      await userEvent.click(screen.getByRole('button', { name: 'Teen' }));

      expect(screen.getByRole('button', { name: 'RPG' })).toHaveAttribute('aria-pressed', 'true');
      expect(screen.getByRole('button', { name: /^filters/i })).toHaveAccessibleName('Filters, 2 active');
    });
  });

  describe('inside the panel', () => {
    it('offers every genre as a toggle and the ratings as "or better" choices', async () => {
      setup();
      await open();

      const genres = await screen.findByRole('group', { name: /filter by genre/i });
      expect(genres).toHaveTextContent('Massively Multiplayer');
      expect(screen.getByRole('button', { name: 'RPG' })).toHaveAttribute('aria-pressed', 'false');
      expect(screen.getByRole('option', { name: 'Any rating' })).toBeInTheDocument();
      expect(screen.getByRole('option', { name: 'Very Positive or better' })).toBeInTheDocument();
      expect(screen.getByRole('option', { name: 'Overwhelmingly Positive' })).toBeInTheDocument(); // nothing is better than the top
    });

    it('adds and removes genres independently, keeping the rating', async () => {
      const onChange = setup({ genres: ['RPG'], minRating: 8, ages: [] });
      await open();

      expect(await screen.findByRole('button', { name: 'RPG' })).toHaveAttribute('aria-pressed', 'true');
      await userEvent.click(screen.getByRole('button', { name: 'Action' }));
      expect(onChange).toHaveBeenLastCalledWith({ genres: ['RPG', 'ACTION'], minRating: 8, ages: [] });

      await userEvent.click(screen.getByRole('button', { name: 'RPG' }));
      expect(onChange).toHaveBeenLastCalledWith({ genres: [], minRating: 8, ages: [] });
    });

    it('offers the ESRB age ratings as toggles that combine with the other filters', async () => {
      const onChange = setup({ genres: ['RPG'], minRating: 8, ages: ['TEEN'] });
      await open();

      const group = await screen.findByRole('group', { name: /filter by age rating/i });
      expect(group).toHaveTextContent('Mature 17+');
      expect(screen.getByRole('button', { name: 'Teen' })).toHaveAttribute('aria-pressed', 'true');
      expect(screen.getByRole('button', { name: 'Everyone' })).toHaveAttribute('aria-pressed', 'false');

      await userEvent.click(screen.getByRole('button', { name: 'Mature 17+' }));
      expect(onChange).toHaveBeenLastCalledWith({ genres: ['RPG'], minRating: 8, ages: ['TEEN', 'MATURE'] });
      await userEvent.click(screen.getByRole('button', { name: 'Teen' }));
      expect(onChange).toHaveBeenLastCalledWith({ genres: ['RPG'], minRating: 8, ages: [] });
    });

    it('changes the rating, keeping the genres', async () => {
      const onChange = setup({ genres: ['ACTION'], minRating: 0, ages: [] });
      await open();

      await userEvent.selectOptions(await screen.findByRole('combobox', { name: /rating/i }), 'Very Positive or better');

      expect(onChange).toHaveBeenLastCalledWith({ genres: ['ACTION'], minRating: 8, ages: [] });
    });

    it('clears the age rating along with everything else', async () => {
      const onChange = setup({ genres: [], minRating: 0, ages: ['MATURE'] });
      await open();

      await userEvent.click(await screen.findByRole('button', { name: /clear filters/i }));

      expect(onChange).toHaveBeenLastCalledWith(NO_FILTERS);
    });

    it('offers "Clear filters" only while filtering, and clears both', async () => {
      const onChange = setup({ genres: ['ACTION'], minRating: 6, ages: [] });
      await open();

      await userEvent.click(await screen.findByRole('button', { name: /clear filters/i }));

      expect(onChange).toHaveBeenLastCalledWith(NO_FILTERS);
    });

    it('has no clear button when nothing is filtered', async () => {
      setup();
      await open();
      await screen.findByRole('group', { name: /filter by genre/i });
      expect(screen.queryByRole('button', { name: /clear filters/i })).toBeNull();
    });
  });

  it('renders nothing when the option lists cannot be loaded, rather than a broken control', async () => {
    stubApi({ 'GET /api/catalog/filters': () => jsonResponse({}, 500) });
    const { container } = renderApp(<FilterBar filters={NO_FILTERS} onChange={vi.fn()} />);

    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(container.querySelector('.filter-bar')).toBeNull();
  });
});
