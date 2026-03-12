import type { StrengthRecord, StrengthSetRecord, WorkoutRecordItem } from './types';

export interface StrengthCarryover {
  reps: number;
  weightKg: number;
  setCount: number;
}

export function getLatestStrengthSet(record?: WorkoutRecordItem | null): StrengthSetRecord | null {
  if (!record || record.kind !== 'strength' || record.sets.length === 0) {
    return null;
  }

  const orderedSets = [...record.sets].sort((left, right) => left.order - right.order);
  const lastCompletedSet = [...orderedSets].reverse().find((set) => set.completed);

  return lastCompletedSet ?? orderedSets[orderedSets.length - 1] ?? null;
}

export function getStrengthCarryover(record?: WorkoutRecordItem | null): StrengthCarryover | null {
  if (!record || record.kind !== 'strength') {
    return null;
  }

  const latestSet = getLatestStrengthSet(record);

  return {
    reps: latestSet?.actualReps ?? latestSet?.plannedReps ?? 10,
    weightKg: latestSet?.actualWeightKg ?? latestSet?.plannedWeightKg ?? 0,
    setCount: Math.max(record.sets.length, 1)
  };
}

export function isStrengthRecord(record: WorkoutRecordItem): record is StrengthRecord {
  return record.kind === 'strength';
}
