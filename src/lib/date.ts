import type { DayOfWeek, WorkoutSession } from './types';

export const dayOrder: DayOfWeek[] = [
  'sun',
  'mon',
  'tue',
  'wed',
  'thu',
  'fri',
  'sat'
];

export const dayLabels: Record<DayOfWeek, string> = {
  mon: '월',
  tue: '화',
  wed: '수',
  thu: '목',
  fri: '금',
  sat: '토',
  sun: '일'
};

export function toDateInput(date: Date): string {
  const year = date.getFullYear();
  const month = `${date.getMonth() + 1}`.padStart(2, '0');
  const day = `${date.getDate()}`.padStart(2, '0');
  return `${year}-${month}-${day}`;
}

export function parseDateInput(value: string): Date {
  const [year, month, day] = value.split('-').map(Number);
  return new Date(year, month - 1, day);
}

export function formatKoreanDate(dateLike: Date | string): string {
  const date =
    typeof dateLike === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(dateLike)
      ? parseDateInput(dateLike)
      : typeof dateLike === 'string'
        ? new Date(dateLike)
        : dateLike;
  return new Intl.DateTimeFormat('ko-KR', {
    month: 'long',
    day: 'numeric',
    weekday: 'short'
  }).format(date);
}

export function startOfWeek(date = new Date()): Date {
  const cursor = new Date(date);
  const day = cursor.getDay();
  cursor.setHours(0, 0, 0, 0);
  cursor.setDate(cursor.getDate() - day);
  return cursor;
}

export function endOfWeek(date = new Date()): Date {
  const cursor = startOfWeek(date);
  cursor.setDate(cursor.getDate() + 6);
  cursor.setHours(23, 59, 59, 999);
  return cursor;
}

export function isDateWithinCurrentWeek(dateString: string, referenceDate = new Date()): boolean {
  const value = parseDateInput(dateString);
  return value >= startOfWeek(referenceDate) && value <= endOfWeek(referenceDate);
}

export function buildWeekStrip(referenceDate = new Date()): Array<{
  label: string;
  dateKey: string;
  isToday: boolean;
}> {
  const weekStart = startOfWeek(referenceDate);

  return dayOrder.map((day, index) => {
    const cursor = new Date(weekStart);
    cursor.setDate(weekStart.getDate() + index);
    return {
      label: dayLabels[day],
      dateKey: toDateInput(cursor),
      isToday: toDateInput(cursor) === toDateInput(referenceDate)
    };
  });
}

export function getCompletedSessionDates(sessions: WorkoutSession[]): string[] {
  return [...new Set(sessions.filter((session) => session.status !== 'skipped').map((session) => session.sessionDate))];
}

export function getRelativeDate(daysAgo: number): string {
  const cursor = new Date();
  cursor.setDate(cursor.getDate() - daysAgo);
  return toDateInput(cursor);
}
