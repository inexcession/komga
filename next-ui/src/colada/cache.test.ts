import { afterEach, describe, expect, test, vi } from 'vitest'
import { useQueryCache, type UseQueryEntry } from '@pinia/colada'
import { enableAutoUnmount } from '@vue/test-utils'
import { createMockColada } from '@/mocks/pinia-colada'
import { clearAllCollections, clearCollection, clearSeries } from '@/colada/cache'
import { QUERY_KEYS_COLLECTIONS } from '@/colada/collections'
import { QUERY_KEYS_SERIES } from '@/colada/series'

enableAutoUnmount(afterEach)

function makeEntry(data: unknown): UseQueryEntry<unknown, unknown, unknown> {
  return {
    state: ref({ data }),
  } as unknown as UseQueryEntry<unknown, unknown, unknown>
}

describe('colada cache', () => {
  test('clearAllCollections invalidates collections and series queries', () => {
    let queryCache!: ReturnType<typeof useQueryCache>
    createMockColada(() => {
      queryCache = useQueryCache()
    })

    const spy = vi.spyOn(queryCache, 'invalidateQueries')

    clearAllCollections()

    expect(spy).toHaveBeenCalledWith({ key: QUERY_KEYS_COLLECTIONS.root })
    expect(spy).toHaveBeenCalledWith({ key: QUERY_KEYS_SERIES.root })
  })

  test('clearCollection invalidates collection, bySeries, and series queries', () => {
    let queryCache!: ReturnType<typeof useQueryCache>
    createMockColada(() => {
      queryCache = useQueryCache()
    })

    const spy = vi.spyOn(queryCache, 'invalidateQueries')

    clearCollection('col-1')

    expect(spy).toHaveBeenCalledWith(expect.objectContaining({ key: QUERY_KEYS_COLLECTIONS.root }))
    expect(spy).toHaveBeenCalledWith({ key: QUERY_KEYS_COLLECTIONS.bySeries() })
    expect(spy).toHaveBeenCalledWith({ key: QUERY_KEYS_SERIES.root })
  })

  test('clearSeries invalidates series and grouped collection pages', () => {
    let queryCache!: ReturnType<typeof useQueryCache>
    createMockColada(() => {
      queryCache = useQueryCache()
    })

    const spy = vi.spyOn(queryCache, 'invalidateQueries')

    clearSeries('series-2', false)

    expect(spy).toHaveBeenCalledTimes(1)
    const callArg = spy.mock.calls[0]?.[0]
    expect(callArg?.key).toEqual(QUERY_KEYS_SERIES.root)
    const predicate = callArg?.predicate
    expect(predicate).toBeTypeOf('function')

    // matches standalone series by id
    expect(predicate!(makeEntry({ content: [{ id: 'series-2' }] }))).toBe(true)

    // matches inline collection containing seriesId in standard page
    expect(
      predicate!(
        makeEntry({
          content: [
            { id: 'series-1' },
            { id: 'col-1', seriesIds: ['series-2', 'series-3'] },
          ],
        }),
      ),
    ).toBe(true)

    // matches inline collection containing seriesId in infinite page
    expect(
      predicate!(
        makeEntry({
          pages: [
            { content: [{ id: 'series-1' }] },
            { content: [{ id: 'col-1', seriesIds: ['series-2'] }] },
          ],
        }),
      ),
    ).toBe(true)

    // does not match unrelated page
    expect(
      predicate!(
        makeEntry({
          content: [
            { id: 'series-1' },
            { id: 'col-1', seriesIds: ['series-3'] },
          ],
        }),
      ),
    ).toBe(false)
  })
})
