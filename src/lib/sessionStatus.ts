import type { WorkoutSessionStatus } from './types';

export function getSessionStatusLabel(status: WorkoutSessionStatus): string {
  switch (status) {
    case 'completed':
      return '완료';
    case 'partial':
      return '부분';
    case 'skipped':
      return '건너뜀';
    default:
      return status;
  }
}
