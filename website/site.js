const themeButton = document.querySelector('.theme-toggle');
const systemTheme = window.matchMedia('(prefers-color-scheme: dark)');

function currentTheme() {
  return document.documentElement.dataset.theme || (systemTheme.matches ? 'dark' : 'light');
}

function updateThemeButton() {
  const next = currentTheme() === 'dark' ? 'light' : 'dark';
  themeButton.textContent = `${next === 'dark' ? 'Dark' : 'Light'} mode`;
  themeButton.setAttribute('aria-label', `Switch to ${next} mode`);
}

themeButton.hidden = false;
updateThemeButton();
systemTheme.addEventListener('change', updateThemeButton);
themeButton.addEventListener('click', () => {
  const next = currentTheme() === 'dark' ? 'light' : 'dark';
  document.documentElement.dataset.theme = next;
  try { localStorage.setItem('whp-theme', next); } catch {}
  updateThemeButton();
});
