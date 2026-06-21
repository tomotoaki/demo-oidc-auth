<template>
  <div class="glass-card fade-in home-card">
    <div v-if="loading" class="loading-state">
      <div class="spinner"></div>
      <p>ユーザー情報を取得中...</p>
    </div>

    <div v-else-if="error" class="error-state">
      <svg class="error-icon" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor">
        <path stroke-linecap="round" stroke-linejoin="round" d="M12 9v3.75m9-.75a9 9 0 1 1-18 0 9 9 0 0 1 18 0Zm-9 3.75h.008v.008H12v-.008Z" />
      </svg>
      <h2>エラーが発生しました</h2>
      <p class="error-msg">{{ error }}</p>
      <button @click="retry" class="outline-btn retry-btn">再試行</button>
    </div>

    <div v-else class="content-state">
      <div class="user-avatar">
        <span>{{ userInitials }}</span>
      </div>

      <h1 class="welcome-title">
        Welcome, <span class="gradient-text">{{ user.preferred_username || 'User' }}</span>!
      </h1>

      <p class="status-msg">
        OIDCによる認証に成功しました。ブラウザとServerの間ではセッションCookie（SESSION）のみがやり取りされています。
      </p>

      <div class="user-details">
        <div class="detail-row">
          <span class="label">ユーザーID (sub)</span>
          <span class="value code-val">{{ user.sub }}</span>
        </div>
        <div class="detail-row">
          <span class="label">ユーザー名</span>
          <span class="value">{{ user.preferred_username || '(未設定)' }}</span>
        </div>
        <div class="detail-row">
          <span class="label">メールアドレス</span>
          <span class="value">{{ user.email || '(未設定)' }}</span>
        </div>
      </div>

      <div class="action-buttons">
        <button @click="checkAccessToken" class="outline-btn check-btn" style="margin-right:0.5rem;">
          <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="2" stroke="currentColor" class="btn-icon">
            <path stroke-linecap="round" stroke-linejoin="round" d="M12 11c0 1.657-1.343 3-3 3s-3-1.343-3-3 1.343-3 3-3 3 1.343 3 3zM21 21v-2a4 4 0 00-4-4H7a4 4 0 00-4 4v2" />
          </svg>
          API呼び出し（トークン確認）
        </button>

        <button @click="logout" class="outline-btn logout-btn">
          <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="2" stroke="currentColor" class="btn-icon">
            <path stroke-linecap="round" stroke-linejoin="round" d="M15.75 9V5.25A2.25 2.25 0 0 0 13.5 3h-6a2.25 2.25 0 0 0-2.25 2.25v13.5A2.25 2.25 0 0 0 7.5 21h6a2.25 2.25 0 0 0 2.25-2.25V15m3 0 3-3m0 0-3-3m3 3H9" />
          </svg>
          ログアウト
        </button>
      </div>
      <div v-if="tokenCheckMessage" class="token-check-result" style="margin-top:1rem;text-align:center;">
        <p>{{ tokenCheckMessage }}</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue';
import api from '../api/axios';

const loading = ref(true);
const error = ref(null);
const user = ref({
  sub: '',
  preferred_username: '',
  email: ''
});

const userInitials = computed(() => {
  const name = user.value.preferred_username || 'U';
  return name.substring(0, 2).toUpperCase();
});

const fetchUser = async () => {
  loading.value = true;
  error.value = null;
  try {
    const response = await api.get('/user');
    user.value = response.data;
  } catch (err) {
    console.error(err);
    error.value = err.response?.data?.message || 'セッションの取得またはトークン検証に失敗しました。';
  } finally {
    loading.value = false;
  }
};

const logout = () => {
  // Spring Bootのログアウトエンドポイントへ直接遷移
  window.location.href = 'http://localhost:8080/api/v2/logout';
};

const retry = () => {
  fetchUser();
};

const tokenCheckMessage = ref('');

const checkAccessToken = async () => {
  tokenCheckMessage.value = '確認中...';
  try {
    const res = await fetch('http://localhost:8080/api/v2/user', {
      method: 'GET',
      credentials: 'include',
      headers: { 'Accept': 'application/json' }
    });

    if (res.status === 401) {
      tokenCheckMessage.value = 'アクセストークンが期限切れまたは無効です（401）。ログインが必要です。';
      return;
    }

    if (!res.ok) {
      tokenCheckMessage.value = `APIエラー: HTTP ${res.status}`;
      return;
    }

    const data = await res.json();
    // サーバーはデバッグ用に all_claims を返している
    const expJst = data.expJst || (data.all_claims?.exp ? new Date(data.all_claims.exp * 1000).toLocaleString('ja-JP', { timeZone: 'Asia/Tokyo' }) : null);
    if (expJst) {
      tokenCheckMessage.value = `アクセストークンは有効です。期限: ${expJst}`;
    } else {
      tokenCheckMessage.value = 'アクセストークンは有効です（期限情報がありません）。';
    }
  } catch (e) {
    console.error(e);
    tokenCheckMessage.value = 'ネットワークエラーが発生しました。コンソールを確認してください。';
  }
};

onMounted(() => {
  fetchUser();
});
</script>

<style scoped>
.home-card {
  max-width: 600px;
}

/* Loading style */
.loading-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1.5rem;
  padding: 2rem 0;
}

.spinner {
  width: 48px;
  height: 48px;
  border: 4px solid rgba(99, 102, 241, 0.1);
  border-left-color: #818cf8;
  border-radius: 50%;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

/* User Avatar */
.user-avatar {
  display: inline-flex;
  justify-content: center;
  align-items: center;
  width: 80px;
  height: 80px;
  background: linear-gradient(135deg, #6366f1 0%, #a855f7 100%);
  border-radius: 50%;
  font-size: 1.8rem;
  font-weight: 700;
  color: white;
  margin-bottom: 1.5rem;
  box-shadow: 0 8px 24px rgba(99, 102, 241, 0.35);
}

.welcome-title {
  font-size: 2.2rem;
  font-weight: 700;
  margin: 0 0 0.5rem 0;
  letter-spacing: -0.5px;
}

.status-msg {
  font-size: 0.95rem;
  line-height: 1.6;
  color: #94a3b8;
  margin-bottom: 2rem;
}

/* Details Table */
.user-details {
  background: rgba(255, 255, 255, 0.02);
  border: 1px solid rgba(255, 255, 255, 0.05);
  border-radius: 16px;
  padding: 1.5rem;
  margin-bottom: 2.5rem;
  text-align: left;
}

.detail-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0.8rem 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.05);
}

.detail-row:last-child {
  border-bottom: none;
  padding-bottom: 0;
}

.detail-row:first-child {
  padding-top: 0;
}

.label {
  font-size: 0.85rem;
  color: #64748b;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.value {
  font-size: 1rem;
  color: #cbd5e1;
  font-weight: 500;
}

.code-val {
  font-family: monospace;
  background: rgba(255, 255, 255, 0.05);
  padding: 0.2rem 0.5rem;
  border-radius: 6px;
  font-size: 0.85rem;
  word-break: break-all;
}

.action-buttons {
  display: flex;
  justify-content: center;
}

.logout-btn {
  width: 100%;
}

.btn-icon {
  width: 20px;
  height: 20px;
}

/* Error style */
.error-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 1rem 0;
}

.error-icon {
  width: 64px;
  height: 64px;
  color: #ef4444;
  margin-bottom: 1.5rem;
}

.error-msg {
  color: #f87171;
  font-size: 0.95rem;
  margin-bottom: 1.5rem;
  text-align: center;
}

.retry-btn {
  margin-top: 1.5rem;
  width: 100%;
}
</style>
