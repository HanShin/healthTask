import { isDateWithinCurrentWeek } from './date';
import type { Profile, Routine, WorkoutSession } from './types';

function compareRoutineSequence(left: Routine, right: Routine): number {
  const createdAtComparison = left.createdAt.localeCompare(right.createdAt);

  if (createdAtComparison !== 0) {
    return createdAtComparison;
  }

  return left.id.localeCompare(right.id);
}

export function getOrderedRoutines(routines: Routine[]): Routine[] {
  return [...routines].sort(compareRoutineSequence);
}

export function getOrderedActiveRoutines(routines: Routine[]): Routine[] {
  return getOrderedRoutines(routines).filter((routine) => routine.isActive);
}

export function getCompletedSessionsThisWeek(
  sessions: WorkoutSession[],
  referenceDate = new Date()
): WorkoutSession[] {
  return sessions.filter(
    (session) => session.status !== 'skipped' && isDateWithinCurrentWeek(session.sessionDate, referenceDate)
  );
}

export function getWeeklyRoutineSequence(input: {
  profile: Profile | null;
  routines: Routine[];
  sessions: WorkoutSession[];
  referenceDate?: Date;
}): {
  activeRoutines: Routine[];
  completedCount: number;
  goal: number;
  remainingCount: number;
  isGoalComplete: boolean;
  nextSequenceNumber: number | null;
  nextRoutine: Routine | null;
} {
  const referenceDate = input.referenceDate ?? new Date();
  const activeRoutines = getOrderedActiveRoutines(input.routines);
  const completedCount = getCompletedSessionsThisWeek(input.sessions, referenceDate).length;
  const goal = Math.max(input.profile?.weeklyGoalCount ?? activeRoutines.length, 0);
  const isGoalComplete = goal > 0 && completedCount >= goal;

  if (activeRoutines.length === 0 || isGoalComplete) {
    return {
      activeRoutines,
      completedCount,
      goal,
      remainingCount: Math.max(goal - completedCount, 0),
      isGoalComplete,
      nextSequenceNumber: null,
      nextRoutine: null
    };
  }

  const nextSequenceNumber = completedCount + 1;

  return {
    activeRoutines,
    completedCount,
    goal,
    remainingCount: Math.max(goal - completedCount, 0),
    isGoalComplete,
    nextSequenceNumber,
    nextRoutine: activeRoutines[completedCount % activeRoutines.length] ?? null
  };
}
