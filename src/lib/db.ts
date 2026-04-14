import Dexie, { type Table } from 'dexie';
import type { Exercise, HealthMetricEntry, Profile, Routine, WorkoutSession } from './types';
import {
  migrateLegacyExercise,
  normalizeRoutineItem,
  normalizeWorkoutRecordItem,
} from './workoutModel';

export class HealthTaskDatabase extends Dexie {
  profile!: Table<Profile, string>;
  exercises!: Table<Exercise, string>;
  routines!: Table<Routine, string>;
  sessions!: Table<WorkoutSession, string>;
  healthEntries!: Table<HealthMetricEntry, string>;

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

    this.version(3).stores({
      profile: 'id, onboardingDone, updatedAt',
      exercises: 'id, kind, muscleGroup, name',
      routines: 'id, kind, isActive, updatedAt',
      sessions: 'id, sessionDate, status, routineId, createdAt',
      healthEntries: 'id, recordDate, updatedAt'
    });

    this.version(4)
      .stores({
        profile: 'id, onboardingDone, updatedAt',
        exercises: 'id, category, recordMode, muscleGroup, name',
        routines: 'id, isActive, updatedAt',
        sessions: 'id, sessionDate, status, routineId, createdAt',
        healthEntries: 'id, recordDate, updatedAt'
      })
      .upgrade((transaction) =>
        Promise.all([
          transaction
            .table('exercises')
            .toCollection()
            .modify((exercise) => {
              Object.assign(exercise, migrateLegacyExercise(exercise as Exercise));
            }),
          transaction
            .table('routines')
            .toCollection()
            .modify((routine) => {
              routine.items = routine.items.map((item: Routine['items'][number]) =>
                normalizeRoutineItem(item)
              );
            }),
          transaction
            .table('sessions')
            .toCollection()
            .modify((session) => {
              session.items = session.items.map((item: WorkoutSession['items'][number]) =>
                normalizeWorkoutRecordItem(item)
              );
            }),
        ])
      );
  }
}

export const db = new HealthTaskDatabase();
