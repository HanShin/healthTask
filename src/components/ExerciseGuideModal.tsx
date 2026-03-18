import type { Exercise } from '../lib/types';
import {
  getExerciseEquipmentLabel,
  getExerciseKindLabel,
  getExercisePlanningHint,
  getExerciseTargetLabel
} from '../lib/exercise';

interface ExerciseGuideModalProps {
  exercise: Exercise;
  onClose: () => void;
}

export function ExerciseGuideModal({ exercise, onClose }: ExerciseGuideModalProps) {
  if (!exercise.guide) {
    return null;
  }

  const videoResources = (exercise.guide.resources ?? []).filter((resource) => resource.kind === 'video');
  const featuredVideo = videoResources.find((resource) => resource.embedUrl) ?? videoResources[0] ?? null;

  return (
    <div className="modal-backdrop" role="presentation" onClick={onClose}>
      <section
        className="modal-sheet modal-sheet--guide"
        aria-modal="true"
        role="dialog"
        onClick={(event) => event.stopPropagation()}
        >
        <div className="modal-sheet__header">
          <div>
            <h2>{exercise.name}</h2>
            <div className="chip-row guide-meta-row">
              <span className="chip">{getExerciseKindLabel(exercise)}</span>
              <span className="chip">{getExerciseTargetLabel(exercise)}</span>
              <span className="chip">{getExerciseEquipmentLabel(exercise)}</span>
            </div>
          </div>
          <button className="ghost-button" type="button" onClick={onClose}>
            닫기
          </button>
        </div>

        <section className="guide-fact-grid">
          <div className="guide-fact-card">
            <span>주요 부위</span>
            <strong>{getExerciseTargetLabel(exercise)}</strong>
          </div>
          <div className="guide-fact-card">
            <span>장비</span>
            <strong>{getExerciseEquipmentLabel(exercise)}</strong>
          </div>
          <div className="guide-fact-card">
            <span>운동 유형</span>
            <strong>{getExerciseKindLabel(exercise)}</strong>
          </div>
          <div className="guide-fact-card">
            <span>설계 포인트</span>
            <strong>{getExercisePlanningHint(exercise)}</strong>
          </div>
        </section>

        {featuredVideo ? (
          <section className="guide-copy">
            <h3>움직이는 참고 영상</h3>
            <div className="guide-video-frame">
              <iframe
                src={featuredVideo.embedUrl}
                title={featuredVideo.title}
                loading="lazy"
                allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share"
                referrerPolicy="strict-origin-when-cross-origin"
                allowFullScreen
              />
            </div>
            <a
              className="resource-card resource-card--inline"
              href={featuredVideo.url}
              target="_blank"
              rel="noreferrer"
            >
              <div>
                <strong>{featuredVideo.title}</strong>
                <p>{featuredVideo.provider}</p>
              </div>
              <span>열기</span>
            </a>
          </section>
        ) : null}

        <div className="stack-list">
          <p className="lead-copy">{exercise.guide.headline}</p>

          <section className="guide-copy">
            <h3>체크 포인트</h3>
            <ul className="guide-list">
              {exercise.guide.cues.map((cue) => (
                <li key={cue}>{cue}</li>
              ))}
            </ul>
          </section>

          {exercise.guide.warning ? (
            <section className="guide-warning">
              <strong>주의</strong>
              <p>{exercise.guide.warning}</p>
            </section>
          ) : null}
        </div>
      </section>
    </div>
  );
}
