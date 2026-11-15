import { type KeyboardEvent, type PointerEvent, useId, useLayoutEffect, useRef, useState } from "react";
import type { Metric, MetricUnit } from "../../hooks/useMetrics";
import { formatRate } from "../../lib/format";

// Categorical slots one and two of the validated palette, each stepped for its own theme.
const SERIES = [
  { stroke: "stroke-[#2a78d6] dark:stroke-[#3987e5]", fill: "fill-[#2a78d6] dark:fill-[#3987e5]", key: "bg-[#2a78d6] dark:bg-[#3987e5]", text: "text-[#2a78d6] dark:text-[#3987e5]" },
  { stroke: "stroke-[#eb6834] dark:stroke-[#d95926]", fill: "fill-[#eb6834] dark:fill-[#d95926]", key: "bg-[#eb6834] dark:bg-[#d95926]", text: "text-[#eb6834] dark:text-[#d95926]" },
];

const HEIGHT = 200;
const PAD = { top: 10, right: 12, bottom: 26, left: 64 };

/** A value as the axis and tooltip print it. */
export function formatMetric(value: number, unit: MetricUnit): string {
  switch (unit) {
    case "percent":
      return `${Number(value.toFixed(value < 10 ? 1 : 0))}%`;
    case "bytes":
      return formatRate(value);
    case "iops":
      return `${Number(value.toFixed(1))} IOPS`;
    case "pps":
      return `${Number(value.toFixed(1))} pps`;
  }
}

/** A round top for the axis: 1, 2 or 5 times a power of ten, split into four even steps. */
function niceMax(max: number, unit: MetricUnit): number {
  if (unit === "percent" && max > 50) return 100;
  const raw = Math.max(max, unit === "percent" ? 4 : 1) / 4;
  const power = 10 ** Math.floor(Math.log10(raw));
  const step = [1, 2, 5, 10].map((m) => m * power).find((s) => s >= raw) ?? raw;
  return step * 4;
}

interface Props {
  readonly metric: Metric;
  readonly timestamps: readonly number[];
  /** The span the x axis covers, epoch milliseconds; samples sit at their time within it. */
  readonly domain: readonly [number, number];
  /** How a timestamp is printed on the axis, which depends on the range. */
  readonly formatTime: (at: number) => string;
  /** Beside the title, e.g. what 100% means. */
  readonly note?: string;
}

/** One metric over time: a line and a light area per series, with a crosshair readout. */
export function MetricChart({ metric, timestamps, domain, formatTime, note }: Props) {
  const box = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(640);
  const [hover, setHover] = useState<number | null>(null);
  const titleId = useId();
  const gradientId = useId();

  // Drawn at the container's own width, so text keeps its size instead of scaling with a viewBox.
  useLayoutEffect(() => {
    const element = box.current;
    if (!element) return;
    const fit = () => element.clientWidth > 0 && setWidth(Math.max(240, element.clientWidth));
    fit();
    if (typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(fit);
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  const count = timestamps.length;
  const plotWidth = width - PAD.left - PAD.right;
  const plotHeight = HEIGHT - PAD.top - PAD.bottom;
  const top = niceMax(Math.max(0, ...metric.series.flatMap((s) => s.values)), metric.unit);
  const [start, end] = domain;
  const at = (time: number) => PAD.left + (end > start ? ((time - start) / (end - start)) * plotWidth : plotWidth);
  const x = (i: number) => at(timestamps[i]);
  const y = (value: number) => PAD.top + plotHeight - (Math.min(value, top) / top) * plotHeight;
  const ticks = [0, 1, 2, 3, 4].map((i) => (top / 4) * i);
  const labels = Math.max(2, Math.min(6, Math.floor(plotWidth / 110)));
  // Evenly spaced times across the span rather than sample indices, which repeat when there are few samples.
  const labelTimes = Array.from({ length: labels }, (_, i) => start + (i / (labels - 1)) * (end - start));

  const line = (values: readonly number[]) => values.map((v, i) => `${i === 0 ? "M" : "L"}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join("");
  const area = (values: readonly number[]) => `${line(values)}L${x(count - 1).toFixed(1)},${y(0)}L${x(0).toFixed(1)},${y(0)}Z`;
  const nearest = (time: number) => timestamps.reduce((best, t, i) => (Math.abs(t - time) < Math.abs(timestamps[best] - time) ? i : best), 0);

  function pick(event: PointerEvent<SVGSVGElement>) {
    const rect = event.currentTarget.getBoundingClientRect();
    const position = ((event.clientX - rect.left) / rect.width) * width;
    if (count > 0) setHover(nearest(start + ((position - PAD.left) / plotWidth) * (end - start)));
  }

  function step(event: KeyboardEvent<SVGSVGElement>) {
    const moves: Record<string, number> = { ArrowLeft: -1, ArrowRight: 1 };
    if (event.key in moves) {
      event.preventDefault();
      setHover((current) => Math.max(0, Math.min(count - 1, (current ?? count - 1) + moves[event.key])));
    } else if (event.key === "Escape") {
      setHover(null);
    }
  }

  const latest = metric.series.map((s) => `${s.label} ${formatMetric(s.values.at(-1) ?? 0, metric.unit)}`).join(", ");
  const tooltipLeft = hover === null ? 0 : x(hover);

  return (
    <section aria-labelledby={titleId} className="card flex min-w-0 flex-col gap-3 p-4 sm:p-5">
      <header className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
        <h2 id={titleId} className="text-sm font-medium">{metric.title}</h2>
        <div className="flex items-center gap-3 text-xs text-zinc-500 dark:text-zinc-400">
          {note && <span>{note}</span>}
          {metric.series.length > 1 && metric.series.map((series, i) => (
            <span key={series.key} className="flex items-center gap-1.5">
              <span aria-hidden className={`h-0.5 w-3 rounded-full ${SERIES[i % SERIES.length].key}`} />{series.label}
            </span>
          ))}
        </div>
      </header>
      <div ref={box} className="relative">
        <svg width="100%" height={HEIGHT} viewBox={`0 0 ${width} ${HEIGHT}`} role="img" tabIndex={0}
          aria-label={`${metric.title}. Latest: ${latest}. Use the arrow keys to read earlier values.`}
          onPointerMove={pick} onPointerLeave={() => setHover(null)} onKeyDown={step} onBlur={() => setHover(null)}
          className="touch-pan-y overflow-visible rounded focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-zinc-400">
          {ticks.map((tick) => (
            <g key={tick}>
              <line x1={PAD.left} x2={width - PAD.right} y1={y(tick)} y2={y(tick)} className="stroke-zinc-200 dark:stroke-zinc-800" strokeWidth={1} />
              <text x={PAD.left - 8} y={y(tick)} dy="0.32em" textAnchor="end" className="fill-zinc-500 text-[11px] tabular-nums dark:fill-zinc-400">{formatMetric(tick, metric.unit)}</text>
            </g>
          ))}
          {labelTimes.map((time, n) => (
            <text key={n} x={at(time)} y={HEIGHT - 6} textAnchor={n === 0 ? "start" : n === labelTimes.length - 1 ? "end" : "middle"}
              className="fill-zinc-500 text-[11px] tabular-nums dark:fill-zinc-400">{formatTime(time)}</text>
          ))}
          <defs>
            {metric.series.map((series, i) => (
              <linearGradient key={series.key} id={`${gradientId}-${i}`} x1="0" x2="0" y1="0" y2="1" className={SERIES[i % SERIES.length].text}>
                <stop offset="0%" stopColor="currentColor" stopOpacity={0.22} />
                <stop offset="100%" stopColor="currentColor" stopOpacity={0} />
              </linearGradient>
            ))}
          </defs>
          {count > 0 && metric.series.map((series, i) => (
            <g key={series.key}>
              <path d={area(series.values)} fill={`url(#${gradientId}-${i})`} />
              <path d={line(series.values)} className={SERIES[i % SERIES.length].stroke} fill="none" strokeWidth={1.75} strokeLinejoin="round" strokeLinecap="round" />
              {/* The newest sample, which is the only mark a VM a minute old has. */}
              <circle cx={x(count - 1)} cy={y(series.values[count - 1])} r={3} className={SERIES[i % SERIES.length].fill} />
            </g>
          ))}
          {hover !== null && (
            <g aria-hidden>
              <line x1={x(hover)} x2={x(hover)} y1={PAD.top} y2={PAD.top + plotHeight} className="stroke-zinc-400 dark:stroke-zinc-500" strokeWidth={1} />
              {metric.series.map((series, i) => (
                <circle key={series.key} cx={x(hover)} cy={y(series.values[hover])} r={4} strokeWidth={2}
                  className={`${SERIES[i % SERIES.length].fill} stroke-white dark:stroke-zinc-950`} />
              ))}
            </g>
          )}
        </svg>
        {hover !== null && (
          <div role="status" className="pointer-events-none absolute top-0 z-10 min-w-36 rounded-lg border border-zinc-200 bg-white px-3 py-2 text-xs shadow-lg dark:border-zinc-700 dark:bg-zinc-900"
            style={tooltipLeft > width / 2 ? { right: width - tooltipLeft + 12 } : { left: tooltipLeft + 12 }}>
            <p className="mb-1 text-zinc-500 dark:text-zinc-400">{formatTime(timestamps[hover])}</p>
            {metric.series.map((series, i) => (
              <p key={series.key} className="flex items-center gap-2">
                <span aria-hidden className={`h-0.5 w-3 shrink-0 rounded-full ${SERIES[i % SERIES.length].key}`} />
                <span className="font-semibold tabular-nums">{formatMetric(series.values[hover], metric.unit)}</span>
                <span className="text-zinc-500 dark:text-zinc-400">{series.label}</span>
              </p>
            ))}
          </div>
        )}
      </div>
    </section>
  );
}
