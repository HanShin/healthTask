import { getStarterTemplateIds, routineTemplates } from '../data/catalog';
import { db } from './db';
import { createId } from './id';
import type {
  BackupPayload,
  Exercise,
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

export async function buildBackupPayload(): Promise<BackupPayload> {
  const [profile, exercises, routines, sessions] = await Promise.all([
    db.profile.get('local-profile'),
    db.exercises.toArray(),
    db.routines.toArray(),
    db.sessions.toArray()
  ]);

  const payload: BackupPayload = {
    exportedAt: new Date().toISOString(),
    profile: profile ?? null,
    exercises,
    routines,
    sessions
  };

  return payload;
}

export async function exportBackup(): Promise<string> {
  const payload = await buildBackupPayload();

  return JSON.stringify(payload, null, 2);
}

export async function restoreBackupPayload(payload: BackupPayload): Promise<void> {
  await db.transaction('rw', db.profile, db.exercises, db.routines, db.sessions, async () => {
    await db.profile.clear();
    await db.exercises.clear();
    await db.routines.clear();
    await db.sessions.clear();

    if (payload.profile) {
      await db.profile.put(payload.profile);
    }

    await db.exercises.bulkPut(payload.exercises);
    await db.routines.bulkPut(payload.routines);
    await db.sessions.bulkPut(payload.sessions);
  });
}

export async function importBackup(rawText: string): Promise<void> {
  const payload = JSON.parse(rawText) as BackupPayload;

  await restoreBackupPayload(payload);
}

export async function resetAllData(exercises: Exercise[]): Promise<void> {
  await db.transaction('rw', db.profile, db.exercises, db.routines, db.sessions, async () => {
    await db.profile.clear();
    await db.exercises.clear();
    await db.routines.clear();
    await db.sessions.clear();
    await db.exercises.bulkPut(exercises);
  });
}
