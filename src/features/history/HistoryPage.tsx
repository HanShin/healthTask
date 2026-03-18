import { useEffect, useState } from 'react';
import { useLiveQuery } from 'dexie-react-hooks';
import { Link } from 'react-router-dom';
import { EmptyState } from '../../components/EmptyState';
import { SectionCard } from '../../components/SectionCard';
import { db } from '../../lib/db';
import { buildWeekStrip, formatKoreanDate } from '../../lib/date';
import { loadHolidayMap } from '../../lib/holidays';
import { getSessionStatusLabel } from '../../lib/sessionStatus';
import { getHistoryInsights, getSessionsThisWeek, getWeeklyCompletion, getCurrentStreak } from '../../lib/stats';

export function HistoryPage() {
  const profile = useLiveQuery(() => db.profile.get('local-profile'), []);
  const sessions = useLiveQuery(() => db.sessions.orderBy('sessionDate').reverse().toArray(), []) ?? [];
  const exercises = useLiveQuery(() => db.exercises.toArray(), []) ?? [];
  const exerciseMap = new Map(exercises.map((exercise) => [exercise.id, exercise.name]));
  const weekStrip = buildWeekStrip();
  const weekStripRangeKey = weekStrip.map((day) => day.dateKey).join('|');
  const weeklyDates = new Set(getSessionsThisWeek(sessions).map((session) => session.sessionDate));
  const insights = getHistoryInsights(sessions);
  const weeklyCompletion = getWeeklyCompletion(profile ?? null, sessions);
  const streak = getCurrentStreak(sessions);
  const [holidayMap, setHolidayMap] = useState<Record<string, string>>({});

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
