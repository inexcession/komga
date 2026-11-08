import { afterEach, describe, expect, test, vi } from 'vitest'
import { useQueryCache } from '@pinia/colada'
import { enableAutoUnmount } from '@vue/test-utils'
import { createMockColada } from '@/mocks/pinia-colada'
import { clearAllCollections, clearCollection } from '@/colada/cache'
import { QUERY_KEYS_COLLECTIONS } from '@/colada/collections'
import { QUERY_KEYS_SERIES } from '@/colada/series'

enableAutoUnmount(afterEach)

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
})
