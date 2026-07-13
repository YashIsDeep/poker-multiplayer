import { expect, test } from '../fixtures/contexts'

test('busted and sitting-out states expose the correct recovery controls', async ({ alice }) => {
  await alice.goto('/room/mock-room?mock=busted&seats=2')
  await expect(alice.getByTestId('actionbar-busted')).toBeVisible()
  await expect(alice.getByText('Rebuy unlocks after 5 more completed rounds.')).toBeVisible()
  await expect(alice.getByText('Original stack')).toBeVisible()
  await expect(alice.getByRole('button', { name: 'Rebuy locked · 5R 400' })).toBeDisabled()
  await expect(alice.getByLabel('Small blind')).toBeVisible()
  await expect(alice.getByLabel('Big blind')).toBeVisible()

  await alice.goto('/room/mock-room?mock=sitout&seats=2')
  await expect(alice.getByTestId('actionbar-sitout')).toBeVisible()
  await expect(alice.getByRole('button', { name: "I'm back (sit in)" })).toBeVisible()
})
