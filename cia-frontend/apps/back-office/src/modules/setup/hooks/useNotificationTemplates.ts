import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createNotificationTemplate,
  deleteNotificationTemplate,
  getNotificationTemplateDefaults,
  getNotificationTemplateVariables,
  listNotificationTemplates,
  previewNotificationTemplate,
  updateNotificationTemplate,
  type ApiError,
  type ApiResponse,
  type NotificationTemplatePreviewRequest,
  type NotificationTemplateRequest,
} from '@cia/api-client';
import { toast } from '@cia/ui';

interface ApiHttpError { response?: { data?: ApiResponse<unknown> }; message?: string }

const QK = ['setup', 'notification-templates'] as const;

export function useNotificationTemplates() {
  return useQuery({ queryKey: QK, queryFn: () => listNotificationTemplates() });
}

export function useNotificationTemplateDefaults() {
  return useQuery({
    queryKey: [...QK, 'defaults'],
    queryFn: () => getNotificationTemplateDefaults(),
    staleTime: Infinity,
  });
}

export function useNotificationTemplateVariables() {
  return useQuery({
    queryKey: [...QK, 'variables'],
    queryFn: () => getNotificationTemplateVariables(),
    staleTime: Infinity,
  });
}

export function useSaveNotificationTemplate() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (params: { id?: string; req: NotificationTemplateRequest }) =>
      params.id
        ? updateNotificationTemplate(params.id, params.req)
        : createNotificationTemplate(params.req),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: QK });
      toast({ title: 'Template saved', description: 'Notification template updated successfully.' });
    },
    onError: (error) => {
      const ax = error as ApiHttpError;
      const errors: ApiError[] = ax?.response?.data?.errors ?? [];
      const code = errors[0]?.code ?? '';
      const serverMessage = errors[0]?.message ?? ax?.message ?? 'Failed to save template';
      let description: string;
      if (code === 'UNKNOWN_TEMPLATE_VARIABLE') {
        description = `Unknown variable: ${serverMessage}`;
      } else if (code === 'TEMPLATE_TYPE_CHANNEL_CONFLICT') {
        description = 'An override already exists for this combination';
      } else if (code === 'EMPTY_OVERRIDE') {
        description = 'Provide a subject or body';
      } else {
        description = errors.length > 0
          ? errors.map(e => e.message).filter(Boolean).join('. ')
          : ax?.message ?? 'Failed to save template';
      }
      toast({ variant: 'destructive', title: 'Save failed', description });
    },
  });
}

export function useResetNotificationTemplate() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => deleteNotificationTemplate(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: QK });
      toast({ title: 'Reset to default', description: 'Template has been reset to the system default.' });
    },
    onError: (error) => {
      const ax = error as ApiHttpError;
      const errors: ApiError[] = ax?.response?.data?.errors ?? [];
      const description = errors.length > 0
        ? errors.map(e => e.message).filter(Boolean).join('. ')
        : ax?.message ?? 'Failed to reset template';
      toast({ variant: 'destructive', title: 'Reset failed', description });
    },
  });
}

export function usePreviewNotificationTemplate() {
  return useMutation({
    mutationFn: (req: NotificationTemplatePreviewRequest) => previewNotificationTemplate(req),
  });
}
