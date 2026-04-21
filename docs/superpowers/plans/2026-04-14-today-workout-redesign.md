# 오늘운동 리디자인 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `오늘운동`을 `웨이트 / 맨몸운동 / 유산소` 3카테고리와 `세트형 / 유산소형` 기록 엔진으로 재구성하고, 아이폰 17 Pro Max 사파리 기준으로 스크롤을 줄인 모바일 UI를 완성한다.

**Architecture:** 데이터 모델은 `category`와 `recordMode`를 분리한 순수 함수 계층으로 먼저 정리하고, Dexie 마이그레이션과 저장소 계층이 그 모델을 사용하도록 맞춘다. UI는 기존 페이지 구조를 유지하되, 큰 파일에서 순수 helper를 추출해 테스트 가능한 경계로 나누고, 공통 헤더/카드/카피를 `오늘운동` 브랜드 기준으로 한글화한다.

**Tech Stack:** React, TypeScript, Vite, Dexie, React Router, Vitest, Testing Library

---

## File Structure

### Core domain and persistence

- Create: `src/lib/workoutModel.ts`
  - 운동 카테고리, 기록 방식, 루틴 요약, 하위 호환 변환을 담당하는 순수 함수 모듈
- Modify: `src/lib/types.ts`
  - `category`, `recordMode`, 세트형/유산소형 plan/record 타입으로 재정의
- Modify: `src/lib/db.ts`
  - Dexie v4 스키마와 기존 데이터 마이그레이션
- Modify: `src/lib/repository.ts`
  - 저장 시 새 타입을 정규화하고 세션 상태를 `recordMode` 기준으로 계산

### Catalog and derived copy

- Modify: `src/data/catalog.ts`
  - 운동 카탈로그와 스타터 템플릿을 `유산소` 중심 모델로 변환
- Modify: `src/lib/exercise.ts`
  - 카테고리/입력 방식에 맞는 한글 라벨과 요약 문구
- Modify: `src/lib/recommendations.ts`
  - `유산소` 추천 문구와 새 카테고리 분기
- Modify: `src/lib/stats.ts`
  - 주간 인사이트를 `웨이트 / 맨몸운동 / 유산소` 기준으로 재계산
- Modify: `src/lib/sessionStatus.ts`
  - 더 짧은 한글 상태 라벨

### UI and page flows

- Modify: `src/components/AppShell.tsx`
  - 서비스 이름 `오늘운동` 반영, 헤더/탭 카피 축약
- Modify: `src/components/SectionCard.tsx`
  - 카드 헤더 밀도 재조정
- Create: `src/features/routines/routineEditorModel.ts`
  - 루틴 편집기의 카테고리별 입력 모델 helper
- Create: `src/features/today/sessionModel.ts`
  - 세션 기록용 기본값/복사/진행률 helper
- Modify: `src/features/setup/SetupPage.tsx`
  - 온보딩 카테고리 3분할과 스타터 루틴 요약
- Modify: `src/features/routines/RoutinesPage.tsx`
  - 카테고리 우선 편집 구조와 중량 표시 조건
- Modify: `src/features/today/SessionPage.tsx`
  - 세트형/유산소형 입력 UI 분리
- Modify: `src/features/today/TodayPage.tsx`
  - 요약 카드와 최근 기록 압축
- Modify: `src/features/history/HistoryPage.tsx`
  - 기간/지표 카드와 목록 밀도 축소
- Modify: `src/features/history/SessionDetailPage.tsx`
  - 운동별 결과 카드를 새 카테고리와 짧은 라벨에 맞게 정리
- Modify: `src/features/settings/SettingsPage.tsx`
  - 경고/백업 문구 축약과 스크롤 감소
- Modify: `src/styles/app.css`
  - 헤더/카드/텍스트 줄 길이/안전영역 기준 재조정
- Modify: `README.md`
  - 서비스 이름과 카테고리 설명 갱신

### Tests and tooling

- Modify: `package.json`
  - `test` script와 test devDependencies 추가
- Create: `vitest.config.ts`
- Create: `src/test/setup.ts`
- Create: `src/lib/workoutModel.test.ts`
- Create: `src/data/catalog.test.ts`
- Create: `src/components/AppShell.test.tsx`
- Create: `src/features/today/sessionModel.test.ts`

---

### Task 1: Add test harness for domain and component work

**Files:**
- Modify: `package.json`
- Create: `vitest.config.ts`
- Create: `src/test/setup.ts`

- [ ] **Step 1: Add test scripts and dependencies**

Update `package.json` with explicit test tooling:

```json
{
  "scripts": {
    "dev": "vite",
    "build": "tsc -b && vite build",
    "preview": "vite preview",
    "test": "vitest",
    "test:run": "vitest run --passWithNoTests"
  },
  "devDependencies": {
    "@testing-library/jest-dom": "^6.6.3",
    "@testing-library/react": "^16.0.1",
    "@testing-library/user-event": "^14.5.2",
    "@types/react": "latest",
    "@types/react-dom": "latest",
    "@vitejs/plugin-react": "latest",
    "jsdom": "^25.0.1",
    "typescript": "latest",
    "vite": "latest",
    "vite-plugin-pwa": "latest",
    "vitest": "^2.1.1"
  }
}
```

- [ ] **Step 2: Install dependencies**

Run: `npm install`

Expected: install completes and `vitest` becomes available in `node_modules/.bin`

- [ ] **Step 3: Add Vitest config and setup**

Create `vitest.config.ts`:

```ts
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: true
  }
});
```

Create `src/test/setup.ts`:

```ts
import '@testing-library/jest-dom/vitest';
```

- [ ] **Step 4: Verify the test runner boots**

Run: `npm run test:run`

Expected: exits successfully with `No test files found` or runs zero tests without crashing

- [ ] **Step 5: Commit**

```bash
git add package.json package-lock.json vitest.config.ts src/test/setup.ts
git commit -m "test: add vitest and testing library setup"
```

### Task 2: Define the new workout model and prove category logic with tests

**Files:**
- Create: `src/lib/workoutModel.ts`
- Create: `src/lib/workoutModel.test.ts`
- Modify: `src/lib/types.ts`

- [ ] **Step 1: Write the failing tests for category and summary helpers**

Create `src/lib/workoutModel.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import {
  deriveRoutineCategorySummary,
  getExerciseCategoryLabel,
  inferLegacyExerciseCategory,
  shouldShowWeightField
} from './workoutModel';

describe('workoutModel', () => {
  it('maps legacy bodyweight strength entries to the bodyweight category', () => {
    expect(inferLegacyExerciseCategory('strength', 'bodyweight', 'push-up')).toBe('bodyweight');
  });

  it('maps legacy running entries to the cardio category', () => {
    expect(inferLegacyExerciseCategory('running', 'running', 'easy-run')).toBe('cardio');
  });

  it('returns Korean category labels', () => {
    expect(getExerciseCategoryLabel('weight')).toBe('웨이트');
    expect(getExerciseCategoryLabel('bodyweight')).toBe('맨몸운동');
    expect(getExerciseCategoryLabel('cardio')).toBe('유산소');
  });

  it('shows weight fields only for weight set-based items', () => {
    expect(shouldShowWeightField('weight', 'sets')).toBe(true);
    expect(shouldShowWeightField('bodyweight', 'sets')).toBe(false);
    expect(shouldShowWeightField('cardio', 'cardio')).toBe(false);
  });

  it('summarizes mixed routines without legacy kind strings', () => {
    expect(
      deriveRoutineCategorySummary([
        { category: 'weight', recordMode: 'sets' },
        { category: 'bodyweight', recordMode: 'sets' },
        { category: 'cardio', recordMode: 'cardio' }
      ])
    ).toBe('웨이트 · 맨몸운동 · 유산소');
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npm run test:run -- src/lib/workoutModel.test.ts`

Expected: FAIL because `src/lib/workoutModel.ts` does not exist yet

- [ ] **Step 3: Implement the new core helpers and types**

Create `src/lib/workoutModel.ts`:

```ts
export type ExerciseCategory = 'weight' | 'bodyweight' | 'cardio';
export type RecordMode = 'sets' | 'cardio';
export type LegacyExerciseKind = 'strength' | 'running';
export type ExerciseEquipment =
  | 'barbell'
  | 'dumbbell'
  | 'kettlebell'
  | 'bodyweight'
  | 'machine'
  | 'cable'
  | 'running';

const categoryLabelMap: Record<ExerciseCategory, string> = {
  weight: '웨이트',
  bodyweight: '맨몸운동',
  cardio: '유산소'
};

export function inferLegacyExerciseCategory(
  kind: LegacyExerciseKind,
  equipment?: ExerciseEquipment,
  name?: string
): ExerciseCategory {
  if (kind === 'running') {
    return 'cardio';
  }

  if (equipment === 'bodyweight') {
    return 'bodyweight';
  }

  if (name?.toLowerCase().includes('plank') || name?.toLowerCase().includes('push')) {
    return 'bodyweight';
  }

  return 'weight';
}

export function getExerciseCategoryLabel(category: ExerciseCategory): string {
  return categoryLabelMap[category];
}

export function shouldShowWeightField(category: ExerciseCategory, recordMode: RecordMode): boolean {
  return category === 'weight' && recordMode === 'sets';
}

export function deriveRoutineCategorySummary(
  items: Array<Pick<{ category: ExerciseCategory; recordMode: RecordMode }, 'category' | 'recordMode'>>
): string {
  const categories = [...new Set(items.map((item) => item.category))];
  return categories.map((category) => categoryLabelMap[category]).join(' · ');
}
```

Update `src/lib/types.ts` so the primary model uses the new fields:

```ts
export type ExerciseCategory = 'weight' | 'bodyweight' | 'cardio';
export type RecordMode = 'sets' | 'cardio';

export interface Profile {
  id: 'local-profile';
  workoutTypes: ExerciseCategory[];
  workoutsPerWeek: number;
  weeklyGoalCount: number;
  units: {
    weight: 'kg';
    distance: 'km';
  };
  onboardingDone: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface Exercise {
  id: string;
  name: string;
  category: ExerciseCategory;
  recordMode: RecordMode;
  muscleGroup?: 'chest' | 'back' | 'legs' | 'shoulders' | 'arms' | 'core';
  equipment?: 'barbell' | 'dumbbell' | 'kettlebell' | 'bodyweight' | 'machine' | 'cable' | 'running';
  guide?: ExerciseGuide;
  isCustom: boolean;
  createdAt: string;
}

export interface SetBasedPlan {
  id: string;
  category: 'weight' | 'bodyweight';
  recordMode: 'sets';
  exerciseId: string;
  order: number;
  sets: number;
  targetReps: number;
  restSeconds?: number;
  targetWeightKg?: number;
  note?: string;
}

export interface CardioPlan {
  id: string;
  category: 'cardio';
  recordMode: 'cardio';
  exerciseId: string;
  order: number;
  targetActivityLabel: string;
  targetDistanceKm?: number;
  targetDurationMin?: number;
  targetPaceMinPerKm?: number;
  note?: string;
}

export type RoutineDraftItem = SetBasedPlan | CardioPlan;

export interface SetBasedRecord {
  id: string;
  category: 'weight' | 'bodyweight';
  recordMode: 'sets';
  exerciseId: string;
  routineItemId?: string;
  order: number;
  sets: StrengthSetRecord[];
  note?: string;
}

export interface CardioRecord {
  id: string;
  category: 'cardio';
  recordMode: 'cardio';
  exerciseId: string;
  routineItemId?: string;
  order: number;
  activityLabel: string;
  distanceKm?: number;
  durationMin?: number;
  avgPaceMinPerKm?: number;
  note?: string;
}

export type WorkoutRecordItem = SetBasedRecord | CardioRecord;
```

Compatibility guardrail for this task:

- Keep `category` and `recordMode` as the new primary fields.
- Preserve temporary legacy compatibility in `src/lib/types.ts` where needed so the repository still compiles before later migration tasks update all callers.
- Existing callers that still discriminate on `kind` may continue to type-check at this stage through compatibility fields or compatibility unions, as long as the new model fields are present for the migrated path.
- This temporary compatibility may also apply to `workoutTypes` and similar selection unions until onboarding and repository callers migrate in later tasks.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `npm run test:run -- src/lib/workoutModel.test.ts`

Expected: PASS with 5 passing tests

- [ ] **Step 5: Commit**

```bash
git add src/lib/types.ts src/lib/workoutModel.ts src/lib/workoutModel.test.ts
git commit -m "feat: add workout category and record mode model"
```

### Task 3: Migrate Dexie storage and repository normalization to the new model

**Files:**
- Modify: `src/lib/db.ts`
- Modify: `src/lib/repository.ts`
- Modify: `src/lib/workoutModel.ts`
- Modify: `src/lib/workoutModel.test.ts`

- [ ] **Step 1: Write the failing migration and normalization tests**

Extend `src/lib/workoutModel.test.ts`:

```ts
import { migrateLegacyExercise, normalizeRoutineItem, normalizeWorkoutRecordItem } from './workoutModel';

it('migrates legacy exercises into category and recordMode fields', () => {
  expect(
    migrateLegacyExercise({
      id: 'push-up',
      name: 'Push Up',
      kind: 'strength',
      equipment: 'bodyweight',
      isCustom: false,
      createdAt: '2026-01-01T00:00:00.000Z'
    }).category
  ).toBe('bodyweight');
});

it('normalizes cardio items with a default activity label', () => {
  expect(
    normalizeRoutineItem({
      id: 'plan-1',
      category: 'cardio',
      recordMode: 'cardio',
      exerciseId: 'easy-run',
      order: 1,
      targetDurationMin: 20
    }).targetActivityLabel
  ).toBe('유산소');
});

it('keeps bodyweight records free of actual weight values', () => {
  const result = normalizeWorkoutRecordItem({
    id: 'record-1',
    category: 'bodyweight',
    recordMode: 'sets',
    exerciseId: 'push-up',
    order: 1,
    sets: [{ order: 1, actualReps: 12, completed: true, actualWeightKg: 0 }]
  });

  expect(result.sets[0].actualWeightKg).toBeUndefined();
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npm run test:run -- src/lib/workoutModel.test.ts`

Expected: FAIL because the new migration helpers are not implemented yet

- [ ] **Step 3: Implement migration helpers and Dexie v4**

Add pure helpers in `src/lib/workoutModel.ts`:

```ts
export function migrateLegacyExercise(
  exercise: Exercise & { kind?: LegacyExerciseKind }
): Exercise {
  if (exercise.category && exercise.recordMode) {
    return exercise;
  }

  const category = inferLegacyExerciseCategory(
    exercise.kind ?? 'strength',
    exercise.equipment,
    exercise.name
  );

  return {
    ...exercise,
    category,
    recordMode: category === 'cardio' ? 'cardio' : 'sets'
  };
}

export function normalizeRoutineItem(item: RoutineDraftItem): RoutineDraftItem {
  if (item.recordMode === 'cardio') {
    return {
      ...item,
      targetActivityLabel: item.targetActivityLabel?.trim() || '유산소'
    };
  }

  return {
    ...item,
    targetWeightKg: shouldShowWeightField(item.category, item.recordMode) ? item.targetWeightKg ?? 0 : undefined
  };
}

export function normalizeWorkoutRecordItem(item: WorkoutRecordItem): WorkoutRecordItem {
  if (item.recordMode === 'cardio') {
    return item;
  }

  return {
    ...item,
    sets: item.sets.map((set) => ({
      ...set,
      actualWeightKg: item.category === 'weight' ? set.actualWeightKg : undefined,
      plannedWeightKg: item.category === 'weight' ? set.plannedWeightKg : undefined
    }))
  };
}
```

Update `src/lib/db.ts` with a v4 migration:

```ts
this.version(4)
  .stores({
    profile: 'id, onboardingDone, updatedAt',
    exercises: 'id, category, recordMode, muscleGroup, name',
    routines: 'id, isActive, updatedAt',
    sessions: 'id, sessionDate, status, routineId, createdAt',
    healthEntries: 'id, recordDate, updatedAt'
  })
  .upgrade(async (transaction) => {
    await transaction.table('exercises').toCollection().modify((exercise) => {
      Object.assign(exercise, migrateLegacyExercise(exercise));
      delete (exercise as { kind?: unknown }).kind;
    });

    await transaction.table('routines').toCollection().modify((routine) => {
      routine.items = routine.items.map(normalizeRoutineItem);
    });

    await transaction.table('sessions').toCollection().modify((session) => {
      session.items = session.items.map(normalizeWorkoutRecordItem);
    });
  });
```

Update `src/lib/repository.ts` so every save path normalizes through the helper:

```ts
const items = input.items.map((item, index) =>
  normalizeRoutineItem({
    ...item,
    id: item.id || createId('plan'),
    order: index + 1
  })
);

function deriveSessionStatus(items: WorkoutRecordItem[]): WorkoutSessionStatus {
  const completedItems = items.filter((item) =>
    item.recordMode === 'sets'
      ? item.sets.length > 0 && item.sets.every((set) => set.completed)
      : Boolean(item.activityLabel || item.durationMin)
  ).length;

  if (completedItems === 0) return 'skipped';
  if (completedItems === items.length) return 'completed';
  return 'partial';
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `npm run test:run -- src/lib/workoutModel.test.ts`

Expected: PASS with migration and normalization cases green

- [ ] **Step 5: Commit**

```bash
git add src/lib/db.ts src/lib/repository.ts src/lib/workoutModel.ts src/lib/workoutModel.test.ts
git commit -m "feat: migrate storage and repository to the new workout model"
```

### Task 4: Convert the catalog, templates, and derived copy to the new categories

**Files:**
- Create: `src/data/catalog.test.ts`
- Modify: `src/data/catalog.ts`
- Modify: `src/lib/exercise.ts`
- Modify: `src/lib/recommendations.ts`
- Modify: `src/lib/stats.ts`
- Modify: `src/lib/sessionStatus.ts`

- [ ] **Step 1: Write the failing tests for starter templates and Korean labels**

Create `src/data/catalog.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { getStarterTemplateIds } from './catalog';

describe('catalog starter templates', () => {
  it('returns cardio-first templates when the user only selects cardio', () => {
    expect(
      getStarterTemplateIds({
        workoutTypes: ['cardio'],
        workoutsPerWeek: 3,
        starterMode: 'recommended',
        starterDifficulty: 'beginner'
      })
    ).toEqual(['template-easy-run']);
  });

  it('returns mixed templates when bodyweight and cardio are selected together', () => {
    expect(
      getStarterTemplateIds({
        workoutTypes: ['bodyweight', 'cardio'],
        workoutsPerWeek: 4,
        starterMode: 'recommended',
        starterDifficulty: 'beginner'
      })
    ).toContain('template-bodyweight-foundation');
  });
});
```

Add one more assertion to `src/lib/workoutModel.test.ts`:

```ts
it('labels cardio copy as 유산소 instead of 러닝', () => {
  expect(getExerciseCategoryLabel('cardio')).toBe('유산소');
});
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `npm run test:run -- src/data/catalog.test.ts src/lib/workoutModel.test.ts`

Expected: FAIL because `getStarterTemplateIds` still expects `strength/running`

- [ ] **Step 3: Update catalog helpers and derived copy**

In `src/data/catalog.ts`, rename the cardio helper and starter pool:

```ts
function cardioItem(
  exerciseId: string,
  order: number,
  targetDistanceKm: number,
  targetDurationMin: number,
  note?: string,
  targetActivityLabel = '달리기'
): RoutineDraftItem {
  return {
    id: createId('plan'),
    category: 'cardio',
    recordMode: 'cardio',
    exerciseId,
    order,
    targetActivityLabel,
    targetDistanceKm,
    targetDurationMin,
    targetPaceMinPerKm: targetDistanceKm > 0 ? Number((targetDurationMin / targetDistanceKm).toFixed(1)) : undefined,
    note
  };
}

const starterTemplatePool: Record<
  'weight-bodyweight-cardio' | 'strength-family' | 'cardio',
  Record<RoutineDifficulty, string[]>
> = {
  'weight-bodyweight-cardio': { ... },
  'strength-family': { ... },
  cardio: { ... }
};
```

Update `src/lib/exercise.ts`:

```ts
export function getExerciseKindLabel(exercise: Exercise): string {
  return getExerciseCategoryLabel(exercise.category);
}

export function getExerciseSummary(exercise: Exercise): string {
  if (exercise.guide?.headline) return exercise.guide.headline;
  if (exercise.recordMode === 'cardio') return '종류, 거리, 시간, 페이스를 기준으로 유산소 루틴을 기록할 수 있어요.';
  if (exercise.category === 'bodyweight') return '세트, 횟수, 휴식을 기준으로 맨몸운동 루틴을 기록할 수 있어요.';
  return '세트, 횟수, 중량, 휴식을 기준으로 웨이트 루틴을 기록할 수 있어요.';
}
```

Update `src/lib/recommendations.ts` and `src/lib/stats.ts` to branch on `category` and `recordMode`, replacing all visible `러닝` copy with `유산소`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `npm run test:run -- src/data/catalog.test.ts src/lib/workoutModel.test.ts`

Expected: PASS with starter template selection and Korean category copy green

- [ ] **Step 5: Commit**

```bash
git add src/data/catalog.ts src/data/catalog.test.ts src/lib/exercise.ts src/lib/recommendations.ts src/lib/stats.ts src/lib/sessionStatus.ts
git commit -m "feat: convert templates and derived copy to weight bodyweight cardio"
```

### Task 5: Rebrand the app shell and onboarding around 오늘운동

**Files:**
- Create: `src/components/AppShell.test.tsx`
- Modify: `src/components/AppShell.tsx`
- Modify: `src/features/setup/SetupPage.tsx`
- Modify: `src/App.tsx`
- Modify: `README.md`

- [ ] **Step 1: Write the failing branding test**

Create `src/components/AppShell.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react';
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
    expect(screen.getByText('오늘')).toBeInTheDocument();
    expect(screen.getByText('루틴')).toBeInTheDocument();
    expect(screen.getByText('기록')).toBeInTheDocument();
    expect(screen.getByText('설정')).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npm run test:run -- src/components/AppShell.test.tsx`

Expected: FAIL because the header still renders `한신의 운동기록` and legacy subtitle copy

- [ ] **Step 3: Update the app shell, onboarding copy, and README**

Update `src/components/AppShell.tsx`:

```tsx
const titleMap = [
  { match: '/setup', title: '오늘운동', subtitle: '나에게 맞는 운동 흐름을 시작해요' },
  { match: '/today/session/', title: '오늘 기록', subtitle: '지금 한 운동만 간단히 남겨요' },
  { match: '/today', title: '오늘', subtitle: '오늘 할 운동과 최근 흐름을 한 번에 봐요' },
  { match: '/routines', title: '루틴', subtitle: '웨이트, 맨몸운동, 유산소 루틴을 정리해요' },
  { match: '/history/', title: '상세 기록', subtitle: '지난 운동을 짧고 선명하게 확인해요' },
  { match: '/history', title: '기록', subtitle: '꾸준함과 변화를 빠르게 확인해요' },
  { match: '/settings', title: '설정', subtitle: '백업과 보관 상태를 관리해요' }
];

<div className="app-header__badge">오늘운동</div>
```

Update `src/features/setup/SetupPage.tsx`:

```tsx
const workoutTypeOptions = [
  { label: '웨이트', value: 'weight', note: '세트, 횟수, 중량 중심' },
  { label: '맨몸운동', value: 'bodyweight', note: '세트, 횟수, 휴식 중심' },
  { label: '유산소', value: 'cardio', note: '종류, 거리, 시간, 페이스 중심' }
];

const [workoutTypes, setWorkoutTypes] = useState<ExerciseCategory[]>(['weight', 'bodyweight', 'cardio']);
```

Update `README.md` heading and summary:

```md
# 오늘운동

개인용 운동 루틴과 기록을 모바일에서 짧고 빠르게 남기는 웹앱입니다.
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `npm run test:run -- src/components/AppShell.test.tsx`

Expected: PASS with the `오늘운동` brand visible

- [ ] **Step 5: Commit**

```bash
git add src/components/AppShell.tsx src/components/AppShell.test.tsx src/features/setup/SetupPage.tsx src/App.tsx README.md
git commit -m "feat: rebrand onboarding and app shell to 오늘운동"
```

### Task 6: Extract routine and session helpers, then refactor editors around category-specific forms

**Files:**
- Create: `src/features/today/sessionModel.ts`
- Create: `src/features/today/sessionModel.test.ts`
- Create: `src/features/routines/routineEditorModel.ts`
- Modify: `src/features/today/SessionPage.tsx`
- Modify: `src/features/routines/RoutinesPage.tsx`

- [ ] **Step 1: Write the failing session helper tests**

Create `src/features/today/sessionModel.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { buildSessionItemFromExercise, getWorkoutProgressCopy } from './sessionModel';

describe('sessionModel', () => {
  it('creates bodyweight session items without weight defaults', () => {
    const item = buildSessionItemFromExercise(
      {
        id: 'push-up',
        name: '푸시업',
        category: 'bodyweight',
        recordMode: 'sets',
        equipment: 'bodyweight',
        isCustom: false,
        createdAt: '2026-01-01T00:00:00.000Z'
      },
      [],
      1
    );

    expect(item.recordMode).toBe('sets');
    expect(item.category).toBe('bodyweight');
    expect(item.sets[0].actualWeightKg).toBeUndefined();
  });

  it('creates cardio session items with an activity label and duration default', () => {
    const item = buildSessionItemFromExercise(
      {
        id: 'easy-run',
        name: '가벼운 달리기',
        category: 'cardio',
        recordMode: 'cardio',
        equipment: 'running',
        isCustom: false,
        createdAt: '2026-01-01T00:00:00.000Z'
      },
      [],
      1
    );

    expect(item.activityLabel).toBe('가벼운 달리기');
    expect(item.durationMin).toBe(20);
  });

  it('shows compact progress copy for cardio items', () => {
    expect(
      getWorkoutProgressCopy({
        id: 'record-1',
        category: 'cardio',
        recordMode: 'cardio',
        exerciseId: 'easy-run',
        order: 1,
        activityLabel: '달리기',
        durationMin: 24,
        distanceKm: 4
      })
    ).toBe('달리기 · 4km · 24분');
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npm run test:run -- src/features/today/sessionModel.test.ts`

Expected: FAIL because `sessionModel.ts` does not exist yet

- [ ] **Step 3: Extract model helpers and refactor the pages**

Create `src/features/today/sessionModel.ts`:

```ts
import { createId } from '../../lib/id';
import { getStrengthCarryover } from '../../lib/workoutRecord';
import type { Exercise, WorkoutSession, WorkoutRecordItem } from '../../lib/types';

export function buildSessionItemFromExercise(
  exercise: Exercise,
  sessions: WorkoutSession[],
  order: number
): WorkoutRecordItem {
  const previousRecord = sessions
    .flatMap((session) => session.items)
    .find((record) => record.exerciseId === exercise.id && record.recordMode === exercise.recordMode);

  if (exercise.recordMode === 'sets') {
    const carryover = getStrengthCarryover(previousRecord);
    return {
      id: createId('record'),
      category: exercise.category,
      recordMode: 'sets',
      exerciseId: exercise.id,
      order,
      sets: Array.from({ length: Math.max(carryover?.setCount ?? 0, 3) }, (_, index) => ({
        order: index + 1,
        plannedReps: carryover?.reps ?? 10,
        actualReps: carryover?.reps ?? 10,
        plannedWeightKg: exercise.category === 'weight' ? carryover?.weightKg ?? 0 : undefined,
        actualWeightKg: exercise.category === 'weight' ? carryover?.weightKg ?? 0 : undefined,
        completed: false
      }))
    };
  }

  return {
    id: createId('record'),
    category: 'cardio',
    recordMode: 'cardio',
    exerciseId: exercise.id,
    order,
    activityLabel: exercise.name,
    durationMin: previousRecord && previousRecord.recordMode === 'cardio' ? previousRecord.durationMin ?? 20 : 20,
    distanceKm: previousRecord && previousRecord.recordMode === 'cardio' ? previousRecord.distanceKm : undefined,
    avgPaceMinPerKm: previousRecord && previousRecord.recordMode === 'cardio' ? previousRecord.avgPaceMinPerKm : undefined
  };
}
```

Create `src/features/routines/routineEditorModel.ts` with `toEditorItems`, `toDraftItems`, and `buildEditorItemFromExercise` so `RoutinesPage.tsx` can hide weight inputs for `bodyweight` and show `activityLabel` for `cardio`.

Update `src/features/today/SessionPage.tsx` and `src/features/routines/RoutinesPage.tsx` to import the extracted helpers and branch on `recordMode` instead of `kind`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `npm run test:run -- src/features/today/sessionModel.test.ts`

Expected: PASS with bodyweight and cardio defaults behaving correctly

- [ ] **Step 5: Commit**

```bash
git add src/features/today/sessionModel.ts src/features/today/sessionModel.test.ts src/features/today/SessionPage.tsx src/features/routines/routineEditorModel.ts src/features/routines/RoutinesPage.tsx
git commit -m "feat: refactor editors around category-specific workout forms"
```

### Task 7: Condense the major pages and Korean copy for iPhone 17 Pro Max Safari

**Files:**
- Modify: `src/features/today/TodayPage.tsx`
- Modify: `src/features/history/HistoryPage.tsx`
- Modify: `src/features/history/SessionDetailPage.tsx`
- Modify: `src/features/settings/SettingsPage.tsx`
- Modify: `src/components/SectionCard.tsx`
- Modify: `src/styles/app.css`

- [ ] **Step 1: Write the failing shell density test**

Extend `src/components/AppShell.test.tsx`:

```tsx
it('keeps the header copy short enough for compact mobile layouts', () => {
  render(
    <MemoryRouter initialEntries={['/today']}>
      <AppShell>
        <div>내용</div>
      </AppShell>
    </MemoryRouter>
  );

  expect(screen.getByText('오늘')).toBeInTheDocument();
  expect(screen.getByText('오늘 할 운동과 최근 흐름을 한 번에 봐요')).toBeInTheDocument();
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npm run test:run -- src/components/AppShell.test.tsx`

Expected: FAIL if the old long subtitle copy or old brand strings are still present

- [ ] **Step 3: Update page structure and CSS**

Adjust `src/styles/app.css` to reduce header height and line length:

```css
:root {
  --device-max-width: 456px;
  --shell-inline: clamp(14px, 3vw, 18px);
  --nav-offset: calc(78px + var(--safe-bottom) + 10px);
}

.app-header {
  padding: 4px 2px 12px;
}

.app-header h1 {
  margin-top: 0.55rem;
  font-size: clamp(1.9rem, 6vw, 2.4rem);
  max-inline-size: 8ch;
}

.app-header p,
.panel p,
.hero-card > p,
.muted-copy,
.lead-copy {
  max-inline-size: 26ch;
  line-height: 1.4;
}

.page-stack,
.setup-stack,
.stack-list {
  gap: 12px;
}

.panel,
.hero-card,
.loading-screen {
  padding: 16px;
}
```

Update the page copy and section ordering so the first screen shows action-first cards, for example in `src/features/today/TodayPage.tsx`:

```tsx
<section className="hero-card hero-card--compact">
  <div className="hero-card__label">{formatKoreanDate(new Date())}</div>
  <h2>오늘 할 운동 {todayRoutine ? '1개' : '0개'}</h2>
  <p>{todayRoutine ? '바로 시작하고 끝난 뒤 짧게 기록해요.' : '오늘은 회복이나 가벼운 움직임으로 흐름을 이어가요.'}</p>
</section>
```

Update `src/features/history/SessionDetailPage.tsx` so visible labels read `웨이트`, `맨몸운동`, `유산소` based on `item.category`, and compact the cardio detail row to `종류 · 거리 · 시간 · 페이스`.

Update `src/features/settings/SettingsPage.tsx` so warning cards lead with one sentence and one next action, keeping each card under roughly two short lines of body copy.

- [ ] **Step 4: Run tests and build**

Run:

```bash
npm run test:run -- src/components/AppShell.test.tsx src/features/today/sessionModel.test.ts src/lib/workoutModel.test.ts src/data/catalog.test.ts
npm run build
```

Expected:

- Vitest: PASS across all four test files
- Build: PASS with Vite production bundle generated

- [ ] **Step 5: Commit**

```bash
git add src/features/today/TodayPage.tsx src/features/history/HistoryPage.tsx src/features/history/SessionDetailPage.tsx src/features/settings/SettingsPage.tsx src/components/SectionCard.tsx src/styles/app.css
git commit -m "feat: condense mobile pages and Korean copy for 오늘운동"
```

### Task 8: Final regression sweep, docs cleanup, and manual mobile verification

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-04-14-mobile-ui-and-workout-model-redesign-design.md`
- Modify: any touched files from previous tasks if regression fixes are needed

- [ ] **Step 1: Write the manual regression checklist into the README**

Append a short verification section to `README.md`:

```md
## 리디자인 확인 항목

- 오늘 / 루틴 / 기록 / 설정 첫 화면에서 핵심 카드가 바로 보이는지 확인
- 맨몸운동 루틴에서 중량 입력이 보이지 않는지 확인
- 유산소 기록에서 종류와 시간이 기본 입력으로 보이는지 확인
- 기존 기록이 깨지지 않고 열리는지 확인
```

- [ ] **Step 2: Run the full automated regression**

Run:

```bash
npm run test:run
npm run build
```

Expected:

- All tests PASS
- Production build PASS

- [ ] **Step 3: Run the manual mobile verification**

Run:

```bash
npm run dev -- --host 127.0.0.1 --port 5173
```

Then verify manually at `http://127.0.0.1:5173/`:

- 온보딩에서 `웨이트 / 맨몸운동 / 유산소` 선택이 보이는지
- 세션 입력에서 맨몸운동은 중량 없이 저장되는지
- 유산소는 `종류 / 거리 / 시간 / 페이스` 입력이 보이는지
- 오늘, 기록, 설정 화면의 첫 카드가 아이폰 17 Pro Max 폭에서 바로 보이는지
- 영어 라벨이 눈에 띄게 남지 않았는지

- [ ] **Step 4: Fix any regression found during verification**

If a regression is found, patch the smallest relevant file and re-run:

```bash
npm run test:run
npm run build
```

Expected: PASS after the regression fix

- [ ] **Step 5: Commit**

```bash
git add README.md
git commit -m "chore: finalize 오늘운동 redesign verification"
```

---

## Self-Review

### Spec coverage

- `웨이트 / 맨몸운동 / 유산소` 3카테고리: Task 2, 4, 5, 6, 7
- `세트형 / 유산소형` 기록 엔진 분리: Task 2, 3, 6
- `오늘운동` 브랜드 반영: Task 5, 8
- 스크롤 최소화와 짧은 문장 원칙: Task 7
- 영어 문구 한글화: Task 4, 5, 7
- 기존 데이터 마이그레이션 보존: Task 3

### Placeholder scan

- `TBD`, `TODO`, `implement later` 없음
- 각 실행 단계에 명시적 파일 경로와 명령 포함
- 테스트 단계마다 실제 테스트 코드와 예상 실패 원인 포함

### Type consistency

- 카테고리 타입은 `weight | bodyweight | cardio`
- 기록 방식 타입은 `sets | cardio`
- 세트형 helper는 `recordMode === 'sets'`
- 유산소 helper는 `recordMode === 'cardio'`
- UI는 `kind` 대신 `category`와 `recordMode`를 기준으로 분기
