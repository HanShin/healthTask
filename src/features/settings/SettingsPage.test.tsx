import { render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SettingsPage } from './SettingsPage';

vi.mock('dexie-react-hooks', () => ({
  useLiveQuery: vi.fn(() => ({
    workoutTypes: ['weight', 'cardio'],
    weeklyGoalCount: 4
  }))
}));

vi.mock('../../lib/storage', async () => {
  const actual = await vi.importActual<typeof import('../../lib/storage')>('../../lib/storage');

  return {
    ...actual,
    getStorageDurabilityStatus: vi.fn(async () => ({
      supported: true,
      canPersist: true,
      persisted: false
    }))
  };
});

describe('SettingsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows local backup controls without any cloud backup section', async () => {
    render(<SettingsPage />);

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: '데이터 백업' })).toBeInTheDocument();
    });

    expect(screen.getByRole('button', { name: 'JSON 내보내기' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'JSON 불러오기' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: '클라우드 백업' })).not.toBeInTheDocument();
    expect(screen.queryByText('백업 키')).not.toBeInTheDocument();
    expect(screen.queryByText(/Supabase/i)).not.toBeInTheDocument();
  });
});
