import { useEffect, useRef, useState, type ChangeEvent } from 'react';
import { useLiveQuery } from 'dexie-react-hooks';
import { SectionCard } from '../../components/SectionCard';
import { exerciseCatalog } from '../../data/catalog';
import { db } from '../../lib/db';
import { exportBackup, importBackup, resetAllData } from '../../lib/repository';
import {
  ensurePersistentStorage,
  getBackupAgeInDays,
  getLastBackupAt,
  getStorageDurabilityStatus,
  markBackupExported,
  type StorageDurabilityStatus
} from '../../lib/storage';

const workoutTypeLabelMap: Record<string, string> = {
  weight: '웨이트',
  bodyweight: '맨몸운동',
  cardio: '유산소',
  strength: '웨이트',
  running: '유산소'
};

function formatDateTime(value: string | null): string {
  if (!value) {
    return '없음';
  }

  const date = new Date(value);

  if (Number.isNaN(date.getTime())) {
    return '확인 불가';
  }

  return new Intl.DateTimeFormat('ko-KR', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit'
  }).format(date);
}

function formatWorkoutTypes(value: string[] | undefined): string {
  if (!value || value.length === 0) {
    return '-';
  }

  return value.map((item) => workoutTypeLabelMap[item] ?? item).join(' · ');
}

function getStorageProtectionCopy(status: StorageDurabilityStatus | null): {
  tone: 'neutral' | 'safe' | 'caution';
  title: string;
  body: string;
  label: string;
} {
  if (!status) {
    return {
      tone: 'neutral',
      title: '브라우저 저장 보호 상태를 확인 중입니다.',
      body: '지원 여부와 현재 보호 상태를 읽어오는 중입니다.',
      label: '확인 중'
    };
  }

  if (!status.supported) {
    return {
      tone: 'caution',
      title: '이 브라우저는 저장 보호 API를 제공하지 않습니다.',
      body: '기기 교체나 브라우저 초기화에 대비하려면 JSON 백업이 특히 중요합니다.',
      label: '지원 안 됨'
    };
  }

  if (status.persisted) {
    return {
      tone: 'safe',
      title: '브라우저에 로컬 기록을 오래 보존해 달라고 요청해둔 상태입니다.',
      body: '브라우저 정리 대상에서 제외될 가능성을 높여주지만, 앱 삭제나 기기 분실까지 막아주지는 않습니다.',
      label: '보호 요청됨'
    };
  }

  if (status.canPersist) {
    return {
      tone: 'caution',
      title: '영구 저장 요청이 아직 확정되지 않았습니다.',
      body: '저장 공간 정리나 브라우저 정책에 따라 데이터가 삭제될 수 있어, 백업을 함께 유지하는 편이 안전합니다.',
      label: '일반 저장'
    };
  }

  return {
    tone: 'caution',
    title: '이 브라우저에서는 영구 저장 요청을 직접 지원하지 않습니다.',
    body: '운동 기록을 오래 보관하려면 주기적인 JSON 백업을 권장합니다.',
    label: '일반 저장'
  };
}

function getBackupReminderCopy(lastBackupAt: string | null): {
  tone: 'safe' | 'caution' | 'danger';
  title: string;
  body: string;
} {
  const backupAgeInDays = getBackupAgeInDays(lastBackupAt);

  if (backupAgeInDays === null) {
    return {
      tone: 'danger',
      title: '아직 JSON 백업이 없습니다.',
      body: '휴대폰 교체, 브라우저 초기화, 앱 삭제 전에 복구할 수 있도록 최소 한 번은 백업 파일을 만들어 두는 편이 안전합니다.'
    };
  }

  if (backupAgeInDays > 30) {
    return {
      tone: 'danger',
      title: `마지막 백업이 ${backupAgeInDays}일 전입니다.`,
      body: '최근 운동 기록이 백업 파일에 반영되지 않았을 수 있으니 새로 내보내 두는 것을 권장합니다.'
    };
  }

  if (backupAgeInDays > 7) {
    return {
      tone: 'caution',
      title: `마지막 백업이 ${backupAgeInDays}일 전입니다.`,
      body: '장기간 기록을 남기려면 1주일 이상 지났을 때 한 번씩 새 백업을 만들어 두면 훨씬 안전합니다.'
    };
  }

  return {
    tone: 'safe',
    title: backupAgeInDays === 0 ? '오늘 백업한 기록이 있습니다.' : `최근 ${backupAgeInDays}일 안에 백업했습니다.`,
    body: '로컬 저장 보호와 별개로, 중요한 기록이 늘어났다면 새 백업을 한 번 더 만들어 두면 복구가 쉬워집니다.'
  };
}

export function SettingsPage() {
  const profile = useLiveQuery(() => db.profile.get('local-profile'), []);
  const [isBusy, setIsBusy] = useState(false);
  const [isCheckingProtection, setIsCheckingProtection] = useState(false);
  const [storageProtection, setStorageProtection] = useState<StorageDurabilityStatus | null>(null);
  const [lastBackupAt, setLastBackupAt] = useState<string | null>(() => getLastBackupAt());
  const fileInputRef = useRef<HTMLInputElement>(null);
  const storageCopy = getStorageProtectionCopy(storageProtection);
  const backupCopy = getBackupReminderCopy(lastBackupAt);

  useEffect(() => {
    let active = true;

    void getStorageDurabilityStatus().then((status) => {
      if (active) {
        setStorageProtection(status);
      }
    });

    return () => {
      active = false;
    };
  }, []);

  async function handleRequestStorageProtection() {
    setIsCheckingProtection(true);

    try {
      setStorageProtection(await ensurePersistentStorage());
    } finally {
      setIsCheckingProtection(false);
    }
  }

  async function handleExport() {
    setIsBusy(true);

    try {
      const rawText = await exportBackup();
      const exportedAt = new Date().toISOString();
      const blob = new Blob([rawText], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = `today-workout-log-${exportedAt.slice(0, 10)}.json`;
      anchor.click();
      markBackupExported(exportedAt);
      setLastBackupAt(exportedAt);
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
      <SectionCard title="운동 기본값">
        <div className="stack-list">
          <div className="detail-row detail-row--wide">
            <span>운동 유형</span>
            <strong>{formatWorkoutTypes(profile?.workoutTypes)}</strong>
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

      <SectionCard
        title="로컬 저장 보호"
        action={
          storageProtection?.supported && storageProtection.canPersist && !storageProtection.persisted ? (
            <button
              className="ghost-button ghost-button--compact"
              type="button"
              onClick={handleRequestStorageProtection}
              disabled={isCheckingProtection}
            >
              {isCheckingProtection ? '요청 중...' : '보호 다시 요청'}
            </button>
          ) : null
        }
      >
        <div className="stack-list">
          <div className={`notice-card notice-card--${storageCopy.tone}`}>
            <strong>{storageCopy.title}</strong>
            <p>{storageCopy.body}</p>
          </div>
          <div className="detail-row detail-row--wide">
            <span>브라우저 보존 상태</span>
            <strong>{storageCopy.label}</strong>
          </div>
        </div>
      </SectionCard>

      <SectionCard title="데이터 백업">
        <div className="stack-list">
          <div className="notice-card notice-card--neutral">
            <strong>운동 기록은 현재 브라우저에 저장됩니다.</strong>
            <p>Vercel에 배포하더라도 서버가 아니라 지금 사용하는 브라우저에만 남으니, 기기 교체나 브라우저 초기화 전에는 JSON 백업을 꼭 저장해 두세요.</p>
          </div>
          <div className={`notice-card notice-card--${backupCopy.tone}`}>
            <strong>{backupCopy.title}</strong>
            <p>{backupCopy.body}</p>
          </div>
          <div className="detail-row detail-row--wide">
            <span>마지막 JSON 백업</span>
            <strong>{formatDateTime(lastBackupAt)}</strong>
          </div>
          <p className="muted-copy">기기 교체 전에는 JSON 백업을 한 번 저장해 두는 편이 안전합니다.</p>
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

      <SectionCard title="초기화">
        <button className="ghost-button ghost-button--danger" type="button" onClick={handleReset} disabled={isBusy}>
          앱 데이터 초기화
        </button>
      </SectionCard>
    </div>
  );
}
