# Session Handoff

## 기준 일자
- 2026-03-12

## 이번에 반영된 큰 방향
- 루틴은 더 이상 요일 기준이 아니라, `일요일~토요일` 한 주 안에서 `이번 주 몇 번째 운동인지` 기준으로 순서대로 실행됩니다.
- 해당 주에 루틴을 다 못 끝내도 일요일이 되면 다시 1번째 루틴부터 시작합니다.
- 오늘 운동 화면은 한 번에 한 운동만 펼쳐지고, 세트 입력은 한 줄에서 빠르게 처리하는 방향으로 정리했습니다.
- 웨이트 운동은 같은 운동의 최근 기록에서 `마지막 완료 세트`를 다음 기본 중량/횟수로 이어받습니다. 완료 체크가 없으면 마지막 세트를 fallback으로 사용합니다.

## 이번 세션에서 마무리한 기능
- 루틴 편집/생성 화면의 요일 선택 제거
- 루틴 순번 기반 실행 로직 적용
- Dexie 마이그레이션으로 기존 `scheduleDays` 제거
- 오늘 운동 화면에서:
  - 운동 카드 아코디언
  - 세트 수 추가/삭제
  - 세션 중 운동 추가
  - 중량/횟수 입력 폭 축소
- 최근 기록 홈 카드 UI를 `세션 카드형`으로 재구성
- 상태값 `completed/partial/skipped`를 `완료/일부 완료/미완료`로 통일
- 상태 배지에서 `일부 완료` 줄바꿈 방지

## 중요 파일
- `src/lib/routineSequence.ts`
  - 주간 루틴 순서 계산
- `src/lib/date.ts`
  - 주 시작일을 일요일 기준으로 변경
- `src/lib/db.ts`
  - `scheduleDays` 제거용 마이그레이션
- `src/lib/repository.ts`
  - 루틴/세션 저장 로직 정리
- `src/lib/workoutRecord.ts`
  - 최근 웨이트 기록 carry-over 계산
- `src/lib/sessionStatus.ts`
  - 세션 상태 한글 라벨 공통화
- `src/features/routines/RoutinesPage.tsx`
  - 요일 선택 제거, 순번형 루틴 편집
- `src/features/today/TodayPage.tsx`
  - 오늘 추천, 최근 기록 카드, 상태 표시
- `src/features/today/SessionPage.tsx`
  - 세션 입력 UX, 세트 조작, 운동 추가
- `src/features/history/HistoryPage.tsx`
  - 회고 기록 상태 라벨 한글화
- `src/features/history/SessionDetailPage.tsx`
  - 상세 기록 상태 라벨 한글화
- `src/styles/app.css`
  - 루틴/세션/최근 기록 관련 UI 정리

## 현재 UX 기준 메모
- `운동 추가`는 현재 세션 기록에만 반영됩니다.
  - 루틴 템플릿 자체에 영구 추가하는 흐름은 아직 별도 구현이 필요합니다.
- 세션에서 추가한 운동은 `제외` 버튼으로 다시 뺄 수 있습니다.
- 최근 기록 카드는 각 세션에서 대표 운동 2개만 먼저 보여주고, 나머지는 `+N개 운동 더 보기`로 압축합니다.

## 다음 세션에서 바로 이어가기 좋은 작업
- 루틴 편집 화면에서 운동 순서 drag/drop 또는 위/아래 이동 추가
- 루틴 편집 화면에서 운동 영구 추가/삭제 UX 보강
- 최근 기록 카드에 `+2.5kg`, `+1회` 같은 변화 배지 추가
- 실기기에서 모바일 입력 UX와 카드 밀도 최종 점검

## 검증 기록
- `./node_modules/.bin/tsc -b`
- `./node_modules/.bin/vite build --configLoader runner`

## 참고
- 현재 변경은 `/Users/shin-han/.codex/worktrees/7fb9/healthTask` 기준으로 정리되어 있습니다.
- 실제 서비스 폴더 `/Users/shin-han/workspace/healthTask` 에도 주요 변경 파일은 동기화해둔 상태입니다.
