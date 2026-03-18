interface HolidayApiRecord {
  date: string;
  localName: string;
  name: string;
}

interface HolidayCachePayload {
  fetchedAt: string;
  holidays: HolidayApiRecord[];
}

const HOLIDAY_CACHE_PREFIX = 'health-task:holiday-cache:';
const HOLIDAY_API_BASE = 'https://date.nager.at/api/v3/PublicHolidays';
const HOLIDAY_CACHE_TTL_MS = 1000 * 60 * 60 * 24 * 14;

function getHolidayCacheKey(year: number, countryCode: string): string {
  return `${HOLIDAY_CACHE_PREFIX}${countryCode}:${year}`;
}

function isHolidayApiRecord(value: unknown): value is HolidayApiRecord {
  return (
    typeof value === 'object' &&
    value !== null &&
    'date' in value &&
    typeof value.date === 'string' &&
    'localName' in value &&
    typeof value.localName === 'string' &&
    'name' in value &&
    typeof value.name === 'string'
  );
}

function readHolidayCache(year: number, countryCode: string): HolidayApiRecord[] | null {
  if (typeof window === 'undefined') {
    return null;
  }

  try {
    const cached = window.localStorage.getItem(getHolidayCacheKey(year, countryCode));

    if (!cached) {
      return null;
    }

    const parsed = JSON.parse(cached) as HolidayCachePayload;

    if (
      !parsed ||
      typeof parsed.fetchedAt !== 'string' ||
      !Array.isArray(parsed.holidays) ||
      !parsed.holidays.every(isHolidayApiRecord)
    ) {
      return null;
    }

    if (Date.now() - new Date(parsed.fetchedAt).getTime() > HOLIDAY_CACHE_TTL_MS) {
      return null;
    }

    return parsed.holidays;
  } catch {
    return null;
  }
}

function writeHolidayCache(year: number, countryCode: string, holidays: HolidayApiRecord[]): void {
  if (typeof window === 'undefined') {
    return;
  }

  try {
    const payload: HolidayCachePayload = {
      fetchedAt: new Date().toISOString(),
      holidays
    };

    window.localStorage.setItem(getHolidayCacheKey(year, countryCode), JSON.stringify(payload));
  } catch {
    // Ignore cache write failures and continue with in-memory data only.
  }
}

export async function fetchPublicHolidays(year: number, countryCode = 'KR'): Promise<HolidayApiRecord[]> {
  const cached = readHolidayCache(year, countryCode);

  if (cached) {
    return cached;
  }

  const response = await fetch(`${HOLIDAY_API_BASE}/${year}/${countryCode}`);

  if (!response.ok) {
    throw new Error(`공휴일 데이터를 가져오지 못했습니다. ${response.status}`);
  }

  const data = (await response.json()) as unknown;
  const holidays = Array.isArray(data) ? data.filter(isHolidayApiRecord) : [];

  writeHolidayCache(year, countryCode, holidays);

  return holidays;
}

export async function loadHolidayMap(dateKeys: string[], countryCode = 'KR'): Promise<Record<string, string>> {
  const years = [...new Set(dateKeys.map((dateKey) => Number(dateKey.slice(0, 4))).filter(Number.isFinite))];
  const map: Record<string, string> = {};
  const results = await Promise.allSettled(years.map((year) => fetchPublicHolidays(year, countryCode)));

  results.forEach((result) => {
    if (result.status !== 'fulfilled') {
      return;
    }

    result.value.forEach((holiday) => {
      map[holiday.date] = holiday.localName || holiday.name;
    });
  });

  return map;
}
