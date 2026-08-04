import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { apiClient, validatedGet } from '@cia/api-client';
import { ReportDefinitionSchema } from '../types/report.types';
import type { ReportCategory, ReportDefinition } from '../types/report.types';

export function useReportDefinitions(category?: ReportCategory) {
  return useQuery<ReportDefinition[]>({
    queryKey: ['reports', 'definitions', category ?? 'all'],
    queryFn: () => {
      const params = category ? `?category=${category}` : '';
      return validatedGet(`/api/v1/reports/definitions${params}`, z.array(ReportDefinitionSchema));
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

export function useCloneReport() {
  const queryClient = useQueryClient();
  return useMutation<ReportDefinition, Error, { id: string; name?: string }>({
    mutationFn: async ({ id, name }) => {
      const params = name ? `?name=${encodeURIComponent(name)}` : '';
      const res = await apiClient.post<{ data: ReportDefinition }>(
        `/api/v1/reports/definitions/${id}/clone${params}`
      );
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['reports', 'definitions'] });
    },
  });
}
