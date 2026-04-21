import { useLiveQuery } from 'dexie-react-hooks';
import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { EmptyState } from '../../components/EmptyState';
import { SectionCard } from '../../components/SectionCard';
import { db } from '../../lib/db';
import { formatKoreanDate } from '../../lib/date';
import { formatDistance, formatDuration, formatPace, formatSpeed, formatWeight, paceToSpeedKmh } from '../../lib/format';
import { deleteWorkoutSession } from '../../lib/repository';
import { getSessionStatusLabel } from '../../lib/sessionStatus';

export function SessionDetailPage() {
  const { sessionId } = useParams();
  const navigate = useNavigate();
  const sessions = useLiveQuery(() => db.sessions.toArray(), []) ?? [];
  const routines = useLiveQuery(() => db.routines.toArray(), []) ?? [];
  const exercises = useLiveQuery(() => db.exercises.toArray(), []) ?? [];
  const [isDeleting, setIsDeleting] = useState(false);
  const session = sessions.find((item) => item.id === sessionId) ?? null;
  const routineMap = new Map(routines.map((routine) => [routine.id, routine.name]));
  const exerciseMap = new Map(exercises.map((exercise) => [exercise.id, exercise.name]));

  async function handleDelete() {
    if (!session) {
      return;
    }

    if (!window.confirm('이 기록을 삭제할까요? 삭제하면 되돌릴 수 없습니다.')) {
      return;
    }

    setIsDeleting(true);

    try {
      await deleteWorkoutSession(session.id);
      navigate('/history', { replace: true });
    } finally {
      setIsDeleting(false);
    }
  }

  if (!session) {
    return (
      <EmptyState
        title="세션을 찾지 못했어요"
        body="기록이 삭제되었거나 잘못된 경로일 수 있습니다."
        action={
          <Link className="primary-button" to="/history">
            기록 화면으로 이동
          </Link>
        }
      />
    );
  }

  return (
    <div className="page-stack">
      <section className="hero-card hero-card--compact">
        <div className="hero-card__label">{formatKoreanDate(session.sessionDate)}</div>
        <h2>{routineMap.get(session.routineId ?? '') ?? '자유 기록 세션'}</h2>
        <p>{getSessionStatusLabel(session.status)} · {session.items.length}개 운동</p>
        <div className="button-row">
          <Link className="ghost-button" to={`/history/${session.id}/edit`}>
            기록 수정
          </Link>
          <button
            className="ghost-button ghost-button--danger"
            type="button"
            onClick={handleDelete}
            disabled={isDeleting}
          >
            {isDeleting ? '삭제 중...' : '기록 삭제'}
          </button>
        </div>
      </section>

      <SectionCard title="운동별 결과">
        <div className="stack-list">
          {session.items.map((item) => (
            <article key={item.id} className="session-card">
              <div className="session-card__title">
                <h3>{exerciseMap.get(item.exerciseId) ?? '운동'}</h3>
                <span>{item.kind === 'strength' ? '웨이트' : '유산소'}</span>
              </div>

              {item.kind === 'strength' ? (
                <div className="stack-list">
                  {item.sets.map((set) => (
                    <div key={set.order} className="detail-row">
                      <strong>{set.order}세트</strong>
                      <span>{formatWeight(set.actualWeightKg ?? set.plannedWeightKg)}</span>
                      <span>{set.actualReps ?? set.plannedReps}회</span>
                      <span>{set.completed ? '완료' : '미완료'}</span>
                    </div>
                  ))}
                </div>
              ) : (
                <div className="detail-row detail-row--wide">
                  <span>{formatDistance(item.distanceKm)}</span>
                  <span>{formatDuration(item.durationMin)}</span>
                  <span>{formatPace(item.avgPaceMinPerKm)}</span>
                  <span>{formatSpeed(paceToSpeedKmh(item.avgPaceMinPerKm))}</span>
                </div>
              )}
            </article>
          ))}
        </div>
      </SectionCard>

      {session.memo ? (
        <SectionCard title="메모">
          <p className="lead-copy">{session.memo}</p>
        </SectionCard>
      ) : null}
    </div>
  );
}
