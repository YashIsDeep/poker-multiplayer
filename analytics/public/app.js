'use strict';

const $ = (selector) => document.querySelector(selector);
const fmt = new Intl.NumberFormat('en-US');
const text = (value) => String(value ?? '');
const number = (value) => Number.isFinite(Number(value)) ? Number(value) : 0;
const list = (value) => Array.isArray(value) ? value : [];
const chips = (value) => fmt.format(number(value));
const signed = (value) => (number(value) > 0 ? '+' : '') + chips(value);
const netClass = (value) => number(value) > 0 ? 'pos' : number(value) < 0 ? 'neg' : '';
const reason = (value) => text(value).replaceAll('_', ' ').toLowerCase();
const SERIES = ['--series-1', '--series-2', '--series-3', '--series-4', '--series-5', '--series-6'];
const state = { days: '', roomId: '', playerId: '', hands: [], stats: null };

// Archive values are always text nodes, DOM property values, or finite numbers.
// Never parse archive data as HTML, including cards, IDs and event descriptions.
function el(tag, className, ...children) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  for (const child of children.flat(Infinity)) {
    node.append(child instanceof Node ? child : document.createTextNode(text(child)));
  }
  return node;
}

function svgNode(tag, attributes, content) {
  const node = document.createElementNS('http://www.w3.org/2000/svg', tag);
  for (const [name, value] of Object.entries(attributes)) node.setAttribute(name, text(value));
  if (content !== undefined) node.textContent = text(content);
  return node;
}

function timeLabel(iso) {
  if (!iso) return '—';
  const date = new Date(iso);
  if (Number.isNaN(date.valueOf())) return 'Unknown date';
  return date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
    + ' ' + date.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
}

function cards(values) {
  const result = list(values).map((value) => {
    const card = text(value);
    const suit = card.slice(-1);
    const symbol = new Map([['s', '♠'], ['h', '♥'], ['d', '♦'], ['c', '♣']]).get(suit) ?? suit;
    return el('span', `pcard${suit === 'h' || suit === 'd' ? ' red' : ''}`, card.slice(0, -1) + symbol);
  });
  return result.length ? result : [el('span', 'pcard', '—')];
}

function table(headers, rows) {
  return el('table', '',
    el('thead', '', el('tr', '', headers.map(([label, numeric]) => el('th', numeric ? 'num' : '', label)))),
    el('tbody', '', rows));
}

function query() {
  const params = new URLSearchParams();
  if (state.roomId) params.set('roomId', state.roomId);
  if (state.playerId) params.set('playerId', state.playerId);
  if (state.days) params.set('from', new Date(Date.now() - Number(state.days) * 86400000).toISOString());
  return params.toString();
}

async function getJson(url) {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`Archive request failed (${response.status})`);
  return response.json();
}

async function refresh() {
  const qs = query();
  const [hands, stats] = await Promise.all([getJson('/api/hands?' + qs), getJson('/api/stats?' + qs)]);
  state.hands = list(hands);
  state.stats = stats;
  renderFilters();
  renderKpis();
  renderNetChart();
  renderDayChart();
  renderPlayerTable();
  renderHandsTable();
}

let filtersSeeded = false;
function renderFilters() {
  if (filtersSeeded) return;
  const rooms = new Map();
  const players = new Map();
  for (const hand of state.hands) {
    rooms.set(text(hand.roomId), text(hand.roomName ?? text(hand.roomId).slice(0, 8)));
    for (const player of list(hand.players)) {
      players.set(text(player.userId), text(player.displayName ?? text(player.userId).slice(0, 8)));
    }
  }
  for (const [id, name] of rooms) $('#f-room').append(new Option(name, id));
  for (const [id, name] of [...players].sort((a, b) => a[1].localeCompare(b[1]))) {
    $('#f-player').append(new Option(name, id));
  }
  filtersSeeded = state.hands.length > 0;
}

function showError(error) { $('#archive-note').textContent = 'failed to load archive: ' + error.message; }
$('#f-room').addEventListener('change', (event) => { state.roomId = event.target.value; refresh().catch(showError); });
$('#f-player').addEventListener('change', (event) => { state.playerId = event.target.value; refresh().catch(showError); });
$('#f-range').addEventListener('click', (event) => {
  const button = event.target.closest('button');
  if (!button) return;
  state.days = button.dataset.days;
  for (const candidate of $('#f-range').querySelectorAll('button')) candidate.classList.toggle('on', candidate === button);
  refresh().catch(showError);
});

function renderKpis() {
  const overview = state.stats?.overview ?? {};
  const biggest = overview.biggestPot;
  const tile = (label, value, hint) => el('div', 'tile', el('div', 'label', label),
    el('div', 'value', chips(value)), el('div', 'hint', hint));
  $('#kpis').replaceChildren(
    tile('Hands', overview.hands, `${text(overview.rooms ?? 0)} room${overview.rooms === 1 ? '' : 's'}`),
    tile('Players', overview.players, 'seen in these hands'),
    tile('Chips through pots', overview.totalPot, 'total awarded'),
    tile('Biggest pot', biggest?.potAwarded, biggest ? `${text(biggest.roomName)} · hand #${text(biggest.handId)}` : '—'));
  $('#archive-note').textContent = overview.lastAt
    ? `${chips(overview.hands)} hands archived · latest ${timeLabel(overview.lastAt)}`
    : 'no hands archived yet — finish a hand in the game and it appears here';
}

function chartFrame(width, height, pad) {
  return { width, height, x0: pad.l, x1: width - pad.r, y0: height - pad.b, y1: pad.t };
}

function ticks(min, max, count) {
  if (min === max) { min -= 1; max += 1; }
  const span = max - min;
  const step = Math.pow(10, Math.floor(Math.log10(span / count)));
  const error = span / count / step;
  const increment = step * (error >= 7.5 ? 10 : error >= 3.5 ? 5 : error >= 1.5 ? 2 : 1);
  const result = [];
  // Bound work even when malformed numeric archive values overflow a series.
  if (!Number.isFinite(increment) || increment <= 0) return result;
  for (let value = Math.ceil(min / increment) * increment; value <= max + increment / 1000 && result.length < 100; value += increment) {
    result.push(value);
  }
  return result;
}

function chartSvg(frame, label) {
  return svgNode('svg', { viewBox: `0 0 ${frame.width} ${frame.height}`, role: 'img', 'aria-label': label });
}
function chartText(svg, x, y, content, attrs = {}) {
  svg.append(svgNode('text', { x, y, 'font-size': 11, fill: 'var(--text-muted)', ...attrs }, content));
}
function chartLine(svg, x1, x2, y1, y2, attrs = {}) {
  const line = svgNode('line', { x1, x2, y1, y2, stroke: 'var(--grid)', 'stroke-width': 1, ...attrs });
  svg.append(line);
  return line;
}
function swatch(color) {
  const node = el('i', '');
  node.style.background = color;
  return node;
}
const tipRow = (label, value) => el('div', 't-row', el('span', '', label), el('span', 'v', value));

function renderNetChart() {
  const box = $('#net-chart');
  const legend = $('#net-legend');
  const ascending = [...state.hands].reverse();
  const counts = new Map();
  for (const hand of ascending) {
    for (const player of list(hand.players)) counts.set(player.userId, (counts.get(player.userId) ?? 0) + 1);
  }
  const top = new Set([...counts].sort((a, b) => b[1] - a[1]).slice(0, 6).map(([id]) => id));
  const series = new Map();
  ascending.forEach((hand, index) => {
    for (const player of list(hand.players)) {
      if (!top.has(player.userId)) continue;
      const item = series.get(player.userId) ?? { name: player.displayName ?? text(player.userId).slice(0, 8), points: [] };
      const last = item.points.at(-1)?.v ?? 0;
      item.points.push({ i: index + 1, v: number(last + number(player.net)) });
      series.set(player.userId, item);
    }
  });
  const items = [...series.values()];
  legend.replaceChildren();
  if (!items.length || ascending.length < 2) {
    box.replaceChildren(el('div', 'empty', 'Not enough hands yet'));
    return;
  }

  const frame = chartFrame(640, 310, { l: 54, r: 90, t: 12, b: 40 });
  const maxI = ascending.length;
  let lo = 0, hi = 0;
  for (const item of items) for (const point of item.points) { lo = Math.min(lo, point.v); hi = Math.max(hi, point.v); }
  if (lo === hi) { lo -= 10; hi += 10; }
  const X = (index) => frame.x0 + ((index - 1) / Math.max(1, maxI - 1)) * (frame.x1 - frame.x0);
  const Y = (value) => frame.y0 - ((value - lo) / (hi - lo)) * (frame.y0 - frame.y1);
  const svg = chartSvg(frame, 'Cumulative net chips per player');
  for (const tick of ticks(lo, hi, 5)) {
    chartLine(svg, frame.x0, frame.x1, Y(tick), Y(tick));
    chartText(svg, frame.x0 - 8, Y(tick) + 4, fmt.format(tick), { 'text-anchor': 'end' });
  }
  if (lo < 0 && hi > 0) chartLine(svg, frame.x0, frame.x1, Y(0), Y(0), { stroke: 'var(--baseline)', 'stroke-width': 1.5 });
  for (const tick of ticks(1, maxI, Math.min(6, maxI)).filter((value) => Number.isInteger(value) && value >= 1 && value <= maxI)) {
    chartText(svg, X(tick), frame.y0 + 18, tick, { 'text-anchor': 'middle' });
  }
  chartText(svg, (frame.x0 + frame.x1) / 2, frame.height - 2, 'hand number', { 'text-anchor': 'middle', 'font-size': 10 });

  const labels = [];
  items.forEach((item, index) => {
    const color = `var(${SERIES[index]})`;
    const d = item.points.map((point, offset) => `${offset ? 'L' : 'M'}${X(point.i).toFixed(1)},${Y(point.v).toFixed(1)}`).join('');
    svg.append(svgNode('path', { d, fill: 'none', stroke: color, 'stroke-width': 2, 'stroke-linejoin': 'round' }));
    const end = item.points.at(-1);
    labels.push({ name: item.name, x: X(end.i) + 6, y: Y(end.v) + 4 });
    legend.append(el('span', '', swatch(color), item.name));
  });
  labels.sort((a, b) => a.y - b.y);
  for (let index = 1; index < labels.length; index++) {
    if (labels[index].y - labels[index - 1].y < 15) labels[index].y = labels[index - 1].y + 15;
  }
  for (const label of labels) chartText(svg, label.x, Math.min(label.y, frame.y0), label.name, { 'font-weight': 600, fill: 'var(--text-secondary)' });
  svg.append(svgNode('rect', { x: frame.x0, y: frame.y1, width: frame.x1 - frame.x0, height: frame.y0 - frame.y1, fill: 'transparent' }));
  const cross = chartLine(svg, 0, 0, frame.y1, frame.y0, { stroke: 'var(--baseline)' });
  cross.style.display = 'none';
  const tip = el('div', 'tooltip');
  tip.id = 'net-tip';
  box.replaceChildren(svg, tip);

  svg.addEventListener('mousemove', (event) => {
    const rect = svg.getBoundingClientRect();
    const px = ((event.clientX - rect.left) / rect.width) * frame.width;
    if (px < frame.x0 || px > frame.x1) { cross.style.display = 'none'; tip.style.display = 'none'; return; }
    const index = Math.max(1, Math.min(maxI, Math.round(((px - frame.x0) / (frame.x1 - frame.x0)) * (maxI - 1) + 1)));
    cross.setAttribute('x1', X(index)); cross.setAttribute('x2', X(index));
    cross.style.display = '';
    tip.replaceChildren(el('div', 't-head', `after hand ${index}`));
    items.forEach((item, offset) => {
      let value = null;
      for (const point of item.points) { if (point.i <= index) value = point.v; else break; }
      if (value !== null) tip.append(tipRow([swatch(`var(${SERIES[offset]})`), item.name], signed(value)));
    });
    tip.style.display = 'block';
    tip.style.left = Math.min(((X(index) / frame.width) * rect.width) + 14, box.getBoundingClientRect().width - tip.offsetWidth - 8) + 'px';
    tip.style.top = '14px';
  });
  svg.addEventListener('mouseleave', () => { cross.style.display = 'none'; tip.style.display = 'none'; });
}

function renderDayChart() {
  const box = $('#day-chart');
  const days = list(state.stats?.handsPerDay);
  if (!days.length) { box.replaceChildren(el('div', 'empty', 'No data')); return; }
  const frame = chartFrame(420, 300, { l: 40, r: 10, t: 12, b: 40 });
  const max = days.reduce((value, day) => Math.max(value, number(day.hands)), 0);
  const band = (frame.x1 - frame.x0) / days.length;
  const barW = Math.max(4, Math.min(40, band - 2));
  const Y = (value) => frame.y0 - (number(value) / (max || 1)) * (frame.y0 - frame.y1);
  const svg = chartSvg(frame, 'Hands per day');
  for (const tick of ticks(0, max, 4).filter((value) => value >= 0 && Number.isInteger(value))) {
    chartLine(svg, frame.x0, frame.x1, Y(tick), Y(tick));
    chartText(svg, frame.x0 - 6, Y(tick) + 4, tick, { 'text-anchor': 'end' });
  }
  days.forEach((day, index) => {
    const cx = frame.x0 + band * index + band / 2;
    const height = frame.y0 - Y(day.hands);
    const radius = Math.min(4, barW / 2, height);
    const d = `M${(cx - barW / 2).toFixed(1)},${frame.y0} v${-(height - radius).toFixed(1)} q0,${-radius} ${radius},${-radius} h${(barW - 2 * radius).toFixed(1)} q${radius},0 ${radius},${radius} v${(height - radius).toFixed(1)} z`;
    svg.append(svgNode('path', { 'data-i': index, d, fill: 'var(--seq)' }));
    const date = new Date(text(day.date) + 'T00:00:00');
    const label = Number.isNaN(date.valueOf()) ? 'Unknown date' : date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
    if (days.length <= 10 || index % Math.ceil(days.length / 8) === 0) chartText(svg, cx, frame.y0 + 16, label, { 'text-anchor': 'middle', 'font-size': 10.5 });
  });
  chartLine(svg, frame.x0, frame.x1, frame.y0, frame.y0, { stroke: 'var(--baseline)', 'stroke-width': 1.5 });
  const tip = el('div', 'tooltip');
  tip.id = 'day-tip';
  box.replaceChildren(svg, tip);
  svg.addEventListener('mousemove', (event) => {
    const bar = event.target.closest('path[data-i]');
    if (!bar) { tip.style.display = 'none'; return; }
    const day = days[Number(bar.dataset.i)];
    tip.replaceChildren(el('div', 't-head', day.date), tipRow('hands', chips(day.hands)), tipRow('pot volume', chips(day.pot)));
    tip.style.display = 'block';
    const bounds = box.getBoundingClientRect();
    tip.style.left = Math.min(event.clientX - bounds.left + 12, bounds.width - tip.offsetWidth - 6) + 'px';
    tip.style.top = (event.clientY - bounds.top - 40) + 'px';
  });
  svg.addEventListener('mouseleave', () => { tip.style.display = 'none'; });
}

function renderPlayerTable() {
  const rows = list(state.stats?.players);
  $('#player-table').replaceChildren(rows.length ? table([
    ['Player'], ['Hands', true], ['VPIP', true], ['PFR', true], ['Showdowns', true],
    ['SD wins', true], ['Chips won', true], ['Biggest pot', true], ['Net', true],
  ], rows.map((player) => el('tr', '', el('td', '', player.name), el('td', 'num', chips(player.hands)),
    el('td', 'num', `${text(player.vpipPct)}%`), el('td', 'num', `${text(player.pfrPct)}%`),
    el('td', 'num', chips(player.showdowns)), el('td', 'num', chips(player.showdownWins)),
    el('td', 'num', chips(player.won)), el('td', 'num', chips(player.biggestPotWon)),
    el('td', `num ${netClass(player.net)}`, signed(player.net))))) : el('div', 'empty', 'No players yet'));
}

function renderHandsTable() {
  const hands = state.hands.slice(0, 200);
  const box = $('#hands-table');
  if (!hands.length) { box.replaceChildren(el('div', 'empty', 'No hands recorded yet')); return; }
  box.replaceChildren(table([
    ['When'], ['Room'], ['Hand', true], ['Board'], ['Winners'], ['Pot', true], ['Ended by'],
  ], hands.map((hand) => {
    const winners = list(hand.players).filter((player) => number(player.won) > 0).map((player) => text(player.displayName ?? '?')).join(', ');
    const row = el('tr', 'click', el('td', '', timeLabel(hand.completedAt)), el('td', '', hand.roomName),
      el('td', 'num', `#${text(hand.handId)}`), el('td', '', cards(hand.board)), el('td', '', winners || '—'),
      el('td', 'num', chips(hand.potAwarded)), el('td', '', reason(hand.completionReason)));
    row.dataset.room = text(hand.roomId);
    row.dataset.hand = text(hand.handId);
    return row;
  })));
  if (state.hands.length > 200) box.append(el('div', 'empty', `Showing newest 200 of ${state.hands.length}`));
}

$('#hands-table').addEventListener('click', (event) => {
  const row = event.target.closest('tr.click');
  if (row) openDetail(row.dataset.room, row.dataset.hand).catch(showError);
});

function describeEvent(event, seatNames) {
  const who = (seat) => text(seatNames.get(seat) ?? `seat ${text(seat)}`);
  switch (event.type) {
    case 'BLIND_POSTED':
      return [`${who(event.seat)} posts ${event.action === 'SMALL_BLIND' ? 'small' : 'big'} blind ${chips(event.amount)}${event.allIn ? ' (all-in)' : ''}`];
    case 'HOLE_CARD_DEALT': return [`${who(event.seat)} dealt `, cards(event.cards)];
    case 'PLAYER_ACTED': {
      const verbs = new Map([['FOLD', 'folds'], ['CHECK', 'checks'], ['CALL', `calls ${chips(event.amount)}`], ['RAISE_TO', `raises to ${chips(event.raiseTo)}`]]);
      return [`${who(event.seat)} ${text(verbs.get(event.action) ?? event.action)} `, el('span', 'ph', `stack ${chips(event.stackAfter)}`)];
    }
    case 'UNCALLED_BET_REFUNDED': return [`${who(event.seat)} gets back uncalled ${chips(event.amount)}`];
    case 'STREET_DEALT': return ['board: ', cards(event.board)];
    case 'POT_AWARDED': return [`pot of ${chips(event.potAmount)} → ${list(event.winners).map((winner) => `${who(winner.seat)} +${chips(winner.amount)}`).join(', ')}`];
    case 'HAND_COMPLETED': return [`hand complete (${reason(event.reason)})`];
    default: return [text(event.type)];
  }
}

async function openDetail(roomId, handId) {
  const hand = await getJson('/api/hand?' + new URLSearchParams({ roomId, handId }));
  const players = list(hand.players);
  const seatNames = new Map(players.map((player) => [player.seat, player.displayName ?? `seat ${text(player.seat)}`]));
  $('#d-title').textContent = `${text(hand.roomName ?? 'Room')} · hand #${text(hand.handId)}`;
  $('#d-body').replaceChildren(
    el('div', 'd-meta', `${timeLabel(hand.completedAt)} · blinds ${chips(hand.smallBlind)}/${chips(hand.bigBlind)} · pot ${chips(hand.potAwarded)} · ${reason(hand.completionReason)}`),
    table([['Seat'], ['Player'], ['Cards'], ['Start', true], ['End', true], ['Net', true]],
      players.map((player) => el('tr', '', el('td', '', player.seat),
        el('td', '', player.displayName ?? '?', player.folded ? el('span', 'ph', ' (folded)') : ''),
        el('td', '', cards(player.holeCards)), el('td', 'num', chips(player.startingStack)),
        el('td', 'num', chips(player.endingStack)), el('td', `num ${netClass(player.net)}`, signed(player.net))))),
    el('div', 'd-board', 'Final board: ', cards(hand.board)),
    el('ol', 'events', list(hand.events).map((event) => el('li', '', el('span', 'ph', event.phase), describeEvent(event, seatNames)))));
  $('#detail').showModal();
}

$('#d-close').addEventListener('click', () => $('#detail').close());
$('#detail').addEventListener('click', (event) => { if (event.target === $('#detail')) $('#detail').close(); });
refresh().catch(showError);
setInterval(() => refresh().catch(showError), 15000);
