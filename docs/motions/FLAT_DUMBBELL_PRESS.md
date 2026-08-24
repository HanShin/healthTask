# 플랫 덤벨 프레스 모션 정의

- 운동 ID: `flat-dumbbell-press`
- 리소스 접두사: `flat_dumbbell_press`
- 제작 기준: `docs/MOTION_QUALITY_STANDARD.md`
- 사람형 베이스: 승인된 스쿼트 MakeHuman/MPFB 메시·의상·리그

## 레퍼런스

- 앱의 기존 자세 영상: https://www.youtube.com/watch?v=4NpzFtatcK8
- 정면과 측면 동작, 발·등 고정 접점 확인: https://www.youtube.com/watch?v=ugX-53TIst4
- 수직 이동이 주가 되고 가슴선과 어깨선 사이의 작은 수평 이동이 함께 생기는 프레스 경로: https://pmc.ncbi.nlm.nih.gov/articles/PMC5400411/

레퍼런스 영상은 자세와 타이밍 확인에만 사용하며 영상·음원·프레임은 앱에 포함하지 않는다.

## 동작 정의

- 머리, 상부 등, 둔부는 벤치 패드에 고정하고 두 발은 플랫폼에 고정한다.
- 무릎은 발 방향을 따라 굽힌 정적 자세를 유지하며 반복 중 발과 골반이 미끄러지지 않는다.
- 어깨는 벤치 쪽으로 안정시키고 허리는 자연스러운 중립 범위의 아치만 유지한다.
- 최하단에서 팔꿈치는 몸통에서 약 50도 벌어지고 손목 아래에 놓인다.
- 전완과 손목은 덤벨 손잡이 아래에서 이어지며 손가락과 엄지가 손잡이를 감싼다.
- 덤벨은 가슴 양옆으로 내려가고 올라오면서 약간 안쪽으로 모이되 서로 충돌하지 않는다.
- 프레임 1과 241은 같은 잠금 자세이며 MP4에는 중복 프레임 241을 넣지 않는다.

## 장비와 경로

- 인물 팔레트: 자연스러운 중간 밝기 피부, 네이비 상·하의, 밝은 회색 신발, 짙은 갈색 머리
- 인물 조명: 따뜻한 중성 주광과 약한 청색 보조광, 보라색 림 조명
- 외부 MPFB 이미지가 없을 때는 누락 텍스처 연결을 제거하고 Blender 파일에 저장된 팔레트를 사용한다.
- 벤치 패드: 길이 1.22 m, 폭 0.30 m, 중심 `y=0.670`, 상단 높이 0.55 m
- 패드는 둔부 접점부터 머리 방향으로 배치하고 포니테일은 머리 옆으로 내려 벤치와 교차하지 않는다.
- 덤벨 손잡이: 지름 36 mm, 손잡이 구간 0.20 m
- 덤벨 중심 상단: `x=±0.255, y=0.355, z=1.015`
- 덤벨 중심 하단: `x=±0.370, y=0.320, z=0.720`
- 발목 target: `x=±0.255, y=-0.150, z=0.062`
- 팔꿈치 pole 상단: `x=±0.230, y=0.340, z=0.790`
- 팔꿈치 pole 하단: `x=±0.320, y=0.380, z=0.520`
- 타임라인: 출력 240 프레임, 30 fps, 정확히 8초의 하강·정지·상승·잠금 정지

## 검수 산출물

- Blender 원본: `design/motion/flat_dumbbell_press_human_sample.blend`
- 생성 코드: `tools/blender/generate_human_flat_dumbbell_press.py`
- 6개 자세: `design/motion/previews/human_flat_dumbbell_press_{front,side}_{top,mid,bottom}.png`
- 그립 3방향: `design/motion/previews/human_flat_dumbbell_press_grip_{front,angle,rear}.png`
- 앱 영상: `app/src/main/res/raw/flat_dumbbell_press_{front,side}.mp4`
- 자동 충돌 검사: 최종 변형 인체·의상·신발·머리카락과 벤치 전체 및 덤벨 플레이트를 시작·중간·최하단에서 검사한다.

Blender에 MP4 출력이 없으면 생성 코드는 임시 PNG 프레임을 렌더한 뒤 시스템 FFmpeg로 H.264, `yuv420p`, 무음 MP4를 만든다. 임시 프레임은 인코딩이 끝나면 자동으로 제거한다.
