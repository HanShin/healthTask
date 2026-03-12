import type { WorkoutSessionStatus } from './types';

export function getSessionStatusLabel(status: WorkoutSessionStatus): string {
  switch (status) {
    case 'completed':
      return '완료';
    case 'partial':
      return '일부 완료';
    case 'skipped':
      return '미완료';
    default:
      return status;
  }
}
