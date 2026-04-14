import type {
  Exercise,
  ModernExercise,
  RoutineDraftItem,
  StrengthSetRecord,
  WorkoutRecordItem,
} from './types';

export type ExerciseCategory = 'weight' | 'bodyweight' | 'cardio';
export type RecordMode = 'sets' | 'cardio';
export type LegacyExerciseKind = 'strength' | 'running';
export type ExerciseEquipment =
  | 'barbell'
  | 'dumbbell'
  | 'kettlebell'
  | 'bodyweight'
  | 'machine'
  | 'cable'
  | 'running';

const BODYWEIGHT_NAME_PATTERN =
  /\b(push-up|pushup|pull-up|pullup|chin-up|chinup|sit-up|situp|dip|burpee|plank|mountain-climber|air-squat|bodyweight-squat|bodyweight-reverse-lunge|glute-bridge|hanging-knee-raise|knee-raise|inverted-row)\b/;

const CATEGORY_LABELS: Record<ExerciseCategory, string> = {
  weight: '웨이트',
  bodyweight: '맨몸운동',
  cardio: '유산소',
};

const LEGACY_CATEGORY_LABELS: Record<LegacyExerciseKind, string> = {
  strength: '웨이트',
  running: '유산소',
};

type NormalizableRoutineItem = {
  id: string;
  exerciseId: string;
  order: number;
  note?: string;
  kind?: LegacyExerciseKind;
  category?: ExerciseCategory;
  recordMode?: RecordMode;
  sets?: number;
  targetReps?: number;
  restSeconds?: number;
  targetWeightKg?: number;
  targetActivityLabel?: string;
  targetDistanceKm?: number;
  targetDurationMin?: number;
  targetPaceMinPerKm?: number;
};

type NormalizableWorkoutRecordItem = {
  id: string;
  exerciseId: string;
  routineItemId?: string;
  order: number;
  note?: string;
  kind?: LegacyExerciseKind;
  category?: ExerciseCategory;
  recordMode?: RecordMode;
  sets?: StrengthSetRecord[];
  activityLabel?: string;
  distanceKm?: number;
  durationMin?: number;
  avgPaceMinPerKm?: number;
};

export function inferLegacyExerciseCategory(
  kind: LegacyExerciseKind,
  equipment?: ExerciseEquipment,
  name?: string,
): ExerciseCategory {
  if (kind === 'running' || equipment === 'running') {
    return 'cardio';
  }

  if (equipment === 'bodyweight') {
    return 'bodyweight';
  }

  if (name && BODYWEIGHT_NAME_PATTERN.test(name.toLowerCase())) {
    return 'bodyweight';
  }

  return 'weight';
}

function deriveLegacyKindFromCategory(
  category: ExerciseCategory,
): LegacyExerciseKind {
  return category === 'cardio' ? 'running' : 'strength';
}

function normalizeSetWeights(
  sets: StrengthSetRecord[],
  shouldClearWeight: boolean,
): StrengthSetRecord[] {
  if (!shouldClearWeight) {
    return sets.map((set) => ({ ...set }));
  }

  return sets.map((set) => ({
    ...set,
    plannedWeightKg: undefined,
    actualWeightKg: undefined,
  }));
}

function inferLegacySetCategory(input: {
  kind?: LegacyExerciseKind;
  category?: ExerciseCategory;
  exerciseId?: string;
}): 'weight' | 'bodyweight' {
  if (input.category === 'bodyweight') {
    return 'bodyweight';
  }

  const inferredCategory = inferLegacyExerciseCategory(
    input.kind ?? 'strength',
    undefined,
    input.exerciseId,
  );

  return inferredCategory === 'bodyweight' ? 'bodyweight' : 'weight';
}

export function migrateLegacyExercise(exercise: Exercise): ModernExercise {
  if ('category' in exercise && 'recordMode' in exercise) {
    const category = exercise.category as ExerciseCategory;
    const recordMode = exercise.recordMode as RecordMode;
    const kind = deriveLegacyKindFromCategory(category);

    return {
      ...exercise,
      category,
      recordMode,
      kind,
    } as ModernExercise;
  }

  const category = inferLegacyExerciseCategory(
    exercise.kind,
    exercise.equipment,
    exercise.name,
  );

  if (exercise.kind === 'running' || category === 'cardio') {
    return {
      ...exercise,
      category: 'cardio',
      recordMode: 'cardio',
      kind: 'running',
    } as ModernExercise;
  }

  return {
    ...exercise,
    category,
    recordMode: 'sets',
    kind: 'strength',
  } as ModernExercise;
}

export function normalizeRoutineItem(
  item: NormalizableRoutineItem,
): RoutineDraftItem {
  if (
    item.kind === 'running' ||
    item.category === 'cardio' ||
    item.recordMode === 'cardio'
  ) {
    return {
      ...item,
      category: 'cardio',
      recordMode: 'cardio',
      targetActivityLabel: item.targetActivityLabel?.trim() || '유산소',
      kind: 'running',
    };
  }

  const category = inferLegacySetCategory({
    kind: item.kind,
    category: item.category,
    exerciseId: item.exerciseId,
  });

  return {
    ...item,
    category,
    recordMode: 'sets',
    kind: 'strength',
    sets: item.sets ?? 0,
    targetReps: item.targetReps ?? 0,
    targetWeightKg: category === 'weight' ? item.targetWeightKg ?? 0 : undefined,
  };
}

export function normalizeWorkoutRecordItem(
  item: NormalizableWorkoutRecordItem,
): WorkoutRecordItem {
  if (
    ('category' in item && item.category === 'cardio') ||
    ('recordMode' in item && item.recordMode === 'cardio') ||
    item.kind === 'running'
  ) {
    return {
      ...item,
      category: 'cardio',
      recordMode: 'cardio',
      kind: 'running',
      activityLabel: 'activityLabel' in item ? item.activityLabel ?? '' : '',
    };
  }

  const normalizedCategory = inferLegacySetCategory({
    kind: item.kind,
    category: item.category,
    exerciseId: item.exerciseId,
  });

  return {
    ...item,
    category: normalizedCategory,
    recordMode: 'sets',
    kind: 'strength',
    sets: normalizeSetWeights(item.sets ?? [], normalizedCategory === 'bodyweight'),
  } as WorkoutRecordItem;
}

export function getExerciseCategoryLabel(
  category: ExerciseCategory | LegacyExerciseKind,
): string {
  if (category in CATEGORY_LABELS) {
    return CATEGORY_LABELS[category as ExerciseCategory];
  }

  return LEGACY_CATEGORY_LABELS[category as LegacyExerciseKind];
}

export function shouldShowWeightField(
  category: ExerciseCategory,
  recordMode: RecordMode,
): boolean {
  return category === 'weight' && recordMode === 'sets';
}

export function deriveRoutineCategorySummary(
  items: ReadonlyArray<{
    category: ExerciseCategory;
    recordMode: RecordMode;
  }>,
): string {
  const uniqueCategories = items.reduce<ExerciseCategory[]>((categories, item) => {
    if (!categories.includes(item.category)) {
      categories.push(item.category);
    }

    return categories;
  }, []);

  return uniqueCategories.map(getExerciseCategoryLabel).join(' · ');
}
