import { http } from './http.js';

export async function getBillingPackages() {
  const { data } = await http.get('/api/v1/billing/packages');
  return data;
}

export async function getBillingWallet() {
  const { data } = await http.get('/api/v1/billing/wallet');
  return data;
}

export async function getBillingLedger(params = {}) {
  const { data } = await http.get('/api/v1/billing/ledger', { params });
  return data;
}

export async function createRechargeOrder(payload) {
  const { data } = await http.post('/api/v1/billing/recharge-orders', payload);
  return data;
}

export async function getRechargeOrders(params = {}) {
  const { data } = await http.get('/api/v1/billing/recharge-orders', { params });
  return data;
}
