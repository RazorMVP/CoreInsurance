import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '@cia/api-client';
import type { ReportDefinition } from '../types/report.types';

export function useReportPins() {
  return useQuery<ReportDefinition[]>({
    queryKey: ['reports', 'pins'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: ReportDefinition[] }>('/api/v1/reports/pins');
      return res.data.data;
    },
  });
}

export function usePinReport() {
  const queryClient = useQueryClient();
  return useMutation<void, Error, string>({
    mutationFn: async (reportId) => {
      await apiClient.post(`/api/v1/reports/pins/${reportId}`);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['reports', 'pins'] });
    },
  });
}

export function useUnpinReport() {
  const queryClient = useQueryClient();
  return useMutation<void, Error, string>({
    mutationFn: async (reportId) => {
      await apiClient.delete(`/api/v1/reports/pins/${reportId}`);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['reports', 'pins'] });
    },
  });
}
