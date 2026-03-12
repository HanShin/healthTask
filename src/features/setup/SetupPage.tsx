import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { createProfile } from '../../lib/repository';
import type { ExerciseKind } from '../../lib/types';

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

export function SetupPage() {
  const navigate = useNavigate();
  const [workoutTypes, setWorkoutTypes] = useState<ExerciseKind[]>(['strength', 'running']);
  const [workoutsPerWeek, setWorkoutsPerWeek] = useState(4);
  const [starterMode, setStarterMode] = useState<'recommended' | 'blank'>('recommended');
  const [isSaving, setIsSaving] = useState(false);

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
        starterMode
      });
      navigate('/today', { replace: true });
    } finally {
      setIsSaving(false);
    }
  }

  return (
    <form className="setup-stack" onSubmit={handleSubmit}>
      <section className="hero-card">
        <div className="hero-card__label">Mobile-first setup</div>
        <h2>로그인 없이 바로 시작하는 개인 운동 웹앱</h2>
        <p>
          오늘부터 바로 사용할 수 있도록 기본 설정과 추천 루틴만 빠르게 고릅니다.
        </p>
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
      </section>

      <button className="primary-button primary-button--full" type="submit" disabled={isSaving}>
        {isSaving ? '세팅 중...' : '시작하기'}
      </button>
    </form>
  );
}
