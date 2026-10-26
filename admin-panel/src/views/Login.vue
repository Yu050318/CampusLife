<template>
  <div class="login-container">
    <el-card class="login-card">
      <div class="login-brand">
        <el-icon class="brand-icon"><Shop /></el-icon>
        <h2>校邻 · 管理后台</h2>
        <p>CAMPUSLIFE / 校园生活运营</p><p class="project-note">个人学习项目，非华南农业大学官方平台</p>
      </div>
      <el-form :model="form" :rules="rules" ref="formRef">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="用户名" prefix-icon="User" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input v-model="form.password" type="password" placeholder="密码" prefix-icon="Lock" show-password />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" style="width:100%" :loading="loading" @click="handleLogin">登 录</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script>
import request from '../utils/request'

export default {
  data() {
    return {
      form: { username: 'admin', password: '' },
      rules: {
        username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
        password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
      },
      loading: false
    }
  },
  methods: {
    async handleLogin() {
      const valid = await this.$refs.formRef.validate().catch(() => false)
      if (!valid) return
      this.loading = true
      try {
        const res = await request.post('/admin/login', this.form)
        if (res.success !== false) {
          localStorage.setItem('admin_token', res.data || res)
          localStorage.setItem('admin_user', this.form.username)
          this.$router.push('/dashboard')
        }
      } catch (e) { } finally { this.loading = false }
    }
  }
}
</script>

<style scoped>
.login-container {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: radial-gradient(ellipse at top left, #dce8d5 0%, #edf3e8 45%, #f6f7f2 100%);
}
.login-card {
  width: min(400px, calc(100vw - 40px));
  border-radius: 12px;
  box-shadow: 0 10px 40px rgba(24, 62, 48, 0.10);
  border: none;
}
.login-brand { text-align: center; margin-bottom: 28px; }
.login-brand .brand-icon { font-size: 40px; color: #216b50; }
.login-brand h2 { margin: 10px 0 4px; font-size: 20px; color: #1f2d3d; }
.login-brand p { margin: 0; font-size: 12px; color: #a0a4ac; letter-spacing: 1px; }
.login-brand .project-note { margin-top: 16px; font-size: 11px; letter-spacing: 0; line-height: 1.7; }
</style>
