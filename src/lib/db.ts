import Dexie, { type Table } from 'dexie';
import type { Exercise, Profile, Routine, WorkoutSession } from './types';

export class HealthTaskDatabase extends Dexie {
  profile!: Table<Profile, string>;
  exercises!: Table<Exercise, string>;
  routines!: Table<Routine, string>;
  sessions!: Table<WorkoutSession, string>;

  constructor() {
    super('health-task-db');

    this.version(1).stores({
      profile: 'id, onboardingDone, updatedAt',
      exercises: 'id, kind, muscleGroup, name',
      routines: 'id, kind, isActive, updatedAt, *scheduleDays',
      sessions: 'id, sessionDate, status, routineId, createdAt'
    });

    this.version(2)
      .stores({
        profile: 'id, onboardingDone, updatedAt',
        exercises: 'id, kind, muscleGroup, name',
        routines: 'id, kind, isActive, updatedAt',
        sessions: 'id, sessionDate, status, routineId, createdAt'
      })
      .upgrade((transaction) =>
        transaction
          .table('routines')
          .toCollection()
          .modify((routine) => {
            delete (routine as { scheduleDays?: unknown }).scheduleDays;
          })
      );
  }
}

export const db = new HealthTaskDatabase();
