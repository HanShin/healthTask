import type {
  Exercise,
  ExerciseInput,
  ExerciseGuide,
  GuideResource,
  RoutineDifficulty,
  RoutineDraftItem,
  RoutineTemplate,
  SetupInput
} from '../lib/types';
import { createId } from '../lib/id';

const catalogCreatedAt = new Date().toISOString();

function exercise<T extends ExerciseInput>(input: T): T & { createdAt: string } {
  return {
    ...input,
    createdAt: catalogCreatedAt
  };
}

function strengthItem(
  exerciseId: string,
  order: number,
  sets: number,
  targetReps: number,
  targetWeightKg: number,
  restSeconds = 90,
  note?: string
): RoutineDraftItem {
  return {
    id: createId('plan'),
    kind: 'strength',
    exerciseId,
    order,
    sets,
    targetReps,
    targetWeightKg,
    restSeconds,
    note
  };
}

function bodyweightItem(
  exerciseId: string,
  order: number,
  sets: number,
  targetReps: number,
  restSeconds = 60,
  note?: string
): RoutineDraftItem {
  return strengthItem(exerciseId, order, sets, targetReps, 0, restSeconds, note);
}

function runningItem(
  exerciseId: string,
  order: number,
  targetDistanceKm: number,
  targetDurationMin: number,
  note?: string
): RoutineDraftItem {
  return {
    id: createId('plan'),
    kind: 'running',
    exerciseId,
    order,
    targetDistanceKm,
    targetDurationMin,
    targetPaceMinPerKm: Number((targetDurationMin / targetDistanceKm).toFixed(1)),
    note
  };
}

function youtubeResource(
  videoId: string,
  title: string,
  provider: string,
  description?: string
): GuideResource {
  return {
    kind: 'video',
    title,
    provider,
    url: `https://www.youtube.com/watch?v=${videoId}`,
    embedUrl: `https://www.youtube.com/embed/${videoId}`,
    thumbnailSrc: `https://img.youtube.com/vi/${videoId}/hqdefault.jpg`,
    description
  };
}

const guidePresets = {
  benchPress: {
    headline: '견갑을 벤치에 고정하고 발로 바닥을 밀어내며 바를 수직으로 올립니다.',
    cues: ['가슴을 열고 어깨는 아래로 고정', '손목은 꺾지 않고 전완은 수직', '바는 명치 위쪽으로 천천히 내리기'],
    warning: '엉덩이가 뜨거나 바가 목 쪽으로 흐르지 않게 유지하세요.',
    resources: [
      youtubeResource(
        'FN4zDtiy3Gg',
        '벤치프레스, 초보자를 위한 완벽정리 ( 효과, 자세, 주의사항, 연습방법)',
        '보통트레이너',
        '벤치프레스 기본 자세와 주의점을 짧게 다시 확인하기 좋은 한국 영상입니다.'
      )
    ]
  },
  dumbbellPress: {
    headline: '견갑을 고정한 채 덤벨을 가슴 위로 모으며 좌우 균형을 일정하게 유지합니다.',
    cues: ['덤벨은 손목 위로 수직 정렬', '내릴 때 팔꿈치는 몸통보다 살짝 아래', '반동 없이 천천히 내려 상부와 중부 가슴 자극 유지'],
    warning: '허리가 과하게 뜨거나 덤벨이 어깨선 뒤로 빠지지 않게 하세요.',
    resources: [
      youtubeResource(
        '4NpzFtatcK8',
        '[가슴운동] 덤벨 벤치 프레스 자세',
        '오픈짐',
        '덤벨 벤치 프레스의 기본 세팅과 움직임을 간단하게 보기 좋습니다.'
      )
    ]
  },
  dumbbellRow: {
    headline: '골반과 척추를 고정하고 팔꿈치를 엉덩이 쪽으로 당겨 광배를 수축합니다.',
    cues: ['몸통은 흔들지 않고 가슴을 길게 유지', '덤벨은 몸 가까이 끌어오기', '어깨를 으쓱하지 않고 등으로 당기기'],
    warning: '상체를 비틀거나 반동으로 당기면 허리 부담이 커질 수 있습니다.',
    resources: [
      youtubeResource(
        'bXrkh09AcKg',
        '원암덤벨로우 자세 기본세팅법',
        '김성환헬스유튜브',
        '원암 덤벨 로우의 기본 세팅과 상체 고정을 한국어로 확인할 수 있습니다.'
      )
    ]
  },
  latPulldown: {
    headline: '바를 가슴 위로 끌어오며 광배로 당긴다는 느낌을 유지합니다.',
    cues: ['가슴을 들어 손보다 팔꿈치를 아래로 내리기', '목 뒤가 아니라 쇄골 앞쪽으로 당기기', '올라갈 때도 광배 긴장 유지'],
    warning: '상체를 과하게 뒤로 젖혀 로우 동작처럼 바뀌지 않게 하세요.',
    resources: [
      youtubeResource(
        'hVa5dsH3DaA',
        '랫풀다운의 올바른 운동법',
        '김성환헬스유튜브',
        '랫풀다운에서 팔이 아니라 광배로 당기는 감각을 짧게 복습하기 좋은 한국 영상입니다.'
      )
    ]
  },
  seatedRow: {
    headline: '가슴을 세운 상태에서 팔이 아니라 팔꿈치로 뒤를 긁어낸다는 느낌으로 당깁니다.',
    cues: ['가슴을 펴고 허리는 중립 유지', '손보다 팔꿈치가 뒤로 가는 느낌', '수축 지점에서 어깨를 으쓱하지 않기'],
    warning: '상체를 과하게 젖히며 반동으로 당기지 마세요.',
    resources: [
      youtubeResource(
        'b_seZMf3MfM',
        '1분만에 마스터한다.섹시한 등을 만들어보자!  시티드케이블로우 (롱풀)',
        '권혁',
        '시티드 로우 기본 궤적과 당기는 방향을 짧게 확인하기 좋은 한국 영상입니다.'
      )
    ]
  },
  barbellSquat: {
    headline: '발 전체로 지면을 밀고 무릎과 발끝 방향을 맞추며 내려갑니다.',
    cues: ['복압을 먼저 만들고 내려가기', '무릎은 안으로 모이지 않게', '일어날 때 가슴과 골반이 함께 올라오기'],
    warning: '허리가 말리거나 발뒤꿈치가 뜨는 깊이는 피하세요.',
    resources: [
      youtubeResource(
        '50f62PSGY7k',
        '【스쿼트】 ⭐️최종본⭐️ 장담하는데 3번만 보면 이해합니다! (발 위치, 힙드라이브, 척추정렬, 시선)',
        '핏블리 FITVELY',
        '바벨 스쿼트에서 자주 무너지는 발 위치와 척추 정렬을 짧게 다시 보기 좋습니다.'
      )
    ]
  },
  gobletSquat: {
    headline: '덤벨 또는 케틀벨을 가슴 앞에 붙여 들고 발 전체로 바닥을 밀며 내려갑니다.',
    cues: ['팔꿈치는 아래로 두고 코어를 먼저 잠그기', '무릎과 발끝 방향 일치', '올라올 때 가슴과 골반이 함께 올라오기'],
    warning: '무게를 앞으로 멀리 두면 허리가 말릴 수 있으니 몸 가까이에 유지하세요.',
    resources: [
      youtubeResource(
        'lI6Z6VIhOR4',
        '고블릿 스쿼트 자세 제대로 하는 법',
        '빅씨스 Bigsis',
        '고블릿 스쿼트를 처음 시작할 때 따라 보기 쉬운 한국 영상입니다.'
      )
    ]
  },
  splitSquat: {
    headline: '앞다리로 중심을 잡고 수직으로 내려가며 엉덩이와 대퇴사두를 함께 사용합니다.',
    cues: ['앞발 전체로 지면을 누르기', '상체는 너무 숙이지 않고 길게 유지', '올라올 때 무릎이 안으로 모이지 않게'],
    warning: '균형을 잃기 쉬우니 무게보다 동작 깊이와 중심부터 맞추세요.',
    resources: [
      youtubeResource(
        'bqKv1toW73Y',
        '불가리안 스플릿 스쿼트 자세 이대로만 하세요',
        '근거 기반 운동 지식',
        '발 위치와 균형 잡는 법을 짧고 명확하게 정리한 한국 영상입니다.'
      )
    ]
  },
  hinge: {
    headline: '무게를 몸 가까이에 둔 채 엉덩이를 뒤로 보내며 햄스트링 신장을 느낍니다.',
    cues: ['무릎은 살짝만 굽히고 고정', '등은 길게 펴고 복압 유지', '올라올 때 엉덩이로 바닥을 민다는 느낌'],
    warning: '무게가 몸에서 멀어지거나 허리가 둥글어지지 않게 주의하세요.',
    resources: [
      youtubeResource(
        'hRVNKm9K4zU',
        '루마니안 데드리프트 정석 (허리 통증 없이 운동하는 법)',
        '채코치의 운동생활',
        '루마니안 데드리프트의 힙힌지 패턴과 허리 중립을 짧게 확인하기 좋은 한국 영상입니다.'
      )
    ]
  },
  shoulderPress: {
    headline: '갈비뼈를 잠근 채 덤벨 또는 케틀벨을 머리 위로 부드럽게 밀어 올립니다.',
    cues: ['팔꿈치는 손목 아래에 위치', '코어에 힘을 주고 허리 과신전 방지', '내릴 때 귀 옆으로 제어하며 내리기'],
    warning: '가슴을 과하게 열며 허리를 꺾지 않도록 조심하세요.',
    resources: [
      youtubeResource(
        'OMCJoZfKhxM',
        '[덤벨숄더프레스] 완전 기초 자세',
        '탄단지혜 TanDanJihye',
        '덤벨 숄더프레스의 가장 기본적인 시작 자세를 보기 좋은 영상입니다.'
      )
    ]
  },
  plank: {
    headline: '머리부터 발뒤꿈치까지 일직선을 유지하며 복부를 단단하게 조입니다.',
    cues: ['팔꿈치는 어깨 바로 아래', '엉덩이는 들리지도 처지지도 않게', '짧게 호흡하며 복부 긴장 유지'],
    warning: '허리가 처지기 시작하면 시간을 줄이고 자세를 다시 맞추세요.',
    resources: [
      youtubeResource(
        'QVRZhClEHLw',
        '완벽한 플랭크 자세',
        '하이닥',
        '승모근 힘 빼기와 코어 조이기 포인트를 한국어로 깔끔하게 설명합니다.'
      )
    ]
  },
  pushUp: {
    headline: '손바닥으로 바닥을 밀어내며 몸통 전체를 하나의 판처럼 곧게 유지합니다.',
    cues: ['손은 어깨보다 살짝 넓게', '가슴과 엉덩이가 함께 내려가기', '정점에서 견갑을 끝까지 밀어내기'],
    warning: '허리가 꺾이거나 턱만 먼저 떨어지지 않게 주의하세요.'
  },
  pullUp: {
    headline: '가슴을 열고 견갑을 먼저 끌어내린 뒤 팔꿈치를 아래로 당깁니다.',
    cues: ['매달린 시작 자세에서 어깨를 끌어내리기', '턱만 들지 말고 가슴을 바 쪽으로 가져가기', '하강도 반동 없이 천천히'],
    warning: '반동으로 턱만 넘기려 하면 어깨와 팔꿈치 부담이 커질 수 있습니다.'
  },
  bodyweightSquat: {
    headline: '발 전체로 바닥을 누르며 고관절과 무릎을 함께 접어 자연스럽게 앉습니다.',
    cues: ['무릎과 발끝 방향 맞추기', '하단에서도 가슴 길게 유지', '일어날 때 발바닥 중앙으로 밀기'],
    warning: '발뒤꿈치가 뜨거나 허리가 말리는 깊이까지 무리하지 마세요.'
  },
  pikePushUp: {
    headline: '엉덩이를 높게 든 상태에서 정수리 방향으로 몸을 내렸다가 어깨로 밀어 올립니다.',
    cues: ['손으로 바닥을 강하게 밀기', '팔꿈치는 몸통에서 너무 벌리지 않기', '내릴 때 시선은 손 사이 바닥'],
    warning: '어깨가 아프다면 가동범위를 줄이고 엉덩이 높이를 먼저 맞추세요.'
  },
  dip: {
    headline: '어깨를 아래로 고정한 채 상체를 살짝 세우고 팔꿈치를 뒤로 접습니다.',
    cues: ['하강 전에 견갑을 먼저 안정화', '상완이 바닥과 평행해질 정도까지만', '올라올 때 반동 없이 삼두와 가슴으로 밀기'],
    warning: '어깨 앞쪽에 날카로운 통증이 느껴지면 깊이를 줄이거나 다른 동작으로 바꾸세요.'
  },
  hangingRaise: {
    headline: '철봉에 매달린 상태에서 반동을 줄이고 복부 힘으로 무릎 또는 다리를 끌어올립니다.',
    cues: ['견갑을 살짝 끌어내려 몸통 흔들림 줄이기', '골반을 말아 복부 수축 만들기', '내릴 때도 천천히 제어하기'],
    warning: '몸을 크게 흔들며 반복하지 말고 통제 가능한 범위에서 진행하세요.'
  },
  kettlebellSwing: {
    headline: '팔로 들어 올리는 것이 아니라 힙힌지와 엉덩이 폭발력으로 케틀벨을 보냅니다.',
    cues: ['케틀벨을 다리 사이 깊게 보내기', '정점에서 엉덩이와 복부를 강하게 잠그기', '팔은 고리처럼 느슨하게 유지'],
    warning: '스쿼트처럼 앉아버리거나 허리를 젖혀 들어 올리지 마세요.',
    resources: [
      youtubeResource(
        'UIxi5LPD-6A',
        '정확한 케틀벨스윙 자세를 알고계시나요?',
        '헤롤드킴',
        '케틀벨 스윙의 기본 경로와 잘못된 습관을 한국어로 체크하기 좋습니다.'
      )
    ]
  },
  kettlebellCleanPress: {
    headline: '클린으로 전완 위에 부드럽게 안착시킨 뒤 코어를 잠그고 수직으로 밀어 올립니다.',
    cues: ['손으로 케틀벨을 감싸며 충격 없이 랙 포지션 잡기', '프레스 전 갈비뼈를 닫고 엉덩이 조이기', '하강도 천천히 제어'],
    warning: '케틀벨이 손목을 강하게 때리면 경로와 손목 회전을 다시 점검하세요.',
    resources: [
      youtubeResource(
        'Rc4PqOSg4OQ',
        'Kettlebell Double Clean & Press',
        '더건강한피티TV',
        '클린에서 랙 포지션을 거쳐 프레스로 이어지는 흐름을 짧게 확인할 수 있는 영상입니다.'
      )
    ]
  },
  turkishGetUp: {
    headline: '케틀벨을 천장으로 밀어 올린 채 단계별로 몸을 세워 안정성을 유지합니다.',
    cues: ['시선은 케틀벨을 계속 따라가기', '팔꿈치-손바닥-힙 브리지 순서로 차분히 진행', '일어나는 내내 위쪽 팔은 수직 유지'],
    warning: '서두르지 말고 각 단계에서 균형을 확인한 뒤 다음 단계로 넘어가세요.',
    resources: [
      youtubeResource(
        'htV9K6wgApI',
        '터키시 겟 업 운동 방법',
        '트레이닝센터바른',
        '터키시 겟업의 동작 순서를 짧게 복습하기 좋은 한국 영상입니다.'
      )
    ]
  },
  runningEasy: {
    headline: '호흡이 편한 강도로 상체를 세우고 부드럽게 착지합니다.',
    cues: ['몸통은 길게 세우고 시선은 정면', '팔은 가볍게 앞뒤로 흔들기', '보폭보다 리듬을 우선'],
    warning: '과한 전경사나 과도한 보폭은 피로를 빨리 키웁니다.',
    resources: [
      youtubeResource(
        'Ggbm_coe5uM',
        '달리기 러닝 전 이 영상을 생각해 주세요. 기본 자세 3가지 간단하게 정리했습니다. (처음 운동 하시는 분들을 위해 쉽게 정리했습니다.) #러닝 #자세 #달리기',
        '런앤다이어트',
        '이지 런 전 기본 자세 3가지를 짧게 다시 확인하기 좋은 한국 영상입니다.'
      )
    ]
  },
  runningTempo: {
    headline: '약간 숨찰 정도의 리듬에서 상체를 흔들지 않고 추진력을 유지합니다.',
    cues: ['발은 몸 아래에 가깝게 착지', '팔치기는 조금 더 명확하게', '페이스보다 자세 붕괴 여부를 우선 확인'],
    warning: '초반부터 과속하면 자세가 무너지고 지속 시간이 짧아집니다.',
    resources: [
      youtubeResource(
        'GpQ21f1b-cg',
        '올바른 러닝 자세',
        'RUNUP TV / 런업TV',
        '템포 런에서 무너지기 쉬운 상체와 착지 리듬을 복습하기 좋습니다.'
      )
    ]
  },
  runningLong: {
    headline: '지속 가능한 리듬으로 상체 힘을 빼고 하체 충격을 분산합니다.',
    cues: ['어깨와 손에 힘을 빼기', '착지는 조용하게', '후반부에도 보폭보다 케이던스 유지'],
    warning: '지치기 시작할수록 상체가 접히지 않게 체크하세요.',
    resources: [
      youtubeResource(
        'ewHVH6-udbg',
        '마라톤 달리기 러닝 자세 교정 기본 동작 3가지 #마라톤 #달리기 #러닝 #자세교정',
        '런앤다이어트',
        '롱런 전에 자세가 무너지지 않도록 기본 동작 3가지를 빠르게 점검하기 좋은 한국 영상입니다.'
      )
    ]
  }
} satisfies Record<string, ExerciseGuide>;

export const exerciseCatalog: Exercise[] = [
  exercise({
    id: 'bench-press',
    name: '벤치프레스',
    kind: 'strength',
    muscleGroup: 'chest',
    equipment: 'barbell',
    guide: guidePresets.benchPress,
    isCustom: false
  }),
  exercise({
    id: 'flat-dumbbell-press',
    name: '플랫 덤벨 프레스',
    kind: 'strength',
    muscleGroup: 'chest',
    equipment: 'dumbbell',
    guide: guidePresets.dumbbellPress,
    isCustom: false
  }),
  exercise({
    id: 'incline-dumbbell-press',
    name: '인클라인 덤벨 프레스',
    kind: 'strength',
    muscleGroup: 'chest',
    equipment: 'dumbbell',
    guide: guidePresets.dumbbellPress,
    isCustom: false
  }),
  exercise({
    id: 'dumbbell-floor-press',
    name: '덤벨 플로어 프레스',
    kind: 'strength',
    muscleGroup: 'chest',
    equipment: 'dumbbell',
    guide: guidePresets.dumbbellPress,
    isCustom: false
  }),
  exercise({
    id: 'dumbbell-fly',
    name: '덤벨 플라이',
    kind: 'strength',
    muscleGroup: 'chest',
    equipment: 'dumbbell',
    isCustom: false
  }),
  exercise({
    id: 'push-up',
    name: '푸시업',
    kind: 'strength',
    muscleGroup: 'chest',
    equipment: 'bodyweight',
    guide: guidePresets.pushUp,
    isCustom: false
  }),
  exercise({
    id: 'seated-row',
    name: '시티드 로우',
    kind: 'strength',
    muscleGroup: 'back',
    equipment: 'cable',
    guide: guidePresets.seatedRow,
    isCustom: false
  }),
  exercise({
    id: 'lat-pulldown',
    name: '랫풀다운',
    kind: 'strength',
    muscleGroup: 'back',
    equipment: 'cable',
    guide: guidePresets.latPulldown,
    isCustom: false
  }),
  exercise({
    id: 'one-arm-dumbbell-row',
    name: '원암 덤벨 로우',
    kind: 'strength',
    muscleGroup: 'back',
    equipment: 'dumbbell',
    guide: guidePresets.dumbbellRow,
    isCustom: false
  }),
  exercise({
    id: 'chest-supported-dumbbell-row',
    name: '체스트 서포티드 덤벨 로우',
    kind: 'strength',
    muscleGroup: 'back',
    equipment: 'dumbbell',
    guide: guidePresets.dumbbellRow,
    isCustom: false
  }),
  exercise({
    id: 'dumbbell-pullover',
    name: '덤벨 풀오버',
    kind: 'strength',
    muscleGroup: 'back',
    equipment: 'dumbbell',
    isCustom: false
  }),
  exercise({
    id: 'kettlebell-row',
    name: '케틀벨 로우',
    kind: 'strength',
    muscleGroup: 'back',
    equipment: 'kettlebell',
    guide: guidePresets.dumbbellRow,
    isCustom: false
  }),
  exercise({
    id: 'inverted-row',
    name: '인버티드 로우',
    kind: 'strength',
    muscleGroup: 'back',
    equipment: 'bodyweight',
    guide: guidePresets.pullUp,
    isCustom: false
  }),
  exercise({
    id: 'pull-up',
    name: '풀업',
    kind: 'strength',
    muscleGroup: 'back',
    equipment: 'bodyweight',
    guide: guidePresets.pullUp,
    isCustom: false
  }),
  exercise({
    id: 'chin-up',
    name: '친업',
    kind: 'strength',
    muscleGroup: 'back',
    equipment: 'bodyweight',
    guide: guidePresets.pullUp,
    isCustom: false
  }),
  exercise({
    id: 'squat',
    name: '스쿼트',
    kind: 'strength',
    muscleGroup: 'legs',
    equipment: 'barbell',
    guide: guidePresets.barbellSquat,
    isCustom: false
  }),
  exercise({
    id: 'romanian-deadlift',
    name: '루마니안 데드리프트',
    kind: 'strength',
    muscleGroup: 'legs',
    equipment: 'barbell',
    guide: guidePresets.hinge,
    isCustom: false
  }),
  exercise({
    id: 'leg-press',
    name: '레그프레스',
    kind: 'strength',
    muscleGroup: 'legs',
    equipment: 'machine',
    guide: {
      headline: '등과 엉덩이를 패드에 붙인 채 발 전체로 플랫폼을 밀어냅니다.',
      cues: ['내릴 때 무릎이 가슴을 살짝 향하는 정도까지만', '발 중앙으로 힘 전달', '잠금 직전까지 밀고 무릎은 완전 잠그지 않기'],
      warning: '하단에서 골반이 말려 엉덩이가 뜨지 않게 하세요.',
      resources: [
        youtubeResource(
          'hYwJrXpzEfs',
          '1분만에 마스터하는 레그프레스의 잘못된 자세 5가지와 올바른 레그프레스',
          '권혁',
          '레그프레스에서 흔한 실수를 빠르게 점검하기 좋은 한국 영상입니다.'
        )
      ]
    },
    isCustom: false
  }),
  exercise({
    id: 'dumbbell-goblet-squat',
    name: '덤벨 고블릿 스쿼트',
    kind: 'strength',
    muscleGroup: 'legs',
    equipment: 'dumbbell',
    guide: guidePresets.gobletSquat,
    isCustom: false
  }),
  exercise({
    id: 'dumbbell-bulgarian-split-squat',
    name: '덤벨 불가리안 스플릿 스쿼트',
    kind: 'strength',
    muscleGroup: 'legs',
    equipment: 'dumbbell',
    guide: guidePresets.splitSquat,
    isCustom: false
  }),
  exercise({
    id: 'dumbbell-reverse-lunge',
    name: '덤벨 리버스 런지',
    kind: 'strength',
    muscleGroup: 'legs',
    equipment: 'dumbbell',
    guide: guidePresets.splitSquat,
    isCustom: false
  }),
  exercise({
    id: 'dumbbell-step-up',
    name: '덤벨 스텝업',
    kind: 'strength',
    muscleGroup: 'legs',
    equipment: 'dumbbell',
    guide: guidePresets.splitSquat,
    isCustom: false
  }),
  exercise({
    id: 'dumbbell-romanian-deadlift',
    name: '덤벨 루마니안 데드리프트',
    kind: 'strength',
    muscleGroup: 'legs',
    equipment: 'dumbbell',
    guide: guidePresets.hinge,
    isCustom: false
  }),
  exercise({
    id: 'kettlebell-goblet-squat',
    name: '케틀벨 고블릿 스쿼트',
    kind: 'strength',
    muscleGroup: 'legs',
    equipment: 'kettlebell',
    guide: guidePresets.gobletSquat,
    isCustom: false
  }),
  exercise({
    id: 'kettlebell-deadlift',
    name: '케틀벨 데드리프트',
    kind: 'strength',
    muscleGroup: 'legs',
    equipment: 'kettlebell',
    guide: guidePresets.hinge,
    isCustom: false
  }),
  exercise({
    id: 'kettlebell-swing',
    name: '케틀벨 스윙',
    kind: 'strength',
    muscleGroup: 'legs',
    equipment: 'kettlebell',
    guide: guidePresets.kettlebellSwing,
    isCustom: false
  }),
  exercise({
    id: 'kettlebell-front-rack-reverse-lunge',
    name: '케틀벨 프론트 랙 리버스 런지',
    kind: 'strength',
    muscleGroup: 'legs',
    equipment: 'kettlebell',
    guide: guidePresets.splitSquat,
    isCustom: false
  }),
  exercise({
    id: 'bodyweight-squat',
    name: '맨몸 스쿼트',
    kind: 'strength',
    muscleGroup: 'legs',
    equipment: 'bodyweight',
    guide: guidePresets.bodyweightSquat,
    isCustom: false
  }),
  exercise({
    id: 'bodyweight-reverse-lunge',
    name: '맨몸 리버스 런지',
    kind: 'strength',
    muscleGroup: 'legs',
    equipment: 'bodyweight',
    guide: guidePresets.splitSquat,
    isCustom: false
  }),
  exercise({
    id: 'glute-bridge',
    name: '글루트 브리지',
    kind: 'strength',
    muscleGroup: 'legs',
    equipment: 'bodyweight',
    guide: guidePresets.hinge,
    isCustom: false
  }),
  exercise({
    id: 'shoulder-press',
    name: '숄더프레스',
    kind: 'strength',
    muscleGroup: 'shoulders',
    equipment: 'dumbbell',
    guide: guidePresets.shoulderPress,
    isCustom: false
  }),
  exercise({
    id: 'dumbbell-push-press',
    name: '덤벨 푸시 프레스',
    kind: 'strength',
    muscleGroup: 'shoulders',
    equipment: 'dumbbell',
    guide: guidePresets.shoulderPress,
    isCustom: false
  }),
  exercise({
    id: 'dumbbell-thruster',
    name: '덤벨 쓰러스터',
    kind: 'strength',
    muscleGroup: 'shoulders',
    equipment: 'dumbbell',
    guide: guidePresets.shoulderPress,
    isCustom: false
  }),
  exercise({
    id: 'dumbbell-lateral-raise',
    name: '덤벨 레터럴 레이즈',
    kind: 'strength',
    muscleGroup: 'shoulders',
    equipment: 'dumbbell',
    isCustom: false
  }),
  exercise({
    id: 'dumbbell-rear-delt-fly',
    name: '덤벨 리어델트 플라이',
    kind: 'strength',
    muscleGroup: 'shoulders',
    equipment: 'dumbbell',
    isCustom: false
  }),
  exercise({
    id: 'pike-push-up',
    name: '파이크 푸시업',
    kind: 'strength',
    muscleGroup: 'shoulders',
    equipment: 'bodyweight',
    guide: guidePresets.pikePushUp,
    isCustom: false
  }),
  exercise({
    id: 'kettlebell-clean-and-press',
    name: '케틀벨 클린 앤 프레스',
    kind: 'strength',
    muscleGroup: 'shoulders',
    equipment: 'kettlebell',
    guide: guidePresets.kettlebellCleanPress,
    isCustom: false
  }),
  exercise({
    id: 'kettlebell-halo',
    name: '케틀벨 헤일로',
    kind: 'strength',
    muscleGroup: 'shoulders',
    equipment: 'kettlebell',
    isCustom: false
  }),
  exercise({
    id: 'plank',
    name: '플랭크',
    kind: 'strength',
    muscleGroup: 'core',
    equipment: 'bodyweight',
    guide: guidePresets.plank,
    isCustom: false
  }),
  exercise({
    id: 'hanging-knee-raise',
    name: '행잉 니 레이즈',
    kind: 'strength',
    muscleGroup: 'core',
    equipment: 'bodyweight',
    guide: guidePresets.hangingRaise,
    isCustom: false
  }),
  exercise({
    id: 'kettlebell-turkish-get-up',
    name: '케틀벨 터키시 겟업',
    kind: 'strength',
    muscleGroup: 'core',
    equipment: 'kettlebell',
    guide: guidePresets.turkishGetUp,
    isCustom: false
  }),
  exercise({
    id: 'dumbbell-biceps-curl',
    name: '덤벨 바이셉 컬',
    kind: 'strength',
    muscleGroup: 'arms',
    equipment: 'dumbbell',
    isCustom: false
  }),
  exercise({
    id: 'hammer-curl',
    name: '해머 컬',
    kind: 'strength',
    muscleGroup: 'arms',
    equipment: 'dumbbell',
    isCustom: false
  }),
  exercise({
    id: 'overhead-dumbbell-triceps-extension',
    name: '오버헤드 덤벨 트라이셉 익스텐션',
    kind: 'strength',
    muscleGroup: 'arms',
    equipment: 'dumbbell',
    isCustom: false
  }),
  exercise({
    id: 'dip',
    name: '딥스',
    kind: 'strength',
    muscleGroup: 'arms',
    equipment: 'bodyweight',
    guide: guidePresets.dip,
    isCustom: false
  }),
  exercise({
    id: 'easy-run',
    name: '이지 런',
    kind: 'running',
    equipment: 'running',
    guide: guidePresets.runningEasy,
    isCustom: false
  }),
  exercise({
    id: 'tempo-run',
    name: '템포 런',
    kind: 'running',
    equipment: 'running',
    guide: guidePresets.runningTempo,
    isCustom: false
  }),
  exercise({
    id: 'long-run',
    name: '롱 런',
    kind: 'running',
    equipment: 'running',
    guide: guidePresets.runningLong,
    isCustom: false
  })
];

export const routineTemplates: RoutineTemplate[] = [
  {
    id: 'template-dumbbell-upper',
    name: 'Dumbbell Push Pull',
    blurb: '아령 위주로 상체 밀기와 당기기를 균형 있게 구성한 루틴',
    focus: '덤벨 위주',
    difficulty: 'intermediate',
    targets: ['가슴', '등', '어깨'],
    benefits: ['상체 밸런스', '프리웨이트 적응'],
    items: [
      strengthItem('flat-dumbbell-press', 1, 4, 8, 18, 90),
      strengthItem('one-arm-dumbbell-row', 2, 4, 10, 18, 90),
      strengthItem('shoulder-press', 3, 3, 10, 14, 75),
      strengthItem('hammer-curl', 4, 3, 12, 10, 60)
    ]
  },
  {
    id: 'template-dumbbell-lower',
    name: 'Dumbbell Lower Builder',
    blurb: '스쿼트, 힙힌지, 단측 하체를 아령으로 채우는 하체 루틴',
    focus: '덤벨 위주',
    difficulty: 'intermediate',
    targets: ['하체', '코어', '둔근'],
    benefits: ['하체 안정성', '균형 강화'],
    items: [
      strengthItem('dumbbell-goblet-squat', 1, 4, 10, 20, 90),
      strengthItem('dumbbell-romanian-deadlift', 2, 4, 10, 18, 90),
      strengthItem('dumbbell-bulgarian-split-squat', 3, 3, 10, 12, 75),
      strengthItem('plank', 4, 3, 1, 0, 45, '60초 유지 기준')
    ]
  },
  {
    id: 'template-freeweight-full',
    name: 'Freeweight Full Body',
    blurb: '장비가 많지 않아도 바로 돌릴 수 있는 덤벨 전신 루틴',
    focus: '프리웨이트 입문',
    difficulty: 'beginner',
    targets: ['전신', '코어'],
    benefits: ['기초 체력', '입문용 루틴'],
    items: [
      strengthItem('dumbbell-goblet-squat', 1, 3, 10, 18, 90),
      strengthItem('flat-dumbbell-press', 2, 3, 10, 16, 90),
      strengthItem('one-arm-dumbbell-row', 3, 3, 10, 16, 90),
      strengthItem('shoulder-press', 4, 3, 10, 12, 75),
      strengthItem('plank', 5, 3, 1, 0, 45, '45~60초 유지')
    ]
  },
  {
    id: 'template-kettlebell-flow',
    name: 'Kettlebell Flow',
    blurb: '케틀벨만으로 하체, 코어, 전신 협응을 함께 끌어올리는 루틴',
    focus: '케틀벨 위주',
    difficulty: 'intermediate',
    targets: ['하체', '코어', '전신'],
    benefits: ['전신 협응', '컨디셔닝'],
    items: [
      strengthItem('kettlebell-swing', 1, 5, 15, 16, 60),
      strengthItem('kettlebell-goblet-squat', 2, 4, 10, 16, 75),
      strengthItem('kettlebell-clean-and-press', 3, 4, 8, 12, 75),
      strengthItem('kettlebell-halo', 4, 3, 10, 10, 45)
    ]
  },
  {
    id: 'template-dumbbell-hybrid',
    name: 'Freeweight + Run',
    blurb: '덤벨 전신 자극 후 가볍게 러닝까지 연결하는 혼합 루틴',
    focus: '덤벨 + 러닝',
    difficulty: 'intermediate',
    targets: ['전신', '심폐'],
    benefits: ['체지방 관리', '운동 습관 만들기'],
    items: [
      strengthItem('dumbbell-goblet-squat', 1, 3, 10, 18, 75),
      strengthItem('flat-dumbbell-press', 2, 3, 10, 16, 75),
      strengthItem('one-arm-dumbbell-row', 3, 3, 10, 16, 75),
      runningItem('easy-run', 4, 3, 20, '웨이트 후 가볍게 호흡 정리')
    ]
  },
  {
    id: 'template-bodyweight-foundation',
    name: 'Bodyweight Foundation',
    blurb: '맨몸 기본 패턴으로 전신 밸런스와 운동 습관을 만들기 좋은 루틴',
    focus: '맨몸 기본기',
    difficulty: 'beginner',
    targets: ['가슴', '하체', '코어'],
    benefits: ['입문용', '기초 체력', '어디서나 가능'],
    items: [
      bodyweightItem('push-up', 1, 4, 10, 60),
      bodyweightItem('bodyweight-squat', 2, 4, 15, 45),
      bodyweightItem('bodyweight-reverse-lunge', 3, 3, 10, 45, '한쪽씩 10회'),
      bodyweightItem('plank', 4, 3, 1, 45, '45~60초 유지')
    ]
  },
  {
    id: 'template-bodyweight-upper-core',
    name: 'Bodyweight Upper + Core',
    blurb: '푸시업과 맨몸 당기기 패턴, 코어를 함께 묶은 상체 중심 루틴',
    focus: '맨몸 상체',
    difficulty: 'intermediate',
    targets: ['가슴', '등', '코어'],
    benefits: ['상체 지구력', '코어 강화'],
    items: [
      bodyweightItem('push-up', 1, 4, 12, 60),
      bodyweightItem('inverted-row', 2, 4, 10, 60),
      bodyweightItem('pike-push-up', 3, 3, 8, 60),
      bodyweightItem('hanging-knee-raise', 4, 3, 10, 45),
      bodyweightItem('plank', 5, 3, 1, 45, '45초 유지')
    ]
  },
  {
    id: 'template-bodyweight-lower-stability',
    name: 'Bodyweight Lower Stability',
    blurb: '맨몸 스쿼트와 런지, 힙 브리지로 하체 안정성과 균형을 다지는 루틴',
    focus: '맨몸 하체',
    difficulty: 'beginner',
    targets: ['하체', '둔근', '코어'],
    benefits: ['균형 강화', '하체 안정성'],
    items: [
      bodyweightItem('bodyweight-squat', 1, 4, 18, 45),
      bodyweightItem('bodyweight-reverse-lunge', 2, 3, 12, 45, '좌우 각각 진행'),
      bodyweightItem('glute-bridge', 3, 4, 15, 45),
      bodyweightItem('plank', 4, 3, 1, 45, '60초 유지 기준')
    ]
  },
  {
    id: 'template-travel-reset',
    name: 'Travel Reset',
    blurb: '출장이나 여행 중에도 침대 옆 공간에서 바로 할 수 있는 맨몸 리셋 루틴',
    focus: '맨몸 미니멀',
    difficulty: 'beginner',
    targets: ['전신', '코어'],
    benefits: ['짧고 효율적', '지속성'],
    items: [
      bodyweightItem('push-up', 1, 3, 12, 45),
      bodyweightItem('bodyweight-squat', 2, 3, 20, 30),
      bodyweightItem('glute-bridge', 3, 3, 15, 30),
      bodyweightItem('plank', 4, 3, 1, 30, '30~45초 유지')
    ]
  },
  {
    id: 'template-pullup-bar-builder',
    name: 'Pull-up Bar Builder',
    blurb: '철봉을 중심으로 등, 팔, 코어를 단계적으로 강화하는 루틴',
    focus: '철봉 기본기',
    difficulty: 'intermediate',
    targets: ['등', '팔', '코어'],
    benefits: ['등 힘 강화', '철봉 적응'],
    items: [
      bodyweightItem('pull-up', 1, 5, 5, 90),
      bodyweightItem('chin-up', 2, 4, 6, 90),
      bodyweightItem('hanging-knee-raise', 3, 4, 10, 45),
      bodyweightItem('push-up', 4, 3, 12, 45)
    ]
  },
  {
    id: 'template-pullup-bar-volume',
    name: 'Pull-up Bar Volume Day',
    blurb: '풀업 볼륨과 맨몸 보조 운동으로 철봉 횟수를 늘리기 좋은 루틴',
    focus: '철봉 볼륨',
    difficulty: 'advanced',
    targets: ['등', '어깨', '팔'],
    benefits: ['반복 수 향상', '상체 지구력'],
    items: [
      bodyweightItem('inverted-row', 1, 4, 12, 60),
      bodyweightItem('pull-up', 2, 6, 4, 75, '매 세트 품질 우선'),
      bodyweightItem('chin-up', 3, 3, 8, 75),
      bodyweightItem('dip', 4, 3, 10, 60)
    ]
  },
  {
    id: 'template-pullup-bar-core',
    name: 'Bar + Core Control',
    blurb: '철봉 매달리기, 친업, 행잉 레이즈로 상체 통제와 코어를 함께 잡는 루틴',
    focus: '철봉 + 코어',
    difficulty: 'advanced',
    targets: ['등', '코어'],
    benefits: ['그립 강화', '코어 안정성'],
    items: [
      bodyweightItem('chin-up', 1, 4, 6, 90),
      bodyweightItem('hanging-knee-raise', 2, 4, 12, 45),
      bodyweightItem('inverted-row', 3, 3, 12, 60),
      bodyweightItem('plank', 4, 3, 1, 45, '45~60초 유지')
    ]
  },
  {
    id: 'template-dumbbell-push-builder',
    name: 'Dumbbell Push Builder',
    blurb: '가슴과 어깨, 삼두를 아령만으로 채우는 밀기 중심 루틴',
    focus: '아령 밀기',
    difficulty: 'intermediate',
    targets: ['가슴', '어깨', '팔'],
    benefits: ['상체 볼륨', '프레스 향상'],
    items: [
      strengthItem('flat-dumbbell-press', 1, 4, 8, 18, 90),
      strengthItem('incline-dumbbell-press', 2, 3, 10, 16, 90),
      strengthItem('shoulder-press', 3, 3, 10, 14, 75),
      strengthItem('overhead-dumbbell-triceps-extension', 4, 3, 12, 12, 60)
    ]
  },
  {
    id: 'template-dumbbell-pull-lower',
    name: 'Dumbbell Pull + Lower',
    blurb: '로우와 힙힌지, 단측 하체를 섞어 균형을 챙기는 아령 루틴',
    focus: '아령 균형형',
    difficulty: 'intermediate',
    targets: ['등', '하체', '둔근'],
    benefits: ['균형 강화', '후면 사슬'],
    items: [
      strengthItem('one-arm-dumbbell-row', 1, 4, 10, 18, 90),
      strengthItem('dumbbell-romanian-deadlift', 2, 4, 10, 18, 90),
      strengthItem('dumbbell-step-up', 3, 3, 10, 12, 75),
      strengthItem('hammer-curl', 4, 3, 12, 10, 60)
    ]
  },
  {
    id: 'template-dumbbell-strength-circuit',
    name: 'Dumbbell Strength Circuit',
    blurb: '전신 자극을 고르게 담아 집에서도 돌리기 쉬운 아령 루틴',
    focus: '아령 전신',
    difficulty: 'beginner',
    targets: ['전신', '코어'],
    benefits: ['전신 자극', '집운동'],
    items: [
      strengthItem('dumbbell-goblet-squat', 1, 4, 10, 20, 75),
      strengthItem('dumbbell-floor-press', 2, 4, 10, 16, 75),
      strengthItem('chest-supported-dumbbell-row', 3, 4, 10, 16, 75),
      strengthItem('dumbbell-thruster', 4, 3, 10, 10, 60),
      bodyweightItem('plank', 5, 3, 1, 45, '45초 유지')
    ]
  },
  {
    id: 'template-dumbbell-aesthetic-upper',
    name: 'Dumbbell Aesthetic Upper',
    blurb: '프레스, 레이즈, 컬을 묶어 상체 볼륨을 채우는 아령 루틴',
    focus: '아령 상체 볼륨',
    difficulty: 'advanced',
    targets: ['가슴', '어깨', '팔'],
    benefits: ['볼륨 확보', '상체 라인'],
    items: [
      strengthItem('incline-dumbbell-press', 1, 4, 10, 16, 90),
      strengthItem('dumbbell-lateral-raise', 2, 3, 14, 6, 45),
      strengthItem('dumbbell-rear-delt-fly', 3, 3, 14, 6, 45),
      strengthItem('dumbbell-biceps-curl', 4, 3, 12, 10, 45),
      strengthItem('overhead-dumbbell-triceps-extension', 5, 3, 12, 10, 45)
    ]
  },
  {
    id: 'template-kettlebell-strength-base',
    name: 'Kettlebell Strength Base',
    blurb: '스윙과 스쿼트, 프레스로 케틀벨 기본 패턴을 탄탄하게 만드는 루틴',
    focus: '케틀벨 기본기',
    difficulty: 'beginner',
    targets: ['하체', '어깨', '코어'],
    benefits: ['전신 자극', '케틀벨 적응'],
    items: [
      strengthItem('kettlebell-deadlift', 1, 4, 10, 20, 75),
      strengthItem('kettlebell-goblet-squat', 2, 4, 10, 16, 75),
      strengthItem('kettlebell-clean-and-press', 3, 4, 6, 12, 75),
      strengthItem('kettlebell-halo', 4, 3, 10, 8, 45)
    ]
  },
  {
    id: 'template-kettlebell-conditioning-ladder',
    name: 'Kettlebell Conditioning Ladder',
    blurb: '짧은 휴식으로 심폐와 전신 협응을 함께 끌어올리는 케틀벨 루틴',
    focus: '케틀벨 컨디셔닝',
    difficulty: 'advanced',
    targets: ['전신', '심폐'],
    benefits: ['컨디셔닝', '칼로리 소모'],
    items: [
      strengthItem('kettlebell-swing', 1, 6, 15, 16, 45),
      strengthItem('kettlebell-front-rack-reverse-lunge', 2, 4, 8, 12, 60, '좌우 각각 진행'),
      strengthItem('kettlebell-row', 3, 4, 10, 16, 60),
      strengthItem('kettlebell-clean-and-press', 4, 3, 8, 10, 60)
    ]
  },
  {
    id: 'template-kettlebell-stability-core',
    name: 'Kettlebell Stability Core',
    blurb: '겟업과 헤일로, 스쿼트로 몸통 안정성과 전신 제어를 키우는 루틴',
    focus: '케틀벨 안정성',
    difficulty: 'advanced',
    targets: ['코어', '어깨', '하체'],
    benefits: ['안정성 향상', '전신 통제'],
    items: [
      strengthItem('kettlebell-turkish-get-up', 1, 3, 3, 8, 90, '좌우 각각 3회'),
      strengthItem('kettlebell-halo', 2, 3, 12, 8, 45),
      strengthItem('kettlebell-goblet-squat', 3, 4, 8, 16, 75),
      bodyweightItem('plank', 4, 3, 1, 45, '45~60초 유지')
    ]
  },
  {
    id: 'template-upper',
    name: 'Upper Momentum',
    blurb: '가슴과 등을 한 번에 밀어올리는 상체 루틴',
    focus: '웨이트 주력',
    difficulty: 'intermediate',
    targets: ['가슴', '등', '어깨'],
    benefits: ['상체 근력', '중량 향상'],
    items: [
      strengthItem('bench-press', 1, 4, 8, 40, 120),
      strengthItem('incline-dumbbell-press', 2, 3, 10, 16, 90),
      strengthItem('seated-row', 3, 4, 10, 35, 90),
      strengthItem('shoulder-press', 4, 3, 10, 14, 75)
    ]
  },
  {
    id: 'template-lower',
    name: 'Lower Power',
    blurb: '하체 중량과 안정성을 끌어올리는 기본 루틴',
    focus: '웨이트 주력',
    difficulty: 'advanced',
    targets: ['하체', '코어'],
    benefits: ['하체 근력', '중량 적응'],
    items: [
      strengthItem('squat', 1, 4, 6, 60, 150),
      strengthItem('romanian-deadlift', 2, 3, 8, 50, 120),
      strengthItem('leg-press', 3, 3, 12, 100, 90),
      strengthItem('plank', 4, 3, 1, 0, 45, '60초 유지 기준')
    ]
  },
  {
    id: 'template-full',
    name: 'Full Body Express',
    blurb: '주 3회 기준으로 운영하기 쉬운 전신 루틴',
    focus: '웨이트 입문',
    difficulty: 'beginner',
    targets: ['전신', '코어'],
    benefits: ['입문용', '짧고 효율적'],
    items: [
      strengthItem('squat', 1, 3, 8, 50, 120),
      strengthItem('bench-press', 2, 3, 8, 35, 120),
      strengthItem('lat-pulldown', 3, 3, 10, 35, 90),
      strengthItem('plank', 4, 3, 1, 0, 45, '60초 유지 기준')
    ]
  },
  {
    id: 'template-hybrid',
    name: 'Hybrid Reset',
    blurb: '하체 자극 후 짧은 러닝으로 마무리하는 혼합 루틴',
    focus: '웨이트 + 러닝',
    difficulty: 'intermediate',
    targets: ['하체', '심폐'],
    benefits: ['체력 보강', '혼합 루틴'],
    items: [
      strengthItem('squat', 1, 4, 8, 55, 120),
      strengthItem('leg-press', 2, 3, 12, 90, 90),
      runningItem('easy-run', 3, 3, 22, '웨이트 후 가볍게 호흡 정리')
    ]
  },
  {
    id: 'template-easy-run',
    name: 'Easy Run Base',
    blurb: '주간 러닝 빈도를 채우기 좋은 회복성 러닝',
    focus: '러닝 베이스',
    difficulty: 'beginner',
    targets: ['심폐', '러닝 기본기'],
    benefits: ['회복 주행', '지구력 베이스'],
    items: [runningItem('easy-run', 1, 4, 30, '호흡이 편한 강도로 유지')]
  },
  {
    id: 'template-tempo-run',
    name: 'Tempo Sharpener',
    blurb: '조금 빠른 페이스 감각을 익히는 템포 러닝',
    focus: '러닝 향상',
    difficulty: 'advanced',
    targets: ['심폐', '페이스 감각'],
    benefits: ['러닝 향상', '속도 적응'],
    items: [runningItem('tempo-run', 1, 5, 28, '중간 3km는 약간 숨찰 정도')]
  }
];

const starterTemplatePool: Record<
  'strength-running' | 'strength' | 'running',
  Record<RoutineDifficulty, string[]>
> = {
  'strength-running': {
    beginner: [
      'template-bodyweight-foundation',
      'template-dumbbell-strength-circuit',
      'template-kettlebell-strength-base',
      'template-easy-run'
    ],
    intermediate: [
      'template-bodyweight-upper-core',
      'template-dumbbell-push-builder',
      'template-kettlebell-flow',
      'template-easy-run'
    ],
    advanced: [
      'template-pullup-bar-builder',
      'template-dumbbell-aesthetic-upper',
      'template-kettlebell-conditioning-ladder',
      'template-tempo-run'
    ]
  },
  strength: {
    beginner: [
      'template-bodyweight-foundation',
      'template-bodyweight-lower-stability',
      'template-dumbbell-strength-circuit',
      'template-kettlebell-strength-base'
    ],
    intermediate: [
      'template-bodyweight-upper-core',
      'template-pullup-bar-builder',
      'template-dumbbell-pull-lower',
      'template-kettlebell-flow'
    ],
    advanced: [
      'template-pullup-bar-volume',
      'template-pullup-bar-core',
      'template-dumbbell-aesthetic-upper',
      'template-kettlebell-stability-core'
    ]
  },
  running: {
    beginner: ['template-easy-run', 'template-tempo-run'],
    intermediate: ['template-easy-run', 'template-tempo-run'],
    advanced: ['template-tempo-run', 'template-easy-run']
  }
};

export function getStarterTemplateIds(input: SetupInput): string[] {
  const wantsStrength = input.workoutTypes.includes('strength');
  const wantsRunning = input.workoutTypes.includes('running');
  const starterDifficulty = input.starterDifficulty ?? 'beginner';

  if (wantsStrength && wantsRunning) {
    return starterTemplatePool['strength-running'][starterDifficulty].slice(
      0,
      input.workoutsPerWeek >= 4 ? 4 : 3
    );
  }

  if (wantsStrength) {
    return starterTemplatePool.strength[starterDifficulty].slice(0, input.workoutsPerWeek >= 4 ? 4 : 3);
  }

  return starterTemplatePool.running[starterDifficulty].slice(0, input.workoutsPerWeek >= 4 ? 2 : 1);
}
