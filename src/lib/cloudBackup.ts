import { buildBackupPayload, restoreBackupPayload } from './repository';
import { getSupabaseClient } from './supabase';
import type { BackupPayload } from './types';

const CLOUD_BACKUP_TABLE = 'health_cloud_backups';
const KEY_DERIVATION_CONTEXT = 'health-task-cloud-backup';
const PBKDF2_ITERATIONS = 250_000;

interface CloudBackupRow {
  id: string;
  schema_version: number;
  encrypted_payload: string;
  iv: string;
  salt: string;
  exported_at: string;
  updated_at: string;
}

export interface CloudBackupMetadata {
  exportedAt: string;
  updatedAt: string;
}

function normalizeBackupKey(value: string): string {
  return value.trim();
}

function ensureCryptoSupport(): void {
  if (typeof crypto === 'undefined' || !crypto.subtle) {
    throw new Error('이 브라우저는 클라우드 백업 암호화를 지원하지 않습니다.');
  }
}

function toHex(bytes: Uint8Array): string {
  return Array.from(bytes, (value) => value.toString(16).padStart(2, '0')).join('');
}

function toBase64(bytes: Uint8Array): string {
  let binary = '';
  const chunkSize = 0x8000;

  for (let index = 0; index < bytes.length; index += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(index, index + chunkSize));
  }

  return btoa(binary);
}

function fromBase64(value: string): Uint8Array {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);

  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }

  return bytes;
}

function toArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
}

async function buildBackupRecordId(backupKey: string): Promise<string> {
  ensureCryptoSupport();

  const normalized = normalizeBackupKey(backupKey);

  if (!normalized) {
    throw new Error('클라우드 백업 키를 먼저 입력해 주세요.');
  }

  const hashBuffer = await crypto.subtle.digest(
    'SHA-256',
    new TextEncoder().encode(`${KEY_DERIVATION_CONTEXT}:${normalized}`)
  );

  return toHex(new Uint8Array(hashBuffer));
}

async function deriveEncryptionKey(backupKey: string, salt: Uint8Array): Promise<CryptoKey> {
  ensureCryptoSupport();

  const keyMaterial = await crypto.subtle.importKey(
    'raw',
    new TextEncoder().encode(normalizeBackupKey(backupKey)),
    'PBKDF2',
    false,
    ['deriveKey']
  );

  return crypto.subtle.deriveKey(
    {
      name: 'PBKDF2',
      hash: 'SHA-256',
      salt: toArrayBuffer(salt),
      iterations: PBKDF2_ITERATIONS
    },
    keyMaterial,
    {
      name: 'AES-GCM',
      length: 256
    },
    false,
    ['encrypt', 'decrypt']
  );
}

async function encryptPayload(payload: BackupPayload, backupKey: string): Promise<{
  encryptedPayload: string;
  iv: string;
  salt: string;
}> {
  ensureCryptoSupport();

  const salt = crypto.getRandomValues(new Uint8Array(16));
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const key = await deriveEncryptionKey(backupKey, salt);
  const plaintext = new TextEncoder().encode(JSON.stringify(payload));
  const ciphertext = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, plaintext);

  return {
    encryptedPayload: toBase64(new Uint8Array(ciphertext)),
    iv: toBase64(iv),
    salt: toBase64(salt)
  };
}

async function decryptPayload(row: CloudBackupRow, backupKey: string): Promise<BackupPayload> {
  ensureCryptoSupport();

  try {
    const key = await deriveEncryptionKey(backupKey, fromBase64(row.salt));
    const decrypted = await crypto.subtle.decrypt(
      {
        name: 'AES-GCM',
        iv: toArrayBuffer(fromBase64(row.iv))
      },
      key,
      toArrayBuffer(fromBase64(row.encrypted_payload))
    );

    return JSON.parse(new TextDecoder().decode(decrypted)) as BackupPayload;
  } catch {
    throw new Error('백업 키가 일치하지 않거나 클라우드 데이터가 손상되었습니다.');
  }
}

function mapMetadata(row: Pick<CloudBackupRow, 'exported_at' | 'updated_at'>): CloudBackupMetadata {
  return {
    exportedAt: row.exported_at,
    updatedAt: row.updated_at
  };
}

export async function getCloudBackupMetadata(backupKey: string): Promise<CloudBackupMetadata | null> {
  const client = getSupabaseClient();

  if (!client) {
    throw new Error('Supabase 연결 정보가 아직 설정되지 않았습니다.');
  }

  const id = await buildBackupRecordId(backupKey);
  const { data, error } = await client
    .from(CLOUD_BACKUP_TABLE)
    .select('exported_at, updated_at')
    .eq('id', id)
    .maybeSingle();

  if (error) {
    throw new Error(`클라우드 백업 상태를 확인하지 못했습니다. ${error.message}`);
  }

  return data ? mapMetadata(data as Pick<CloudBackupRow, 'exported_at' | 'updated_at'>) : null;
}

export async function backupToCloud(backupKey: string): Promise<CloudBackupMetadata> {
  const client = getSupabaseClient();

  if (!client) {
    throw new Error('Supabase 연결 정보가 아직 설정되지 않았습니다.');
  }

  const [id, payload] = await Promise.all([buildBackupRecordId(backupKey), buildBackupPayload()]);
  const encrypted = await encryptPayload(payload, backupKey);
  const updatedAt = new Date().toISOString();
  const { data, error } = await client
    .from(CLOUD_BACKUP_TABLE)
    .upsert(
      {
        id,
        schema_version: 1,
        encrypted_payload: encrypted.encryptedPayload,
        iv: encrypted.iv,
        salt: encrypted.salt,
        exported_at: payload.exportedAt,
        updated_at: updatedAt
      },
      {
        onConflict: 'id'
      }
    )
    .select('exported_at, updated_at')
    .single();

  if (error) {
    throw new Error(`클라우드에 저장하지 못했습니다. ${error.message}`);
  }

  return mapMetadata((data ?? { exported_at: payload.exportedAt, updated_at: updatedAt }) as Pick<
    CloudBackupRow,
    'exported_at' | 'updated_at'
  >);
}

export async function restoreFromCloud(backupKey: string): Promise<CloudBackupMetadata> {
  const client = getSupabaseClient();

  if (!client) {
    throw new Error('Supabase 연결 정보가 아직 설정되지 않았습니다.');
  }

  const id = await buildBackupRecordId(backupKey);
  const { data, error } = await client.from(CLOUD_BACKUP_TABLE).select('*').eq('id', id).maybeSingle();

  if (error) {
    throw new Error(`클라우드 백업을 불러오지 못했습니다. ${error.message}`);
  }

  if (!data) {
    throw new Error('해당 백업 키로 저장된 클라우드 백업이 아직 없습니다.');
  }

  const payload = await decryptPayload(data as CloudBackupRow, backupKey);
  await restoreBackupPayload(payload);

  return mapMetadata(data as CloudBackupRow);
}
