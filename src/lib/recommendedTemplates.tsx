import { createContext, useContext, type ReactNode } from 'react';
import { routineTemplates } from '../data/catalog';
import type { RoutineTemplate } from './types';

interface RecommendationTemplatesContextValue {
  templates: RoutineTemplate[];
}

const RecommendationTemplatesContext = createContext<RecommendationTemplatesContextValue | null>(null);

const recommendationTemplatesValue: RecommendationTemplatesContextValue = {
  templates: routineTemplates
};

export function RecommendationTemplatesProvider({ children }: { children: ReactNode }) {
  return (
    <RecommendationTemplatesContext.Provider value={recommendationTemplatesValue}>
      {children}
    </RecommendationTemplatesContext.Provider>
  );
}

export function useRecommendationTemplates(): RecommendationTemplatesContextValue {
  const context = useContext(RecommendationTemplatesContext);

  if (!context) {
    throw new Error('useRecommendationTemplates must be used within RecommendationTemplatesProvider.');
  }

  return context;
}
