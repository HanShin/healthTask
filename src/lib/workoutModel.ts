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
  /\b(push-up|pushup|pull-up|pullup|sit-up|situp|dip|burpee|plank|mountain-climber|air-squat)\b/;

const CATEGORY_LABELS: Record<ExerciseCategory, string> = {
  weight: '웨이트',
  bodyweight: '맨몸운동',
  cardio: '유산소',
};

const LEGACY_CATEGORY_LABELS: Record<LegacyExerciseKind, string> = {
  strength: '웨이트',
  running: '유산소',
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
