# 기술 스택

2026-10-20 기준 저장소의 실제 의존성과 구현입니다. 휴대폰·워치 앱 버전은 2.0.0 (`versionCode 10`)입니다.

| 구분 | 기술 | 버전 | 역할 |
|---|---|---:|---|
| 언어 | Kotlin | AGP 9 내장 Kotlin + Compose compiler 2.3.21 | 앱 및 도메인 구현 |
| UI | Jetpack Compose Material 3 | BOM 2026.06.00 | 네이티브 화면과 디자인 시스템 |
| Navigation | Navigation Compose | 2.9.8 | 러닝·러닝 기록·인바디·설정 4탭, 온보딩 없음 |
| DB | Room | 2.8.4 / DB v7 | 러닝·체성분·CSV 원본·상세 센서 청크·동기화 및 이전 데이터 호환 |
| 설정 | DataStore Preferences | 1.2.1 | 동기화·러닝 안내·인바디 그래프 표시 설정 |
| 건강 연동 | Health Connect Client | 1.1.0 | 삼성/NRC 러닝·삼성 체성분 읽기, 로컬 러닝 세션·거리 쓰기 |
| 캡처 인식 | ML Kit Korean Text Recognition | 16.0.1 | 기기 내 인바디 캡처 텍스트 인식 |
| 지도 | MapLibre Native Android / OkHttp | 13.6.1 / 4.12.0 | OpenStreetMap 배경·경로 표시, 타일 HTTP 캐시 |
| JSON | Gson | 2.13.2 | 복원용 v5·분석용 v3, legacy-v1 및 v2~v4 가져오기 |
| 비동기 | Kotlin Coroutines | 1.10.2 | Flow 기반 DB/UI와 동기화 |
| 워치 UI | Compose for Wear OS Material 3 | 1.6.2 | Galaxy Watch 원형 화면 UI |
| 워치 센서 | Health Services Client | 1.1.0-rc02 | 지원 센서의 심박·속도·케이던스·걸음·거리·칼로리·고도·상승/하강 |
| 워치 화면 유지 | Wear Ongoing / Compose AmbientModeManager | 1.1.0 / Wear Compose 1.6.2 | 진행 중 러닝 바로가기와 시스템 저전력 표시 |
| 기기간 연동 | Play Services Wearable Data Layer | 20.0.1 | 러닝 시작 요청, 완료 요약·경로·gzip 센서 Asset 영속 전송 |
| 폰→워치 실행 | Wear Remote Interactions | 1.2.0 | 휴대폰의 워치 러닝 시작 흐름 |
| 빌드 | Gradle / AGP | 9.4.1 / 9.2.0 | Android 빌드와 검증 |
| 코드 생성 | KSP | 2.3.10 | Room 구현 생성 |

휴대폰은 `minSdk 34`, 워치는 `minSdk 30`, 두 앱의 `targetSdk`는 36이고 패키지는 `com.hanshin.healthtask`로 같습니다. 시스템 JDK 26 대신 Android Studio 번들 JDK를 사용합니다. 휴대폰만 Room과 Health Connect의 기준 저장소이며 워치는 오프라인 운동 수집기 역할을 합니다.

기능·데이터 형식은 [README](README.md), [인바디 CSV](docs/inbody-csv.md), [센서 기록](docs/running-sensors.md), [분석 내보내기](docs/analysis-export.md)를 참고하세요.

## 보관용 도구 — 현재 앱 의존성 아님

Blender 4.5.10 LTS / MPFB 2.0.17로 제작한 근력 모션 원본과 스크립트는 보존합니다. 기존 Media3 1.11.0 플레이어는 제거했고, MP4/SVG는 `legacy/strength-assets`로 옮겨 APK에서 제외했습니다.
