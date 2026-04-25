import { useQuery } from '@tanstack/react-query';
import { apiClient } from '@cia/api-client';
import type { ReportCategory, ReportDefinition } from '../types/report.types';

export function useReportDefinitions(category?: ReportCategory) {
  return useQuery<ReportDefinition[]>({
    queryKey: ['reports', 'definitions', category ?? 'all'],
    queryFn: async () => {
      const params = category ? `?category=${category}` : '';
      const res = await apiClient.get<{ data: ReportDefinition[] }>(
        `/api/v1/reports/definitions${params}`
      );
      return res.data.data;
    },
    staleTime: 5 * 60 * 1000,
  });
}

export function useReportDefinition(id: string | undefined) {
  return useQuery<ReportDefinition>({
    queryKey: ['reports', 'definitions', id],
    queryFn: async () => {
      const res = await apiClient.get<{ data: ReportDefinition }>(
        `/api/v1/reports/definitions/${id}`
      );
      return res.data.data;
    },
    enabled: !!id,
  });
}
