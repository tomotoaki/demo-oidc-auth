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

      <!-- ── スイッチ中バナー ──────────────────────────────────── -->
      <div v-if="user.is_switched" class="switch-banner" :class="user.switch_method">
        <div class="switch-banner-icon">
          <span v-if="user.switch_method === 'session'">⚠</span>
          <span v-else>✅</span>
        </div>
        <div class="switch-banner-body">
          <div class="switch-banner-title">
            <strong>{{ user.switched_from }}</strong> として
            <strong>{{ user.preferred_username }}</strong> に切替中
          </div>
          <div class="switch-banner-desc" v-if="user.switch_method === 'session'">
            Approach A: セッション方式 — JWTは <code>{{ user.switched_from }}</code> のまま
            （actual_jwt_username: <code>{{ user.actual_jwt_username }}</code>）
          </div>
          <div class="switch-banner-desc" v-else>
            Approach B: Token Exchange方式 — JWTが <code>{{ user.preferred_username }}</code> の本物に切り替わっています
            （actual_jwt_username: <code>{{ user.actual_jwt_username }}</code>）
          </div>
        </div>
        <button id="exit-switch-btn" @click="exitSwitch" class="exit-switch-btn">元に戻す</button>
      </div>

      <!-- ── ユーザーアバター ────────────────────────────────────── -->
      <div class="user-avatar" :class="{ 'avatar-switched': user.is_switched }">
        <span>{{ userInitials }}</span>
      </div>

      <h1 class="welcome-title">
        Welcome, <span class="gradient-text">{{ user.preferred_username || 'User' }}</span>!
      </h1>

      <!-- ── ロールバッジ ────────────────────────────────────────── -->
      <div class="role-badges" v-if="user.roles && user.roles.length > 0">
        <span
          v-for="role in displayRoles"
          :key="role"
          class="role-badge"
          :class="getRoleBadgeClass(role)"
        >{{ role }}</span>
      </div>

      <p class="status-msg">
        OIDCによる認証に成功しました。ブラウザとServerの間ではセッションCookie（SESSION）のみがやり取りされています。
      </p>

      <!-- ── ユーザー詳細 ────────────────────────────────────────── -->
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
        <!-- スイッチ中: 実際のJWT情報も表示 -->
        <template v-if="user.is_switched">
          <div class="detail-row highlight-row">
            <span class="label">実際のJWT sub</span>
            <span class="value code-val accent">{{ user.actual_jwt_sub }}</span>
          </div>
          <div class="detail-row highlight-row">
            <span class="label">実際のJWT username</span>
            <span class="value accent">{{ user.actual_jwt_username }}</span>
          </div>
        </template>
      </div>

      <!-- ── アクションボタン ────────────────────────────────────── -->
      <div class="action-buttons">
        <button id="check-token-btn" @click="checkAccessToken" class="outline-btn check-btn">
          <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="2" stroke="currentColor" class="btn-icon">
            <path stroke-linecap="round" stroke-linejoin="round" d="M12 11c0 1.657-1.343 3-3 3s-3-1.343-3-3 1.343-3 3-3 3 1.343 3 3zM21 21v-2a4 4 0 00-4-4H7a4 4 0 00-4 4v2" />
          </svg>
          API呼び出し（トークン確認）
        </button>

        <button id="logout-btn" @click="logout" class="outline-btn logout-btn">
          <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="2" stroke="currentColor" class="btn-icon">
            <path stroke-linecap="round" stroke-linejoin="round" d="M15.75 9V5.25A2.25 2.25 0 0 0 13.5 3h-6a2.25 2.25 0 0 0-2.25 2.25v13.5A2.25 2.25 0 0 0 7.5 21h6a2.25 2.25 0 0 0 2.25-2.25V15m3 0 3-3m0 0-3-3m3 3H9" />
          </svg>
          ログアウト
        </button>
      </div>

      <div v-if="tokenCheckMessage" class="token-check-result" style="margin-top:1rem;text-align:center;">
        <p>{{ tokenCheckMessage }}</p>
      </div>

      <!-- ── スイッチユーザーパネル (ROLE_ADMIN のみ表示) ──────── -->
      <div v-if="isAdmin && !user.is_switched" class="switch-panel">
        <div class="switch-panel-header">
          <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="switch-icon">
            <path stroke-linecap="round" stroke-linejoin="round" d="M7.5 21 3 16.5m0 0L7.5 12M3 16.5h13.5m0-13.5L21 7.5m0 0L16.5 3M21 7.5H7.5" />
          </svg>
          <h3>スイッチユーザー</h3>
        </div>
        <p class="switch-panel-desc">
          管理者として別ユーザーに切り替えます。2つのアプローチを比較できます。
        </p>

        <div class="switch-input-row">
          <input
            id="switch-username-input"
            v-model="switchTarget"
            type="text"
            placeholder="切替先ユーザー名 (例: demo)"
            class="switch-input"
          />
        </div>

        <div class="switch-buttons">
          <!-- Approach A: セッションスイッチ -->
          <button id="switch-session-btn" @click="switchUser('session')" class="switch-btn approach-a" :disabled="!switchTarget.trim() || switching">
            <div class="switch-btn-label">Approach A</div>
            <div class="switch-btn-name">セッション方式</div>
            <div class="switch-btn-desc">JWTはadminのまま</div>
          </button>

          <!-- Approach B: Token Exchange (Phase B 実装後に有効化) -->
          <button id="switch-exchange-btn" @click="switchUser('exchange')" class="switch-btn approach-b" :disabled="!switchTarget.trim() || switching || !tokenExchangeEnabled">
            <div class="switch-btn-label">Approach B</div>
            <div class="switch-btn-name">Token Exchange</div>
            <div class="switch-btn-desc">JWTが切替先の本物</div>
            <div v-if="!tokenExchangeEnabled" class="switch-btn-badge">Phase B 未実装</div>
          </button>
        </div>

        <div v-if="switchError" class="switch-error">{{ switchError }}</div>
      </div>

    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue';
import api from '../api/axios';

const loading = ref(true);
const error = ref(null);
const switching = ref(false);
const switchError = ref('');
const switchTarget = ref('demo');
const tokenExchangeEnabled = ref(true); // Phase B 実装後に true にする

const user = ref({
  sub: '',
  preferred_username: '',
  email: '',
  roles: [],
  is_switched: false,
  switched_from: null,
  switch_method: null,
  actual_jwt_sub: null,
  actual_jwt_username: null,
});

const userInitials = computed(() => {
  const name = user.value.preferred_username || 'U';
  return name.substring(0, 2).toUpperCase();
});

const isAdmin = computed(() =>
  (user.value.roles || []).includes('ROLE_ADMIN')
);

/** ROLE_ADMIN / ROLE_USER など表示用ロールのみ */
const displayRoles = computed(() =>
  (user.value.roles || []).filter(r =>
    r === 'ROLE_ADMIN' || r === 'ROLE_USER' || r === 'ROLE_PREVIOUS_ADMINISTRATOR'
  )
);

const getRoleBadgeClass = (role) => {
  if (role === 'ROLE_ADMIN') return 'badge-admin';
  if (role === 'ROLE_PREVIOUS_ADMINISTRATOR') return 'badge-prev-admin';
  return 'badge-user';
};

// ─── データ取得 ────────────────────────────────────────────────

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

// ─── ユーザー操作 ──────────────────────────────────────────────

const logout = () => {
  window.location.href = 'http://localhost:8080/api/v2/logout';
};

const retry = () => {
  fetchUser();
};

/**
 * スイッチユーザー実行。
 * POST /login/impersonate?username={target}&method={session|exchange}
 * フォーム認証時代の SwitchUserFilter 互換 URL を使用。
 */
const switchUser = async (method) => {
  if (!switchTarget.value.trim()) return;
  switching.value = true;
  switchError.value = '';
  try {
    const url = `http://localhost:8080/api/v2/login/impersonate?username=${encodeURIComponent(switchTarget.value.trim())}&method=${method}`;
    // redirect: 'manual' でリダイレクトを追わず、セッション更新のみ待つ
    await fetch(url, {
      method: 'POST',
      credentials: 'include',
      redirect: 'manual',
    });
    // セッション属性が更新されたので /user を再取得
    await fetchUser();
  } catch (e) {
    console.error('Switch user error:', e);
    switchError.value = 'ユーザー切替に失敗しました。';
  } finally {
    switching.value = false;
  }
};

/**
 * スイッチユーザー解除。
 * POST /logout/impersonate (SwitchUserFilter 互換)
 */
const exitSwitch = async () => {
  try {
    await fetch('http://localhost:8080/api/v2/logout/impersonate', {
      method: 'POST',
      credentials: 'include',
      redirect: 'manual',
    });
    await fetchUser();
  } catch (e) {
    console.error('Exit switch error:', e);
  }
};

// ─── トークン確認 ───────────────────────────────────────────────

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
    const expJst = data.expJst || null;
    const switchInfo = data.is_switched
      ? ` [切替中: ${data.switch_method} 方式, 実JWT=${data.actual_jwt_username}]`
      : '';
    if (expJst) {
      tokenCheckMessage.value = `アクセストークンは有効です。期限: ${expJst}${switchInfo}`;
    } else {
      tokenCheckMessage.value = `アクセストークンは有効です（期限情報なし）${switchInfo}`;
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
  max-width: 640px;
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
  to { transform: rotate(360deg); }
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
  transition: all 0.3s ease;
}

.user-avatar.avatar-switched {
  background: linear-gradient(135deg, #f59e0b 0%, #ef4444 100%);
  box-shadow: 0 8px 24px rgba(245, 158, 11, 0.35);
}

.welcome-title {
  font-size: 2.2rem;
  font-weight: 700;
  margin: 0 0 0.5rem 0;
  letter-spacing: -0.5px;
}

/* Role Badges */
.role-badges {
  display: flex;
  justify-content: center;
  gap: 8px;
  margin-bottom: 1rem;
  flex-wrap: wrap;
}

.role-badge {
  padding: 0.25rem 0.75rem;
  border-radius: 20px;
  font-size: 0.75rem;
  font-weight: 700;
  letter-spacing: 0.5px;
  text-transform: uppercase;
}

.badge-admin {
  background: linear-gradient(135deg, rgba(99,102,241,0.2), rgba(168,85,247,0.2));
  border: 1px solid rgba(99, 102, 241, 0.5);
  color: #a78bfa;
}

.badge-user {
  background: rgba(16, 185, 129, 0.1);
  border: 1px solid rgba(16, 185, 129, 0.4);
  color: #34d399;
}

.badge-prev-admin {
  background: rgba(245, 158, 11, 0.1);
  border: 1px solid rgba(245, 158, 11, 0.4);
  color: #fbbf24;
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
  margin-bottom: 2rem;
  text-align: left;
}

.detail-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0.8rem 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.05);
  gap: 1rem;
}

.detail-row:last-child {
  border-bottom: none;
  padding-bottom: 0;
}

.detail-row:first-child {
  padding-top: 0;
}

.highlight-row {
  background: rgba(245, 158, 11, 0.04);
  margin: 0 -1.5rem;
  padding-left: 1.5rem;
  padding-right: 1.5rem;
}

.label {
  font-size: 0.85rem;
  color: #64748b;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  white-space: nowrap;
}

.value {
  font-size: 0.95rem;
  color: #cbd5e1;
  font-weight: 500;
  word-break: break-all;
  text-align: right;
}

.value.accent {
  color: #fbbf24;
}

.code-val {
  font-family: monospace;
  background: rgba(255, 255, 255, 0.05);
  padding: 0.2rem 0.5rem;
  border-radius: 6px;
  font-size: 0.8rem;
  word-break: break-all;
}

.code-val.accent {
  background: rgba(245, 158, 11, 0.1);
  color: #fbbf24;
}

/* Action Buttons */
.action-buttons {
  display: flex;
  justify-content: center;
  gap: 0.75rem;
  margin-bottom: 1.5rem;
  flex-wrap: wrap;
}

.check-btn,
.logout-btn {
  flex: 1;
  min-width: 180px;
}

.btn-icon {
  width: 20px;
  height: 20px;
}

/* Switch Banner */
.switch-banner {
  display: flex;
  align-items: center;
  gap: 1rem;
  padding: 1rem 1.25rem;
  border-radius: 12px;
  margin-bottom: 1.5rem;
  animation: slideDown 0.3s ease;
}

@keyframes slideDown {
  from { opacity: 0; transform: translateY(-10px); }
  to { opacity: 1; transform: translateY(0); }
}

.switch-banner.session {
  background: rgba(245, 158, 11, 0.08);
  border: 1px solid rgba(245, 158, 11, 0.3);
}

.switch-banner.exchange {
  background: rgba(16, 185, 129, 0.08);
  border: 1px solid rgba(16, 185, 129, 0.3);
}

.switch-banner-icon {
  font-size: 1.4rem;
  flex-shrink: 0;
}

.switch-banner-body {
  flex: 1;
  text-align: left;
}

.switch-banner-title {
  font-size: 0.95rem;
  color: #e2e8f0;
  margin-bottom: 0.25rem;
}

.switch-banner-desc {
  font-size: 0.78rem;
  color: #94a3b8;
  line-height: 1.5;
}

.switch-banner-desc code {
  background: rgba(255, 255, 255, 0.08);
  padding: 0.1rem 0.3rem;
  border-radius: 4px;
  font-size: 0.75rem;
  color: #fbbf24;
}

.exit-switch-btn {
  padding: 0.4rem 0.9rem;
  border: 1px solid rgba(255, 255, 255, 0.2);
  background: rgba(255, 255, 255, 0.05);
  color: #e2e8f0;
  border-radius: 8px;
  font-size: 0.8rem;
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.2s ease;
  flex-shrink: 0;
}

.exit-switch-btn:hover {
  background: rgba(255, 255, 255, 0.1);
  border-color: rgba(255, 255, 255, 0.3);
}

/* Switch Panel */
.switch-panel {
  margin-top: 2rem;
  padding: 1.5rem;
  background: rgba(99, 102, 241, 0.04);
  border: 1px solid rgba(99, 102, 241, 0.15);
  border-radius: 16px;
  text-align: left;
  animation: fadeIn 0.3s ease;
}

@keyframes fadeIn {
  from { opacity: 0; }
  to { opacity: 1; }
}

.switch-panel-header {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  margin-bottom: 0.5rem;
}

.switch-panel-header h3 {
  font-size: 1rem;
  font-weight: 700;
  color: #e2e8f0;
  margin: 0;
}

.switch-icon {
  width: 20px;
  height: 20px;
  color: #818cf8;
}

.switch-panel-desc {
  font-size: 0.85rem;
  color: #64748b;
  margin-bottom: 1.25rem;
  line-height: 1.5;
}

.switch-input-row {
  margin-bottom: 1rem;
}

.switch-input {
  width: 100%;
  padding: 0.65rem 0.9rem;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 10px;
  color: #e2e8f0;
  font-size: 0.9rem;
  outline: none;
  box-sizing: border-box;
  transition: border-color 0.2s ease;
}

.switch-input:focus {
  border-color: rgba(99, 102, 241, 0.5);
}

.switch-input::placeholder {
  color: #475569;
}

.switch-buttons {
  display: flex;
  gap: 0.75rem;
}

.switch-btn {
  flex: 1;
  padding: 0.75rem 1rem;
  border-radius: 10px;
  border: 1px solid;
  cursor: pointer;
  text-align: center;
  transition: all 0.2s ease;
  position: relative;
  overflow: hidden;
}

.switch-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.switch-btn.approach-a {
  background: rgba(245, 158, 11, 0.06);
  border-color: rgba(245, 158, 11, 0.3);
  color: #fbbf24;
}

.switch-btn.approach-a:not(:disabled):hover {
  background: rgba(245, 158, 11, 0.12);
  border-color: rgba(245, 158, 11, 0.5);
}

.switch-btn.approach-b {
  background: rgba(16, 185, 129, 0.06);
  border-color: rgba(16, 185, 129, 0.3);
  color: #34d399;
}

.switch-btn.approach-b:not(:disabled):hover {
  background: rgba(16, 185, 129, 0.12);
  border-color: rgba(16, 185, 129, 0.5);
}

.switch-btn-label {
  font-size: 0.7rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  opacity: 0.7;
  margin-bottom: 0.2rem;
}

.switch-btn-name {
  font-size: 0.9rem;
  font-weight: 700;
  margin-bottom: 0.15rem;
}

.switch-btn-desc {
  font-size: 0.72rem;
  opacity: 0.7;
}

.switch-btn-badge {
  font-size: 0.65rem;
  background: rgba(255,255,255,0.1);
  border-radius: 4px;
  padding: 0.1rem 0.35rem;
  margin-top: 0.3rem;
  display: inline-block;
}

.switch-error {
  margin-top: 0.75rem;
  font-size: 0.85rem;
  color: #f87171;
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
