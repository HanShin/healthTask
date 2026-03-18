# Supabase Setup

이 프로젝트는 로컬 `IndexedDB`와 별개로, 암호화된 백업 스냅샷을 Supabase에 저장할 수 있습니다.

## 1. 환경변수

루트에 `.env.local` 파일을 만들고 아래 값을 넣습니다.

```bash
VITE_SUPABASE_URL=https://your-project-ref.supabase.co
VITE_SUPABASE_ANON_KEY=your-supabase-anon-key
```

예시는 [.env.example](/Users/shin-han/.codex/worktrees/0f88/healthTask/.env.example)에 있습니다.

## 2. 테이블 생성

Supabase SQL Editor에서 아래 SQL을 실행합니다.

```sql
create table if not exists public.health_cloud_backups (
  id text primary key,
  schema_version integer not null default 1,
  encrypted_payload text not null,
  iv text not null,
  salt text not null,
  exported_at timestamptz not null,
  updated_at timestamptz not null default timezone('utc', now())
);

alter table public.health_cloud_backups enable row level security;

drop policy if exists "Allow encrypted backup access" on public.health_cloud_backups;

create policy "Allow encrypted backup access"
on public.health_cloud_backups
for all
using (true)
with check (true);

create table if not exists public.health_recommendation_templates (
  id text primary key,
  name text not null,
  blurb text not null,
  focus text not null,
  difficulty text not null default 'beginner',
  targets text[] not null default '{}',
  benefits text[] not null default '{}',
  items jsonb not null default '[]'::jsonb,
  sort_order integer not null default 0,
  is_active boolean not null default true,
  updated_at timestamptz not null default timezone('utc', now())
);

alter table public.health_recommendation_templates
  add column if not exists difficulty text not null default 'beginner';

alter table public.health_recommendation_templates
  drop constraint if exists health_recommendation_templates_difficulty_check;

alter table public.health_recommendation_templates
  add constraint health_recommendation_templates_difficulty_check
  check (difficulty in ('beginner', 'intermediate', 'advanced'));

alter table public.health_recommendation_templates enable row level security;

drop policy if exists "Allow recommendation template reads" on public.health_recommendation_templates;

create policy "Allow recommendation template reads"
on public.health_recommendation_templates
for select
using (true);
```

## 3. 보안 메모

- 이 테이블에는 평문 운동 기록이 아니라 브라우저에서 암호화한 스냅샷만 저장됩니다.
- 사용자가 설정 화면에 입력한 `백업 키`가 암호화/복호화에 사용됩니다.
- 같은 키를 다른 기기에서도 입력해야 복원할 수 있습니다.
- 백업 키를 잊어버리면 복호화할 수 없으니 따로 안전하게 보관해야 합니다.

## 4. 운영 메모

- 현재 구현은 개인용 앱 기준의 가벼운 클라우드 백업입니다.
- 여러 사용자 계정으로 확장하려면 Supabase Auth와 RLS를 `auth.uid()` 기준으로 다시 설계하는 편이 좋습니다.
- 추천 템플릿은 `health_recommendation_templates`에서 읽고, 앱은 기본 15분마다 최신 데이터를 다시 확인합니다.
- 원격 추천 템플릿이 비어 있거나 연결이 실패하면 앱에 포함된 기본 템플릿으로 자동 fallback 됩니다.
