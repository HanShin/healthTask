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
  migrateLegacyExercise,
  normalizeRoutineItem,
  normalizeWorkoutRecordItem,
  shouldShowWeightField,
} from './workoutModel';

describe('workoutModel helpers', () => {
  it('accepts explicit modern and legacy exercise branches only', () => {
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

    // @ts-expect-error Exercise must keep either the modern fields or the legacy kind.
    const invalidExercise: Exercise = {
      id: 'invalid-exercise',
      name: 'Air Squat',
      isCustom: false,
      createdAt: '2026-04-14T00:00:00.000Z',
    };

    // @ts-expect-error Compatibility kind must match the modern category and record mode.
    const invalidHybridExercise: Exercise = {
      id: 'invalid-hybrid-exercise',
      name: 'Tempo Run',
      kind: 'strength',
      category: 'cardio',
      recordMode: 'cardio',
      isCustom: false,
      createdAt: '2026-04-14T00:00:00.000Z',
    };

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
    void invalidExercise;
    void invalidHybridExercise;
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

  it('migrates legacy bodyweight exercises to modern category and record mode', () => {
    expect(
      migrateLegacyExercise({
        id: 'legacy-bodyweight',
        name: 'Push Up',
        kind: 'strength',
        equipment: 'bodyweight',
        isCustom: false,
        createdAt: '2026-04-14T00:00:00.000Z',
      }),
    ).toMatchObject({
      category: 'bodyweight',
      recordMode: 'sets',
      kind: 'strength',
    });
  });

  it('defaults cardio routine items to the 유산소 activity label', () => {
    expect(
      normalizeRoutineItem({
        id: 'routine-cardio',
        exerciseId: 'run-1',
        order: 1,
        category: 'cardio',
        recordMode: 'cardio',
        targetActivityLabel: '   ',
      }),
    ).toMatchObject({
      category: 'cardio',
      recordMode: 'cardio',
      targetActivityLabel: '유산소',
      kind: 'running',
    });
  });

  it('normalizes legacy running items as cardio plans', () => {
    expect(
      normalizeRoutineItem({
        id: 'legacy-running',
        exerciseId: 'easy-run',
        order: 1,
        kind: 'running',
        targetDurationMin: 20,
      }),
    ).toMatchObject({
      category: 'cardio',
      recordMode: 'cardio',
      targetActivityLabel: '유산소',
      kind: 'running',
    });
  });

  it('infers bodyweight routine items from legacy identifiers', () => {
    expect(
      normalizeRoutineItem({
        id: 'legacy-pushup',
        exerciseId: 'push-up',
        order: 1,
        kind: 'strength',
        sets: 3,
        targetReps: 12,
        targetWeightKg: 0,
      }),
    ).toMatchObject({
      category: 'bodyweight',
      recordMode: 'sets',
      kind: 'strength',
      targetWeightKg: undefined,
    });
  });

  it('infers built-in bodyweight identifiers beyond push-up patterns', () => {
    expect(
      normalizeRoutineItem({
        id: 'bodyweight-squat',
        exerciseId: 'bodyweight-squat',
        order: 1,
        kind: 'strength',
        sets: 3,
        targetReps: 15,
      }),
    ).toMatchObject({
      category: 'bodyweight',
      recordMode: 'sets',
      kind: 'strength',
      targetWeightKg: undefined,
    });

    expect(
      normalizeRoutineItem({
        id: 'chin-up',
        exerciseId: 'chin-up',
        order: 2,
        kind: 'strength',
        sets: 3,
        targetReps: 8,
      }),
    ).toMatchObject({
      category: 'bodyweight',
      recordMode: 'sets',
      kind: 'strength',
      targetWeightKg: undefined,
    });

    expect(
      normalizeRoutineItem({
        id: 'bodyweight-reverse-lunge',
        exerciseId: 'bodyweight-reverse-lunge',
        order: 3,
        kind: 'strength',
        sets: 3,
        targetReps: 10,
      }),
    ).toMatchObject({
      category: 'bodyweight',
      recordMode: 'sets',
      kind: 'strength',
      targetWeightKg: undefined,
    });
  });

  it('keeps weighted routine items as weight even when the placeholder weight is zero', () => {
    expect(
      normalizeRoutineItem({
        id: 'legacy-bench',
        exerciseId: 'bench-press',
        order: 1,
        kind: 'strength',
        sets: 3,
        targetReps: 8,
        targetWeightKg: 0,
      }),
    ).toMatchObject({
      category: 'weight',
      recordMode: 'sets',
      kind: 'strength',
      targetWeightKg: 0,
    });
  });

  it('clears weight values from bodyweight set record items', () => {
    expect(
      normalizeWorkoutRecordItem({
        id: 'record-bodyweight',
        exerciseId: 'pushup-1',
        order: 1,
        category: 'bodyweight',
        recordMode: 'sets',
        sets: [
          {
            order: 1,
            plannedWeightKg: 20,
            actualWeightKg: 10,
            completed: true,
          },
        ],
      }),
    ).toMatchObject({
      category: 'bodyweight',
      recordMode: 'sets',
      kind: 'strength',
      sets: [
        {
          plannedWeightKg: undefined,
          actualWeightKg: undefined,
        },
      ],
    });
  });

  it('keeps weighted record placeholders on non-bodyweight exercises', () => {
    expect(
      normalizeWorkoutRecordItem({
        id: 'record-weight',
        exerciseId: 'bench-press',
        order: 1,
        kind: 'strength',
        sets: [
          {
            order: 1,
            plannedWeightKg: 0,
            actualWeightKg: 0,
            completed: false,
          },
        ],
      }),
    ).toMatchObject({
      category: 'weight',
      recordMode: 'sets',
      kind: 'strength',
      sets: [
        {
          plannedWeightKg: 0,
          actualWeightKg: 0,
        },
      ],
    });
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
