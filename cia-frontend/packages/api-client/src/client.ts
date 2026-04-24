import axios, { type AxiosInstance } from 'axios';

let _tokenGetter: (() => string | undefined) | null = null;

export function setTokenGetter(fn: () => string | undefined) {
  _tokenGetter = fn;
}

export function createApiClient(baseURL: string): AxiosInstance {
  const client = axios.create({ baseURL });

  client.interceptors.request.use((config) => {
    const token = _tokenGetter?.();
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  });

  client.interceptors.response.use(
    (res) => res,
    (error) => {
      if (error.response?.status === 401) {
        window.dispatchEvent(new CustomEvent('cia:unauthorized'));
      }
      return Promise.reject(error);
    }
  );

  return client;
}

export let apiClient: AxiosInstance = createApiClient('http://localhost:8080');

export function initApiClient(baseURL: string) {
  apiClient = createApiClient(baseURL);
}
