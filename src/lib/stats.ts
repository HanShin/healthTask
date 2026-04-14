import { isDateWithinCurrentWeek, parseDateInput, startOfWeek, toDateInput } from './date';
import type { Profile, WorkoutSession } from './types';

function isSetSessionItem(item: WorkoutSession['items'][number]): boolean {
  return item.recordMode === 'sets' || item.category === 'weight' || item.category === 'bodyweight' || item.kind === 'strength';
}

function isCardioSessionItem(item: WorkoutSession['items'][number]): boolean {
  return item.recordMode === 'cardio' || item.category === 'cardio' || item.kind === 'running';
}

export function getSessionsThisWeek(sessions: WorkoutSession[], referenceDate = new Date()): WorkoutSession[] {
  return sessions.filter((session) => isDateWithinCurrentWeek(session.sessionDate, referenceDate));
}

export function getWeeklyCompletion(profile: Profile | null, sessions: WorkoutSession[]): {
  completed: number;
  goal: number;
  percent: number;
} {
  const goal = profile?.weeklyGoalCount ?? 0;
  const completed = getSessionsThisWeek(sessions).filter((session) => session.status !== 'skipped').length;

  return {
    completed,
    goal,
    percent: goal > 0 ? Math.min(100, Math.round((completed / goal) * 100)) : 0
  };
}

export function getCurrentStreak(sessions: WorkoutSession[]): number {
  const uniqueDates = [...new Set(sessions.filter((session) => session.status !== 'skipped').map((session) => session.sessionDate))].sort(
    (left, right) => right.localeCompare(left)
  );

  if (uniqueDates.length === 0) {
    return 0;
  }

  let streak = 1;
  let cursor = parseDateInput(uniqueDates[0]);

  for (let index = 1; index < uniqueDates.length; index += 1) {
    const nextCursor = new Date(cursor);
    nextCursor.setDate(cursor.getDate() - 1);

    if (uniqueDates[index] !== toDateInput(nextCursor)) {
      break;
    }

    streak += 1;
    cursor = nextCursor;
  }

  return streak;
}

export function getRecentSessions(sessions: WorkoutSession[], limit = 4): WorkoutSession[] {
  return [...sessions]
    .sort((left, right) => {
      const rightKey = `${right.sessionDate}-${right.createdAt}`;
      const leftKey = `${left.sessionDate}-${left.createdAt}`;
      return rightKey.localeCompare(leftKey);
    })
    .slice(0, limit);
}

export function getHistoryInsights(sessions: WorkoutSession[]): string[] {
  const weeklySessions = getSessionsThisWeek(sessions);
  const setCount = weeklySessions.flatMap((session) => session.items).filter(isSetSessionItem).length;
  const cardioCount = weeklySessions.flatMap((session) => session.items).filter(isCardioSessionItem).length;

  const insights: string[] = [];

  if (setCount > 0) {
    insights.push(`이번 주 웨이트 기록 ${setCount}개가 쌓였어요.`);
  }

  if (cardioCount > 0) {
    insights.push(`이번 주 유산소 기록 ${cardioCount}개로 흐름을 유지 중입니다.`);
  }

  if (weeklySessions.length === 0) {
    insights.push('이번 주 첫 운동을 기록하면 주간 분석이 바로 시작됩니다.');
  }

  return insights;
}

export function getWeekMomentumLabel(sessions: WorkoutSession[]): string {
  const weekStart = startOfWeek();
  const daysElapsed = Math.max(
    1,
    Math.round((new Date().getTime() - weekStart.getTime()) / (1000 * 60 * 60 * 24))
  );
  const count = getSessionsThisWeek(sessions).filter((session) => session.status !== 'skipped').length;

  if (count >= Math.max(2, Math.floor(daysElapsed / 2))) {
    return '리듬이 안정적입니다';
  }

  return '한 번 더 움직이면 리듬이 살아나요';
}
