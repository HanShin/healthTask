import { describe, expect, it } from 'vitest';

import {
  deriveRoutineCategorySummary,
  getExerciseCategoryLabel,
  inferLegacyExerciseCategory,
  shouldShowWeightField,
} from './workoutModel';

describe('workoutModel helpers', () => {
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
