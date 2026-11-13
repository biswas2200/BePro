import * as fmt from '../src/utils/format';

test('minutes', () => {
  expect(fmt.minutes(45)).toBe('45m');
  expect(fmt.minutes(60)).toBe('1h');
  expect(fmt.minutes(135)).toBe('2h 15m');
  expect(fmt.minutes(undefined)).toBe('—');
});

test('date helpers stay on local calendar days', () => {
  expect(fmt.addDays('2026-10-01', -1)).toBe('2026-09-30');
  expect(fmt.addDays('2026-12-31', 1)).toBe('2027-01-01');
  expect(fmt.dayLabel('2026-10-05')).toBe('Mon 5 Oct');
  expect(fmt.shortDay('2026-10-04')).toBe('Su');
});

test('hours and counts', () => {
  expect(fmt.hours(7.46)).toBe('7.5 h');
  expect(fmt.hours(null)).toBe('—');
  expect(fmt.count(undefined)).toBe('—');
});
