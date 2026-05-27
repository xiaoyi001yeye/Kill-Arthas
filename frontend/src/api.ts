import axios from 'axios';

const apiBaseUrl = import.meta.env.VITE_FORDRING_API_BASE_URL ?? 'http://localhost:8080';
export const wsBaseUrl = import.meta.env.VITE_FORDRING_WS_BASE_URL ?? 'ws://localhost:8080';
const operatorName = import.meta.env.VITE_FORDRING_OPERATOR_NAME ?? 'admin';

export type ApiResponse<T> =
  | { success: true; data: T; requestId: string }
  | { success: false; error: { code: string; message: string }; requestId: string };

export const api = axios.create({
  baseURL: apiBaseUrl,
  headers: { 'X-Fordring-Operator': operatorName }
});

export async function unwrap<T>(promise: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  let response: ApiResponse<T>;
  try {
    response = (await promise).data;
  } catch (error) {
    if (axios.isAxiosError<ApiResponse<T>>(error) && error.response?.data && !error.response.data.success) {
      throw new Error(error.response.data.error.message);
    }
    throw error;
  }
  if (!response.success) throw new Error(response.error.message);
  return response.data;
}
