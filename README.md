# 오늘운동

개인용 운동 루틴과 기록을 모바일에서 짧고 빠르게 남기는 웹앱입니다.

현재 구현 범위:

- 첫 진입 온보딩
- `오늘 / 루틴 / 기록 / 설정` 4탭 구조
- 루틴 생성/수정/삭제
- 세트/횟수/중량 및 유산소 거리/시간/페이스 기록
- IndexedDB 기반 로컬 저장
- JSON 백업/복원
- Supabase 기반 암호화 클라우드 백업
- Supabase 기반 추천 템플릿 주기 갱신
- PWA 설치 및 오프라인 캐싱
- Vercel Hobby 배포용 SPA 리라이트 설정

## 실행 방법

```bash
npm install
npm run dev
```

프로덕션 빌드:

```bash
npm run build
```

## 배포 메모

- Vercel Hobby에 GitHub 저장소를 연결하면 바로 배포할 수 있습니다.
- SPA 라우팅을 위해 `vercel.json`이 포함되어 있습니다.
- 운동 데이터는 서버가 아니라 브라우저 `IndexedDB`에 저장됩니다.
- 필요하면 `설정` 탭에서 JSON 백업 또는 Supabase 클라우드 백업을 사용할 수 있습니다.
- Supabase 연결 방법은 `SUPABASE_SETUP.md`를 참고하세요.

## 작업 추적 문서

- `WORK_PROGRESS.md`: 요구사항/진행/테스트 로그
- `TECH_STACK_LOG.md`: 사용 기술 및 라이브러리 기록
