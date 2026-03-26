import { getStarterTemplateIds, routineTemplates } from '../data/catalog';
import { db } from './db';
import { createId } from './id';
import type {
  BackupPayload,
  Exercise,
  HealthMetricEntry,
  Profile,
  Routine,
  RoutineDraftItem,
  RoutineKind,
  RoutineTemplate,
  SetupInput,
  WorkoutRecordItem,
  WorkoutSession,
  WorkoutSessionStatus
} from './types';

function deriveRoutineKind(items: RoutineDraftItem[]): RoutineKind {
  const hasStrength = items.some((item) => item.kind === 'strength');
  const hasRunning = items.some((item) => item.kind === 'running');

  if (hasStrength && hasRunning) {
    return 'hybrid';
  }

  return hasStrength ? 'strength' : 'running';
}

function cloneTemplateItems(items: RoutineDraftItem[]): RoutineDraftItem[] {
  return items.map((item, index) =>
    item.kind === 'strength'
      ? {
          ...item,
          id: createId('plan'),
          order: index + 1
        }
      : {
          ...item,
          id: createId('plan'),
          order: index + 1
        }
  );
}

function buildRoutineFromTemplate(template: RoutineTemplate): Routine {
  const timestamp = new Date().toISOString();
  const items = cloneTemplateItems(template.items);

  return {
    id: createId('routine'),
    name: template.name,
    kind: deriveRoutineKind(items),
    source: 'template',
    isActive: true,
    items,
    createdAt: timestamp,
    updatedAt: timestamp
  };
}

export async function createProfile(
  input: SetupInput,
  availableTemplates: RoutineTemplate[] = routineTemplates
): Promise<void> {
  const timestamp = new Date().toISOString();

  const profile: Profile = {
    id: 'local-profile',
    workoutTypes: input.workoutTypes,
    workoutsPerWeek: input.workoutsPerWeek,
    weeklyGoalCount: input.workoutsPerWeek,
    units: {
      weight: 'kg',
      distance: 'km'
    },
    onboardingDone: true,
    createdAt: timestamp,
    updatedAt: timestamp
  };

  const templatesToInstall =
    input.starterMode === 'recommended'
      ? getStarterTemplateIds(input)
          .map(
            (templateId) =>
              availableTemplates.find((template) => template.id === templateId) ??
              routineTemplates.find((template) => template.id === templateId)
          )
          .filter((template): template is RoutineTemplate => Boolean(template))
          .map((template) => buildRoutineFromTemplate(template))
      : [];

  await db.transaction('rw', db.profile, db.routines, async () => {
    await db.profile.put(profile);

    if (templatesToInstall.length > 0) {
      await db.routines.bulkPut(templatesToInstall);
    }
  });
}

export async function saveRoutine(input: {
  name: string;
  items: RoutineDraftItem[];
  source?: Routine['source'];
  routineId?: string;
}): Promise<void> {
  const timestamp = new Date().toISOString();
  const items = input.items.map((item, index) => ({
    ...item,
    id: item.id || createId('plan'),
    order: index + 1
  }));
  const kind = deriveRoutineKind(items);

  if (input.routineId) {
    const existing = await db.routines.get(input.routineId);

    if (!existing) {
      throw new Error('루틴을 찾을 수 없습니다.');
    }

    const { scheduleDays: _legacyScheduleDays, ...legacySafeRoutine } = existing as Routine & {
      scheduleDays?: unknown;
    };

    await db.routines.put({
      ...legacySafeRoutine,
      name: input.name,
      kind,
      items,
      updatedAt: timestamp
    });

    return;
  }

  await db.routines.add({
    id: createId('routine'),
    name: input.name,
    kind,
    source: input.source ?? 'manual',
    isActive: true,
    items,
    createdAt: timestamp,
    updatedAt: timestamp
  });
}

export async function installTemplate(
  templateId: string,
  availableTemplates: RoutineTemplate[] = routineTemplates
): Promise<void> {
  const template =
    availableTemplates.find((item) => item.id === templateId) ??
    routineTemplates.find((item) => item.id === templateId);

  if (!template) {
    throw new Error('템플릿을 찾을 수 없습니다.');
  }

  await db.routines.add(buildRoutineFromTemplate(template));
}

export async function deleteRoutine(routineId: string): Promise<void> {
  await db.routines.delete(routineId);
}

function deriveSessionStatus(items: WorkoutRecordItem[]): WorkoutSessionStatus {
  const completedItems = items.filter((item) =>
    item.kind === 'strength'
      ? item.sets.length > 0 && item.sets.every((set) => set.completed)
      : Boolean(item.distanceKm || item.durationMin)
  ).length;

  if (completedItems === 0) {
    return 'skipped';
  }

  if (completedItems === items.length) {
    return 'completed';
  }

  return 'partial';
}

export async function saveWorkoutSession(input: {
  sessionId?: string;
  routineId?: string;
  sessionDate: string;
  memo?: string;
  items: WorkoutRecordItem[];
}): Promise<string> {
  const timestamp = new Date().toISOString();

  if (input.sessionId) {
    const existing = await db.sessions.get(input.sessionId);

    if (!existing) {
      throw new Error('수정할 세션을 찾을 수 없습니다.');
    }

    await db.sessions.put({
      ...existing,
      routineId: input.routineId ?? existing.routineId,
      sessionDate: input.sessionDate,
      memo: input.memo,
      status: deriveSessionStatus(input.items),
      items: input.items,
      endedAt: timestamp
    });

    return existing.id;
  }

  const session: WorkoutSession = {
    id: createId('session'),
    routineId: input.routineId,
    sessionDate: input.sessionDate,
    memo: input.memo,
    status: deriveSessionStatus(input.items),
    items: input.items,
    startedAt: timestamp,
    endedAt: timestamp,
    createdAt: timestamp
  };

  await db.sessions.add(session);

  return session.id;
}

export async function deleteWorkoutSession(sessionId: string): Promise<void> {
  await db.sessions.delete(sessionId);
}

function normalizeHealthMetric(value?: number): number | undefined {
  if (value === undefined || Number.isNaN(value) || value <= 0) {
    return undefined;
  }

  return value;
}

function sanitizeHealthMetrics(input: {
  weightKg?: number;
  skeletalMuscleKg?: number;
  bodyFatKg?: number;
  visceralFatLevel?: number;
}): Pick<HealthMetricEntry, 'weightKg' | 'skeletalMuscleKg' | 'bodyFatKg' | 'visceralFatLevel'> {
  return {
    weightKg: normalizeHealthMetric(input.weightKg),
    skeletalMuscleKg: normalizeHealthMetric(input.skeletalMuscleKg),
    bodyFatKg: normalizeHealthMetric(input.bodyFatKg),
    visceralFatLevel: normalizeHealthMetric(input.visceralFatLevel)
  };
}

export async function getHealthEntryByDate(recordDate: string): Promise<HealthMetricEntry | undefined> {
  return db.healthEntries.where('recordDate').equals(recordDate).first();
}

export async function saveHealthEntry(input: {
  entryId?: string;
  recordDate: string;
  weightKg?: number;
  skeletalMuscleKg?: number;
  bodyFatKg?: number;
  visceralFatLevel?: number;
}): Promise<string> {
  const timestamp = new Date().toISOString();
  const metrics = sanitizeHealthMetrics(input);
  const hasAnyMetric = Object.values(metrics).some((value) => value !== undefined);

  if (!input.recordDate) {
    throw new Error('건강 기록 날짜를 먼저 선택해 주세요.');
  }

  if (!hasAnyMetric) {
    throw new Error('건강 데이터는 최소 1개 값을 입력해야 합니다.');
  }

  const existingForDate = await getHealthEntryByDate(input.recordDate);

  if (input.entryId) {
    const existing = await db.healthEntries.get(input.entryId);

    if (!existing) {
      throw new Error('수정할 건강 기록을 찾을 수 없습니다.');
    }

    if (existingForDate && existingForDate.id !== input.entryId) {
      throw new Error('같은 날짜 건강 기록이 이미 있습니다. 기존 기록을 선택해 수정해 주세요.');
    }

    await db.healthEntries.put({
      ...existing,
      recordDate: input.recordDate,
      ...metrics,
      updatedAt: timestamp
    });

    return existing.id;
  }

  if (existingForDate) {
    await db.healthEntries.put({
      ...existingForDate,
      ...metrics,
      updatedAt: timestamp
    });

    return existingForDate.id;
  }

  const entry: HealthMetricEntry = {
    id: createId('health'),
    recordDate: input.recordDate,
    ...metrics,
    createdAt: timestamp,
    updatedAt: timestamp
  };

  await db.healthEntries.add(entry);

  return entry.id;
}

export async function deleteHealthEntry(entryId: string): Promise<void> {
  await db.healthEntries.delete(entryId);
}

export async function buildBackupPayload(): Promise<BackupPayload> {
  const [profile, exercises, routines, sessions, healthEntries] = await Promise.all([
    db.profile.get('local-profile'),
    db.exercises.toArray(),
    db.routines.toArray(),
    db.sessions.toArray(),
    db.healthEntries.toArray()
  ]);

  const payload: BackupPayload = {
    exportedAt: new Date().toISOString(),
    profile: profile ?? null,
    exercises,
    routines,
    sessions,
    healthEntries
  };

  return payload;
}

export async function exportBackup(): Promise<string> {
  const payload = await buildBackupPayload();

  return JSON.stringify(payload, null, 2);
}

export async function restoreBackupPayload(payload: BackupPayload): Promise<void> {
  await db.transaction('rw', [db.profile, db.exercises, db.routines, db.sessions, db.healthEntries], async () => {
    await db.profile.clear();
    await db.exercises.clear();
    await db.routines.clear();
    await db.sessions.clear();
    await db.healthEntries.clear();

    if (payload.profile) {
      await db.profile.put(payload.profile);
    }

    await db.exercises.bulkPut(payload.exercises);
    await db.routines.bulkPut(payload.routines);
    await db.sessions.bulkPut(payload.sessions);
    await db.healthEntries.bulkPut(payload.healthEntries ?? []);
  });
}

export async function importBackup(rawText: string): Promise<void> {
  const payload = JSON.parse(rawText) as BackupPayload;

  await restoreBackupPayload(payload);
}

export async function resetAllData(exercises: Exercise[]): Promise<void> {
  await db.transaction('rw', [db.profile, db.exercises, db.routines, db.sessions, db.healthEntries], async () => {
    await db.profile.clear();
    await db.exercises.clear();
    await db.routines.clear();
    await db.sessions.clear();
    await db.healthEntries.clear();
    await db.exercises.bulkPut(exercises);
  });
}
