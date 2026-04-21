import { parseDateInput, toDateInput } from './date';
import type { HealthMetricEntry, WorkoutSession } from './types';

export type TrendRangeDays = 14 | 30 | 90;
export type WorkoutTrendMetric = 'sessionCount' | 'completedStrengthSets' | 'runningDistanceKm';
export type HealthTrendMetric = 'weightKg' | 'skeletalMuscleKg' | 'bodyFatKg' | 'visceralFatLevel';

export interface TrendPoint {
  dateKey: string;
  label: string;
  value: number | null;
}

export interface TrendDelta {
  latest: number | null;
  previous: number | null;
  change: number | null;
}

export const trendRangeOptions: TrendRangeDays[] = [14, 30, 90];

export const workoutMetricMeta: Record<
  WorkoutTrendMetric,
  {
    label: string;
    unit: string;
    digits: number;
    emptyLabel: string;
  }
> = {
  sessionCount: {
    label: '세션 수',
    unit: '회',
    digits: 0,
    emptyLabel: '아직 운동 세션이 없어 추이가 비어 있어요.'
  },
  completedStrengthSets: {
    label: '웨이트 완료 세트',
    unit: '세트',
    digits: 0,
    emptyLabel: '완료된 웨이트 세트가 쌓이면 여기서 흐름을 볼 수 있어요.'
  },
  runningDistanceKm: {
    label: '유산소 거리',
    unit: 'km',
    digits: 1,
    emptyLabel: '유산소 기록이 생기면 거리 추이가 표시됩니다.'
  }
};

export const healthMetricMeta: Record<
  HealthTrendMetric,
  {
    label: string;
    unit: string;
    digits: number;
    emptyLabel: string;
  }
> = {
  weightKg: {
    label: '체중',
    unit: 'kg',
    digits: 1,
    emptyLabel: '체중을 한 번 입력하면 날짜별 변화를 확인할 수 있어요.'
  },
  skeletalMuscleKg: {
    label: '골격근량',
    unit: 'kg',
    digits: 1,
    emptyLabel: '골격근량을 입력하면 변화 추이가 여기에 쌓입니다.'
  },
  bodyFatKg: {
    label: '체지방량',
    unit: 'kg',
    digits: 1,
    emptyLabel: '체지방량 기록이 생기면 날짜별 흐름을 볼 수 있어요.'
  },
  visceralFatLevel: {
    label: '복부비만레벨',
    unit: '레벨',
    digits: 1,
    emptyLabel: '복부비만레벨을 입력하면 추이를 바로 볼 수 있어요.'
  }
};

function buildDateRange(days: number, referenceDate = new Date()): string[] {
  const dates: string[] = [];

  for (let index = days - 1; index >= 0; index -= 1) {
    const cursor = new Date(referenceDate);
    cursor.setHours(0, 0, 0, 0);
    cursor.setDate(referenceDate.getDate() - index);
    dates.push(toDateInput(cursor));
  }

  return dates;
}

function formatDateLabel(dateKey: string): string {
  return dateKey.slice(5).replace('-', '/');
}

function countCompletedStrengthSets(session: WorkoutSession): number {
  return session.items.reduce((count, item) => {
    if (item.kind !== 'strength') {
      return count;
    }

    return count + item.sets.filter((set) => set.completed).length;
  }, 0);
}

function sumRunningDistance(session: WorkoutSession): number {
  return session.items.reduce((total, item) => {
    if (item.kind !== 'running') {
      return total;
    }

    return total + (item.distanceKm ?? 0);
  }, 0);
}

function roundMetric(value: number, digits: number): number {
  return Number(value.toFixed(digits));
}

export function buildWorkoutTrendSeries(
  sessions: WorkoutSession[],
  metric: WorkoutTrendMetric,
  days: TrendRangeDays,
  referenceDate = new Date()
): TrendPoint[] {
  const dates = buildDateRange(days, referenceDate);
  const rangeStart = parseDateInput(dates[0]);
  const rangeEnd = parseDateInput(dates[dates.length - 1]);
  const valuesByDate = new Map<string, number>();

  sessions.forEach((session) => {
    const sessionDate = parseDateInput(session.sessionDate);

    if (sessionDate < rangeStart || sessionDate > rangeEnd) {
      return;
    }

    let nextValue = 0;

    if (metric === 'sessionCount') {
      nextValue = session.status === 'skipped' ? 0 : 1;
    } else if (metric === 'completedStrengthSets') {
      nextValue = countCompletedStrengthSets(session);
    } else {
      nextValue = sumRunningDistance(session);
    }

    valuesByDate.set(session.sessionDate, (valuesByDate.get(session.sessionDate) ?? 0) + nextValue);
  });

  return dates.map((dateKey) => ({
    dateKey,
    label: formatDateLabel(dateKey),
    value:
      metric === 'runningDistanceKm'
        ? roundMetric(valuesByDate.get(dateKey) ?? 0, workoutMetricMeta[metric].digits)
        : valuesByDate.get(dateKey) ?? 0
  }));
}

export function buildHealthTrendSeries(
  entries: HealthMetricEntry[],
  metric: HealthTrendMetric,
  days: TrendRangeDays,
  referenceDate = new Date()
): TrendPoint[] {
  const dates = buildDateRange(days, referenceDate);
  const entryMap = new Map(entries.map((entry) => [entry.recordDate, entry]));

  return dates.map((dateKey) => ({
    dateKey,
    label: formatDateLabel(dateKey),
    value: entryMap.get(dateKey)?.[metric] ?? null
  }));
}

export function getTrendDelta(points: TrendPoint[]): TrendDelta {
  const valuedPoints = points.filter((point) => point.value !== null);
  const latest = valuedPoints[valuedPoints.length - 1]?.value ?? null;
  const previous = valuedPoints[valuedPoints.length - 2]?.value ?? null;

  return {
    latest,
    previous,
    change: latest !== null && previous !== null ? latest - previous : null
  };
}
