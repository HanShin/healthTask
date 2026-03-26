import type { TrendPoint } from '../lib/historyTrends';

interface TrendChartProps {
  ariaLabel: string;
  points: TrendPoint[];
  variant: 'bar' | 'line';
  tone: 'workout' | 'health';
}

interface ChartCoordinate {
  key: string;
  x: number;
  y: number | null;
  value: number | null;
}

function splitIntoSegments(coordinates: ChartCoordinate[]): ChartCoordinate[][] {
  const segments: ChartCoordinate[][] = [];
  let current: ChartCoordinate[] = [];

  coordinates.forEach((coordinate) => {
    if (coordinate.y === null) {
      if (current.length > 0) {
        segments.push(current);
        current = [];
      }
      return;
    }

    current.push(coordinate);
  });

  if (current.length > 0) {
    segments.push(current);
  }

  return segments;
}

export function TrendChart({ ariaLabel, points, variant, tone }: TrendChartProps) {
  const width = 320;
  const height = 180;
  const paddingX = 12;
  const paddingTop = 16;
  const paddingBottom = 26;
  const innerWidth = width - paddingX * 2;
  const innerHeight = height - paddingTop - paddingBottom;
  const values = points.flatMap((point) => (point.value === null ? [] : [point.value]));
  const maxValue = Math.max(...values, 1);
  const barSlotWidth = points.length > 0 ? innerWidth / points.length : innerWidth;
  const barWidth = Math.max(4, barSlotWidth * 0.64);
  const xStep = points.length > 1 ? innerWidth / (points.length - 1) : 0;
  const coordinates = points.map((point, index) => ({
    key: point.dateKey,
    x: paddingX + (variant === 'bar' ? barSlotWidth * index + barSlotWidth / 2 : xStep * index),
    y:
      point.value === null
        ? null
        : paddingTop + innerHeight - (point.value / maxValue) * innerHeight,
    value: point.value
  }));
  const lineSegments = splitIntoSegments(coordinates);
  const xAxisLabels = [points[0], points[Math.floor(points.length / 2)], points[points.length - 1]].filter(
    (point, index, list): point is TrendPoint => Boolean(point) && list.findIndex((item) => item?.dateKey === point?.dateKey) === index
  );

  return (
    <div className={`trend-chart trend-chart--${variant} trend-chart--${tone}`}>
      <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label={ariaLabel}>
        <title>{ariaLabel}</title>
        <line
          className="trend-chart__baseline"
          x1={paddingX}
          x2={width - paddingX}
          y1={paddingTop + innerHeight}
          y2={paddingTop + innerHeight}
        />

        {variant === 'bar'
          ? coordinates.map((coordinate) => {
              const barHeight =
                coordinate.value === null ? 0 : Math.max(2, ((coordinate.value ?? 0) / maxValue) * innerHeight);

              return (
                <rect
                  key={coordinate.key}
                  className="trend-chart__bar"
                  x={coordinate.x - barWidth / 2}
                  y={paddingTop + innerHeight - barHeight}
                  width={barWidth}
                  height={barHeight}
                  rx="8"
                  ry="8"
                />
              );
            })
          : null}

        {variant === 'line'
          ? lineSegments.map((segment) => {
              const linePath = segment
                .map((coordinate, index) =>
                  `${index === 0 ? 'M' : 'L'} ${coordinate.x.toFixed(2)} ${(coordinate.y ?? 0).toFixed(2)}`
                )
                .join(' ');
              const areaPath = `${linePath} L ${segment[segment.length - 1].x.toFixed(2)} ${(paddingTop + innerHeight).toFixed(2)} L ${segment[0].x.toFixed(2)} ${(paddingTop + innerHeight).toFixed(2)} Z`;

              return (
                <g key={segment[0].key}>
                  <path className="trend-chart__area" d={areaPath} />
                  <path className="trend-chart__line" d={linePath} />
                </g>
              );
            })
          : null}

        {variant === 'line'
          ? coordinates
              .filter((coordinate) => coordinate.y !== null)
              .map((coordinate) => (
                <circle
                  key={coordinate.key}
                  className="trend-chart__dot"
                  cx={coordinate.x}
                  cy={coordinate.y ?? 0}
                  r="4.5"
                />
              ))
          : null}

        {xAxisLabels.map((point) => {
          const coordinate = coordinates.find((item) => item.key === point.dateKey);

          if (!coordinate) {
            return null;
          }

          return (
            <text key={point.dateKey} className="trend-chart__axis-label" x={coordinate.x} y={height - 6} textAnchor="middle">
              {point.label}
            </text>
          );
        })}
      </svg>
    </div>
  );
}
