import { describe, expect, it } from 'vitest';

import { getStarterTemplateIds } from './catalog';
import {
  getExerciseEquipmentLabel,
  getExerciseKindLabel,
  getExercisePlanningHint,
  getExerciseSummary
} from '../lib/exercise';
import { getSessionStatusLabel } from '../lib/sessionStatus';
import type { Exercise } from '../lib/types';

describe('getStarterTemplateIds', () => {
  it('returns cardio-only templates when cardio is the only selected category', () => {
    expect(
      getStarterTemplateIds({
        workoutTypes: ['cardio'],
        workoutsPerWeek: 4,
        starterMode: 'recommended',
        starterDifficulty: 'beginner'
      })
    ).toEqual(['template-easy-run', 'template-tempo-run']);
  });

  it('keeps bodyweight-friendly templates in mixed bodyweight and cardio selections', () => {
    const templateIds = getStarterTemplateIds({
      workoutTypes: ['bodyweight', 'cardio'],
      workoutsPerWeek: 4,
      starterMode: 'recommended',
      starterDifficulty: 'beginner'
    });

    expect(templateIds).toContain('template-bodyweight-foundation');
    expect(templateIds).toContain('template-easy-run');
  });

  it('prefers the modern category labels in exercise copy', () => {
    const cardioExercise: Exercise = {
      id: 'easy-run',
      name: '이지 런',
      category: 'cardio',
      recordMode: 'cardio',
      kind: 'running',
      equipment: 'running',
      isCustom: false,
      createdAt: '2026-04-14T00:00:00.000Z'
    };

    const bodyweightExercise: Exercise = {
      id: 'push-up',
      name: '푸시업',
      category: 'bodyweight',
      recordMode: 'sets',
      kind: 'strength',
      equipment: 'bodyweight',
      muscleGroup: 'chest',
      isCustom: false,
      createdAt: '2026-04-14T00:00:00.000Z'
    };

    expect(getExerciseKindLabel(cardioExercise)).toBe('유산소');
    expect(getExerciseEquipmentLabel(cardioExercise)).toBe('유산소');
    expect(getExerciseSummary(cardioExercise)).toContain('유산소');
    expect(getExercisePlanningHint(cardioExercise)).toBe('종류, 거리, 시간, 페이스');

    expect(getExerciseKindLabel(bodyweightExercise)).toBe('맨몸운동');
    expect(getExerciseEquipmentLabel(bodyweightExercise)).toBe('맨몸운동');
    expect(getExerciseSummary(bodyweightExercise)).toContain('맨몸운동');
    expect(getExercisePlanningHint(bodyweightExercise)).toBe('세트, 횟수, 휴식');
  });

  it('uses shortened Korean session status labels', () => {
    expect(getSessionStatusLabel('partial')).toBe('부분');
    expect(getSessionStatusLabel('skipped')).toBe('건너뜀');
  });
});
