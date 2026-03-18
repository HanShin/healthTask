import type { ReactNode } from 'react';

interface EmptyStateProps {
  title: string;
  body: string;
  action?: ReactNode;
}

export function EmptyState({ title, body, action }: EmptyStateProps) {
  return (
    <section className="panel panel--soft empty-state">
      <h2>{title}</h2>
      <p>{body}</p>
      {action ? <div className="empty-state__action">{action}</div> : null}
    </section>
  );
}
