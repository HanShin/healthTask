export type DayOfWeek =
  | 'mon'
  | 'tue'
  | 'wed'
  | 'thu'
  | 'fri'
  | 'sat'
  | 'sun';

export type ExerciseKind = 'strength' | 'running';
export type RoutineKind = ExerciseKind | 'hybrid';
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
  workoutTypes: ExerciseKind[];
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
  kind: ExerciseKind;
  muscleGroup?: 'chest' | 'back' | 'legs' | 'shoulders' | 'arms' | 'core';
  equipment?:
    | 'barbell'
    | 'dumbbell'
    | 'kettlebell'
    | 'bodyweight'
    | 'machine'
    | 'cable'
    | 'running';
  guide?: ExerciseGuide;
  isCustom: boolean;
  createdAt: string;
}

export interface StrengthPlan {
  id: string;
  kind: 'strength';
  exerciseId: string;
  order: number;
  sets: number;
  targetReps: number;
  targetWeightKg?: number;
  restSeconds?: number;
  note?: string;
}

export interface RunningPlan {
  id: string;
  kind: 'running';
  exerciseId: string;
  order: number;
  targetDistanceKm?: number;
  targetDurationMin?: number;
  targetPaceMinPerKm?: number;
  note?: string;
}

export type RoutineDraftItem = StrengthPlan | RunningPlan;

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

export interface StrengthRecord {
  id: string;
  kind: 'strength';
  exerciseId: string;
  routineItemId?: string;
  order: number;
  sets: StrengthSetRecord[];
  note?: string;
}

export interface RunningRecord {
  id: string;
  kind: 'running';
  exerciseId: string;
  routineItemId?: string;
  order: number;
  distanceKm?: number;
  durationMin?: number;
  avgPaceMinPerKm?: number;
  note?: string;
}

export type WorkoutRecordItem = StrengthRecord | RunningRecord;

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
  workoutTypes: ExerciseKind[];
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
}
