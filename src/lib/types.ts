import type {
  ExerciseCategory,
  ExerciseEquipment,
  LegacyExerciseKind,
  RecordMode,
} from './workoutModel';

export type DayOfWeek =
  | 'mon'
  | 'tue'
  | 'wed'
  | 'thu'
  | 'fri'
  | 'sat'
  | 'sun';

export type ExerciseKind = LegacyExerciseKind;
export type RoutineKind = ExerciseCategory | 'hybrid';
export type WorkoutSessionStatus = 'completed' | 'partial' | 'skipped';
export type RoutineSource = 'manual' | 'template';
export type RoutineDifficulty = 'beginner' | 'intermediate' | 'advanced';

export interface GuideResource {
  kind: 'video' | 'reference';
  title: string;
  provider: string;
  url: string;
  embedUrl?: string;
  thumbnailSrc?: string;
  description?: string;
}

export interface ExerciseGuide {
  headline: string;
  cues: string[];
  warning?: string;
  resources?: GuideResource[];
}

export interface Profile {
  id: 'local-profile';
  workoutTypes: ExerciseCategory[];
  workoutsPerWeek: number;
  weeklyGoalCount: number;
  units: {
    weight: 'kg';
    distance: 'km';
  };
  onboardingDone: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface Exercise {
  id: string;
  name: string;
  category: ExerciseCategory;
  recordMode: RecordMode;
  muscleGroup?: 'chest' | 'back' | 'legs' | 'shoulders' | 'arms' | 'core';
  equipment?: ExerciseEquipment;
  guide?: ExerciseGuide;
  isCustom: boolean;
  createdAt: string;
}

export interface SetBasedPlan {
  id: string;
  category: 'weight' | 'bodyweight';
  recordMode: 'sets';
  exerciseId: string;
  order: number;
  sets: number;
  targetReps: number;
  restSeconds?: number;
  targetWeightKg?: number;
  note?: string;
}

export interface CardioPlan {
  id: string;
  category: 'cardio';
  recordMode: 'cardio';
  exerciseId: string;
  order: number;
  targetActivityLabel: string;
  targetDistanceKm?: number;
  targetDurationMin?: number;
  targetPaceMinPerKm?: number;
  note?: string;
}

export type RoutineDraftItem = SetBasedPlan | CardioPlan;

export type StrengthPlan = SetBasedPlan;
export type RunningPlan = CardioPlan;

export interface Routine {
  id: string;
  name: string;
  kind: RoutineKind;
  source: RoutineSource;
  isActive: boolean;
  items: RoutineDraftItem[];
  createdAt: string;
  updatedAt: string;
}

export interface StrengthSetRecord {
  order: number;
  plannedReps?: number;
  actualReps?: number;
  plannedWeightKg?: number;
  actualWeightKg?: number;
  completed: boolean;
}

export interface SetBasedRecord {
  id: string;
  category: 'weight' | 'bodyweight';
  recordMode: 'sets';
  exerciseId: string;
  routineItemId?: string;
  order: number;
  sets: StrengthSetRecord[];
  note?: string;
}

export interface CardioRecord {
  id: string;
  category: 'cardio';
  recordMode: 'cardio';
  exerciseId: string;
  routineItemId?: string;
  order: number;
  activityLabel: string;
  distanceKm?: number;
  durationMin?: number;
  avgPaceMinPerKm?: number;
  note?: string;
}

export type WorkoutRecordItem = SetBasedRecord | CardioRecord;

export type StrengthRecord = SetBasedRecord;
export type RunningRecord = CardioRecord;

export interface WorkoutSession {
  id: string;
  routineId?: string;
  sessionDate: string;
  status: WorkoutSessionStatus;
  startedAt?: string;
  endedAt?: string;
  memo?: string;
  items: WorkoutRecordItem[];
  createdAt: string;
}

export interface HealthMetricEntry {
  id: string;
  recordDate: string;
  weightKg?: number;
  skeletalMuscleKg?: number;
  bodyFatKg?: number;
  visceralFatLevel?: number;
  createdAt: string;
  updatedAt: string;
}

export interface RoutineTemplate {
  id: string;
  name: string;
  blurb: string;
  focus: string;
  difficulty?: RoutineDifficulty;
  targets: string[];
  benefits: string[];
  items: RoutineDraftItem[];
}

export interface SetupInput {
  workoutTypes: ExerciseCategory[];
  workoutsPerWeek: number;
  starterMode: 'recommended' | 'blank';
  starterDifficulty: RoutineDifficulty;
}

export interface BackupPayload {
  exportedAt: string;
  profile: Profile | null;
  exercises: Exercise[];
  routines: Routine[];
  sessions: WorkoutSession[];
  healthEntries?: HealthMetricEntry[];
}
