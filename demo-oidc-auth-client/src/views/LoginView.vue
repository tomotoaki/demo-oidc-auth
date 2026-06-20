<template>
  <div class="glass-card fade-in">
    <div class="icon-container">
      <svg class="lock-icon" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor">
        <path stroke-linecap="round" stroke-linejoin="round" d="M16.5 10.5V6.75a4.5 4.5 0 1 0-9 0v3.75m-.75 11.25h10.5a2.25 2.25 0 0 0 2.25-2.25v-6.75a2.25 2.25 0 0 0-2.25-2.25H6.75a2.25 2.25 0 0 0-2.25 2.25v6.75a2.25 2.25 0 0 0 2.25 2.25Z" />
      </svg>
    </div>
    
    <h1 class="login-title">
      <span class="gradient-text">Secure OIDC Auth</span>
    </h1>
    
    <p class="description">
      BFF (Backend for Frontend) パターンを用いた、ブラウザ側にアクセス/リフレッシュトークンを保持しない、セッション管理ベースの安全な OpenID Connect 認証デモアプリケーションです。
    </p>

    <div class="tech-badges">
      <span class="badge">Vue 3</span>
      <span class="badge">Spring Boot 4.1</span>
      <span class="badge">Keycloak 26</span>
    </div>

    <button @click="login" class="gradient-btn login-btn">
      <span>Keycloak でログイン</span>
      <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="2" stroke="currentColor" class="arrow-icon">
        <path stroke-linecap="round" stroke-linejoin="round" d="M13.5 4.5 21 12m0 0-7.5 7.5M21 12H3" />
      </svg>
    </button>
  </div>
</template>

<script setup>
const login = () => {
  // Spring BootのOAuth2認可開始エンドポイントへ直接遷移
  window.location.href = 'http://localhost:8080/api/v2/oauth2/authorization/keycloak';
};
</script>

<style scoped>
.icon-container {
  display: inline-flex;
  justify-content: center;
  align-items: center;
  width: 72px;
  height: 72px;
  background: rgba(99, 102, 241, 0.1);
  border: 1px solid rgba(99, 102, 241, 0.25);
  border-radius: 20px;
  margin-bottom: 1.5rem;
}

.lock-icon {
  width: 36px;
  height: 36px;
  color: #818cf8;
}

.login-title {
  font-size: 2.2rem;
  font-weight: 700;
  margin: 0 0 1rem 0;
  letter-spacing: -0.5px;
}

.description {
  font-size: 0.95rem;
  line-height: 1.6;
  color: #94a3b8;
  margin-bottom: 2rem;
}

.tech-badges {
  display: flex;
  justify-content: center;
  gap: 10px;
  margin-bottom: 2.5rem;
}

.badge {
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.1);
  padding: 0.4rem 0.8rem;
  border-radius: 8px;
  font-size: 0.8rem;
  color: #cbd5e1;
  font-weight: 500;
}

.login-btn {
  width: 100%;
}

.arrow-icon {
  width: 18px;
  height: 18px;
  transition: transform 0.3s ease;
}

.gradient-btn:hover .arrow-icon {
  transform: translateX(4px);
}
</style>
