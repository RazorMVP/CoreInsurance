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
