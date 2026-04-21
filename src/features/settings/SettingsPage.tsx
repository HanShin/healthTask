import { useEffect, useRef, useState, type ChangeEvent } from 'react';
import { useLiveQuery } from 'dexie-react-hooks';
import { SectionCard } from '../../components/SectionCard';
import { exerciseCatalog } from '../../data/catalog';
import { backupToCloud, getCloudBackupMetadata, restoreFromCloud, type CloudBackupMetadata } from '../../lib/cloudBackup';
import { db } from '../../lib/db';
import { exportBackup, importBackup, resetAllData } from '../../lib/repository';
import { isSupabaseConfigured } from '../../lib/supabase';
import {
  ensurePersistentStorage,
  getCloudBackupKey,
  getBackupAgeInDays,
  getLastBackupAt,
  getStorageDurabilityStatus,
  markBackupExported,
  setCloudBackupKey,
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

function getCloudSetupCopy(configured: boolean): {
  tone: 'safe' | 'caution';
  title: string;
  body: string;
} {
  if (configured) {
    return {
      tone: 'safe',
      title: 'Supabase 연결 정보가 설정되어 있습니다.',
      body: '같은 백업 키를 입력하면 암호화된 운동 기록을 클라우드에 저장하거나 다른 기기에서 복원할 수 있습니다.'
    };
  }

  return {
    tone: 'caution',
    title: 'Supabase 환경변수가 아직 없습니다.',
    body: '`.env.local`에 URL과 anon key를 넣고, 저장소 루트의 `SUPABASE_SETUP.md`에 있는 테이블 SQL을 먼저 적용해 주세요.'
  };
}

export function SettingsPage() {
  const profile = useLiveQuery(() => db.profile.get('local-profile'), []);
  const [isBusy, setIsBusy] = useState(false);
  const [isCheckingProtection, setIsCheckingProtection] = useState(false);
  const [storageProtection, setStorageProtection] = useState<StorageDurabilityStatus | null>(null);
  const [lastBackupAt, setLastBackupAt] = useState<string | null>(() => getLastBackupAt());
  const [cloudBackupKey, setCloudBackupKeyState] = useState(() => getCloudBackupKey());
  const [cloudMetadata, setCloudMetadata] = useState<CloudBackupMetadata | null>(null);
  const [cloudMessage, setCloudMessage] = useState<string | null>(null);
  const [cloudError, setCloudError] = useState<string | null>(null);
  const [isCloudBusy, setIsCloudBusy] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const supabaseConfigured = isSupabaseConfigured();
  const storageCopy = getStorageProtectionCopy(storageProtection);
  const backupCopy = getBackupReminderCopy(lastBackupAt);
  const cloudSetupCopy = getCloudSetupCopy(supabaseConfigured);

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

  useEffect(() => {
    if (!supabaseConfigured) {
      setCloudMetadata(null);
      return;
    }

    const savedKey = getCloudBackupKey();

    if (!savedKey) {
      setCloudMetadata(null);
      return;
    }

    let active = true;

    void getCloudBackupMetadata(savedKey)
      .then((metadata) => {
        if (active) {
          setCloudMetadata(metadata);
        }
      })
      .catch(() => {
        if (active) {
          setCloudMetadata(null);
        }
      });

    return () => {
      active = false;
    };
  }, [supabaseConfigured]);

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

  function handleCloudBackupKeyChange(value: string) {
    setCloudBackupKeyState(value);
    setCloudBackupKey(value);
    setCloudMessage(null);
    setCloudError(null);
  }

  async function handleCloudBackup() {
    if (!supabaseConfigured) {
      setCloudMessage(null);
      setCloudError('Supabase 연결 정보가 아직 적용되지 않았습니다. `.env.local` 값을 확인한 뒤 서버를 다시 시작해 주세요.');
      return;
    }

    if (!cloudBackupKey.trim()) {
      setCloudMessage(null);
      setCloudError('클라우드에 저장하려면 먼저 백업 키를 입력해 주세요.');
      return;
    }

    setIsCloudBusy(true);
    setCloudMessage(null);
    setCloudError(null);

    try {
      const metadata = await backupToCloud(cloudBackupKey);
      setCloudMetadata(metadata);
      setCloudMessage(`클라우드에 저장했습니다. 마지막 업로드: ${formatDateTime(metadata.updatedAt)}`);
    } catch (error) {
      setCloudError(error instanceof Error ? error.message : '클라우드 저장에 실패했습니다.');
    } finally {
      setIsCloudBusy(false);
    }
  }

  async function handleCloudRestore() {
    if (!supabaseConfigured) {
      setCloudMessage(null);
      setCloudError('Supabase 연결 정보가 아직 적용되지 않았습니다. `.env.local` 값을 확인한 뒤 서버를 다시 시작해 주세요.');
      return;
    }

    if (!cloudBackupKey.trim()) {
      setCloudMessage(null);
      setCloudError('클라우드에서 복원하려면 먼저 백업 키를 입력해 주세요.');
      return;
    }

    if (!window.confirm('클라우드 백업으로 현재 기기 데이터를 덮어쓸까요?')) {
      return;
    }

    setIsCloudBusy(true);
    setCloudMessage(null);
    setCloudError(null);

    try {
      const metadata = await restoreFromCloud(cloudBackupKey);
      setCloudMetadata(metadata);
      setCloudMessage(`클라우드 백업을 복원했습니다. 백업 시각: ${formatDateTime(metadata.exportedAt)}`);
    } catch (error) {
      setCloudError(error instanceof Error ? error.message : '클라우드 복원에 실패했습니다.');
    } finally {
      setIsCloudBusy(false);
    }
  }

  async function handleRefreshCloudMetadata() {
    if (!supabaseConfigured) {
      setCloudMessage(null);
      setCloudError('Supabase 연결 정보가 아직 적용되지 않았습니다. `.env.local` 값을 확인한 뒤 서버를 다시 시작해 주세요.');
      return;
    }

    if (!cloudBackupKey.trim()) {
      setCloudMessage(null);
      setCloudError('클라우드 상태를 확인하려면 먼저 백업 키를 입력해 주세요.');
      return;
    }

    setIsCloudBusy(true);
    setCloudMessage(null);
    setCloudError(null);

    try {
      const metadata = await getCloudBackupMetadata(cloudBackupKey);
      setCloudMetadata(metadata);
      setCloudMessage(metadata ? '클라우드 백업 상태를 새로 확인했습니다.' : '아직 저장된 클라우드 백업이 없습니다.');
    } catch (error) {
      setCloudError(error instanceof Error ? error.message : '클라우드 상태 확인에 실패했습니다.');
    } finally {
      setIsCloudBusy(false);
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

      <SectionCard title="클라우드 백업">
        <div className="stack-list">
          <div className={`notice-card notice-card--${cloudSetupCopy.tone}`}>
            <strong>{cloudSetupCopy.title}</strong>
            <p>{cloudSetupCopy.body}</p>
          </div>
          <label className="field">
            <span>백업 키</span>
            <input
              type="password"
              value={cloudBackupKey}
              placeholder="다른 기기에서도 같은 키를 입력하세요"
              onChange={(event) => handleCloudBackupKeyChange(event.target.value)}
              autoComplete="off"
            />
          </label>
          <p className="muted-copy">이 키는 현재 기기에만 저장되고, 클라우드에는 암호화된 백업만 올라갑니다.</p>
          {!cloudBackupKey.trim() ? (
            <div className="notice-card notice-card--caution">
              <strong>백업 키가 필요합니다.</strong>
              <p>다른 기기에서도 기억할 수 있는 키를 먼저 입력해 주세요.</p>
            </div>
          ) : null}
          <div className="detail-row detail-row--wide">
            <span>클라우드 마지막 업로드</span>
            <strong>{cloudMetadata ? formatDateTime(cloudMetadata.updatedAt) : '없음'}</strong>
          </div>
          <div className="detail-row detail-row--wide">
            <span>클라우드 백업 기준 시각</span>
            <strong>{cloudMetadata ? formatDateTime(cloudMetadata.exportedAt) : '없음'}</strong>
          </div>
          {cloudMessage ? (
            <div className="notice-card notice-card--safe">
              <strong>클라우드 상태</strong>
              <p>{cloudMessage}</p>
            </div>
          ) : null}
          {cloudError ? (
            <div className="notice-card notice-card--danger">
              <strong>확인이 필요합니다.</strong>
              <p>{cloudError}</p>
            </div>
          ) : null}
          <div className="button-row">
            <button
              className="primary-button"
              type="button"
              onClick={handleCloudBackup}
              disabled={isCloudBusy}
            >
              {isCloudBusy ? '처리 중...' : '클라우드에 저장'}
            </button>
            <button
              className="ghost-button"
              type="button"
              onClick={handleCloudRestore}
              disabled={isCloudBusy}
            >
              클라우드에서 복원
            </button>
            <button
              className="ghost-button"
              type="button"
              onClick={handleRefreshCloudMetadata}
              disabled={isCloudBusy}
            >
              상태 새로고침
            </button>
          </div>
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
