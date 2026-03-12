export function formatNumber(value?: number, digits = 0): string {
  if (value === undefined || Number.isNaN(value)) {
    return '-';
  }

  return value.toFixed(digits);
}

export function formatWeight(value?: number): string {
  if (value === undefined || value <= 0) {
    return '-';
  }

  return `${value}kg`;
}

export function formatDistance(value?: number): string {
  if (value === undefined || value <= 0) {
    return '-';
  }

  return `${value}km`;
}

export function formatDuration(value?: number): string {
  if (value === undefined || value <= 0) {
    return '-';
  }

  const hours = Math.floor(value / 60);
  const minutes = value % 60;

  if (hours > 0) {
    return `${hours}시간 ${minutes}분`;
  }

  return `${minutes}분`;
}

export function formatPace(value?: number): string {
  if (value === undefined || value <= 0) {
    return '-';
  }

  const minutes = Math.floor(value);
  const seconds = Math.round((value - minutes) * 60)
    .toString()
    .padStart(2, '0');

  return `${minutes}:${seconds}/km`;
}

export function paceToSpeedKmh(value?: number): number | undefined {
  if (value === undefined || value <= 0) {
    return undefined;
  }

  return Number((60 / value).toFixed(1));
}

export function speedToPaceMinPerKm(value?: number): number | undefined {
  if (value === undefined || value <= 0) {
    return undefined;
  }

  return Number((60 / value).toFixed(1));
}

export function formatSpeed(value?: number): string {
  if (value === undefined || value <= 0) {
    return '-';
  }

  return `${value.toFixed(1)}km/h`;
}
