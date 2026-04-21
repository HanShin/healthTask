import { render, screen } from '@testing-library/react';
import { SectionCard } from './SectionCard';

describe('SectionCard', () => {
  it('wraps content in a shared panel body for consistent vertical spacing', () => {
    const { container } = render(
      <SectionCard title="테스트 섹션">
        <div>첫 번째 줄</div>
        <div>두 번째 줄</div>
      </SectionCard>
    );

    expect(screen.getByRole('heading', { name: '테스트 섹션' })).toBeInTheDocument();

    const panelBody = container.querySelector('.panel__body');
    expect(panelBody).not.toBeNull();
    expect(panelBody?.children).toHaveLength(2);
  });
});
