import {
  createContext,
  startTransition,
  useContext,
  useEffect,
  useState,
  type ReactNode
} from 'react';
import { routineTemplates as defaultRoutineTemplates } from '../data/catalog';
import { getSupabaseClient, isSupabaseConfigured } from './supabase';
import type { RoutineDifficulty, RoutineDraftItem, RoutineTemplate } from './types';

const ROUTINE_TEMPLATES_CACHE_KEY = 'health-task:recommended-templates-cache';
const DEFAULT_REFRESH_MINUTES = 15;
const TEMPLATE_TABLE = 'health_recommendation_templates';

type TemplateSource = 'local' | 'cache' | 'remote';

interface TemplateCachePayload {
  fetchedAt: string;
  templates: RoutineTemplate[];
}

interface RecommendationTemplatesContextValue {
  templates: RoutineTemplate[];
  source: TemplateSource;
  isRefreshing: boolean;
  lastSyncedAt: string | null;
  error: string | null;
  isRemoteConfigured: boolean;
  refreshTemplates: () => Promise<void>;
}

const RecommendationTemplatesContext = createContext<RecommendationTemplatesContextValue | null>(null);

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((item) => typeof item === 'string');
}

function isRoutineDifficulty(value: unknown): value is RoutineDifficulty {
  return value === 'beginner' || value === 'intermediate' || value === 'advanced';
}

function isRoutineDraftItem(value: unknown): value is RoutineDraftItem {
  if (!isObject(value) || typeof value.id !== 'string' || typeof value.exerciseId !== 'string') {
    return false;
  }

  if (value.kind === 'strength') {
    return (
      typeof value.order === 'number' &&
      typeof value.sets === 'number' &&
      typeof value.targetReps === 'number'
    );
  }

  if (value.kind === 'running') {
    return typeof value.order === 'number';
  }

  return false;
}

function isRoutineTemplate(value: unknown): value is RoutineTemplate {
  return (
    isObject(value) &&
    typeof value.id === 'string' &&
    typeof value.name === 'string' &&
    typeof value.blurb === 'string' &&
    typeof value.focus === 'string' &&
    (typeof value.difficulty === 'undefined' || isRoutineDifficulty(value.difficulty)) &&
    isStringArray(value.targets) &&
    isStringArray(value.benefits) &&
    Array.isArray(value.items) &&
    value.items.every(isRoutineDraftItem)
  );
}

function mergeTemplates(remoteTemplates: RoutineTemplate[]): RoutineTemplate[] {
  const mergedTemplates: RoutineTemplate[] = [];
  const remoteById = new Map(remoteTemplates.map((template) => [template.id, template]));

  remoteTemplates.forEach((template) => {
    mergedTemplates.push(template);
  });

  defaultRoutineTemplates.forEach((template) => {
    if (!remoteById.has(template.id)) {
      mergedTemplates.push(template);
    }
  });

  return mergedTemplates;
}

function readTemplateCache(): TemplateCachePayload | null {
  if (typeof window === 'undefined') {
    return null;
  }

  try {
    const cached = window.localStorage.getItem(ROUTINE_TEMPLATES_CACHE_KEY);

    if (!cached) {
      return null;
    }

    const parsed = JSON.parse(cached) as TemplateCachePayload;

    if (
      !parsed ||
      typeof parsed.fetchedAt !== 'string' ||
      !Array.isArray(parsed.templates) ||
      !parsed.templates.every(isRoutineTemplate)
    ) {
      return null;
    }

    return parsed;
  } catch {
    return null;
  }
}

function writeTemplateCache(payload: TemplateCachePayload): void {
  if (typeof window === 'undefined') {
    return;
  }

  try {
    window.localStorage.setItem(ROUTINE_TEMPLATES_CACHE_KEY, JSON.stringify(payload));
  } catch {
    // Ignore cache write failures and continue using in-memory templates.
  }
}

function getRefreshIntervalMs(): number {
  const configuredMinutes = Number(import.meta.env.VITE_RECOMMENDATION_TEMPLATES_REFRESH_MINUTES);

  if (Number.isFinite(configuredMinutes) && configuredMinutes > 0) {
    return configuredMinutes * 60 * 1000;
  }

  return DEFAULT_REFRESH_MINUTES * 60 * 1000;
}

async function readRemoteTemplates(includeDifficulty: boolean): Promise<RoutineTemplate[]> {
  const client = getSupabaseClient();

  if (!client) {
    throw new Error('Supabase 연결 정보가 없어 로컬 추천 템플릿을 사용 중입니다.');
  }

  const columns = includeDifficulty
    ? 'id, name, blurb, focus, difficulty, targets, benefits, items'
    : 'id, name, blurb, focus, targets, benefits, items';

  const { data, error } = await client
    .from(TEMPLATE_TABLE)
    .select(columns)
    .eq('is_active', true)
    .order('sort_order', { ascending: true })
    .order('updated_at', { ascending: false });

  if (error) {
    throw error;
  }

  const rawTemplates: unknown[] = Array.isArray(data) ? [...data] : [];
  const normalizedTemplates: unknown[] = includeDifficulty
    ? rawTemplates
    : rawTemplates.map((template) => {
        if (!isObject(template)) {
          return template;
        }

        const templateRecord: Record<string, unknown> = template;

        return {
          ...templateRecord,
          difficulty: 'beginner' as const
        };
      });

  return normalizedTemplates.filter(isRoutineTemplate);
}

async function fetchRemoteTemplates(): Promise<TemplateCachePayload> {
  let remoteTemplates: RoutineTemplate[];

  try {
    remoteTemplates = await readRemoteTemplates(true);
  } catch (error) {
    if (
      error &&
      typeof error === 'object' &&
      'message' in error &&
      typeof error.message === 'string' &&
      error.message.includes('difficulty')
    ) {
      remoteTemplates = await readRemoteTemplates(false);
    } else {
      throw new Error(
        `추천 템플릿을 가져오지 못했습니다. ${error instanceof Error ? error.message : '알 수 없는 오류'}`
      );
    }
  }

  return {
    fetchedAt: new Date().toISOString(),
    templates: remoteTemplates
  };
}

function getInitialTemplateState(): Pick<
  RecommendationTemplatesContextValue,
  'templates' | 'source' | 'lastSyncedAt' | 'error'
> {
  const cached = readTemplateCache();

  if (cached) {
    return {
      templates: mergeTemplates(cached.templates),
      source: 'cache',
      lastSyncedAt: cached.fetchedAt,
      error: null
    };
  }

  return {
    templates: defaultRoutineTemplates,
    source: 'local',
    lastSyncedAt: null,
    error: null
  };
}

export function RecommendationTemplatesProvider({ children }: { children: ReactNode }) {
  const initialState = getInitialTemplateState();
  const [templates, setTemplates] = useState(initialState.templates);
  const [source, setSource] = useState<TemplateSource>(initialState.source);
  const [lastSyncedAt, setLastSyncedAt] = useState<string | null>(initialState.lastSyncedAt);
  const [error, setError] = useState<string | null>(initialState.error);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const isRemoteConfigured = isSupabaseConfigured();

  async function refreshTemplates() {
    if (!isRemoteConfigured) {
      setError(null);
      return;
    }

    setIsRefreshing(true);

    try {
      const payload = await fetchRemoteTemplates();
      writeTemplateCache(payload);

      startTransition(() => {
        setTemplates(mergeTemplates(payload.templates));
        setSource('remote');
        setLastSyncedAt(payload.fetchedAt);
        setError(null);
      });
    } catch (refreshError) {
      setError(refreshError instanceof Error ? refreshError.message : '추천 템플릿 동기화에 실패했습니다.');
    } finally {
      setIsRefreshing(false);
    }
  }

  useEffect(() => {
    if (!isRemoteConfigured) {
      return;
    }

    void refreshTemplates();

    const intervalId = window.setInterval(() => {
      void refreshTemplates();
    }, getRefreshIntervalMs());

    return () => {
      window.clearInterval(intervalId);
    };
  }, [isRemoteConfigured]);

  return (
    <RecommendationTemplatesContext.Provider
      value={{
        templates,
        source,
        isRefreshing,
        lastSyncedAt,
        error,
        isRemoteConfigured,
        refreshTemplates
      }}
    >
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
