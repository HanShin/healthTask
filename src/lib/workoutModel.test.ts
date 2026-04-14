import { describe, expect, it } from 'vitest';

import type {
  Exercise,
  LegacyExercise,
  ModernExercise,
  RoutineDraftItem,
  WorkoutRecordItem,
} from './types';
import {
  deriveRoutineCategorySummary,
  getExerciseCategoryLabel,
  inferLegacyExerciseCategory,
  shouldShowWeightField,
} from './workoutModel';

describe('workoutModel helpers', () => {
  it('accepts explicit legacy and modern exercise compatibility branches', () => {
    const legacyExercise: LegacyExercise = {
      id: 'legacy-exercise',
      name: 'Bench Press',
      kind: 'strength',
      isCustom: false,
      createdAt: '2026-04-14T00:00:00.000Z',
    };

    const modernExercise: ModernExercise = {
      id: 'modern-exercise',
      name: 'Push-up',
      category: 'bodyweight',
      recordMode: 'sets',
      isCustom: false,
      createdAt: '2026-04-14T00:00:00.000Z',
    };

    const exercises: Exercise[] = [legacyExercise, modernExercise];

    const legacyPlan: RoutineDraftItem = {
      id: 'legacy-plan',
      kind: 'strength',
      exerciseId: legacyExercise.id,
      order: 1,
      sets: 3,
      targetReps: 10,
    };

    const modernPlan: RoutineDraftItem = {
      id: 'modern-plan',
      category: 'cardio',
      recordMode: 'cardio',
      exerciseId: modernExercise.id,
      order: 2,
      targetActivityLabel: 'Easy Run',
      targetDurationMin: 30,
    };

    const legacyRecord: WorkoutRecordItem = {
      id: 'legacy-record',
      kind: 'running',
      exerciseId: 'run-1',
      order: 1,
      distanceKm: 5,
    };

    const modernRecord: WorkoutRecordItem = {
      id: 'modern-record',
      category: 'weight',
      recordMode: 'sets',
      exerciseId: 'bench-1',
      order: 2,
      sets: [
        {
          order: 1,
          actualReps: 8,
          actualWeightKg: 60,
          completed: true,
        },
      ],
    };

    expect(exercises).toHaveLength(2);
    expect(legacyPlan).toBeTruthy();
    expect(modernPlan).toBeTruthy();
    expect(legacyRecord).toBeTruthy();
    expect(modernRecord).toBeTruthy();
  });

  it('infers bodyweight from legacy strength exercises', () => {
    expect(
      inferLegacyExerciseCategory('strength', 'bodyweight', 'push-up'),
    ).toBe('bodyweight');
  });

  it('infers cardio from legacy running exercises', () => {
    expect(inferLegacyExerciseCategory('running', 'running', 'easy-run')).toBe(
      'cardio',
    );
  });

  it('returns the weight category label', () => {
    expect(getExerciseCategoryLabel('weight')).toBe('웨이트');
  });

  it('returns the bodyweight category label', () => {
    expect(getExerciseCategoryLabel('bodyweight')).toBe('맨몸운동');
  });

  it('returns the cardio category label', () => {
    expect(getExerciseCategoryLabel('cardio')).toBe('유산소');
  });

  it('returns the legacy running label through the compatibility bridge', () => {
    expect(getExerciseCategoryLabel('running')).toBe('유산소');
  });

  it('shows the weight field for weight set plans', () => {
    expect(shouldShowWeightField('weight', 'sets')).toBe(true);
  });

  it('hides the weight field for bodyweight set plans', () => {
    expect(shouldShowWeightField('bodyweight', 'sets')).toBe(false);
  });

  it('hides the weight field for cardio plans', () => {
    expect(shouldShowWeightField('cardio', 'cardio')).toBe(false);
  });

  it('derives a joined routine category summary', () => {
    expect(
      deriveRoutineCategorySummary([
        { category: 'weight', recordMode: 'sets' },
        { category: 'bodyweight', recordMode: 'sets' },
        { category: 'cardio', recordMode: 'cardio' },
      ]),
    ).toBe('웨이트 · 맨몸운동 · 유산소');
  });
});
