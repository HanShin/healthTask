import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { routineTemplates } from '../../data/catalog';
import { SetupPage } from './SetupPage';

const navigateMock = vi.fn();

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');

  return {
    ...actual,
    useNavigate: () => navigateMock
  };
});

vi.mock('../../lib/recommendedTemplates', () => ({
  useRecommendationTemplates: () => ({
    templates: routineTemplates
  })
}));

describe('SetupPage', () => {
  beforeEach(() => {
    navigateMock.mockReset();
  });

  it('keeps onboarding focused on local setup and JSON restore only', () => {
    render(
      <MemoryRouter>
        <SetupPage />
      </MemoryRouter>
    );

    expect(screen.getByText('이전 기록 불러오기')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'JSON 불러오기' })).toBeInTheDocument();
    expect(screen.queryByText('클라우드 백업 키')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '클라우드에서 복원' })).not.toBeInTheDocument();
    expect(screen.queryByText(/Supabase/i)).not.toBeInTheDocument();
  });
});
