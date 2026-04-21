import type { ReactNode } from 'react';

interface SectionCardProps {
  eyebrow?: string;
  title: string;
  action?: ReactNode;
  children: ReactNode;
}

export function SectionCard({ eyebrow, title, action, children }: SectionCardProps) {
  return (
    <section className="panel">
      <div className="panel__header">
        <div className="panel__copy">
          {eyebrow ? <div className="eyebrow">{eyebrow}</div> : null}
          <h2>{title}</h2>
        </div>
        {action ? <div className="panel__action">{action}</div> : null}
      </div>
      <div className="panel__body">{children}</div>
    </section>
  );
}
