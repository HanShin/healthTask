import type { Exercise } from './types';

export const equipmentLabelMap: Record<NonNullable<Exercise['equipment']>, string> = {
  barbell: '바벨',
  dumbbell: '아령',
  kettlebell: '케틀벨',
  bodyweight: '맨몸',
  machine: '머신',
  cable: '케이블',
  running: '러닝'
};

export const muscleLabelMap: Record<NonNullable<Exercise['muscleGroup']>, string> = {
  chest: '가슴',
  back: '등',
  legs: '하체',
  shoulders: '어깨',
  arms: '팔',
  core: '코어'
};

export function getExerciseKindLabel(exercise: Exercise): string {
  return exercise.kind === 'running' ? '유산소' : '웨이트';
}

export function getExerciseEquipmentLabel(exercise: Exercise): string {
  if (exercise.kind === 'running') {
    return '러닝';
  }

  return exercise.equipment ? equipmentLabelMap[exercise.equipment] : '기본';
}

export function getExerciseTargetLabel(exercise: Exercise): string {
  if (exercise.kind === 'running') {
    return '심폐지구력';
  }

  return exercise.muscleGroup ? muscleLabelMap[exercise.muscleGroup] : '전신';
}

export function getExerciseGroupLabel(exercise: Exercise): string {
  if (exercise.kind === 'running') {
    return '러닝';
  }

  return exercise.muscleGroup ? muscleLabelMap[exercise.muscleGroup] : '기타';
}

export function getExerciseSummary(exercise: Exercise): string {
  if (exercise.guide?.headline) {
    return exercise.guide.headline;
  }

  if (exercise.kind === 'running') {
    return '거리, 시간, 평균 속도를 기준으로 유산소 루틴을 설계할 수 있어요.';
  }

  return '세트, 횟수, 중량, 휴식 시간을 기준으로 웨이트 루틴을 설계할 수 있어요.';
}

export function getExercisePlanningHint(exercise: Exercise): string {
  return exercise.kind === 'running' ? '거리, 시간, 속도' : '세트, 횟수, 중량, 휴식';
}

export function hasExerciseGuideVideo(exercise: Exercise): boolean {
  return Boolean(exercise.guide?.resources?.some((resource) => resource.kind === 'video'));
}
