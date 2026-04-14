import type { Exercise } from './types';

import { getExerciseCategoryLabel, inferLegacyExerciseCategory } from './workoutModel';

export const equipmentLabelMap: Record<NonNullable<Exercise['equipment']>, string> = {
  barbell: '바벨',
  dumbbell: '아령',
  kettlebell: '케틀벨',
  bodyweight: '맨몸운동',
  machine: '머신',
  cable: '케이블',
  running: '유산소'
};

export const muscleLabelMap: Record<NonNullable<Exercise['muscleGroup']>, string> = {
  chest: '가슴',
  back: '등',
  legs: '하체',
  shoulders: '어깨',
  arms: '팔',
  core: '코어'
};

function getResolvedExerciseCategory(exercise: Exercise) {
  if ('category' in exercise && exercise.category) {
    return exercise.category;
  }

  if (exercise.kind === 'running') {
    return 'cardio';
  }

  return inferLegacyExerciseCategory(exercise.kind ?? 'strength', exercise.equipment, exercise.name);
}

export function getExerciseKindLabel(exercise: Exercise): string {
  return getExerciseCategoryLabel(getResolvedExerciseCategory(exercise));
}

export function getExerciseEquipmentLabel(exercise: Exercise): string {
  const category = getResolvedExerciseCategory(exercise);

  if (category === 'cardio') {
    return '유산소';
  }

  if (category === 'bodyweight') {
    return '맨몸운동';
  }

  return exercise.equipment ? equipmentLabelMap[exercise.equipment] : '기본';
}

export function getExerciseTargetLabel(exercise: Exercise): string {
  if (getResolvedExerciseCategory(exercise) === 'cardio') {
    return '심폐지구력';
  }

  return exercise.muscleGroup ? muscleLabelMap[exercise.muscleGroup] : '전신';
}

export function getExerciseGroupLabel(exercise: Exercise): string {
  const category = getResolvedExerciseCategory(exercise);

  if (category === 'cardio' || category === 'bodyweight') {
    return getExerciseCategoryLabel(category);
  }

  return exercise.muscleGroup ? muscleLabelMap[exercise.muscleGroup] : '기타';
}

export function getExerciseSummary(exercise: Exercise): string {
  if (exercise.guide?.headline) {
    return exercise.guide.headline;
  }

  const category = getResolvedExerciseCategory(exercise);

  if (category === 'cardio') {
    return '종류, 거리, 시간, 페이스를 기준으로 유산소 루틴을 기록할 수 있어요.';
  }

  if (category === 'bodyweight') {
    return '세트, 횟수, 휴식을 기준으로 맨몸운동 루틴을 기록할 수 있어요.';
  }

  return '세트, 횟수, 중량, 휴식을 기준으로 웨이트 루틴을 기록할 수 있어요.';
}

export function getExercisePlanningHint(exercise: Exercise): string {
  const category = getResolvedExerciseCategory(exercise);

  if (category === 'cardio') {
    return '종류, 거리, 시간, 페이스';
  }

  if (category === 'bodyweight') {
    return '세트, 횟수, 휴식';
  }

  return '세트, 횟수, 중량, 휴식';
}

export function hasExerciseGuideVideo(exercise: Exercise): boolean {
  return Boolean(exercise.guide?.resources?.some((resource) => resource.kind === 'video'));
}
