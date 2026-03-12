import { exerciseCatalog } from '../data/catalog';
import { db } from './db';

export async function initializeDatabase(): Promise<void> {
  await db.exercises.bulkPut(exerciseCatalog);
}
