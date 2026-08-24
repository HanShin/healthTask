# 원암 덤벨 로우 모션 정의

- 운동 ID: `one-arm-dumbbell-row`
- 리소스 접두사: `one_arm_dumbbell_row`
- 제작 기준: `docs/MOTION_QUALITY_STANDARD.md`
- 사람형 베이스: 승인된 스쿼트 MakeHuman/MPFB 메시·의상·리그

## 레퍼런스

- 앱의 기존 자세 영상: https://www.youtube.com/watch?v=bXrkh09AcKg
- 벤치 지지, 중립 척추, 몸통 회전 억제와 팔꿈치 경로: https://www.acefitness.org/resources/everyone/exercise-library/126/single-arm-row/

레퍼런스 영상과 문서는 자세와 타이밍 확인에만 사용하며 영상·음원·프레임은 앱에 포함하지 않는다.

## 동작 정의

- 왼손과 왼무릎은 벤치에 고정하고 오른발 전체는 플랫폼에 고정한다.
- 등과 목은 골반에서 어깨로 약 `14°` 완만하게 올라가는 중립 정렬을 유지하고 시선은 자연스럽게 아래를 향하며 골반과 어깨를 회전하지 않는다.
- 왼손은 어깨 바로 아래에서 손바닥 전체로 지지하고, 네 손가락은 서로 가까이 모아 패드 길이 방향으로 편다.
- 왼팔은 팔꿈치를 잠그지 않는 범위에서 약 `174°`로 길게 편 채 전 구간 고정한다.
- 오른팔은 어깨 아래에서 팔꿈치를 잠그지 않고 약 `173°`로 뻗은 상태에서 시작하고 손목은 중립 그립을 유지한다.
- 오른팔 팔꿈치를 몸통 가까이에서 뒤로 보내며 덤벨을 오른쪽 엉덩이 방향으로 당긴다.
- 수축 지점에서는 오른팔 팔꿈치가 약 `90°`가 되고 손목과 팔꿈치가 수직선상에 놓인다.
- 몸통을 돌리지 않고 당길 수 있는 범위에서 멈춘 뒤 같은 경로로 천천히 내린다.
- 프레임 1과 241은 같은 이완 자세이며 MP4에는 중복 프레임 241을 넣지 않는다.

## 장비와 경로

- 벤치 패드: 길이 1.22 m, 폭 0.30 m, 중심 `y=0.450`, 상단 높이 0.345 m
- 덤벨 손잡이: 지름 36 mm, 손잡이 구간 0.20 m, 전후 방향 중립 그립
- 상체: 수평보다 `14°` 높게 고정 (`rig X=76°`, root `z=0.531`)
- 덤벨 중심 이완: `x=-0.280, y=0.280, z=0.424`
- 덤벨 중심 수축: `x=-0.280, y=0.430, z=0.598`
- 왼손 지지 target: `x=0.080, y=0.140, z=0.370` (손가락 패드가 벤치 상단에 닿는 높이)
- 목 보정: 리그 로컬 X축 `-14°`, 머리는 로컬 X축 `+14°`로 상쇄해 척추와 평행한 중립 방향 유지
- 왼쪽 발목 target: `x=0.105, y=1.180, z=0.460`
- 오른발 target: `x=-0.300, y=0.670, z=0.062`
- 타임라인: 출력 240 프레임, 30 fps, 정확히 8초의 당김·정지·하강·이완 정지

## 검수 산출물

- Blender 원본: `design/motion/one_arm_dumbbell_row_human_sample.blend`
- 생성 코드: `tools/blender/generate_human_one_arm_dumbbell_row.py`
- 6개 자세: `design/motion/previews/human_one_arm_dumbbell_row_{front,side}_{bottom,mid,top}.png`
- 그립 3방향: `design/motion/previews/human_one_arm_dumbbell_row_grip_{front,angle,rear}.png`
- 지지 손 접촉: `design/motion/previews/human_one_arm_dumbbell_row_support_hand.png`
- 지지 손 위쪽 형태: `design/motion/previews/human_one_arm_dumbbell_row_support_hand_top.png`
- 앱 영상: `app/src/main/res/raw/one_arm_dumbbell_row_{front,side}.mp4`
- 자동 충돌 검사: 최종 변형 인체·의상·신발·머리카락과 벤치 전체 및 덤벨 플레이트를 시작·중간·최대 수축에서 검사한다.

Blender에 MP4 출력이 없으면 임시 PNG 프레임을 렌더한 뒤 시스템 FFmpeg로 H.264, `yuv420p`, 무음 MP4를 만든다. 임시 프레임은 인코딩 후 제거한다.
