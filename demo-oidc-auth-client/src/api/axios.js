import axios from 'axios';

const api = axios.create({
  baseURL: 'http://localhost:8080/api/v2',  // Spring Boot API Server (with Context Path)
  withCredentials: true,                    // Session Cookieをクロスオリジンで送信するために必須
});

// レスポンスインターセプターで 401 エラーをキャッチしてログイン画面にリダイレクト
api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response && error.response.status === 401) {
      window.location.href = '/login';
    }
    return Promise.reject(error);
  }
);

export default api;
