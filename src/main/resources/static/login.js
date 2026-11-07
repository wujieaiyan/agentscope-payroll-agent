'use strict';

const form = document.getElementById('login-form');
const errorBox = document.getElementById('login-error');
const submit = document.getElementById('login-submit');

function showError(message) {
  errorBox.textContent = message;
  errorBox.classList.toggle('hidden', !message);
}

async function parse(response) {
  const text = await response.text();
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch (parseError) {
    return { status: 'ERROR', message: text };
  }
}

async function alreadyLoggedIn() {
  try {
    const response = await fetch('/api/auth/me');
    if (!response.ok) return false;
    const payload = await parse(response);
    return Boolean(payload && payload.data && payload.data.user);
  } catch (error) {
    return false;
  }
}

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  showError('');
  submit.disabled = true;
  try {
    const response = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        username: form.elements.username.value.trim(),
        password: form.elements.password.value,
      }),
    });
    const payload = await parse(response);
    if (!response.ok || (payload && payload.status === 'ERROR')) {
      throw new Error((payload && payload.message) || `登录失败：HTTP ${response.status}`);
    }
    window.location.href = '/';
  } catch (error) {
    showError(error.message || '登录失败');
    submit.disabled = false;
    form.elements.password.select();
  }
});

alreadyLoggedIn().then((loggedIn) => {
  if (loggedIn) window.location.href = '/';
});
