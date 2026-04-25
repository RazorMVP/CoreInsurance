import { useQuery } from '@tanstack/react-query';
import { apiClient } from '@cia/api-client';

export interface DashboardStats {
  activePolicies: number;
  openClaims: number;
  pendingApprovals: number;
  premiumsMtd: number;
  claimsReserveTotal: number;
  renewalsDue30Days: number;
  outstandingPremium: number;
  riUtilisationPct: number;
}

export interface ApprovalQueue {
  policies: number;
  quotes: number;
  endorsements: number;
  claims: number;
  receipts: number;
  payments: number;
}

export interface LossRatioMonth {
  month: string;
  premium: number;
  claims: number;
  lossRatioPct: number;
}

export interface RenewalDay {
  date: string;
  label: string;
  count: number;
}

export interface RecentActivity {
  id: string;
  entityType: string;
  entityId: string;
  action: string;
  userName: string;
  timeAgo: string;
  statusGroup: 'active' | 'pending' | 'rejected';
}

const STALE = 60 * 1000; // 1 minute

export function useDashboardStats() {
  return useQuery<DashboardStats>({
    queryKey: ['dashboard', 'stats'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: DashboardStats }>('/api/v1/dashboard/stats');
      return res.data.data;
    },
    staleTime: STALE,
  });
}

export function useApprovalQueue() {
  return useQuery<ApprovalQueue>({
    queryKey: ['dashboard', 'approval-queue'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: ApprovalQueue }>('/api/v1/dashboard/approval-queue');
      return res.data.data;
    },
    staleTime: STALE,
  });
}

export function useLossRatioTrend() {
  return useQuery<LossRatioMonth[]>({
    queryKey: ['dashboard', 'loss-ratio'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: LossRatioMonth[] }>('/api/v1/dashboard/loss-ratio');
      return res.data.data;
    },
    staleTime: STALE,
  });
}

export function useRenewalsDue() {
  return useQuery<RenewalDay[]>({
    queryKey: ['dashboard', 'renewals-due'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: RenewalDay[] }>('/api/v1/dashboard/renewals-due');
      return res.data.data;
    },
    staleTime: STALE,
  });
}

export function useRecentActivity() {
  return useQuery<RecentActivity[]>({
    queryKey: ['dashboard', 'recent-activity'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: RecentActivity[] }>('/api/v1/dashboard/recent-activity');
      return res.data.data;
    },
    staleTime: 30 * 1000, // 30s — activity changes more frequently
  });
}
