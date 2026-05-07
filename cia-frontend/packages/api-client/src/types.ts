export interface ApiMeta {
  page?: number;
  size?: number;
  total?: number;
  totalPages?: number;
}

export interface ApiError {
  code: string;
  message: string;
  field?: string;
}

export interface ApiResponse<T> {
  data: T;
  meta?: ApiMeta;
  errors?: ApiError[];
}

export interface PageResponse<T> {
  data: T[];
  meta: Required<ApiMeta>;
}

export interface SpringPageResponse<T> {
  content: T[];
  totalElements?: number;
  totalPages?: number;
  number?: number;
  size?: number;
}

export function normalizePageResponse<T>(page: SpringPageResponse<T> | PageResponse<T> | T[]): PageResponse<T> {
  if (Array.isArray(page)) {
    return {
      data: page,
      meta: { page: 0, size: page.length, total: page.length, totalPages: page.length > 0 ? 1 : 0 },
    };
  }

  if ('content' in page && Array.isArray(page.content)) {
    return {
      data: page.content,
      meta: {
        page: page.number ?? 0,
        size: page.size ?? page.content.length,
        total: page.totalElements ?? page.content.length,
        totalPages: page.totalPages ?? (page.content.length > 0 ? 1 : 0),
      },
    };
  }

  if ('data' in page && Array.isArray(page.data)) {
    return page;
  }

  return {
    data: [],
    meta: { page: 0, size: 0, total: 0, totalPages: 0 },
  };
}

export function unwrapPageData<T>(page: SpringPageResponse<T> | PageResponse<T> | T[]): T[] {
  return normalizePageResponse(page).data;
}
