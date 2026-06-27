import { http } from './http.js';

export async function getMyUsageSummary(days = 7) {
  const { data } = await http.get('/api/v1/token-usage/my/summary', {
    params: { days },
  });
  return data;
}

export async function getMyDailyUsage(days = 7) {
  const { data } = await http.get('/api/v1/token-usage/my/daily', {
    params: { days },
  });
  return data;
}

export async function getMyUsageDetails({ days = 7, page = 1, size = 10 } = {}) {
  const { data } = await http.get('/api/v1/token-usage/my/details', {
    params: { days, page, size },
  });
  return data;
}
