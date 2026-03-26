import { useEffect, useState } from 'react';
import { useLiveQuery } from 'dexie-react-hooks';
import { Link } from 'react-router-dom';
import { EmptyState } from '../../components/EmptyState';
import { SectionCard } from '../../components/SectionCard';
import { TrendChart } from '../../components/TrendChart';
import { db } from '../../lib/db';
import { buildWeekStrip, formatKoreanDate, toDateInput } from '../../lib/date';
import { formatNumber } from '../../lib/format';
import {
  buildHealthTrendSeries,
  buildWorkoutTrendSeries,
  getTrendDelta,
  healthMetricMeta,
  trendRangeOptions,
  type HealthTrendMetric,
  type TrendRangeDays,
  type WorkoutTrendMetric,
  workoutMetricMeta
} from '../../lib/historyTrends';
import { loadHolidayMap } from '../../lib/holidays';
import { deleteHealthEntry, saveHealthEntry } from '../../lib/repository';
import { getSessionStatusLabel } from '../../lib/sessionStatus';
import { getHistoryInsights, getSessionsThisWeek, getWeeklyCompletion, getCurrentStreak } from '../../lib/stats';
import type { HealthMetricEntry } from '../../lib/types';

const defaultHistoryRange: TrendRangeDays = 30;
const workoutMetricOptions: WorkoutTrendMetric[] = ['sessionCount', 'completedStrengthSets', 'runningDistanceKm'];
const healthMetricOptions: HealthTrendMetric[] = ['weightKg', 'skeletalMuscleKg', 'bodyFatKg', 'visceralFatLevel'];

interface HealthFormState {
  entryId: string | null;
  recordDate: string;
  weightKg: string;
  skeletalMuscleKg: string;
  bodyFatKg: string;
  visceralFatLevel: string;
}

function getHealthFieldValue(value?: number): string {
  return value === undefined ? '' : `${value}`;
}

function buildHealthForm(entry?: HealthMetricEntry | null): HealthFormState {
  return {
    entryId: entry?.id ?? null,
    recordDate: entry?.recordDate ?? toDateInput(new Date()),
    weightKg: getHealthFieldValue(entry?.weightKg),
    skeletalMuscleKg: getHealthFieldValue(entry?.skeletalMuscleKg),
    bodyFatKg: getHealthFieldValue(entry?.bodyFatKg),
    visceralFatLevel: getHealthFieldValue(entry?.visceralFatLevel)
  };
}

function parseMetricInput(value: string): number | undefined {
  const trimmed = value.trim();

  if (!trimmed) {
    return undefined;
  }

  const parsed = Number(trimmed);
  return Number.isNaN(parsed) ? undefined : parsed;
}

function formatMetricValue(value: number | null, digits: number, unit: string): string {
  if (value === null) {
    return '-';
  }

  return `${formatNumber(value, digits)}${unit}`;
}

function formatDeltaCopy(delta: number | null, digits: number, unit: string): string {
  if (delta === null) {
    return '직전 비교 데이터 없음';
  }

  if (delta === 0) {
    return '직전과 동일';
  }

  const sign = delta > 0 ? '+' : '';
  return `직전 대비 ${sign}${formatNumber(delta, digits)}${unit}`;
}

function buildHealthEntrySummary(entry: HealthMetricEntry): string {
  const values = [
    entry.weightKg !== undefined ? `체중 ${formatMetricValue(entry.weightKg, 1, 'kg')}` : null,
    entry.skeletalMuscleKg !== undefined ? `골격근량 ${formatMetricValue(entry.skeletalMuscleKg, 1, 'kg')}` : null,
    entry.bodyFatKg !== undefined ? `체지방량 ${formatMetricValue(entry.bodyFatKg, 1, 'kg')}` : null,
    entry.visceralFatLevel !== undefined
      ? `복부비만 ${formatMetricValue(entry.visceralFatLevel, 1, '레벨')}`
      : null
  ].filter((value): value is string => Boolean(value));

  return values.join(' · ');
}

export function HistoryPage() {
  const profile = useLiveQuery(() => db.profile.get('local-profile'), []);
  const sessions = useLiveQuery(() => db.sessions.orderBy('sessionDate').reverse().toArray(), []) ?? [];
  const exercises = useLiveQuery(() => db.exercises.toArray(), []) ?? [];
  const healthEntries = useLiveQuery(() => db.healthEntries.orderBy('recordDate').reverse().toArray(), []) ?? [];
  const exerciseMap = new Map(exercises.map((exercise) => [exercise.id, exercise.name]));
  const weekStrip = buildWeekStrip();
  const weekStripRangeKey = weekStrip.map((day) => day.dateKey).join('|');
  const weeklyDates = new Set(getSessionsThisWeek(sessions).map((session) => session.sessionDate));
  const insights = getHistoryInsights(sessions);
  const weeklyCompletion = getWeeklyCompletion(profile ?? null, sessions);
  const streak = getCurrentStreak(sessions);
  const [holidayMap, setHolidayMap] = useState<Record<string, string>>({});
  const [rangeDays, setRangeDays] = useState<TrendRangeDays>(defaultHistoryRange);
  const [workoutMetric, setWorkoutMetric] = useState<WorkoutTrendMetric>('sessionCount');
  const [healthMetric, setHealthMetric] = useState<HealthTrendMetric>('weightKg');
  const [healthForm, setHealthForm] = useState<HealthFormState>(() => buildHealthForm());
  const [healthMessage, setHealthMessage] = useState<string | null>(null);
  const [healthError, setHealthError] = useState<string | null>(null);
  const [isSavingHealth, setIsSavingHealth] = useState(false);
  const workoutSeries = buildWorkoutTrendSeries(sessions, workoutMetric, rangeDays);
  const healthSeries = buildHealthTrendSeries(healthEntries, healthMetric, rangeDays);
  const workoutDelta = getTrendDelta(workoutSeries);
  const healthDelta = getTrendDelta(healthSeries);
  const workoutMeta = workoutMetricMeta[workoutMetric];
  const healthMeta = healthMetricMeta[healthMetric];
  const hasWorkoutData = workoutSeries.some((point) => (point.value ?? 0) > 0);
  const hasHealthData = healthSeries.some((point) => point.value !== null);
  const selectedHealthEntry =
    healthForm.entryId ? healthEntries.find((entry) => entry.id === healthForm.entryId) ?? null : null;

  useEffect(() => {
    let active = true;

    void loadHolidayMap(weekStrip.map((day) => day.dateKey)).then((nextHolidayMap) => {
      if (active) {
        setHolidayMap(nextHolidayMap);
      }
    });

    return () => {
      active = false;
    };
  }, [weekStripRangeKey]);

  function updateHealthFormField(key: keyof Omit<HealthFormState, 'entryId'>, value: string) {
    setHealthForm((previous) => ({
      ...previous,
      [key]: value
    }));
    setHealthMessage(null);
    setHealthError(null);
  }

  function resetHealthForm() {
    setHealthForm(buildHealthForm());
    setHealthMessage(null);
    setHealthError(null);
  }

  function loadHealthEntry(entry: HealthMetricEntry) {
    setHealthForm(buildHealthForm(entry));
    setHealthMessage(null);
    setHealthError(null);
  }

  async function handleHealthSave() {
    setIsSavingHealth(true);
    setHealthMessage(null);
    setHealthError(null);

    try {
      const savedEntryId = await saveHealthEntry({
        entryId: healthForm.entryId ?? undefined,
        recordDate: healthForm.recordDate,
        weightKg: parseMetricInput(healthForm.weightKg),
        skeletalMuscleKg: parseMetricInput(healthForm.skeletalMuscleKg),
        bodyFatKg: parseMetricInput(healthForm.bodyFatKg),
        visceralFatLevel: parseMetricInput(healthForm.visceralFatLevel)
      });

      const savedEntry = await db.healthEntries.get(savedEntryId);
      setHealthForm(buildHealthForm(savedEntry));
      setHealthMessage('건강 데이터를 저장했습니다.');
    } catch (error) {
      setHealthError(error instanceof Error ? error.message : '건강 데이터 저장에 실패했습니다.');
    } finally {
      setIsSavingHealth(false);
    }
  }

  async function handleHealthDelete() {
    if (!healthForm.entryId) {
      resetHealthForm();
      return;
    }

    if (!window.confirm('이 건강 기록을 삭제할까요?')) {
      return;
    }

    setIsSavingHealth(true);
    setHealthMessage(null);
    setHealthError(null);

    try {
      await deleteHealthEntry(healthForm.entryId);
      resetHealthForm();
      setHealthMessage('건강 기록을 삭제했습니다.');
    } catch (error) {
      setHealthError(error instanceof Error ? error.message : '건강 기록 삭제에 실패했습니다.');
    } finally {
      setIsSavingHealth(false);
    }
  }

  return (
    <div className="page-stack">
      <SectionCard title="이번 주 기록">
        <div className="stat-grid">
          <div className="stat-pill">
            <span>완료 세션</span>
            <strong>{weeklyCompletion.completed}회</strong>
          </div>
          <div className="stat-pill">
            <span>주간 목표</span>
            <strong>{weeklyCompletion.goal}회</strong>
          </div>
          <div className="stat-pill">
            <span>연속 기록</span>
            <strong>{streak}일</strong>
          </div>
        </div>

        <div className="week-strip">
          {weekStrip.map((day) => (
            <div
              key={day.dateKey}
              className={`week-strip__day week-strip__day--${day.dayKey}${holidayMap[day.dateKey] ? ' is-holiday' : ''}${weeklyDates.has(day.dateKey) ? ' is-filled' : ''}${day.isToday ? ' is-today' : ''}`}
              title={holidayMap[day.dateKey] ?? undefined}
            >
              <span>{day.label}</span>
              <strong>{day.dateKey.slice(-2)}</strong>
            </div>
          ))}
        </div>
      </SectionCard>

      <SectionCard eyebrow="Range" title="추이 범위">
        <div className="chip-row">
          {trendRangeOptions.map((option) => (
            <button
              key={option}
              type="button"
              className={`chip chip--button${rangeDays === option ? ' is-active' : ''}`}
              onClick={() => setRangeDays(option)}
            >
              {option}일
            </button>
          ))}
        </div>
      </SectionCard>

      <SectionCard eyebrow="Workout Trend" title="운동 추이">
        <div className="chip-row">
          {workoutMetricOptions.map((metric) => (
            <button
              key={metric}
              type="button"
              className={`chip chip--button${workoutMetric === metric ? ' is-active' : ''}`}
              onClick={() => setWorkoutMetric(metric)}
            >
              {workoutMetricMeta[metric].label}
            </button>
          ))}
        </div>

        <div className="trend-summary-card trend-summary-card--workout">
          <div>
            <span>{workoutMeta.label}</span>
            <strong>{formatMetricValue(workoutDelta.latest, workoutMeta.digits, workoutMeta.unit)}</strong>
          </div>
          <p>{formatDeltaCopy(workoutDelta.change, workoutMeta.digits, workoutMeta.unit)}</p>
        </div>

        {hasWorkoutData ? (
          <TrendChart
            ariaLabel={`${rangeDays}일 운동 추이 그래프`}
            points={workoutSeries}
            variant="bar"
            tone="workout"
          />
        ) : (
          <p className="muted-copy chart-empty-copy">{workoutMeta.emptyLabel}</p>
        )}
      </SectionCard>

      <SectionCard eyebrow="Health Trend" title="건강 추이">
        <div className="chip-row">
          {healthMetricOptions.map((metric) => (
            <button
              key={metric}
              type="button"
              className={`chip chip--button${healthMetric === metric ? ' is-active' : ''}`}
              onClick={() => setHealthMetric(metric)}
            >
              {healthMetricMeta[metric].label}
            </button>
          ))}
        </div>

        <div className="trend-summary-card trend-summary-card--health">
          <div>
            <span>{healthMeta.label}</span>
            <strong>{formatMetricValue(healthDelta.latest, healthMeta.digits, healthMeta.unit)}</strong>
          </div>
          <p>{formatDeltaCopy(healthDelta.change, healthMeta.digits, healthMeta.unit)}</p>
        </div>

        {hasHealthData ? (
          <TrendChart
            ariaLabel={`${rangeDays}일 건강 추이 그래프`}
            points={healthSeries}
            variant="line"
            tone="health"
          />
        ) : (
          <p className="muted-copy chart-empty-copy">{healthMeta.emptyLabel}</p>
        )}
      </SectionCard>

      <SectionCard
        eyebrow="Health Entry"
        title="건강 데이터 입력"
        action={
          healthForm.entryId ? (
            <button className="ghost-button ghost-button--compact" type="button" onClick={resetHealthForm}>
              다른 날짜 입력
            </button>
          ) : null
        }
      >
        <div className="field-grid field-grid--health">
          <label className="field">
            <span>기록 날짜</span>
            <input
              type="date"
              value={healthForm.recordDate}
              onChange={(event) => updateHealthFormField('recordDate', event.currentTarget.value)}
            />
          </label>
          <label className="field">
            <span>체중 (kg)</span>
            <input
              type="number"
              min="0"
              step="0.1"
              placeholder="예: 71.4"
              value={healthForm.weightKg}
              onChange={(event) => updateHealthFormField('weightKg', event.currentTarget.value)}
            />
          </label>
          <label className="field">
            <span>골격근량 (kg)</span>
            <input
              type="number"
              min="0"
              step="0.1"
              placeholder="예: 32.8"
              value={healthForm.skeletalMuscleKg}
              onChange={(event) => updateHealthFormField('skeletalMuscleKg', event.currentTarget.value)}
            />
          </label>
          <label className="field">
            <span>체지방량 (kg)</span>
            <input
              type="number"
              min="0"
              step="0.1"
              placeholder="예: 13.2"
              value={healthForm.bodyFatKg}
              onChange={(event) => updateHealthFormField('bodyFatKg', event.currentTarget.value)}
            />
          </label>
          <label className="field">
            <span>복부비만레벨</span>
            <input
              type="number"
              min="0"
              step="0.1"
              placeholder="예: 6.0"
              value={healthForm.visceralFatLevel}
              onChange={(event) => updateHealthFormField('visceralFatLevel', event.currentTarget.value)}
            />
          </label>
        </div>

        <div className="button-row health-entry-actions">
          <button className="primary-button" type="button" onClick={handleHealthSave} disabled={isSavingHealth}>
            {isSavingHealth ? '저장 중...' : healthForm.entryId ? '건강 데이터 수정' : '건강 데이터 저장'}
          </button>
          <button className="ghost-button" type="button" onClick={resetHealthForm} disabled={isSavingHealth}>
            입력 초기화
          </button>
          <button
            className="ghost-button ghost-button--danger"
            type="button"
            onClick={handleHealthDelete}
            disabled={isSavingHealth || !healthForm.entryId}
          >
            선택 기록 삭제
          </button>
        </div>

        {selectedHealthEntry ? (
          <p className="muted-copy health-entry-caption">
            현재 {formatKoreanDate(selectedHealthEntry.recordDate)} 기록을 수정 중입니다.
          </p>
        ) : null}
        {healthMessage ? <p className="form-feedback form-feedback--success">{healthMessage}</p> : null}
        {healthError ? <p className="form-feedback form-feedback--error">{healthError}</p> : null}
      </SectionCard>

      <SectionCard eyebrow="Health Archive" title="최근 건강 기록">
        {healthEntries.length > 0 ? (
          <div className="stack-list">
            {healthEntries.slice(0, 8).map((entry) => (
              <button
                key={entry.id}
                type="button"
                className={`health-entry-row${healthForm.entryId === entry.id ? ' is-active' : ''}`}
                onClick={() => loadHealthEntry(entry)}
              >
                <div className="health-entry-row__copy">
                  <strong>{formatKoreanDate(entry.recordDate)}</strong>
                  <p>{buildHealthEntrySummary(entry)}</p>
                </div>
                <span className="metric">수정</span>
              </button>
            ))}
          </div>
        ) : (
          <EmptyState
            title="아직 건강 기록이 없어요"
            body="체중이나 체성분을 입력하면 날짜별 건강 변화와 최근 기록이 여기 쌓입니다."
          />
        )}
      </SectionCard>

      <SectionCard title="기록에서 읽힌 흐름">
        <div className="stack-list">
          {insights.map((insight) => (
            <div key={insight} className="insight-row">
              {insight}
            </div>
          ))}
        </div>
      </SectionCard>

      <SectionCard title="세션 기록">
        {sessions.length > 0 ? (
          <div className="stack-list">
            {sessions.map((session) => (
              <Link key={session.id} className="history-row" to={`/history/${session.id}`}>
                <div>
                  <strong>{formatKoreanDate(session.sessionDate)}</strong>
                  <p>
                    {session.items
                      .map((item) => exerciseMap.get(item.exerciseId) ?? '운동')
                      .join(' · ')}
                  </p>
                </div>
                <span className={`status-pill status-pill--${session.status}`}>
                  {getSessionStatusLabel(session.status)}
                </span>
              </Link>
            ))}
          </div>
        ) : (
          <EmptyState
            title="아직 세션 기록이 없어요"
            body="오늘 화면에서 루틴을 시작하면 여기서 날짜별로 다시 확인할 수 있습니다."
          />
        )}
      </SectionCard>
    </div>
  );
}
