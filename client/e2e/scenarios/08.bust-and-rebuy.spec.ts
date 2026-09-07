import { expect, test } from '../fixtures/contexts'

test('busted and sitting-out states expose the correct recovery controls', async ({ alice }) => {
  await alice.goto('/room/mock-room?mock=busted&seats=2')
  await expect(alice.getByTestId('actionbar-busted')).toBeVisible()
  await expect(alice.getByText(/Priority rebuy window: [1-5]s/)).toBeVisible()
  await expect(alice.getByText('Rebuy stack')).toBeVisible()
  await expect(alice.getByRole('button', { name: 'Priority rebuy 400' })).toBeEnabled()
  await expect(alice.getByLabel('Small blind')).toBeVisible()
  // The busted player is unseated, so their old blind marker is hidden.
  await expect(alice.getByRole('button', { name: 'Take seat 1' })).toBeDisabled()
  await expect(alice.getByLabel('Big blind')).toBeHidden()

  await alice.goto('/room/mock-room?mock=sitout&seats=2')
  await expect(alice.getByTestId('actionbar-sitout')).toBeVisible()
  await expect(alice.getByRole('button', { name: "I'm back (sit in)" })).toBeVisible()
})
