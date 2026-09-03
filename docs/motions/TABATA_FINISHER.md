# 타바타 피니셔 모션 정의

- 운동 ID: `tabata-finisher`
- 구성: 20초 운동 + 10초 휴식 × 8라운드, 총 4분
- 가이드 동작: 로우 임팩트 버피, 마운틴 클라이머, 맨몸 스쿼트
- 장비: 없음

## 영상 구성

타바타 타이머는 휴대폰과 워치가 직접 실행한다. 영상은 4분 타이머를
복제하지 않고, 사용자가 선택한 동작의 안전한 자세를 계속 확인할 수 있는
8초 무음 루프로 제공한다. 가이드에서 동작과 `정면 / 측면` 시점을 각각
선택할 수 있다.

## 동작 정의

### 로우 임팩트 버피

- 발을 골반너비로 두고 선 뒤, 엉덩이와 무릎을 함께 굽혀 양손을 발 앞 바닥에 둔다.
- 복부를 조인 채 한 발씩 뒤로 내디뎌 높은 플랭크를 만들고 머리부터 뒤꿈치까지 긴 선을 유지한다.
- 이 가이드는 타바타 중 반복 품질을 확인하기 위한 로우 임팩트 변형으로 푸시업과 수직 점프를 생략한다.
- 한 발씩 손 가까이로 되돌린 뒤 일어서며 양팔을 머리 위로 뻗는다. 마지막 자세에서도 점프하거나 착지하지 않는다.
- 8초 가이드에서는 약 4초에 한 번씩 총 2회 반복하며, 첫 번째와 두 번째 반복의 선행발을 바꾼다.
- 참고: [NASM Squat Thrust Burpees](https://www.nasm.org/resource-center/exercise-library/squat-thrust-burpees), [Royal Berkshire NHS Strength and conditioning class](https://www.royalberkshire.nhs.uk/media/ljrncq23/strength-and-conditioning-exercise-class_jul25.pdf)

### 마운틴 클라이머

- 손은 어깨보다 약간 앞쪽, 손가락은 정면을 향하게 두고 양팔로 바닥을 밀어 높은 플랭크를 만든다.
- 복부와 둔부를 조여 척추와 골반 높이를 고정하고, 한쪽 무릎을 가슴 쪽으로 당기는 동안 반대쪽 다리는 길게 편다.
- 앞발과 뒷발이 자리를 바꾸는 순간 양발이 함께 플랫폼을 떠나는 공중 교대를 사용한다. 한쪽 발은 굽힌 고관절 아래에 착지하고 반대쪽 발은 뒤쪽 앞꿈치로 착지하며, 자세를 확인할 수 있는 속도로 반복한다.
- 접지 구간의 뒷다리는 발가락과 앞꿈치로 플랫폼을 밀고 뒤꿈치를 들어 올린다. 교대 중에는 발을 바닥에서 끌지 않으며 어깨를 과도하게 앞뒤로 흔들지 않는다.
- 참고: [ACE Mountain Climbers](https://www.acefitness.org/resources/everyone/exercise-library/258/mountain-climbers/)

### 맨몸 스쿼트

- 승인된 스쿼트의 발 간격, 발 고정, 무릎 트래킹, 골반 경로와 중립 척추를 그대로 사용한다.
- 양팔은 어깨높이 앞쪽으로 부드럽게 뻗어 균형을 잡고 손가락은 자연스럽게 편다.
- 뒤꿈치를 플랫폼에 붙인 채 엉덩이와 무릎을 함께 굽혔다가 발 전체로 바닥을 밀며 선다.
- 참고: [ACE Bodyweight Squat](https://www.acefitness.org/resources/everyone/exercise-library/135/bodyweight-squat/)

## 산출물

- 생성 코드:
  - `tools/blender/generate_human_tabata_burpee.py`
  - `tools/blender/generate_human_tabata_mountain_climber.py`
  - `tools/blender/generate_human_tabata_bodyweight_squat.py`
- Blender 원본: `design/motion/tabata_{burpee,mountain_climber,bodyweight_squat}_human_sample.blend`
- 단계별 프리뷰: `design/motion/previews/human_tabata_{burpee,mountain_climber,bodyweight_squat}_{front,side}_*.png`
- 앱 영상: `app/src/main/res/raw/tabata_{burpee,mountain_climber,bodyweight_squat}_{front,side}.mp4`

각 MP4는 H.264, 720 × 720, 30 fps, 240프레임, 정확히 8초, 무음이다.
프레임 241은 프레임 1과 같은 루프 경계 자세로 만들고 인코딩에서는 제외한다.
각 생성기는 프레임 1과 241의 루프 경계와 운동별 핵심 조건을 자동 검사한다.
로우 임팩트 버피는 전 프레임 무릎 트래킹·기립 및 플랭크 다리 신전·손목과 어깨 정렬·
팔꿈치 궤적·상완 프레임 회전량과 쇄골 상승, 손바닥·발 접촉을 검사한다. 마운틴 클라이머는
손바닥 고정·양발 공중 교대·앞뒤 착지와 몸통 고정, 맨몸 스쿼트는 좌우 발 접촉·중립 척추·
대칭 팔 자세를 주요 프레임에서 확인한 뒤 Blender 원본과 프리뷰 또는 영상을 저장한다.
