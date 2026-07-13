import { expect, test } from '../fixtures/contexts'

test('busted and sitting-out states expose the correct recovery controls', async ({ alice }) => {
  await alice.goto('/room/mock-room?mock=busted&seats=2')
  await expect(alice.getByTestId('actionbar-busted')).toBeVisible()
  const rebuy = alice.getByLabel('Rebuy amount')
  expect(Number(await rebuy.getAttribute('min'))).toBe(400)
  expect(Number(await rebuy.getAttribute('max'))).toBe(2000)

  await alice.goto('/room/mock-room?mock=sitout&seats=2')
  await expect(alice.getByTestId('actionbar-sitout')).toBeVisible()
  await expect(alice.getByRole('button', { name: "I'm back (sit in)" })).toBeVisible()
})
