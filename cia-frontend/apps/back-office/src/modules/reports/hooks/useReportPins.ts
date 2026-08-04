import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { apiClient, validatedGet } from '@cia/api-client';
import { ReportDefinitionSchema } from '../types/report.types';
import type { ReportDefinition } from '../types/report.types';

export function useReportPins() {
  return useQuery<ReportDefinition[]>({
    queryKey: ['reports', 'pins'],
    queryFn: () => validatedGet('/api/v1/reports/pins', z.array(ReportDefinitionSchema)),
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
