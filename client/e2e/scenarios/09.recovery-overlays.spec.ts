import { expect, test } from '../fixtures/contexts'

test('socket loss state locks controls and a fresh sync restores the room', async ({ alice }) => {
  await alice.goto('/room/mock-room?mock=turn&seats=2&resilience=reconnecting')
  await expect(alice.getByTestId('banner-reconnecting')).toBeVisible()
  await expect(alice.getByTestId('actionbar-reconnecting')).toBeVisible()
  await alice.goto('/room/mock-room?mock=turn&seats=2')
  await expect(alice.getByTestId('banner-reconnecting')).toBeHidden()
  await expect(alice.getByTestId('room')).toBeVisible()
})

test('room-gone development state shows the blocking modal and toast', async ({ alice }) => {
  await alice.goto('/room/mock-room?mock=turn&resilience=gone')
  await expect(alice.getByTestId('modal-room-gone')).toBeVisible()
  await expect(alice.getByTestId('toasts')).toContainText('ROOM_NOT_FOUND')
})
