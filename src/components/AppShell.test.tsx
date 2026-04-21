import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { AppShell } from './AppShell';

describe('AppShell', () => {
  it('shows the 오늘운동 brand and Korean navigation copy', () => {
    render(
      <MemoryRouter initialEntries={['/today']}>
        <AppShell>
          <div>내용</div>
        </AppShell>
      </MemoryRouter>
    );

    expect(screen.getByText('오늘운동')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '오늘' })).toBeInTheDocument();
    expect(screen.getByText('오늘 운동과 최근 흐름을 함께 봐요')).toBeInTheDocument();

    const navigation = screen.getByRole('navigation', { name: '주요 메뉴' });
    expect(within(navigation).getByText('오늘')).toBeInTheDocument();
    expect(within(navigation).getByText('루틴')).toBeInTheDocument();
    expect(within(navigation).getByText('기록')).toBeInTheDocument();
    expect(within(navigation).getByText('설정')).toBeInTheDocument();
  });

  it('keeps the setup screen branded and hides bottom navigation', () => {
    render(
      <MemoryRouter initialEntries={['/setup']}>
        <AppShell>
          <div>내용</div>
        </AppShell>
      </MemoryRouter>
    );

    expect(screen.getByRole('heading', { name: '오늘운동' })).toBeInTheDocument();
    expect(screen.getByText('내 운동 흐름을 가볍게 시작해요')).toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: '주요 메뉴' })).not.toBeInTheDocument();
  });
});
