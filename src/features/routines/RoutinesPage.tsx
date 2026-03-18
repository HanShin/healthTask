import { useDeferredValue, useEffect, useState } from 'react';
import { useLiveQuery } from 'dexie-react-hooks';
import { EmptyState } from '../../components/EmptyState';
import { ExerciseGuideModal } from '../../components/ExerciseGuideModal';
import { SectionCard } from '../../components/SectionCard';
import { db } from '../../lib/db';
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
import { useRecommendationTemplates } from '../../lib/recommendedTemplates';
import { deleteRoutine, installTemplate, saveRoutine } from '../../lib/repository';
import { getOrderedRoutines } from '../../lib/routineSequence';
import type { Exercise, Routine, RoutineDifficulty, RoutineDraftItem, RoutineTemplate } from '../../lib/types';

type EquipmentFilter = 'all' | 'dumbbell' | 'kettlebell' | 'freeweight';
type MuscleFilter = 'all' | 'running' | NonNullable<Exercise['muscleGroup']>;
type TemplateDifficultyFilter = 'all' | RoutineDifficulty;

type EditorItem =
  | {
      uid: string;
      kind: 'strength';
      exerciseId: string;
      sets: number;
      targetReps: number;
      targetWeightKg: number;
      restSeconds: number;
      note: string;
    }
  | {
      uid: string;
      kind: 'running';
      exerciseId: string;
      targetDistanceKm: number;
      targetDurationMin: number;
      targetPaceMinPerKm: number;
      note: string;
    };

function toEditorItems(items: Routine['items']): EditorItem[] {
  return items.map((item) =>
    item.kind === 'strength'
      ? {
          uid: item.id,
          kind: 'strength',
          exerciseId: item.exerciseId,
          sets: item.sets,
          targetReps: item.targetReps,
          targetWeightKg: item.targetWeightKg ?? 0,
          restSeconds: item.restSeconds ?? 90,
          note: item.note ?? ''
        }
      : {
          uid: item.id,
          kind: 'running',
          exerciseId: item.exerciseId,
          targetDistanceKm: item.targetDistanceKm ?? 3,
          targetDurationMin: item.targetDurationMin ?? 20,
          targetPaceMinPerKm: item.targetPaceMinPerKm ?? 6,
          note: item.note ?? ''
        }
  );
}

function toDraftItems(items: EditorItem[]): RoutineDraftItem[] {
  return items.map((item, index) =>
    item.kind === 'strength'
      ? {
          id: item.uid,
          kind: 'strength',
          exerciseId: item.exerciseId,
          order: index + 1,
          sets: item.sets,
          targetReps: item.targetReps,
          targetWeightKg: item.targetWeightKg,
          restSeconds: item.restSeconds,
          note: item.note || undefined
        }
      : {
          id: item.uid,
          kind: 'running',
          exerciseId: item.exerciseId,
          order: index + 1,
          targetDistanceKm: item.targetDistanceKm,
          targetDurationMin: item.targetDurationMin,
          targetPaceMinPerKm: item.targetPaceMinPerKm,
          note: item.note || undefined
        }
  );
}

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

const templateDifficultyOptions: Array<{ value: TemplateDifficultyFilter; label: string }> = [
  { value: 'all', label: '전체' },
  { value: 'beginner', label: '입문' },
  { value: 'intermediate', label: '중급' },
  { value: 'advanced', label: '고강도' }
];

const templateDifficultyLabelMap: Record<RoutineDifficulty, string> = {
  beginner: '입문',
  intermediate: '중급',
  advanced: '고강도'
};

const templateDifficultyDescriptionMap: Record<RoutineDifficulty, string> = {
  beginner: '동작 적응과 루틴 정착에 초점을 둔 템플릿',
  intermediate: '볼륨과 난도가 어느 정도 올라간 템플릿',
  advanced: '강도나 기술 요구가 상대적으로 높은 템플릿'
};

function getTemplateDifficulty(template: RoutineTemplate): RoutineDifficulty {
  return template.difficulty ?? 'beginner';
}

interface RoutineEditorProps {
  exercises: Exercise[];
  templates: RoutineTemplate[];
  isRefreshingTemplates: boolean;
  lastTemplateSyncAt: string | null;
  templateSyncError: string | null;
  onRefreshTemplates: () => Promise<void>;
  initialRoutine?: Routine | null;
  sequenceNumber: number;
  onClose: () => void;
}

function RoutineEditor({
  exercises,
  templates,
  isRefreshingTemplates,
  lastTemplateSyncAt,
  templateSyncError,
  onRefreshTemplates,
  initialRoutine,
  sequenceNumber,
  onClose
}: RoutineEditorProps) {
  const [name, setName] = useState(initialRoutine?.name ?? '');
  const [items, setItems] = useState<EditorItem[]>(initialRoutine ? toEditorItems(initialRoutine.items) : []);
  const [search, setSearch] = useState('');
  const [equipmentFilter, setEquipmentFilter] = useState<EquipmentFilter>('freeweight');
  const [muscleFilter, setMuscleFilter] = useState<MuscleFilter>('all');
  const [isSaving, setIsSaving] = useState(false);
  const [installingTemplateId, setInstallingTemplateId] = useState<string | null>(null);
  const [isTemplatePickerOpen, setIsTemplatePickerOpen] = useState(false);
  const [templateDifficultyFilter, setTemplateDifficultyFilter] = useState<TemplateDifficultyFilter>('all');
  const [guideExerciseId, setGuideExerciseId] = useState<string | null>(null);
  const deferredSearch = useDeferredValue(search);
  const exerciseLookup = new Map(exercises.map((exercise) => [exercise.id, exercise]));
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
  const filteredTemplates = templates.filter((template) =>
    templateDifficultyFilter === 'all'
      ? true
      : getTemplateDifficulty(template) === templateDifficultyFilter
  );
  const groupedTemplateEntries = (['beginner', 'intermediate', 'advanced'] as RoutineDifficulty[])
    .map((difficulty) => [
      difficulty,
      filteredTemplates.filter((template) => getTemplateDifficulty(template) === difficulty)
    ] as const)
    .filter(([, matchedTemplates]) => matchedTemplates.length > 0);

  useEffect(() => {
    setName(initialRoutine?.name ?? '');
    setItems(initialRoutine ? toEditorItems(initialRoutine.items) : []);
    setSearch('');
    setEquipmentFilter('freeweight');
    setMuscleFilter('all');
    setTemplateDifficultyFilter('all');
    setIsTemplatePickerOpen(false);
    setGuideExerciseId(null);
  }, [initialRoutine]);

  function addExercise(exercise: Exercise) {
    setItems((previous) => [
      ...previous,
      exercise.kind === 'strength'
        ? {
            uid: createId('editor'),
            kind: 'strength',
            exerciseId: exercise.id,
            sets: 3,
            targetReps: 10,
            targetWeightKg: 20,
            restSeconds: 90,
            note: ''
          }
        : {
            uid: createId('editor'),
            kind: 'running',
            exerciseId: exercise.id,
            targetDistanceKm: 3,
            targetDurationMin: 20,
            targetPaceMinPerKm: 6.3,
            note: ''
          }
    ]);
  }

  function updateItem(uid: string, nextValue: Partial<EditorItem>) {
    setItems((previous) =>
      previous.map((item) => (item.uid === uid ? { ...item, ...nextValue } as EditorItem : item))
    );
  }

  function removeItem(uid: string) {
    setItems((previous) => previous.filter((item) => item.uid !== uid));
  }

  async function handleTemplateInstall(templateId: string) {
    const hasDraftContent = Boolean(name.trim()) || items.length > 0;

    if (
      hasDraftContent &&
      !window.confirm('지금 입력 중인 새 루틴 내용은 저장되지 않고, 선택한 템플릿이 바로 추가됩니다. 계속할까요?')
    ) {
      return;
    }

    setInstallingTemplateId(templateId);

    try {
      await installTemplate(templateId, templates);
      onClose();
    } finally {
      setInstallingTemplateId(null);
    }
  }

  async function handleSave() {
    if (!name.trim()) {
      window.alert('루틴 이름을 입력해주세요.');
      return;
    }

    if (items.length === 0) {
      window.alert('운동을 하나 이상 추가해주세요.');
      return;
    }

    setIsSaving(true);

    try {
      await saveRoutine({
        name: name.trim(),
        items: toDraftItems(items),
        source: initialRoutine?.source ?? 'manual',
        routineId: initialRoutine?.id
      });
      onClose();
    } finally {
      setIsSaving(false);
    }
  }

  const activeGuideExercise =
    guideExerciseId ? exerciseLookup.get(guideExerciseId) ?? null : null;

  return (
    <div className="modal-backdrop" role="presentation">
      <section className="modal-sheet" aria-modal="true" role="dialog">
        <div className="modal-sheet__header">
          <div>
            <h2>{initialRoutine ? '루틴 수정' : '루틴 만들기'}</h2>
          </div>
          <button className="ghost-button" type="button" onClick={onClose}>
            닫기
          </button>
        </div>

        <div className="stack-list">
          {!initialRoutine ? (
            <section className="template-launcher">
              <div className="field">
                <span>추천 템플릿으로 빠르게 시작</span>
                <p className="muted-copy">
                  새 루틴을 직접 짜기 전에, 템플릿으로 바로 시작한 뒤 내 스타일에 맞게 수정할 수도 있어요.
                </p>
                <p className="muted-copy">
                  {templateSyncError
                    ? templateSyncError
                    : lastTemplateSyncAt
                      ? `마지막 갱신 ${new Intl.DateTimeFormat('ko-KR', {
                          month: 'long',
                          day: 'numeric',
                          hour: 'numeric',
                          minute: '2-digit'
                        }).format(new Date(lastTemplateSyncAt))}`
                      : '앱이 열려 있는 동안 추천 템플릿을 주기적으로 다시 확인합니다.'}
                </p>
              </div>
              <div className="button-row">
                <button
                  className="ghost-button template-launcher__button"
                  type="button"
                  onClick={() => setIsTemplatePickerOpen(true)}
                >
                  추천 템플릿 보기
                </button>
                <button
                  className="ghost-button ghost-button--compact"
                  type="button"
                  onClick={() => void onRefreshTemplates()}
                  disabled={isRefreshingTemplates}
                >
                  {isRefreshingTemplates ? '갱신 중...' : '지금 갱신'}
                </button>
              </div>
            </section>
          ) : null}

          <label className="field">
            <span>루틴 이름</span>
            <input
              type="text"
              value={name}
              placeholder="예: 상체 + 템포 런"
              onChange={(event) => setName(event.target.value)}
            />
          </label>

          <div className="field">
            <span>주간 순서</span>
            <p className="muted-copy">
              {initialRoutine
                ? `이 루틴은 저장 순서 기준 ${sequenceNumber}번째 순환 루틴입니다. 이번 주 운동을 하나 끝낼 때마다 다음 순서로 넘어가고, 새 주가 시작되면 다시 1번째 루틴부터 시작해요.`
                : `지금 저장하면 ${sequenceNumber}번째 순환 루틴으로 추가됩니다. 이번 주 운동을 하나 끝낼 때마다 다음 순서로 넘어가고, 새 주가 시작되면 다시 1번째 루틴부터 시작해요.`}
            </p>
          </div>

          <div className="field">
            <span>운동 추가</span>
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
            <p className="muted-copy">장비와 부위를 먼저 좁히고, 검색어를 입력하면 러닝 같은 유산소 운동도 바로 찾을 수 있어요.</p>
            <div className="inline-form inline-form--stack">
              <input
                type="search"
                value={search}
                placeholder="운동 검색"
                onChange={(event) => setSearch(event.target.value)}
              />
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
                              {exercise.guide?.cues?.length ? (
                                <ul className="exercise-points">
                                  {exercise.guide.cues.slice(0, 2).map((cue) => (
                                    <li key={cue}>{cue}</li>
                                  ))}
                                </ul>
                              ) : (
                                <p className="exercise-option__hint">
                                  {exercise.kind === 'running'
                                    ? '거리, 시간, 속도를 정해두면 유산소 루틴을 훨씬 편하게 관리할 수 있어요.'
                                    : '루틴에 추가한 뒤 세트, 반복, 중량, 휴식까지 바로 설계할 수 있어요.'}
                                </p>
                              )}
                            </div>
                            <div className="exercise-option__meta">
                              {exercise.guide ? <span className="chip">가이드</span> : null}
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
                                onClick={() => addExercise(exercise)}
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

          <div className="stack-list">
            {items.map((item) => {
              const selectedExercise = exerciseLookup.get(item.exerciseId);

              return (
                <article key={item.uid} className="editor-card">
                  <div className="editor-card__header">
                    <div>
                      <h3>{selectedExercise?.name ?? '운동'}</h3>
                      <p>{item.kind === 'strength' ? '웨이트 계획' : '유산소 계획'}</p>
                    </div>
                    <div className="button-row">
                      {selectedExercise?.guide ? (
                        <button
                          className="ghost-button"
                          type="button"
                          onClick={() => setGuideExerciseId(item.exerciseId)}
                        >
                          자세 보기
                        </button>
                      ) : null}
                      <button className="ghost-button" type="button" onClick={() => removeItem(item.uid)}>
                        삭제
                      </button>
                    </div>
                  </div>

                  {selectedExercise ? (
                    <details className="exercise-info-panel">
                      <summary className="exercise-info-panel__summary">
                        <div>
                          <strong>운동 정보</strong>
                          <p>{getExerciseSummary(selectedExercise)}</p>
                        </div>
                        <span>펼쳐서 보기</span>
                      </summary>
                      <div className="exercise-info-panel__content">
                        <div className="chip-row">
                          <span className="chip">{getExerciseKindLabel(selectedExercise)}</span>
                          <span className="chip">{getExerciseTargetLabel(selectedExercise)}</span>
                          <span className="chip">{getExerciseEquipmentLabel(selectedExercise)}</span>
                          {hasExerciseGuideVideo(selectedExercise) ? <span className="chip">영상 가이드</span> : null}
                        </div>

                        <div className="exercise-option__fact-grid">
                          <div className="exercise-option__fact">
                            <span>운동 유형</span>
                            <strong>{getExerciseKindLabel(selectedExercise)}</strong>
                          </div>
                          <div className="exercise-option__fact">
                            <span>주요 부위</span>
                            <strong>{getExerciseTargetLabel(selectedExercise)}</strong>
                          </div>
                          <div className="exercise-option__fact">
                            <span>장비</span>
                            <strong>{getExerciseEquipmentLabel(selectedExercise)}</strong>
                          </div>
                          <div className="exercise-option__fact">
                            <span>설계 포인트</span>
                            <strong>{getExercisePlanningHint(selectedExercise)}</strong>
                          </div>
                        </div>

                        {selectedExercise.guide?.cues?.length ? (
                          <section className="guide-copy">
                            <h3>체크 포인트</h3>
                            <ul className="guide-list">
                              {selectedExercise.guide.cues.map((cue) => (
                                <li key={cue}>{cue}</li>
                              ))}
                            </ul>
                          </section>
                        ) : null}

                        {selectedExercise.guide?.warning ? (
                          <section className="guide-warning">
                            <strong>주의</strong>
                            <p>{selectedExercise.guide.warning}</p>
                          </section>
                        ) : null}
                      </div>
                    </details>
                  ) : null}

                  {item.kind === 'strength' ? (
                    <div className="field-grid">
                      <label className="field">
                        <span>세트</span>
                        <input
                          type="number"
                          min="1"
                          step="1"
                          value={item.sets}
                          onChange={(event) =>
                            updateItem(item.uid, { sets: event.currentTarget.valueAsNumber || 1 })
                          }
                        />
                      </label>
                      <label className="field">
                        <span>목표 횟수</span>
                        <input
                          type="number"
                          min="1"
                          step="1"
                          value={item.targetReps}
                          onChange={(event) =>
                            updateItem(item.uid, { targetReps: event.currentTarget.valueAsNumber || 1 })
                          }
                        />
                      </label>
                      <label className="field">
                        <span>목표 중량 (kg)</span>
                        <input
                          type="number"
                          min="0"
                          step="0.5"
                          value={item.targetWeightKg}
                          onChange={(event) =>
                            updateItem(item.uid, {
                              targetWeightKg: event.currentTarget.valueAsNumber || 0
                            })
                          }
                        />
                      </label>
                      <label className="field">
                        <span>휴식 (초)</span>
                        <input
                          type="number"
                          min="15"
                          step="15"
                          value={item.restSeconds}
                          onChange={(event) =>
                            updateItem(item.uid, { restSeconds: event.currentTarget.valueAsNumber || 60 })
                          }
                        />
                      </label>
                    </div>
                  ) : (
                    <div className="field-grid">
                      <label className="field">
                        <span>목표 거리 (km)</span>
                        <input
                          type="number"
                          min="0.5"
                          step="0.1"
                          value={item.targetDistanceKm}
                          onChange={(event) =>
                            updateItem(item.uid, {
                              targetDistanceKm: event.currentTarget.valueAsNumber || 0
                            })
                          }
                        />
                      </label>
                      <label className="field">
                        <span>목표 시간 (분)</span>
                        <input
                          type="number"
                          min="5"
                          step="1"
                          value={item.targetDurationMin}
                          onChange={(event) =>
                            updateItem(item.uid, {
                              targetDurationMin: event.currentTarget.valueAsNumber || 0
                            })
                          }
                        />
                      </label>
                      <label className="field">
                        <span>목표 속도 (km/h)</span>
                        <input
                          type="number"
                          min="0.5"
                          step="0.1"
                          value={paceToSpeedKmh(item.targetPaceMinPerKm) ?? 0}
                          onChange={(event) =>
                            updateItem(item.uid, {
                              targetPaceMinPerKm:
                                speedToPaceMinPerKm(event.currentTarget.valueAsNumber) ?? 0
                            })
                          }
                        />
                      </label>
                    </div>
                  )}

                  <label className="field">
                    <span>메모</span>
                    <input
                      type="text"
                      value={item.note}
                      placeholder="휴식 강도, 주의점 등을 기록"
                      onChange={(event) => updateItem(item.uid, { note: event.target.value })}
                    />
                  </label>
                </article>
              );
            })}
          </div>
        </div>

        <button className="primary-button primary-button--full" type="button" onClick={handleSave} disabled={isSaving}>
          {isSaving ? '저장 중...' : initialRoutine ? '루틴 수정 저장' : '루틴 저장'}
        </button>

        {activeGuideExercise ? (
          <ExerciseGuideModal
            exercise={activeGuideExercise}
            onClose={() => setGuideExerciseId(null)}
          />
        ) : null}

        {!initialRoutine && isTemplatePickerOpen ? (
          <div className="modal-backdrop modal-backdrop--nested" role="presentation" onClick={() => setIsTemplatePickerOpen(false)}>
            <section
              className="modal-sheet modal-sheet--template-picker"
              aria-modal="true"
              role="dialog"
              onClick={(event) => event.stopPropagation()}
            >
              <div className="modal-sheet__header">
                <div>
                  <h2>추천 템플릿</h2>
                </div>
                <div className="button-row">
                  <button
                    className="ghost-button ghost-button--compact"
                    type="button"
                    onClick={() => void onRefreshTemplates()}
                    disabled={isRefreshingTemplates}
                  >
                    {isRefreshingTemplates ? '갱신 중...' : '새로고침'}
                  </button>
                  <button className="ghost-button" type="button" onClick={() => setIsTemplatePickerOpen(false)}>
                    닫기
                  </button>
                </div>
              </div>

              <div className="stack-list">
                <p className="muted-copy">
                  {templateSyncError
                    ? `원격 갱신 실패: ${templateSyncError}`
                    : lastTemplateSyncAt
                      ? `최신 확인 ${new Intl.DateTimeFormat('ko-KR', {
                          month: 'long',
                          day: 'numeric',
                          hour: 'numeric',
                          minute: '2-digit'
                        }).format(new Date(lastTemplateSyncAt))}`
                      : '기본 추천 템플릿을 표시하고 있습니다.'}
                </p>
                <div className="chip-row">
                  {templateDifficultyOptions.map((option) => (
                    <button
                      key={option.value}
                      type="button"
                      className={`chip chip--button${templateDifficultyFilter === option.value ? ' is-active' : ''}`}
                      onClick={() => setTemplateDifficultyFilter(option.value)}
                    >
                      {option.label}
                    </button>
                  ))}
                </div>
                {groupedTemplateEntries.length > 0 ? (
                  groupedTemplateEntries.map(([difficulty, matchedTemplates]) => (
                    <section key={difficulty} className="stack-list">
                      <div className="field">
                        <span>{templateDifficultyLabelMap[difficulty]}</span>
                        <p className="muted-copy">{templateDifficultyDescriptionMap[difficulty]}</p>
                      </div>
                      {matchedTemplates.map((template) => (
                        <article key={template.id} className="template-card">
                          <div>
                            <h3>{template.name}</h3>
                            <p>{template.blurb}</p>
                            <div className="template-card__meta">
                              <p>
                                <strong>부위</strong> {template.targets.join(' · ')}
                              </p>
                              <p>
                                <strong>효과</strong> {template.benefits.join(' · ')}
                              </p>
                            </div>
                            <div className="chip-row">
                              <span className="chip">{templateDifficultyLabelMap[getTemplateDifficulty(template)]}</span>
                              <span className="chip">{template.focus}</span>
                              <span className="chip">{template.items.length}개 운동</span>
                            </div>
                          </div>
                          <button
                            className="ghost-button"
                            type="button"
                            onClick={() => handleTemplateInstall(template.id)}
                            disabled={installingTemplateId === template.id}
                          >
                            {installingTemplateId === template.id ? '추가 중...' : '이 템플릿으로 시작'}
                          </button>
                        </article>
                      ))}
                    </section>
                  ))
                ) : (
                  <p className="muted-copy">선택한 난이도에 맞는 추천 템플릿이 아직 없습니다.</p>
                )}
              </div>
            </section>
          </div>
        ) : null}
      </section>
    </div>
  );
}

export function RoutinesPage() {
  const exercises = useLiveQuery(() => db.exercises.toArray(), []) ?? [];
  const routineRecords = useLiveQuery(() => db.routines.toArray(), []) ?? [];
  const routines = getOrderedRoutines(routineRecords);
  const {
    templates,
    isRefreshing: isRefreshingTemplates,
    lastSyncedAt,
    error: templateSyncError,
    refreshTemplates
  } = useRecommendationTemplates();
  const exerciseLookup = new Map(exercises.map((exercise) => [exercise.id, exercise]));
  const [editorTarget, setEditorTarget] = useState<Routine | null | undefined>(undefined);
  const [guideExerciseId, setGuideExerciseId] = useState<string | null>(null);

  async function handleDelete(routineId: string) {
    if (!window.confirm('이 루틴을 삭제할까요? 기존 세션 기록은 그대로 남습니다.')) {
      return;
    }

    await deleteRoutine(routineId);
  }

  const activeGuideExercise =
    guideExerciseId ? exerciseLookup.get(guideExerciseId) ?? null : null;
  const editorSequenceNumber =
    editorTarget === undefined
      ? 1
      : editorTarget === null
        ? routines.length + 1
        : Math.max(
            1,
            routines.findIndex((routine) => routine.id === editorTarget.id) + 1
          );

  return (
    <div className="page-stack">
      <SectionCard
        title="내 루틴"
        action={
          <button
            className="primary-button primary-button--header"
            type="button"
            onClick={() => setEditorTarget(null)}
          >
            새 루틴
          </button>
        }
      >
        <div className="stack-list">
          <p className="muted-copy">루틴은 저장한 순서대로 돌아가고, 새 주가 시작되면 1번째 루틴부터 다시 시작해요.</p>
          {routines.length > 0 ? (
            <div className="stack-list">
              {routines.map((routine, index) => (
                <article key={routine.id} className="routine-card">
                  <div className="routine-card__header">
                    <div className="routine-card__summary">
                      <h3>{routine.name}</h3>
                      <p>
                        순서 {index + 1} · {routine.items.length}개 운동
                      </p>
                    </div>
                    <div className="button-row routine-card__actions">
                      <button className="ghost-button" type="button" onClick={() => setEditorTarget(routine)}>
                        수정
                      </button>
                      <button className="ghost-button" type="button" onClick={() => handleDelete(routine.id)}>
                        삭제
                      </button>
                    </div>
                  </div>

                  <div className="chip-row">
                    {routine.items.map((item) => {
                      const exercise = exerciseLookup.get(item.exerciseId);

                      if (exercise?.guide) {
                        return (
                          <button
                            key={item.id}
                            type="button"
                            className="chip-button"
                            onClick={() => setGuideExerciseId(exercise.id)}
                          >
                            {exercise.name}
                          </button>
                        );
                      }

                      return (
                        <span key={item.id} className="chip">
                          {exercise?.name ?? '운동'}
                        </span>
                      );
                    })}
                  </div>
                  {routine.items.some((item) => exerciseLookup.get(item.exerciseId)?.guide) ? (
                    <p className="muted-copy">운동 이름을 누르면 자세 가이드를 볼 수 있어요.</p>
                  ) : null}
                </article>
              ))}
            </div>
          ) : (
            <EmptyState
              title="아직 저장된 루틴이 없어요"
              body="새 루틴을 열면 추천 템플릿으로 바로 시작하거나, 직접 나만의 루틴을 설계할 수 있어요."
              action={
                <button className="primary-button" type="button" onClick={() => setEditorTarget(null)}>
                  첫 루틴 만들기
                </button>
              }
            />
          )}
        </div>
      </SectionCard>

      {editorTarget !== undefined ? (
        <RoutineEditor
          exercises={exercises}
          templates={templates}
          isRefreshingTemplates={isRefreshingTemplates}
          lastTemplateSyncAt={lastSyncedAt}
          templateSyncError={templateSyncError}
          onRefreshTemplates={refreshTemplates}
          initialRoutine={editorTarget}
          sequenceNumber={editorSequenceNumber}
          onClose={() => setEditorTarget(undefined)}
        />
      ) : null}

      {activeGuideExercise ? (
        <ExerciseGuideModal
          exercise={activeGuideExercise}
          onClose={() => setGuideExerciseId(null)}
        />
      ) : null}
    </div>
  );
}
