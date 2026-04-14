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
export type WorkoutTypeSelection = ExerciseCategory | ExerciseKind;
export type RoutineKind = WorkoutTypeSelection | 'hybrid';
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
  workoutTypes: WorkoutTypeSelection[];
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

interface ExerciseBase {
  id: string;
  name: string;
  muscleGroup?: 'chest' | 'back' | 'legs' | 'shoulders' | 'arms' | 'core';
  equipment?: ExerciseEquipment;
  guide?: ExerciseGuide;
  isCustom: boolean;
  createdAt: string;
}

interface ModernSetExercise extends ExerciseBase {
  category: 'weight' | 'bodyweight';
  recordMode: 'sets';
  kind?: 'strength';
}

interface ModernCardioExercise extends ExerciseBase {
  category: 'cardio';
  recordMode: 'cardio';
  kind?: 'running';
}

interface LegacyStrengthExercise extends ExerciseBase {
  kind: 'strength';
  category?: never;
  recordMode?: never;
}

interface LegacyRunningExercise extends ExerciseBase {
  kind: 'running';
  category?: never;
  recordMode?: never;
}

export type ModernExercise = ModernSetExercise | ModernCardioExercise;
export type LegacyExercise = LegacyStrengthExercise | LegacyRunningExercise;
export type Exercise = ModernExercise | LegacyExercise;
export type ExerciseInput =
  | Omit<ModernExercise, 'createdAt'>
  | Omit<LegacyExercise, 'createdAt'>;

interface RoutineDraftItemShape {
  id: string;
  exerciseId: string;
  order: number;
  note?: string;
  kind?: ExerciseKind;
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
}

export interface SetBasedPlan extends RoutineDraftItemShape {
  id: string;
  category: 'weight' | 'bodyweight';
  recordMode: 'sets';
  kind?: 'strength';
  exerciseId: string;
  order: number;
  sets: number;
  targetReps: number;
  restSeconds?: number;
  targetWeightKg?: number;
  note?: string;
}

export interface CardioPlan extends RoutineDraftItemShape {
  id: string;
  category: 'cardio';
  recordMode: 'cardio';
  kind?: 'running';
  exerciseId: string;
  order: number;
  targetActivityLabel: string;
  targetDistanceKm?: number;
  targetDurationMin?: number;
  targetPaceMinPerKm?: number;
  note?: string;
}

interface LegacyStrengthPlan extends RoutineDraftItemShape {
  id: string;
  kind: 'strength';
  category?: 'weight' | 'bodyweight';
  recordMode?: 'sets';
  exerciseId: string;
  order: number;
  sets: number;
  targetReps: number;
  restSeconds?: number;
  targetWeightKg?: number;
  note?: string;
}

interface LegacyRunningPlan extends RoutineDraftItemShape {
  id: string;
  kind: 'running';
  category?: 'cardio';
  recordMode?: 'cardio';
  exerciseId: string;
  order: number;
  targetActivityLabel?: string;
  targetDistanceKm?: number;
  targetDurationMin?: number;
  targetPaceMinPerKm?: number;
  note?: string;
}

export type RoutineDraftItem =
  | SetBasedPlan
  | CardioPlan
  | LegacyStrengthPlan
  | LegacyRunningPlan;

export type StrengthPlan = SetBasedPlan | LegacyStrengthPlan;
export type RunningPlan = CardioPlan | LegacyRunningPlan;

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

interface WorkoutRecordItemShape {
  id: string;
  exerciseId: string;
  routineItemId?: string;
  order: number;
  note?: string;
  kind?: ExerciseKind;
  category?: ExerciseCategory;
  recordMode?: RecordMode;
  sets?: StrengthSetRecord[];
  activityLabel?: string;
  distanceKm?: number;
  durationMin?: number;
  avgPaceMinPerKm?: number;
}

export interface SetBasedRecord extends WorkoutRecordItemShape {
  id: string;
  category: 'weight' | 'bodyweight';
  recordMode: 'sets';
  kind?: 'strength';
  exerciseId: string;
  routineItemId?: string;
  order: number;
  sets: StrengthSetRecord[];
  note?: string;
}

export interface CardioRecord extends WorkoutRecordItemShape {
  id: string;
  category: 'cardio';
  recordMode: 'cardio';
  kind?: 'running';
  exerciseId: string;
  routineItemId?: string;
  order: number;
  activityLabel: string;
  distanceKm?: number;
  durationMin?: number;
  avgPaceMinPerKm?: number;
  note?: string;
}

interface LegacyStrengthRecord extends WorkoutRecordItemShape {
  id: string;
  kind: 'strength';
  category?: 'weight' | 'bodyweight';
  recordMode?: 'sets';
  exerciseId: string;
  routineItemId?: string;
  order: number;
  sets: StrengthSetRecord[];
  note?: string;
}

interface LegacyRunningRecord extends WorkoutRecordItemShape {
  id: string;
  kind: 'running';
  category?: 'cardio';
  recordMode?: 'cardio';
  exerciseId: string;
  routineItemId?: string;
  order: number;
  activityLabel?: string;
  distanceKm?: number;
  durationMin?: number;
  avgPaceMinPerKm?: number;
  note?: string;
}

export type WorkoutRecordItem =
  | SetBasedRecord
  | CardioRecord
  | LegacyStrengthRecord
  | LegacyRunningRecord;

export type StrengthRecord = SetBasedRecord | LegacyStrengthRecord;
export type RunningRecord = CardioRecord | LegacyRunningRecord;

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
  workoutTypes: WorkoutTypeSelection[];
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
