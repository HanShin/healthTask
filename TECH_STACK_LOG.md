# 기술/라이브러리 사용 기록

작업 중 사용하는 기술, 도구, 라이브러리를 지속적으로 업데이트합니다.

## 기록 규칙
- 새 기술/라이브러리를 사용하면 즉시 항목 추가
- 사용 목적, 적용 위치, 관련 이슈를 함께 기록

## 현재 사용 항목
| 구분 | 이름 | 버전/환경 | 사용 목적 | 적용 위치 | 비고 |
|---|---|---|---|---|---|
| VCS | Git | repo local | 변경 이력/협업 관리 | 전체 저장소 | 진행중 |
| 문서화 | Markdown | n/a | 작업 현황 및 기술 기록 | `WORK_PROGRESS.md`, `TECH_STACK_LOG.md` | 진행중 |
| CLI | Bash | container shell | 파일/상태 점검 및 자동화 | 작업 세션 | 진행중 |
| App | React | 19.2.4 | 모바일 웹 UI 구성 | `src/` | 핵심 프론트엔드 |
| Build | Vite | 7.3.1 | 개발 서버 및 번들링 | 전체 앱 | PWA와 함께 사용 |
| Type | TypeScript | 5.9.3 | 타입 안정성 및 모델 설계 | `src/lib/types.ts` 외 | strict 설정 |
| Router | React Router DOM | 7.13.1 | 탭/세부 화면 라우팅 | `src/App.tsx` | BrowserRouter 기반 |
| Storage | Dexie | 4.3.0 | IndexedDB 래퍼 | `src/lib/db.ts`, `src/lib/repository.ts` | 로컬 영속 저장 |
| Storage Hook | dexie-react-hooks | 4.2.0 | DB 변경 실시간 반영 | 각 화면 컴포넌트 | `useLiveQuery` 사용 |
| PWA | vite-plugin-pwa | 1.2.0 | 서비스워커/manifest 생성 | `vite.config.ts` | 설치형 웹앱 지원 |
| Deploy | Vercel SPA Rewrite | `vercel.json` | Hobby 배포 시 클라이언트 라우팅 보정 | `vercel.json` | 정적 배포 대상 |
| Asset | SVG guides | local assets | 운동 자세 시각 가이드 제공 | `public/guides/` | 덤벨/케틀벨 포함 |
| Media | YouTube embeds | remote | 실제 움직임 참고 영상 제공 | `src/components/ExerciseGuideModal.tsx` | 일부 핵심 동작 임베드 |
| Reference | ExRx links | remote | 자세 설명/레퍼런스 자료 연결 | `src/data/catalog.ts` | 운동별 외부 링크 제공 |

## 변경 이력
| 일시(UTC) | 변경 내용 |
|---|---|
| 2026-03-11 | 문서 최초 생성 |
| 2026-03-12 02:20 UTC | React + TypeScript + Vite + Dexie + PWA 기반 MVP 앱 구조 추가 |
| 2026-03-12 02:20 UTC | Vercel Hobby 배포용 `vercel.json` 및 PWA manifest 설정 추가 |
