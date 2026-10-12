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

// Resolve versioned filenames without delaying a click. The HTML link remains
// a working direct download if JavaScript or GitHub's API is unavailable.
async function updateDownloadLink() {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 6000);
  try {
    const response = await fetch('https://api.github.com/repos/ashmerra/wehavephones/releases/latest', {
      signal: controller.signal,
    });
    if (!response.ok) return;
    const release = await response.json();
    const apk = release.assets?.find(asset => /^WeHavePhones-[0-9A-Za-z][0-9A-Za-z._+-]*\.apk$/.test(asset.name));
    if (apk?.browser_download_url) {
      document.querySelector('[data-apk-download]').href = apk.browser_download_url;
    }
  } catch {
    // Keep the direct download from the HTML on network errors or rate limits.
  } finally {
    clearTimeout(timeout);
  }
}

updateDownloadLink();
