import { useMutation } from '@tanstack/react-query';
import { apiClient } from '@cia/api-client';
import type { ReportResultDto, ReportRunRequest } from '../types/report.types';

export function useRunReport() {
  return useMutation<ReportResultDto, Error, ReportRunRequest>({
    mutationFn: async (request) => {
      const res = await apiClient.post<{ data: ReportResultDto }>(
        '/api/v1/reports/run',
        request
      );
      return res.data.data;
    },
  });
}

export function useExportCsv() {
  return useMutation<void, Error, ReportRunRequest>({
    mutationFn: async (request) => {
      const res = await apiClient.post('/api/v1/reports/run/csv', request, {
        responseType: 'blob',
      });
      const url = URL.createObjectURL(new Blob([res.data], { type: 'text/csv' }));
      const a = document.createElement('a');
      a.href = url;
      a.download = `report-${new Date().toISOString().slice(0, 10)}.csv`;
      a.click();
      URL.revokeObjectURL(url);
    },
  });
}

export function useExportPdf() {
  return useMutation<void, Error, ReportRunRequest>({
    mutationFn: async (request) => {
      const res = await apiClient.post('/api/v1/reports/run/pdf', request, {
        responseType: 'blob',
      });
      const url = URL.createObjectURL(new Blob([res.data], { type: 'application/pdf' }));
      const a = document.createElement('a');
      a.href = url;
      a.download = `report-${new Date().toISOString().slice(0, 10)}.pdf`;
      a.click();
      URL.revokeObjectURL(url);
    },
  });
}
