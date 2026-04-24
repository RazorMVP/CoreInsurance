import { useMutation, useQuery, useQueryClient, type UseQueryOptions } from '@tanstack/react-query';
import { apiClient } from './client';
import type { ApiResponse, PageResponse } from './types';

// ── Generic typed query hooks ──────────────────────────────────────────────

export function useGet<T>(
  queryKey: unknown[],
  url:      string,
  options?: Omit<UseQueryOptions<T>, 'queryKey' | 'queryFn'>,
) {
  return useQuery<T>({
    queryKey,
    queryFn: async () => {
      const res = await apiClient.get<ApiResponse<T>>(url);
      return res.data.data;
    },
    ...options,
  });
}

export function useList<T>(
  queryKey: unknown[],
  url:      string,
  params?:  Record<string, unknown>,
  options?: Omit<UseQueryOptions<PageResponse<T>>, 'queryKey' | 'queryFn'>,
) {
  return useQuery<PageResponse<T>>({
    queryKey: [...queryKey, params],
    queryFn: async () => {
      const res = await apiClient.get<ApiResponse<PageResponse<T>>>(url, { params });
      return res.data.data as PageResponse<T>;
    },
    ...options,
  });
}

export function useCreate<TData, TBody>(
  url:          string,
  invalidateKeys: unknown[][],
) {
  const qc = useQueryClient();
  return useMutation<TData, Error, TBody>({
    mutationFn: async (body) => {
      const res = await apiClient.post<ApiResponse<TData>>(url, body);
      return res.data.data;
    },
    onSuccess: () => {
      invalidateKeys.forEach(k => qc.invalidateQueries({ queryKey: k }));
    },
  });
}

export function useUpdate<TData, TBody>(
  urlFn:        (id: string) => string,
  invalidateKeys: unknown[][],
) {
  const qc = useQueryClient();
  return useMutation<TData, Error, { id: string; body: TBody }>({
    mutationFn: async ({ id, body }) => {
      const res = await apiClient.put<ApiResponse<TData>>(urlFn(id), body);
      return res.data.data;
    },
    onSuccess: () => {
      invalidateKeys.forEach(k => qc.invalidateQueries({ queryKey: k }));
    },
  });
}

export function useRemove(
  urlFn:          (id: string) => string,
  invalidateKeys: unknown[][],
) {
  const qc = useQueryClient();
  return useMutation<void, Error, string>({
    mutationFn: async (id) => {
      await apiClient.delete(urlFn(id));
    },
    onSuccess: () => {
      invalidateKeys.forEach(k => qc.invalidateQueries({ queryKey: k }));
    },
  });
}
