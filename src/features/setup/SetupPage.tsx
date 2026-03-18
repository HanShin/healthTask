import { useRef, useState, type ChangeEvent, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { getStarterTemplateIds } from '../../data/catalog';
import { restoreFromCloud } from '../../lib/cloudBackup';
import { db } from '../../lib/db';
import { useRecommendationTemplates } from '../../lib/recommendedTemplates';
import { createProfile, importBackup } from '../../lib/repository';
import { isSupabaseConfigured } from '../../lib/supabase';
import { getCloudBackupKey, setCloudBackupKey } from '../../lib/storage';
import type { ExerciseKind, RoutineDifficulty, RoutineTemplate } from '../../lib/types';

const workoutTypeOptions: Array<{ label: string; value: ExerciseKind; note: string }> = [
  {
    label: '웨이트',
    value: 'strength',
    note: '세트, 횟수, 중량 중심으로 기록'
  },
  {
    label: '러닝',
    value: 'running',
    note: '거리, 시간, 페이스 중심으로 기록'
  }
];

const starterDifficultyOptions: Array<{ value: RoutineDifficulty; label: string; note: string }> = [
  {
    value: 'beginner',
    label: '입문',
    note: '동작 적응과 꾸준한 시작에 맞춘 루틴'
  },
  {
    value: 'intermediate',
    label: '중급',
    note: '볼륨과 자극을 조금 더 올린 균형형 루틴'
  },
  {
    value: 'advanced',
    label: '고강도',
    note: '볼륨, 기술, 체력 요구가 더 높은 루틴'
  }
];

const starterDifficultyLabelMap: Record<RoutineDifficulty, string> = {
  beginner: '입문',
  intermediate: '중급',
  advanced: '고강도'
};

export function SetupPage() {
  const navigate = useNavigate();
  const [workoutTypes, setWorkoutTypes] = useState<ExerciseKind[]>(['strength', 'running']);
  const [workoutsPerWeek, setWorkoutsPerWeek] = useState(4);
  const [starterMode, setStarterMode] = useState<'recommended' | 'blank'>('recommended');
  const [starterDifficulty, setStarterDifficulty] = useState<RoutineDifficulty>('beginner');
  const [isSaving, setIsSaving] = useState(false);
  const [isRestoring, setIsRestoring] = useState(false);
  const [cloudBackupKey, setCloudBackupKeyState] = useState(() => getCloudBackupKey());
  const [restoreMessage, setRestoreMessage] = useState<string | null>(null);
  const [restoreError, setRestoreError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const supabaseConfigured = isSupabaseConfigured();
  const {
    templates,
    lastSyncedAt,
    source: templateSource,
    isRefreshing: isRefreshingTemplates,
    error: templateError,
    isRemoteConfigured
  } = useRecommendationTemplates();
  const starterTemplateIds = getStarterTemplateIds({
    workoutTypes,
    workoutsPerWeek,
    starterMode,
    starterDifficulty
  });
  const starterTemplates = starterTemplateIds
    .map((templateId) => templates.find((template) => template.id === templateId))
    .filter((template): template is RoutineTemplate => Boolean(template));

  function toggleWorkoutType(type: ExerciseKind) {
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
      await createProfile({
        workoutTypes,
        workoutsPerWeek,
        starterMode,
        starterDifficulty
      }, templates);
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

  function handleCloudBackupKeyChange(value: string) {
    setCloudBackupKeyState(value);
    setCloudBackupKey(value);
    setRestoreMessage(null);
    setRestoreError(null);
  }

  async function handleCloudRestore() {
    if (!supabaseConfigured) {
      setRestoreMessage(null);
      setRestoreError('Supabase 연결 정보가 아직 설정되지 않았습니다.');
      return;
    }

    if (!cloudBackupKey.trim()) {
      setRestoreMessage(null);
      setRestoreError('클라우드에서 복원하려면 먼저 백업 키를 입력해 주세요.');
      return;
    }

    setIsRestoring(true);
    setRestoreMessage(null);
    setRestoreError(null);

    try {
      await restoreFromCloud(cloudBackupKey);
      await completeRestoreFlow('클라우드 백업을 복원했습니다.');
    } catch (error) {
      setRestoreError(error instanceof Error ? error.message : '클라우드 복원에 실패했습니다.');
    } finally {
      setIsRestoring(false);
    }
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
        <div className="panel__header">
          <div className="panel__copy">
            <h2>이미 기록이 있나요?</h2>
            <p>빈 앱 상태에서도 JSON 또는 클라우드 백업을 먼저 불러와서 바로 이어서 시작할 수 있습니다.</p>
          </div>
        </div>

        <div className="stack-list">
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

          <label className="field">
            <span>클라우드 백업 키</span>
            <input
              type="password"
              value={cloudBackupKey}
              placeholder="설정 화면에서 사용한 백업 키"
              onChange={(event) => handleCloudBackupKeyChange(event.target.value)}
              autoComplete="off"
            />
          </label>

          <div className={`notice-card notice-card--${supabaseConfigured ? 'neutral' : 'caution'}`}>
            <strong>{supabaseConfigured ? 'Supabase 복원을 사용할 수 있습니다.' : 'Supabase 연결 정보가 아직 없습니다.'}</strong>
            <p>
              {supabaseConfigured
                ? '같은 백업 키를 입력한 뒤 클라우드 백업을 복원하면 기존 운동 기록과 설정을 그대로 가져옵니다.'
                : '현재는 JSON 복원만 사용할 수 있습니다. Supabase 환경변수가 적용되면 클라우드 복원도 바로 열립니다.'}
            </p>
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

          <div className="button-row">
            <button className="primary-button" type="button" onClick={handleCloudRestore} disabled={isRestoring}>
              {isRestoring ? '복원 중...' : '클라우드에서 복원'}
            </button>
          </div>
        </div>
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
              <small>오늘 화면에 바로 루틴과 추천 문구가 채워집니다.</small>
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
              <small>루틴 탭에서 직접 설계부터 진행합니다.</small>
            </span>
          </label>
        </div>

        <div className={`notice-card notice-card--${templateError ? 'caution' : 'neutral'}`}>
          <strong>
            {isRemoteConfigured
              ? isRefreshingTemplates
                ? '추천 루틴을 최신 데이터로 확인 중입니다.'
                : '추천 루틴은 주기적으로 최신 데이터와 동기화됩니다.'
              : '현재는 기본 추천 루틴으로 시작합니다.'}
          </strong>
          <p>
            {templateError
              ? templateError
              : isRemoteConfigured
                ? lastSyncedAt
                  ? `마지막 갱신: ${new Intl.DateTimeFormat('ko-KR', {
                      month: 'long',
                      day: 'numeric',
                      hour: 'numeric',
                      minute: '2-digit'
                    }).format(new Date(lastSyncedAt))} · ${templateSource === 'remote' ? '원격 템플릿 반영됨' : '캐시 템플릿 사용 중'}`
                  : '앱이 열려 있는 동안 일정 주기로 추천 템플릿을 다시 확인합니다.'
                : 'Supabase 추천 템플릿 테이블을 연결하면 추천 루틴도 원격으로 갱신됩니다.'}
          </p>
        </div>

        {starterMode === 'recommended' ? (
          <div className="stack-list">
            <div className="field">
              <span>추천 강도</span>
              <p className="muted-copy">처음 세팅할 추천 루틴을 입문, 중급, 고강도 중 하나로 맞춰서 바로 시작할 수 있습니다.</p>
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
      </section>

      <button className="primary-button primary-button--full" type="submit" disabled={isSaving}>
        {isSaving ? '세팅 중...' : '시작하기'}
      </button>
    </form>
  );
}
