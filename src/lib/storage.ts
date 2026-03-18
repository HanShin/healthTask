const LAST_BACKUP_AT_KEY = 'health-task:last-backup-at';
const CLOUD_BACKUP_KEY = 'health-task:cloud-backup-key';

export interface StorageDurabilityStatus {
  supported: boolean;
  canPersist: boolean;
  persisted: boolean;
}

export async function getStorageDurabilityStatus(): Promise<StorageDurabilityStatus> {
  if (typeof navigator === 'undefined' || !('storage' in navigator)) {
    return {
      supported: false,
      canPersist: false,
      persisted: false
    };
  }

  try {
    const persisted =
      typeof navigator.storage.persisted === 'function' ? await navigator.storage.persisted() : false;

    return {
      supported: true,
      canPersist: typeof navigator.storage.persist === 'function',
      persisted
    };
  } catch {
    return {
      supported: true,
      canPersist: typeof navigator.storage.persist === 'function',
      persisted: false
    };
  }
}

export async function ensurePersistentStorage(): Promise<StorageDurabilityStatus> {
  const status = await getStorageDurabilityStatus();

  if (!status.supported || status.persisted || !status.canPersist) {
    return status;
  }

  try {
    const persisted = await navigator.storage.persist();

    return {
      ...status,
      persisted
    };
  } catch {
    return status;
  }
}

export function getLastBackupAt(): string | null {
  if (typeof window === 'undefined') {
    return null;
  }

  try {
    return window.localStorage.getItem(LAST_BACKUP_AT_KEY);
  } catch {
    return null;
  }
}

export function getCloudBackupKey(): string {
  if (typeof window === 'undefined') {
    return '';
  }

  try {
    return window.localStorage.getItem(CLOUD_BACKUP_KEY) ?? '';
  } catch {
    return '';
  }
}

export function setCloudBackupKey(value: string): void {
  if (typeof window === 'undefined') {
    return;
  }

  try {
    const normalized = value.trim();

    if (!normalized) {
      window.localStorage.removeItem(CLOUD_BACKUP_KEY);
      return;
    }

    window.localStorage.setItem(CLOUD_BACKUP_KEY, normalized);
  } catch {
    // Ignore storage write failures and keep cloud backup optional.
  }
}

export function markBackupExported(exportedAt: string): void {
  if (typeof window === 'undefined') {
    return;
  }

  try {
    window.localStorage.setItem(LAST_BACKUP_AT_KEY, exportedAt);
  } catch {
    // Ignore storage write failures and keep export success independent from reminder metadata.
  }
}

export function getBackupAgeInDays(lastBackupAt: string | null, now = new Date()): number | null {
  if (!lastBackupAt) {
    return null;
  }

  const backupTime = new Date(lastBackupAt).getTime();

  if (Number.isNaN(backupTime)) {
    return null;
  }

  return Math.max(0, Math.floor((now.getTime() - backupTime) / (1000 * 60 * 60 * 24)));
}
