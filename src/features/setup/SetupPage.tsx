import { useRef, useState, type ChangeEvent, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { getStarterTemplateIds } from '../../data/catalog';
import { db } from '../../lib/db';
import { useRecommendationTemplates } from '../../lib/recommendedTemplates';
import { createProfile, importBackup } from '../../lib/repository';
import type { RoutineDifficulty, RoutineTemplate, WorkoutTypeSelection } from '../../lib/types';

const workoutTypeOptions: Array<{ label: string; value: WorkoutTypeSelection; note: string }> = [
  {
    label: '웨이트',
    value: 'weight',
    note: '세트, 횟수, 중량'
  },
  {
    label: '맨몸운동',
    value: 'bodyweight',
    note: '세트, 횟수, 휴식'
  },
  {
    label: '유산소',
    value: 'cardio',
    note: '종류, 거리, 시간'
  }
];

const starterDifficultyOptions: Array<{ value: RoutineDifficulty; label: string; note: string }> = [
  {
    value: 'beginner',
    label: '입문',
    note: '동작 적응과 꾸준한 시작'
  },
  {
    value: 'intermediate',
    label: '중급',
    note: '볼륨과 자극을 조금 더 올린 구성'
  },
  {
    value: 'advanced',
    label: '고강도',
    note: '강도와 체력 요구가 높은 구성'
  }
];

const starterDifficultyLabelMap: Record<RoutineDifficulty, string> = {
  beginner: '입문',
  intermediate: '중급',
  advanced: '고강도'
};

export function SetupPage() {
  const navigate = useNavigate();
  const [workoutTypes, setWorkoutTypes] = useState<WorkoutTypeSelection[]>(['weight', 'cardio']);
  const [workoutsPerWeek, setWorkoutsPerWeek] = useState(4);
  const [starterMode, setStarterMode] = useState<'recommended' | 'blank'>('recommended');
  const [starterDifficulty, setStarterDifficulty] = useState<RoutineDifficulty>('beginner');
  const [isSaving, setIsSaving] = useState(false);
  const [isRestoring, setIsRestoring] = useState(false);
  const [restoreMessage, setRestoreMessage] = useState<string | null>(null);
  const [restoreError, setRestoreError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const { templates } = useRecommendationTemplates();
  const starterTemplateIds = getStarterTemplateIds({
    workoutTypes,
    workoutsPerWeek,
    starterMode,
    starterDifficulty
  });
  const starterTemplates = starterTemplateIds
    .map((templateId) => templates.find((template) => template.id === templateId))
    .filter((template): template is RoutineTemplate => Boolean(template));

  function toggleWorkoutType(type: WorkoutTypeSelection) {
    setWorkoutTypes((previous) => {
      if (previous.includes(type)) {
        return previous.length === 1 ? previous : previous.filter((item) => item !== type);
      }

      return [...previous, type];
    });
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();

    if (workoutTypes.length === 0) {
      window.alert('최소 한 가지 운동 유형은 선택해주세요.');
      return;
    }

    setIsSaving(true);

    try {
      await createProfile(
        {
          workoutTypes,
          workoutsPerWeek,
          starterMode,
          starterDifficulty
        },
        templates
      );
      navigate('/today', { replace: true });
    } finally {
      setIsSaving(false);
    }
  }

  async function completeRestoreFlow(successMessage: string) {
    const profile = await db.profile.get('local-profile');

    if (profile) {
      navigate('/today', { replace: true });
      return;
    }

    setRestoreMessage(`${successMessage} 프로필 정보가 없는 백업이라면 아래 설정을 이어서 완료해 주세요.`);
  }

  async function handleJsonImport(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];

    if (!file) {
      return;
    }

    setIsRestoring(true);
    setRestoreMessage(null);
    setRestoreError(null);

    try {
      await importBackup(await file.text());
      await completeRestoreFlow('JSON 백업을 불러왔습니다.');
    } catch (error) {
      setRestoreError(error instanceof Error ? error.message : 'JSON 백업 복원에 실패했습니다.');
    } finally {
      setIsRestoring(false);
      event.target.value = '';
    }
  }

  return (
    <form className="setup-stack" onSubmit={handleSubmit}>
      <section className="panel panel--soft">
        <details className="compact-details">
          <summary className="compact-details__summary">
            <div>
              <strong>이전 기록 불러오기</strong>
              <p>JSON 백업 파일이 있으면 먼저 복원해요.</p>
            </div>
            <span>열기</span>
          </summary>

          <div className="compact-details__content stack-list">
            <div className="button-row">
              <button
                className="ghost-button"
                type="button"
                onClick={() => fileInputRef.current?.click()}
                disabled={isRestoring}
              >
                {isRestoring ? '복원 중...' : 'JSON 불러오기'}
              </button>
            </div>
            <input
              ref={fileInputRef}
              hidden
              type="file"
              accept="application/json"
              onChange={handleJsonImport}
            />

            <div className="notice-card notice-card--neutral">
              <strong>운동 기록은 JSON 백업으로 기기 사이를 옮길 수 있어요.</strong>
              <p>이 앱은 현재 브라우저에 저장되므로, 다른 기기나 브라우저로 옮길 때는 기존 백업 파일을 불러오면 됩니다.</p>
            </div>

            {restoreMessage ? (
              <div className="notice-card notice-card--safe">
                <strong>복원이 완료되었습니다.</strong>
                <p>{restoreMessage}</p>
              </div>
            ) : null}

            {restoreError ? (
              <div className="notice-card notice-card--danger">
                <strong>복원에 실패했습니다.</strong>
                <p>{restoreError}</p>
              </div>
            ) : null}
          </div>
        </details>
      </section>

      <section className="panel">
        <div className="panel__header">
          <div>
            <div className="eyebrow">01</div>
            <h2>운동 유형</h2>
          </div>
        </div>

        <div className="toggle-grid">
          {workoutTypeOptions.map((option) => (
            <button
              key={option.value}
              type="button"
              className={`toggle-card${workoutTypes.includes(option.value) ? ' is-active' : ''}`}
              onClick={() => toggleWorkoutType(option.value)}
            >
              <strong>{option.label}</strong>
              <span>{option.note}</span>
            </button>
          ))}
        </div>
      </section>

      <section className="panel">
        <div className="panel__header">
          <div>
            <div className="eyebrow">02</div>
            <h2>주간 빈도</h2>
          </div>
        </div>

        <div className="segmented">
          {[3, 4, 5].map((value) => (
            <button
              key={value}
              type="button"
              className={`segmented__item${workoutsPerWeek === value ? ' is-active' : ''}`}
              onClick={() => setWorkoutsPerWeek(value)}
            >
              주 {value}회
            </button>
          ))}
        </div>
      </section>

      <section className="panel">
        <div className="panel__header">
          <div>
            <div className="eyebrow">03</div>
            <h2>시작 방식</h2>
          </div>
        </div>

        <div className="stack-list">
          <div className="stack-list">
            <label className={`choice-card${starterMode === 'recommended' ? ' is-active' : ''}`}>
              <input
                type="radio"
                name="starterMode"
                value="recommended"
                checked={starterMode === 'recommended'}
                onChange={() => setStarterMode('recommended')}
              />
              <span>
                <strong>추천 루틴으로 시작</strong>
                <small>오늘 화면에 루틴과 추천이 바로 채워져요.</small>
              </span>
            </label>

            <label className={`choice-card${starterMode === 'blank' ? ' is-active' : ''}`}>
              <input
                type="radio"
                name="starterMode"
                value="blank"
                checked={starterMode === 'blank'}
                onChange={() => setStarterMode('blank')}
              />
              <span>
                <strong>빈 앱으로 시작</strong>
                <small>루틴 탭에서 직접 설계를 시작해요.</small>
              </span>
            </label>
          </div>

          <div className="notice-card notice-card--neutral">
            <strong>기본 추천 루틴으로 바로 시작합니다.</strong>
            <p>앱에 포함된 추천 루틴을 운동 유형과 주간 빈도에 맞춰 바로 세팅해요.</p>
          </div>

          {starterMode === 'recommended' ? (
            <div className="stack-list">
              <div className="field">
                <span>추천 강도</span>
                <p className="muted-copy">처음 시작할 난도를 골라요.</p>
              </div>

              <div className="segmented">
                {starterDifficultyOptions.map((option) => (
                  <button
                    key={option.value}
                    type="button"
                    className={`segmented__item${starterDifficulty === option.value ? ' is-active' : ''}`}
                    onClick={() => setStarterDifficulty(option.value)}
                  >
                    {option.label}
                  </button>
                ))}
              </div>

              <div className="notice-card notice-card--neutral">
                <strong>{starterDifficultyLabelMap[starterDifficulty]} 기준 추천 루틴 {starterTemplates.length}개를 바로 세팅합니다.</strong>
                <p>{starterDifficultyOptions.find((option) => option.value === starterDifficulty)?.note}</p>
              </div>

              {starterTemplates.length > 0 ? (
                <div className="stack-list">
                  {starterTemplates.map((template) => (
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
                          <span className="chip">{starterDifficultyLabelMap[template.difficulty ?? 'beginner']}</span>
                          <span className="chip">{template.focus}</span>
                          <span className="chip">{template.items.length}개 운동</span>
                        </div>
                      </div>
                    </article>
                  ))}
                </div>
              ) : (
                <p className="muted-copy">현재 조건에 맞는 추천 루틴 미리보기를 준비하지 못했습니다.</p>
              )}
            </div>
          ) : null}
        </div>
      </section>

      <button className="primary-button primary-button--full" type="submit" disabled={isSaving}>
        {isSaving ? '세팅 중...' : '시작하기'}
      </button>
    </form>
  );
}
