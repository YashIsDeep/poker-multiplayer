const adjectives = [
  'Bold',
  'Clever',
  'Golden',
  'Hidden',
  'Lucky',
  'Midnight',
  'Royal',
  'Scarlet',
  'Silver',
  'Smoky',
  'Velvet',
  'Wild',
] as const

const pokerTerms = [
  'Aces',
  'Blinds',
  'Chips',
  'Dealers',
  'Flop',
  'Kings',
  'River',
  'Sharks',
  'Stacks',
  'Straight',
  'Turn',
  'Queens',
] as const

function pick<T>(items: readonly T[], random: () => number): T {
  return items[Math.floor(random() * items.length)]
}

export function generateRandomTableName(random: () => number = Math.random): string {
  return `${pick(adjectives, random)} ${pick(pokerTerms, random)}`
}
