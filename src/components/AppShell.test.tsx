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
    expect(screen.getByText('오늘 할 운동과 최근 흐름을 한 번에 봐요')).toBeInTheDocument();

    const navigation = screen.getByRole('navigation', { name: '주요 메뉴' });
    expect(within(navigation).getByText('오늘')).toBeInTheDocument();
    expect(within(navigation).getByText('루틴')).toBeInTheDocument();
    expect(within(navigation).getByText('기록')).toBeInTheDocument();
    expect(within(navigation).getByText('설정')).toBeInTheDocument();
  });
});
