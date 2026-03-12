import { useRef, useState, type ChangeEvent } from 'react';
import { useLiveQuery } from 'dexie-react-hooks';
import { SectionCard } from '../../components/SectionCard';
import { exerciseCatalog } from '../../data/catalog';
import { db } from '../../lib/db';
import { exportBackup, importBackup, resetAllData } from '../../lib/repository';

export function SettingsPage() {
  const profile = useLiveQuery(() => db.profile.get('local-profile'), []);
  const [isBusy, setIsBusy] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  async function handleExport() {
    setIsBusy(true);

    try {
      const rawText = await exportBackup();
      const blob = new Blob([rawText], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = `hansin-workout-log-${new Date().toISOString().slice(0, 10)}.json`;
      anchor.click();
      URL.revokeObjectURL(url);
    } finally {
      setIsBusy(false);
    }
  }

  async function handleImport(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];

    if (!file) {
      return;
    }

    setIsBusy(true);

    try {
      await importBackup(await file.text());
      window.alert('백업을 불러왔습니다.');
    } finally {
      setIsBusy(false);
      event.target.value = '';
    }
  }

  async function handleReset() {
    if (!window.confirm('앱 데이터를 초기화할까요? 기본 운동 사전만 남기고 모두 지웁니다.')) {
      return;
    }

    setIsBusy(true);

    try {
      await resetAllData(exerciseCatalog);
      window.location.href = '/setup';
    } finally {
      setIsBusy(false);
    }
  }

  return (
    <div className="page-stack">
      <SectionCard eyebrow="Profile" title="현재 설정">
        <div className="stack-list">
          <div className="detail-row detail-row--wide">
            <span>운동 유형</span>
            <strong>{profile?.workoutTypes.join(' + ') ?? '-'}</strong>
          </div>
          <div className="detail-row detail-row--wide">
            <span>주간 목표</span>
            <strong>{profile?.weeklyGoalCount ?? 0}회</strong>
          </div>
          <div className="detail-row detail-row--wide">
            <span>기본 단위</span>
            <strong>kg / km</strong>
          </div>
        </div>
      </SectionCard>

      <SectionCard eyebrow="Backup" title="데이터 백업">
        <div className="stack-list">
          <p className="lead-copy">
            이 앱은 브라우저의 IndexedDB에 저장됩니다. 휴대폰 교체나 브라우저 초기화 전에는 JSON 백업을 받아두는 편이 안전합니다.
          </p>
          <div className="button-row">
            <button className="primary-button" type="button" onClick={handleExport} disabled={isBusy}>
              JSON 내보내기
            </button>
            <button
              className="ghost-button"
              type="button"
              onClick={() => fileInputRef.current?.click()}
              disabled={isBusy}
            >
              JSON 불러오기
            </button>
          </div>
          <input
            ref={fileInputRef}
            hidden
            type="file"
            accept="application/json"
            onChange={handleImport}
          />
        </div>
      </SectionCard>

      <SectionCard eyebrow="Deploy" title="Vercel 배포 메모">
        <div className="stack-list">
          <p className="lead-copy">
            Vercel Hobby에 GitHub 저장소를 연결하면 정적 배포로 바로 사용할 수 있습니다. 이 프로젝트는 `vercel.json` 리라이트를 포함해서 SPA 라우팅도 이미 맞춰두었습니다.
          </p>
          <p className="muted-copy">
            설치형 앱처럼 쓰려면 모바일 브라우저에서 홈 화면에 추가를 실행하세요.
          </p>
        </div>
      </SectionCard>

      <SectionCard eyebrow="Danger zone" title="초기화">
        <button className="ghost-button ghost-button--danger" type="button" onClick={handleReset} disabled={isBusy}>
          앱 데이터 초기화
        </button>
      </SectionCard>
    </div>
  );
}
