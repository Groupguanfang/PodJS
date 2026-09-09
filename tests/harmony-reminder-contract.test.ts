import { expect, test } from 'bun:test';
import { reminderDelay, ReminderNotification } from '../platforms/harmony/entry/src/main/ets/ReminderContract';
const token = '0123456789abcdef0123456789abcdef';
function notification() { const n = new ReminderNotification(); n.id = '提醒'; n.title = '中文'; n.body = '正文'; return n; }
test('system countdown rounds upward and treats immediate/past requests as one second', () => {
  const n = notification(); expect(reminderDelay(n, 1, token, 10000)).toBe(1);
  n.at = 11001; expect(reminderDelay(n, 1, token, 10000)).toBe(2);
  n.at = 0; expect(reminderDelay(n, 1, token, 10000)).toBe(1);
});
test('invalid journal identities, timestamps and text are rejected', () => {
  for (const id of [0, -1, 1.5, 2147483648, NaN]) expect(() => reminderDelay(notification(), id, token, 10000)).toThrow();
  expect(() => reminderDelay(notification(), 1, 'guest-id', 10000)).toThrow();
  for (const at of [-1, 1.5, NaN, Infinity]) {
    const n = notification(); n.at = at; expect(() => reminderDelay(n, 1, token, 10000)).toThrow();
  }
  const n = notification(); n.body = 'cut\0off'; expect(() => reminderDelay(n, 1, token, 10000)).toThrow();
  const category = notification(); category.category = 'bad\0category';
  expect(() => reminderDelay(category, 1, token, 10000)).toThrow();
});
test('arbitrary action buttons are explicitly unsupported, never silently dropped', () => {
  const n = notification(); n.actions = [{ id: 'open', title: '打开' }];
  expect(() => reminderDelay(n, 1, token, 10000)).toThrow('unsupported');
  n.actions = []; expect(reminderDelay(n, 1, token, 10000)).toBe(1);
});
