import { getWeeklyRoutineSequence } from './routineSequence';
import { getRecentSessions, getSessionsThisWeek, getWeeklyCompletion } from './stats';
import { getStrengthCarryover } from './workoutRecord';
import type { Exercise, Profile, Routine, RoutineDraftItem, WorkoutRecordItem, WorkoutSession } from './types';

function isSetRoutineItem(item: RoutineDraftItem): boolean {
  return item.recordMode === 'sets' || item.category === 'weight' || item.category === 'bodyweight' || item.kind === 'strength';
}

function isCardioRoutineItem(item: RoutineDraftItem): boolean {
  return item.recordMode === 'cardio' || item.category === 'cardio' || item.kind === 'running';
}

function isSetRecordItem(item: WorkoutRecordItem): boolean {
  return item.recordMode === 'sets' || item.category === 'weight' || item.category === 'bodyweight' || item.kind === 'strength';
}

function isCardioRecordItem(item: WorkoutRecordItem): boolean {
  return item.recordMode === 'cardio' || item.category === 'cardio' || item.kind === 'running';
}

export function getTodayRecommendation(input: {
  profile: Profile | null;
  routines: Routine[];
  sessions: WorkoutSession[];
  exercises: Exercise[];
}): string {
  const weeklyCompletion = getWeeklyCompletion(input.profile, input.sessions);
  const routineSequence = getWeeklyRoutineSequence(input);
  const currentRoutine = routineSequence.nextRoutine;

  if (!currentRoutine) {
    if (routineSequence.isGoalComplete && routineSequence.goal > 0) {
      return '이번 주 목표를 모두 채웠어요. 가볍게 회복하고, 새 주가 시작되면 1번째 루틴부터 다시 시작해보세요.';
    }

    return weeklyCompletion.goal > weeklyCompletion.completed
      ? `이번 주 목표까지 ${weeklyCompletion.goal - weeklyCompletion.completed}회 남았어요. 오늘은 20분 유산소나 가벼운 전신 루틴이 좋아요.`
      : '오늘 일정은 비어 있어요. 회복 스트레칭이나 산책으로 컨디션을 정리해보세요.';
  }

  const recentSessions = getRecentSessions(input.sessions, 6);
  const firstSetItem = currentRoutine.items.find(isSetRoutineItem);

  if (firstSetItem) {
    const latestRecord = recentSessions
      .flatMap((session) => session.items)
      .find((record) => isSetRecordItem(record) && record.exerciseId === firstSetItem.exerciseId);

    const exerciseName =
      input.exercises.find((exercise) => exercise.id === firstSetItem.exerciseId)?.name ??
      '주요 운동';

    if (latestRecord && latestRecord.kind === 'strength') {
      const carryover = getStrengthCarryover(latestRecord);
      const lastWeight = carryover?.weightKg;

      if (lastWeight && lastWeight > 0) {
        return `${exerciseName}는 지난 기록이 ${lastWeight}kg였어요. 오늘은 컨디션이 괜찮다면 ${lastWeight + 2.5}kg를 시도해보세요.`;
      }
    }

    return `${exerciseName}부터 집중해서 시작하면 오늘 루틴 흐름이 좋아집니다.`;
  }

  const cardioItem = currentRoutine.items.find(isCardioRoutineItem);

  if (cardioItem) {
    const weeklyCardioCount = getSessionsThisWeek(input.sessions)
      .flatMap((session) => session.items)
      .filter(isCardioRecordItem).length;
    return weeklyCardioCount === 0
      ? '이번 주 첫 유산소를 기록해보세요. 짧아도 흐름을 살리는 데 충분합니다.'
      : '오늘 유산소는 페이스보다 호흡 리듬에 집중하면 꾸준함이 쌓입니다.';
  }

  return '지난 기록을 기준으로 조금만 더 정교하게 채우는 날로 만들어보세요.';
}
