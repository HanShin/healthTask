import { useState } from 'react';
import { useLiveQuery } from 'dexie-react-hooks';
import { Link } from 'react-router-dom';
import { EmptyState } from '../../components/EmptyState';
import { ExerciseGuideModal } from '../../components/ExerciseGuideModal';
import { SectionCard } from '../../components/SectionCard';
import { db } from '../../lib/db';
import { buildWeekStrip, formatKoreanDate } from '../../lib/date';
import { formatDistance, formatDuration, formatWeight } from '../../lib/format';
import { getTodayRecommendation } from '../../lib/recommendations';
import { getWeeklyRoutineSequence } from '../../lib/routineSequence';
import { getSessionStatusLabel } from '../../lib/sessionStatus';
import { getCurrentStreak, getRecentSessions, getWeeklyCompletion, getWeekMomentumLabel, getSessionsThisWeek } from '../../lib/stats';
import { getStrengthCarryover } from '../../lib/workoutRecord';
import type { WorkoutRecordItem, WorkoutSession } from '../../lib/types';

function getSessionExerciseValue(item: WorkoutRecordItem): string {
  if (item.kind === 'strength') {
    const carryover = getStrengthCarryover(item);
    const weightCopy = formatWeight(carryover?.weightKg);
    const repsCopy = carryover?.reps ? `${carryover.reps}회` : '-';

    if (weightCopy !== '-') {
      return `${weightCopy} × ${repsCopy}`;
    }

    return repsCopy;
  }

  return [formatDistance(item.distanceKm), formatDuration(item.durationMin)].filter((value) => value !== '-').join(' · ') || '기록 미입력';
}

function getSessionExerciseMeta(item: WorkoutRecordItem): string {
  if (item.kind === 'strength') {
    const completedSetCount = item.sets.filter((set) => set.completed).length;
    return `${completedSetCount}/${item.sets.length}세트`;
  }

  return item.avgPaceMinPerKm ? `${item.avgPaceMinPerKm}분/km 페이스` : '러닝 기록';
}

function getSessionSummary(session: WorkoutSession) {
  const strengthSetCount = session.items.reduce(
    (count, item) => count + (item.kind === 'strength' ? item.sets.length : 0),
    0
  );
  const runningCount = session.items.filter((item) => item.kind === 'running').length;

  return {
    workoutCount: `${session.items.length}개 운동`,
    volumeCopy:
      strengthSetCount > 0 ? `${strengthSetCount}세트` : runningCount > 0 ? `${runningCount}개 러닝` : '기록 없음'
  };
}

export function TodayPage() {
  const profile = useLiveQuery(() => db.profile.get('local-profile'), []);
  const routines = useLiveQuery(() => db.routines.toArray(), []) ?? [];
  const sessions = useLiveQuery(() => db.sessions.orderBy('sessionDate').reverse().toArray(), []) ?? [];
  const exercises = useLiveQuery(() => db.exercises.toArray(), []) ?? [];
  const weeklyCompletion = getWeeklyCompletion(profile ?? null, sessions);
  const streak = getCurrentStreak(sessions);
  const weekStrip = buildWeekStrip();
  const weeklySessionDates = new Set(getSessionsThisWeek(sessions).map((session) => session.sessionDate));
  const recentSessions = getRecentSessions(sessions);
  const exerciseMap = new Map(exercises.map((exercise) => [exercise.id, exercise]));
  const routineMap = new Map(routines.map((routine) => [routine.id, routine.name]));
  const [guideExerciseId, setGuideExerciseId] = useState<string | null>(null);
  const routineSequence = getWeeklyRoutineSequence({
    profile: profile ?? null,
    routines,
    sessions
  });
  const todayRoutine = routineSequence.nextRoutine;
  const recommendation = getTodayRecommendation({
    profile: profile ?? null,
    routines,
    sessions,
    exercises
  });

  if (!profile) {
    return null;
  }

  const activeGuideExercise =
    guideExerciseId ? exerciseMap.get(guideExerciseId) ?? null : null;

  return (
    <div className="page-stack">
      <section className="hero-card hero-card--compact">
        <div className="hero-card__label">{formatKoreanDate(new Date())}</div>
        <h2>{weeklyCompletion.completed} / {weeklyCompletion.goal}회</h2>
        <p>이번 주 진행률 {weeklyCompletion.percent}% · {getWeekMomentumLabel(sessions)}</p>

        <div className="stat-grid">
          <div className="stat-pill">
            <span>연속 기록</span>
            <strong>{streak}일</strong>
          </div>
          <div className="stat-pill">
            <span>오늘 루틴</span>
            <strong>{todayRoutine ? '1개' : '0개'}</strong>
          </div>
          <div className="stat-pill">
            <span>최근 세션</span>
            <strong>{recentSessions.length}개</strong>
          </div>
        </div>
      </section>

      <SectionCard eyebrow="This week" title="주간 리듬">
        <div className="week-strip">
          {weekStrip.map((day) => (
            <div
              key={day.dateKey}
              className={`week-strip__day${weeklySessionDates.has(day.dateKey) ? ' is-filled' : ''}${day.isToday ? ' is-today' : ''}`}
            >
              <span>{day.label}</span>
              <strong>{day.dateKey.slice(-2)}</strong>
            </div>
          ))}
        </div>
      </SectionCard>

      <SectionCard eyebrow="Coach note" title="오늘 추천">
        <p className="lead-copy">{recommendation}</p>
      </SectionCard>

      {todayRoutine ? (
        <SectionCard
          eyebrow="Today"
          title={
            routineSequence.nextSequenceNumber
              ? `이번 주 ${routineSequence.nextSequenceNumber}번째 운동`
              : '오늘 예정 루틴'
          }
        >
          <div className="stack-list">
            <article className="routine-card">
              <div className="routine-card__header">
                <div className="routine-card__summary">
                  <h3>{todayRoutine.name}</h3>
                  <p>
                    이번 주 {routineSequence.nextSequenceNumber}번째 운동 · {todayRoutine.items.length}개 운동
                  </p>
                </div>
                <Link className="primary-button" to={`/today/session/${todayRoutine.id}`}>
                  운동 시작
                </Link>
              </div>

              <div className="chip-row">
                {todayRoutine.items.slice(0, 4).map((item) => (
                  exerciseMap.get(item.exerciseId)?.guide ? (
                    <button
                      key={item.id}
                      type="button"
                      className="chip-button"
                      onClick={() => setGuideExerciseId(item.exerciseId)}
                    >
                      {exerciseMap.get(item.exerciseId)?.name ?? '운동'}
                    </button>
                  ) : (
                    <span key={item.id} className="chip">
                      {exerciseMap.get(item.exerciseId)?.name ?? '운동'}
                    </span>
                  )
                ))}
              </div>
            </article>
          </div>
        </SectionCard>
      ) : (
        <EmptyState
          title={
            routineSequence.activeRoutines.length > 0 && routineSequence.isGoalComplete
              ? '이번 주 목표를 모두 채웠어요'
              : '오늘 등록된 루틴이 없어요'
          }
          body={
            routineSequence.activeRoutines.length > 0 && routineSequence.isGoalComplete
              ? '새 주가 시작되면 다시 1번째 루틴부터 시작됩니다. 이번 주는 회복이나 가벼운 스트레칭으로 마무리해보세요.'
              : '루틴 탭에서 직접 만들면 저장한 순서대로 이번 주 첫 번째, 두 번째 운동에 배정됩니다.'
          }
          action={
            <Link className="primary-button" to="/routines">
              {routineSequence.activeRoutines.length > 0 ? '루틴 순서 보기' : '루틴 만들러 가기'}
            </Link>
          }
        />
      )}

      <SectionCard eyebrow="Recent log" title="최근 기록">
        {recentSessions.length > 0 ? (
          <div className="stack-list">
            {recentSessions.map((session) => {
              const summary = getSessionSummary(session);
              const previewItems = session.items.slice(0, 2);
              const hiddenItemCount = Math.max(session.items.length - previewItems.length, 0);

              return (
                <Link key={session.id} className="session-history-card" to={`/history/${session.id}`}>
                  <div className="session-history-card__header">
                    <div className="session-history-card__copy">
                      <span className="session-history-card__eyebrow">{formatKoreanDate(session.sessionDate)}</span>
                      <strong>{routineMap.get(session.routineId ?? '') ?? '자유 기록 세션'}</strong>
                    </div>
                    <span className={`status-pill status-pill--${session.status}`}>
                      {getSessionStatusLabel(session.status)}
                    </span>
                  </div>

                  <div className="chip-row session-history-card__summary">
                    <span className="chip">{summary.workoutCount}</span>
                    <span className="chip">{summary.volumeCopy}</span>
                  </div>

                  <div className="session-history-card__list">
                    {previewItems.map((item) => (
                      <div key={item.id} className="session-history-card__exercise">
                        <div className="session-history-card__exercise-copy">
                          <strong>{exerciseMap.get(item.exerciseId)?.name ?? '운동'}</strong>
                          <span>{getSessionExerciseMeta(item)}</span>
                        </div>
                        <span className="session-history-card__value">{getSessionExerciseValue(item)}</span>
                      </div>
                    ))}
                    {hiddenItemCount > 0 ? (
                      <div className="session-history-card__more">+{hiddenItemCount}개 운동 더 보기</div>
                    ) : null}
                  </div>
                </Link>
              );
            })}
          </div>
        ) : (
          <p className="muted-copy">첫 운동을 기록하면 여기에 최근 흐름이 쌓입니다.</p>
        )}
      </SectionCard>

      {activeGuideExercise ? (
        <ExerciseGuideModal
          exercise={activeGuideExercise}
          onClose={() => setGuideExerciseId(null)}
        />
      ) : null}
    </div>
  );
}
