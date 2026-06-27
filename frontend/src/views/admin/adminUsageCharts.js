export function fillDailyCostReport(rows = [], days = 7) {
  const byDay = new Map();
  for (const row of rows || []) {
    const day = normalizeDayKey(row?.day);
    if (!day) continue;
    const existing = byDay.get(day);
    byDay.set(day, {
      ...(existing || {}),
      ...row,
      day,
      costUsd: Number(existing?.costUsd || 0) + Number(row?.costUsd || 0),
    });
  }

  const today = new Date();
  today.setHours(0, 0, 0, 0);
  return Array.from({ length: Number(days) || 7 }, (_, index) => {
    const date = new Date(today);
    date.setDate(today.getDate() - ((Number(days) || 7) - 1 - index));
    const day = formatDayKey(date);
    const row = byDay.get(day);
    return {
      ...(row || {}),
      day,
      costUsd: Number(row?.costUsd || 0),
    };
  });
}

export function dailyCostChartOptions(days = 7) {
  const compact = Number(days) > 14;
  return {
    responsive: true,
    maintainAspectRatio: false,
    plugins: { legend: { display: false } },
    scales: {
      x: {
        ticks: {
          autoSkip: false,
          maxRotation: compact ? 45 : 0,
          minRotation: compact ? 45 : 0,
          font: { size: compact ? 10 : 11 },
        },
      },
    },
  };
}

function normalizeDayKey(value) {
  const raw = String(value || '').trim();
  if (!raw) return '';
  if (/^\d{4}-\d{2}-\d{2}/.test(raw)) return raw.slice(0, 10);
  if (/^\d{2}-\d{2}$/.test(raw)) return `${new Date().getFullYear()}-${raw}`;
  const parsed = new Date(raw);
  return Number.isNaN(parsed.getTime()) ? '' : formatDayKey(parsed);
}

function formatDayKey(date) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}
