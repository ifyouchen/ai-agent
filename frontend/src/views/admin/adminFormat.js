export function fmtCost(value) {
  const num = Number(value ?? 0);
  return num.toFixed(num >= 1 ? 4 : 6);
}

export function fmtNum(value) {
  return Number(value ?? 0).toLocaleString();
}

export function fmtPct(value) {
  return `${(Number(value ?? 0) * 100).toFixed(1)}%`;
}

export function shortTime(value) {
  if (!value) return '-';
  return String(value).replace('T', ' ').slice(0, 19);
}

export function parseStatusClass(status) {
  if (status === 'DONE') return 'ok';
  if (status === 'FAILED') return 'error';
  if (['PARSING', 'CHUNKING', 'EMBEDDING', 'RUNNING', 'PROCESSING'].includes(status)) return 'warn';
  return 'info';
}

export function displayUserName(row = {}) {
  return row.nickname || row.username || row.userId || 'anonymous';
}
