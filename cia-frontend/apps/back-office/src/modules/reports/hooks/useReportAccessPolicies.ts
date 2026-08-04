import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { apiClient, validatedGet } from '@cia/api-client';
import { ReportAccessPolicySchema } from '../types/report.types';
import type { ReportAccessPolicy } from '../types/report.types';

export function useReportAccessPolicies(accessGroupId: string | undefined) {
  return useQuery<ReportAccessPolicy[]>({
    queryKey: ['reports', 'access-policies', accessGroupId],
    queryFn: () => validatedGet(
      `/api/v1/reports/access-policies?accessGroupId=${accessGroupId}`,
      z.array(ReportAccessPolicySchema),
    ),
    enabled: !!accessGroupId,
  });
}

interface UpsertPolicyRequest {
  accessGroupId: string;
  category?: string;
  reportId?: string;
  canView: boolean;
  canExportCsv: boolean;
  canExportPdf: boolean;
}

export function useUpsertAccessPolicy() {
  const queryClient = useQueryClient();
  return useMutation<ReportAccessPolicy, Error, UpsertPolicyRequest>({
    mutationFn: async (req) => {
      const res = await apiClient.put<{ data: ReportAccessPolicy }>(
        '/api/v1/reports/access-policies',
        req
      );
      return res.data.data;
    },
    onSuccess: (_, vars) => {
      queryClient.invalidateQueries({
        queryKey: ['reports', 'access-policies', vars.accessGroupId],
      });
    },
  });
}
