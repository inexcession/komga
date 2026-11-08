import { describe, expect, test } from 'vitest'
import { unwrapGroupedPage } from '@/colada/series'
import { mockSeries1 } from '@/mocks/api/handlers/series'
import { mockCollection1 } from '@/mocks/api/handlers/collections'

describe('unwrapGroupedPage', () => {
  test('unwraps series and collections in order while preserving page metadata', () => {
    const unwrapped = unwrapGroupedPage({
      content: [
        { series: mockSeries1 },
        { collection: mockCollection1 },
        {},
      ],
      totalElements: 2,
      totalPages: 1,
      first: true,
      last: true,
    })

    expect(unwrapped.totalElements).toBe(2)
    expect(unwrapped.totalPages).toBe(1)
    expect(unwrapped.content).toEqual([mockSeries1, mockCollection1])
  })
})
