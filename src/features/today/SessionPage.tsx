import { useLiveQuery } from 'dexie-react-hooks';
import { useDeferredValue, useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { EmptyState } from '../../components/EmptyState';
import { ExerciseGuideModal } from '../../components/ExerciseGuideModal';
import { SectionCard } from '../../components/SectionCard';
import { db } from '../../lib/db';
import { toDateInput } from '../../lib/date';
import {
  getExerciseEquipmentLabel,
  getExerciseGroupLabel,
  getExerciseKindLabel,
  getExercisePlanningHint,
  getExerciseSummary,
  getExerciseTargetLabel,
  hasExerciseGuideVideo
} from '../../lib/exercise';
import { paceToSpeedKmh, speedToPaceMinPerKm } from '../../lib/format';
import { createId } from '../../lib/id';
import { saveWorkoutSession } from '../../lib/repository';
import { getStrengthCarryover } from '../../lib/workoutRecord';
import type { Exercise, Routine, StrengthSetRecord, WorkoutRecordItem, WorkoutSession } from '../../lib/types';

type EquipmentFilter = 'all' | 'dumbbell' | 'kettlebell' | 'freeweight';
type MuscleFilter = 'all' | 'running' | NonNullable<Exercise['muscleGroup']>;

const equipmentFilterOptions: Array<{ value: EquipmentFilter; label: string }> = [
  { value: 'all', label: '전체' },
  { value: 'dumbbell', label: '아령' },
  { value: 'kettlebell', label: '케틀벨' },
  { value: 'freeweight', label: '프리웨이트' }
];

const muscleFilterOptions: Array<{ value: MuscleFilter; label: string }> = [
  { value: 'all', label: '전체 부위' },
  { value: 'chest', label: '가슴' },
  { value: 'back', label: '등' },
  { value: 'legs', label: '하체' },
  { value: 'shoulders', label: '어깨' },
  { value: 'arms', label: '팔' },
  { value: 'core', label: '코어' },
  { value: 'running', label: '러닝' }
];

function buildDraftItems(routine: Routine, sessions: WorkoutSession[]) {
  const recentRecords = sessions.flatMap((session) => session.items);

  return routine.items.map((item) => {
    const previousRecord = recentRecords.find(
      (record) => record.exerciseId === item.exerciseId && record.kind === item.kind
    );

    if (item.kind === 'strength') {
      const carryover = getStrengthCarryover(previousRecord);
      const defaultReps = carryover?.reps ?? item.targetReps;
      const defaultWeightKg = carryover?.weightKg ?? item.targetWeightKg ?? 0;

      return {
        id: createId('record'),
        kind: 'strength',
        exerciseId: item.exerciseId,
        routineItemId: item.id,
        order: item.order,
        note: item.note,
        sets: Array.from({ length: item.sets }, (_, index) => ({
          order: index + 1,
          plannedReps: defaultReps,
          actualReps: defaultReps,
          plannedWeightKg: defaultWeightKg,
          actualWeightKg: defaultWeightKg,
          completed: false
        }))
      } satisfies WorkoutRecordItem;
    }

    return {
      id: createId('record'),
      kind: 'running',
      exerciseId: item.exerciseId,
      routineItemId: item.id,
      order: item.order,
      note: item.note,
      distanceKm:
        previousRecord && previousRecord.kind === 'running'
          ? previousRecord.distanceKm ?? item.targetDistanceKm
          : item.targetDistanceKm,
      durationMin:
        previousRecord && previousRecord.kind === 'running'
          ? previousRecord.durationMin ?? item.targetDurationMin
          : item.targetDurationMin,
      avgPaceMinPerKm:
        previousRecord && previousRecord.kind === 'running'
          ? previousRecord.avgPaceMinPerKm ?? item.targetPaceMinPerKm
          : item.targetPaceMinPerKm
    } satisfies WorkoutRecordItem;
  });
}

function cloneWorkoutItems(items: WorkoutRecordItem[]): WorkoutRecordItem[] {
  return items.map((item) =>
    item.kind === 'strength'
      ? {
          ...item,
          sets: item.sets.map((set) => ({ ...set }))
        }
      : {
          ...item
        }
  );
}

function normalizeStrengthSets(sets: StrengthSetRecord[]): StrengthSetRecord[] {
  return sets.map((set, index) => ({
    ...set,
    order: index + 1
  }));
}

function normalizeWorkoutItems(items: WorkoutRecordItem[]): WorkoutRecordItem[] {
  return items.map((item, index) =>
    item.kind === 'strength'
      ? {
          ...item,
          order: index + 1,
          sets: normalizeStrengthSets(item.sets)
        }
      : {
          ...item,
          order: index + 1
        }
  );
}

function buildSessionItemFromExercise(
  exercise: Exercise,
  sessions: WorkoutSession[],
  order: number
): WorkoutRecordItem {
  const previousRecord = sessions
    .flatMap((session) => session.items)
    .find((record) => record.exerciseId === exercise.id && record.kind === exercise.kind);

  if (exercise.kind === 'strength') {
    const carryover = getStrengthCarryover(previousRecord);
    const setCount = Math.max(carryover?.setCount ?? 0, 3);
    const defaultReps = carryover?.reps ?? 10;
    const defaultWeightKg = carryover?.weightKg ?? 0;

    return {
      id: createId('record'),
      kind: 'strength',
      exerciseId: exercise.id,
      order,
      sets: Array.from({ length: setCount }, (_, index) => ({
        order: index + 1,
        plannedReps: defaultReps,
        actualReps: defaultReps,
        plannedWeightKg: defaultWeightKg,
        actualWeightKg: defaultWeightKg,
        completed: false
      }))
    } satisfies WorkoutRecordItem;
  }

  return {
    id: createId('record'),
    kind: 'running',
    exerciseId: exercise.id,
    order,
    distanceKm:
      previousRecord && previousRecord.kind === 'running'
        ? previousRecord.distanceKm ?? 3
        : 3,
    durationMin:
      previousRecord && previousRecord.kind === 'running'
        ? previousRecord.durationMin ?? 20
        : 20,
    avgPaceMinPerKm:
      previousRecord && previousRecord.kind === 'running'
        ? previousRecord.avgPaceMinPerKm ?? 6
        : 6
  } satisfies WorkoutRecordItem;
}

function getWorkoutProgressCopy(item: WorkoutRecordItem): string {
  if (item.kind === 'strength') {
    const completedSets = item.sets.filter((set) => set.completed).length;
    return `${completedSets}/${item.sets.length}세트 완료`;
  }

  const distanceCopy = item.distanceKm ? `${item.distanceKm}km` : '거리 미입력';
  const durationCopy = item.durationMin ? `${item.durationMin}분` : '시간 미입력';

  return `${distanceCopy} · ${durationCopy}`;
}

export function SessionPage() {
  const { routineId, sessionId } = useParams();
  const navigate = useNavigate();
  const routines = useLiveQuery(() => db.routines.toArray(), []) ?? [];
  const sessions = useLiveQuery(() => db.sessions.orderBy('sessionDate').reverse().toArray(), []) ?? [];
  const exercises = useLiveQuery(() => db.exercises.toArray(), []) ?? [];
  const editingSession = sessions.find((item) => item.id === sessionId) ?? null;
  const routine =
    routines.find((item) => item.id === (editingSession?.routineId ?? routineId)) ?? null;
  const exerciseMap = new Map(exercises.map((exercise) => [exercise.id, exercise.name]));
  const exerciseLookup = new Map(exercises.map((exercise) => [exercise.id, exercise]));
  const [sessionDate, setSessionDate] = useState(toDateInput(new Date()));
  const [memo, setMemo] = useState('');
  const [items, setItems] = useState<WorkoutRecordItem[]>([]);
  const [isSaving, setIsSaving] = useState(false);
  const [guideExerciseId, setGuideExerciseId] = useState<string | null>(null);
  const [activeItemId, setActiveItemId] = useState<string | null>(null);
  const [isExercisePickerOpen, setIsExercisePickerOpen] = useState(false);
  const [search, setSearch] = useState('');
  const [equipmentFilter, setEquipmentFilter] = useState<EquipmentFilter>('all');
  const [muscleFilter, setMuscleFilter] = useState<MuscleFilter>('all');
  const deferredSearch = useDeferredValue(search);
  const filteredExercises = exercises.filter((exercise) => {
    const trimmedSearch = deferredSearch.trim().toLowerCase();
    const hasSearch = trimmedSearch.length > 0;
    const matchesSearch = exercise.name.toLowerCase().includes(trimmedSearch);
    const selectedMuscleGroup =
      muscleFilter !== 'all' && muscleFilter !== 'running' ? muscleFilter : null;

    if (!matchesSearch) {
      return false;
    }

    if (muscleFilter === 'running') {
      return exercise.kind === 'running';
    }

    if (hasSearch) {
      return selectedMuscleGroup ? exercise.muscleGroup === selectedMuscleGroup : true;
    }

    if (equipmentFilter === 'all') {
      return selectedMuscleGroup ? exercise.muscleGroup === selectedMuscleGroup : true;
    }

    if (equipmentFilter === 'freeweight') {
      if (!['barbell', 'dumbbell', 'kettlebell', 'bodyweight'].includes(exercise.equipment ?? 'bodyweight')) {
        return false;
      }
    } else if (exercise.equipment !== equipmentFilter) {
      return false;
    }

    if (!selectedMuscleGroup) {
      return true;
    }

    return exercise.muscleGroup === selectedMuscleGroup;
  });
  const groupedExercises = filteredExercises.reduce<Record<string, Exercise[]>>((accumulator, exercise) => {
    const key = getExerciseGroupLabel(exercise);
    accumulator[key] = [...(accumulator[key] ?? []), exercise];
    return accumulator;
  }, {});
  const groupedExerciseEntries = Object.entries(groupedExercises).sort((left, right) =>
    left[0].localeCompare(right[0], 'ko')
  );

  useEffect(() => {
    if (editingSession) {
      setSessionDate(editingSession.sessionDate);
      setMemo(editingSession.memo ?? '');
      const nextItems = cloneWorkoutItems(editingSession.items);
      setItems(nextItems);
      setActiveItemId(nextItems[0]?.id ?? null);
      setIsExercisePickerOpen(false);
      return;
    }

    if (!routine) {
      return;
    }

    setSessionDate(toDateInput(new Date()));
    setMemo('');
    const nextItems = buildDraftItems(routine, sessions);
    setItems(nextItems);
    setActiveItemId(nextItems[0]?.id ?? null);
    setIsExercisePickerOpen(false);
  }, [editingSession, routine, sessions]);

  useEffect(() => {
    if (items.length === 0) {
      if (activeItemId !== null) {
        setActiveItemId(null);
      }
      return;
    }

    if (!activeItemId || !items.some((item) => item.id === activeItemId)) {
      setActiveItemId(items[0]?.id ?? null);
    }
  }, [activeItemId, items]);

  if (!routine && !editingSession) {
    return (
      <EmptyState
        title="기록을 불러오지 못했어요"
        body="루틴이 삭제되었거나 잘못된 경로일 수 있습니다."
        action={
          <Link className="primary-button" to="/history">
            기록 화면으로 돌아가기
          </Link>
        }
      />
    );
  }

  function updateStrengthSet(
    itemId: string,
    setOrder: number,
    key: 'actualReps' | 'actualWeightKg' | 'completed',
    value: number | boolean
  ) {
    setItems((previous) =>
      previous.map((item) => {
        if (item.id !== itemId || item.kind !== 'strength') {
          return item;
        }

        return {
          ...item,
          sets: item.sets.map((set) =>
            set.order === setOrder
              ? {
                  ...set,
                  [key]: value
                }
              : set
          )
        };
      })
    );
  }

  function updateRunningItem(
    itemId: string,
    key: 'distanceKm' | 'durationMin' | 'avgPaceMinPerKm',
    value: number
  ) {
    setItems((previous) =>
      previous.map((item) =>
        item.id === itemId && item.kind === 'running'
          ? {
              ...item,
              [key]: value
            }
          : item
      )
    );
  }

  function addStrengthSet(itemId: string) {
    setItems((previous) =>
      previous.map((item) => {
        if (item.id !== itemId || item.kind !== 'strength') {
          return item;
        }

        const lastSet = item.sets[item.sets.length - 1];

        return {
          ...item,
          sets: normalizeStrengthSets([
            ...item.sets,
            {
              order: item.sets.length + 1,
              plannedReps: lastSet?.actualReps ?? lastSet?.plannedReps ?? 10,
              actualReps: lastSet?.actualReps ?? lastSet?.plannedReps ?? 10,
              plannedWeightKg: lastSet?.actualWeightKg ?? lastSet?.plannedWeightKg ?? 0,
              actualWeightKg: lastSet?.actualWeightKg ?? lastSet?.plannedWeightKg ?? 0,
              completed: false
            }
          ])
        };
      })
    );
  }

  function removeStrengthSet(itemId: string, setOrder: number) {
    setItems((previous) =>
      previous.map((item) => {
        if (item.id !== itemId || item.kind !== 'strength' || item.sets.length <= 1) {
          return item;
        }

        return {
          ...item,
          sets: normalizeStrengthSets(item.sets.filter((set) => set.order !== setOrder))
        };
      })
    );
  }

  function openExercisePicker() {
    setSearch('');
    setEquipmentFilter('all');
    setMuscleFilter('all');
    setIsExercisePickerOpen(true);
  }

  function closeExercisePicker() {
    setIsExercisePickerOpen(false);
  }

  function addExerciseToSession(exercise: Exercise) {
    const nextItem = buildSessionItemFromExercise(exercise, sessions, items.length + 1);

    setItems((previous) => normalizeWorkoutItems([...previous, nextItem]));
    setActiveItemId(nextItem.id);
    closeExercisePicker();
  }

  function removeWorkoutItem(itemId: string) {
    setItems((previous) => normalizeWorkoutItems(previous.filter((item) => item.id !== itemId)));
  }

  async function handleSave() {
    if (!routine && !editingSession) {
      return;
    }

    setIsSaving(true);

    try {
      const savedSessionId = await saveWorkoutSession({
        sessionId: editingSession?.id,
        routineId: editingSession?.routineId ?? routine?.id,
        sessionDate,
        memo,
        items: normalizeWorkoutItems(items)
      });
      navigate(editingSession ? `/history/${savedSessionId}` : '/history', { replace: true });
    } finally {
      setIsSaving(false);
    }
  }

  const activeGuideExercise =
    guideExerciseId ? exerciseLookup.get(guideExerciseId) ?? null : null;
  const sessionTitle = routine?.name ?? '자유 기록 세션';

  return (
    <div className="page-stack">
      <section className="hero-card hero-card--compact">
        <div className="hero-card__label">{sessionTitle}</div>
        <h2>{items.length}개 운동</h2>
        <p>
          {editingSession
            ? '저장한 기록을 다시 수정할 수 있어요. 실수한 완료 체크나 유산소 수치도 바로 고치면 됩니다.'
            : '지난 기록을 참고값으로 채워두었어요. 실제로 한 세트만 완료 체크해가며 기록하면 됩니다.'}
        </p>
      </section>

      <SectionCard title="기록 기본값">
        <div className="field-grid">
          <label className="field">
            <span>운동 날짜</span>
            <input type="date" value={sessionDate} onChange={(event) => setSessionDate(event.target.value)} />
          </label>
          <label className="field">
            <span>메모</span>
            <input
              type="text"
              value={memo}
              placeholder="컨디션이나 통증 메모"
              onChange={(event) => setMemo(event.target.value)}
            />
          </label>
        </div>
      </SectionCard>

      <SectionCard
        title="세트별 입력"
        action={
          <button className="ghost-button" type="button" onClick={openExercisePicker}>
            운동 추가
          </button>
        }
      >
        <div className="stack-list">
          {items.map((item, index) => {
            const isExpanded = activeItemId === item.id;

            return (
              <article key={item.id} className={`session-card${isExpanded ? ' is-active' : ' is-collapsed'}`}>
                <div className="session-card__header">
                  <button
                    type="button"
                    className={`session-card__toggle${isExpanded ? ' is-active' : ''}`}
                    onClick={() => setActiveItemId(item.id)}
                  >
                    <div className="session-card__title">
                      <div>
                        <h3>{exerciseMap.get(item.exerciseId) ?? '운동'}</h3>
                        <span>
                          {index + 1}번 운동 · {item.kind === 'strength' ? '웨이트' : '러닝'} ·{' '}
                          {getWorkoutProgressCopy(item)}
                        </span>
                      </div>
                      <span className="session-card__toggle-label">{isExpanded ? '현재 입력 중' : '열기'}</span>
                    </div>
                  </button>
                  <div className="button-row session-card__actions">
                    {exerciseLookup.get(item.exerciseId)?.guide ? (
                      <button
                        type="button"
                        className="ghost-button ghost-button--compact"
                        onClick={() => setGuideExerciseId(item.exerciseId)}
                      >
                        자세 보기
                      </button>
                    ) : null}
                    {!item.routineItemId ? (
                      <button
                        type="button"
                        className="ghost-button ghost-button--compact"
                        onClick={() => removeWorkoutItem(item.id)}
                      >
                        제외
                      </button>
                    ) : null}
                  </div>
                </div>

                {isExpanded ? (
                  item.kind === 'strength' ? (
                    <div className="stack-list">
                      {item.sets.map((set) => (
                        <div key={set.order} className="set-row set-row--session">
                          <strong className="set-row__label">{set.order}세트</strong>
                          <label className="field field--compact">
                            <span>중량</span>
                            <input
                              type="number"
                              min="0"
                              step="0.5"
                              value={set.actualWeightKg ?? 0}
                              onChange={(event) =>
                                updateStrengthSet(
                                  item.id,
                                  set.order,
                                  'actualWeightKg',
                                  event.currentTarget.valueAsNumber || 0
                                )
                              }
                            />
                          </label>
                          <label className="field field--compact">
                            <span>횟수</span>
                            <input
                              type="number"
                              min="0"
                              step="1"
                              value={set.actualReps ?? 0}
                              onChange={(event) =>
                                updateStrengthSet(
                                  item.id,
                                  set.order,
                                  'actualReps',
                                  event.currentTarget.valueAsNumber || 0
                                )
                              }
                            />
                          </label>
                          <button
                            type="button"
                            className={`ghost-button ghost-button--compact set-row__action${set.completed ? ' is-active' : ''}`}
                            onClick={() =>
                              updateStrengthSet(item.id, set.order, 'completed', !set.completed)
                            }
                          >
                            {set.completed ? '완료' : '체크'}
                          </button>
                          <button
                            type="button"
                            className="ghost-button ghost-button--compact set-row__delete"
                            onClick={() => removeStrengthSet(item.id, set.order)}
                            disabled={item.sets.length <= 1}
                          >
                            삭제
                          </button>
                        </div>
                      ))}
                      <div className="button-row set-row__toolbar">
                        <button
                          type="button"
                          className="ghost-button ghost-button--compact"
                          onClick={() => addStrengthSet(item.id)}
                        >
                          세트 추가
                        </button>
                      </div>
                    </div>
                  ) : (
                    <div className="field-grid">
                      <label className="field">
                        <span>거리 (km)</span>
                        <input
                          type="number"
                          min="0"
                          step="0.1"
                          value={item.distanceKm ?? 0}
                          onChange={(event) =>
                            updateRunningItem(item.id, 'distanceKm', event.currentTarget.valueAsNumber || 0)
                          }
                        />
                      </label>
                      <label className="field">
                        <span>시간 (분)</span>
                        <input
                          type="number"
                          min="0"
                          step="1"
                          value={item.durationMin ?? 0}
                          onChange={(event) =>
                            updateRunningItem(item.id, 'durationMin', event.currentTarget.valueAsNumber || 0)
                          }
                        />
                      </label>
                      <label className="field">
                        <span>평균 속도 (km/h)</span>
                        <input
                          type="number"
                          min="0.5"
                          step="0.1"
                          value={paceToSpeedKmh(item.avgPaceMinPerKm) ?? 0}
                          onChange={(event) =>
                            updateRunningItem(
                              item.id,
                              'avgPaceMinPerKm',
                              speedToPaceMinPerKm(event.currentTarget.valueAsNumber) ?? 0
                            )
                          }
                        />
                      </label>
                    </div>
                  )
                ) : null}
              </article>
            );
          })}
        </div>
      </SectionCard>

      <button className="primary-button primary-button--full" type="button" onClick={handleSave} disabled={isSaving}>
        {isSaving ? '저장 중...' : editingSession ? '기록 수정 저장' : '오늘 기록 저장'}
      </button>

      {activeGuideExercise ? (
        <ExerciseGuideModal
          exercise={activeGuideExercise}
          onClose={() => setGuideExerciseId(null)}
        />
      ) : null}

      {isExercisePickerOpen ? (
        <div className="modal-backdrop modal-backdrop--nested" role="presentation" onClick={closeExercisePicker}>
          <section
            className="modal-sheet modal-sheet--template-picker"
            aria-modal="true"
            role="dialog"
            onClick={(event) => event.stopPropagation()}
          >
            <div className="modal-sheet__header">
              <div>
                <h2>이번 기록에 운동 추가</h2>
                <p className="muted-copy">여기서 추가한 운동은 이번 세션 기록에 바로 반영됩니다.</p>
              </div>
              <button className="ghost-button" type="button" onClick={closeExercisePicker}>
                닫기
              </button>
            </div>

            <div className="stack-list">
              <div className="field">
                <span>운동 검색</span>
                <input
                  type="search"
                  value={search}
                  placeholder="운동 이름 검색"
                  onChange={(event) => setSearch(event.target.value)}
                />
              </div>

              <div className="chip-row">
                {equipmentFilterOptions.map((option) => (
                  <button
                    key={option.value}
                    type="button"
                    className={`chip chip--button${equipmentFilter === option.value ? ' is-active' : ''}`}
                    onClick={() => setEquipmentFilter(option.value)}
                  >
                    {option.label}
                  </button>
                ))}
              </div>

              <div className="chip-row">
                {muscleFilterOptions.map((option) => (
                  <button
                    key={option.value}
                    type="button"
                    className={`chip chip--button${muscleFilter === option.value ? ' is-active' : ''}`}
                    onClick={() => setMuscleFilter(option.value)}
                  >
                    {option.label}
                  </button>
                ))}
              </div>

              <div className="exercise-browser">
                <div className="exercise-browser__summary">
                  <strong>{filteredExercises.length}개</strong>
                  <span>현재 조건에 맞는 운동</span>
                </div>

                {groupedExerciseEntries.length > 0 ? (
                  <div className="exercise-group-stack">
                    {groupedExerciseEntries.map(([groupLabel, groupExercises]) => (
                      <section key={groupLabel} className="exercise-group">
                        <div className="exercise-group__header">
                          <h3>{groupLabel}</h3>
                          <span>{groupExercises.length}개</span>
                        </div>
                        <div className="exercise-option-grid">
                          {groupExercises.map((exercise) => (
                            <article key={exercise.id} className="exercise-option">
                              <div className="exercise-option__body">
                                <div className="exercise-option__copy">
                                  <strong>{exercise.name}</strong>
                                  <p>{getExerciseSummary(exercise)}</p>
                                </div>
                                <div className="chip-row exercise-option__chips">
                                  <span className="chip">{getExerciseKindLabel(exercise)}</span>
                                  <span className="chip">{getExerciseTargetLabel(exercise)}</span>
                                  <span className="chip">{getExerciseEquipmentLabel(exercise)}</span>
                                  {hasExerciseGuideVideo(exercise) ? <span className="chip">영상 가이드</span> : null}
                                </div>
                                <div className="exercise-option__fact-grid">
                                  <div className="exercise-option__fact">
                                    <span>주요 부위</span>
                                    <strong>{getExerciseTargetLabel(exercise)}</strong>
                                  </div>
                                  <div className="exercise-option__fact">
                                    <span>설계 포인트</span>
                                    <strong>{getExercisePlanningHint(exercise)}</strong>
                                  </div>
                                </div>
                              </div>
                              <div className="exercise-option__actions">
                                {exercise.guide ? (
                                  <button
                                    type="button"
                                    className="ghost-button"
                                    onClick={() => setGuideExerciseId(exercise.id)}
                                  >
                                    자세 보기
                                  </button>
                                ) : null}
                                <button
                                  type="button"
                                  className="ghost-button"
                                  onClick={() => addExerciseToSession(exercise)}
                                >
                                  추가
                                </button>
                              </div>
                            </article>
                          ))}
                        </div>
                      </section>
                    ))}
                  </div>
                ) : (
                  <div className="exercise-browser__empty">조건에 맞는 운동이 아직 없습니다.</div>
                )}
              </div>
            </div>
          </section>
        </div>
      ) : null}
    </div>
  );
}
