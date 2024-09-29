function initDarkModeToggle() {
    const toggle = document.getElementById('darkModeToggle');

    function setDarkMode(isDark) {
        document.documentElement.setAttribute('data-bs-theme', isDark ? 'dark' : 'light');
        toggle.innerHTML = isDark ? '<i class="fas fa-sun"></i>' : '<i class="fas fa-moon"></i>';
        localStorage.setItem('darkMode', isDark);
    }

    toggle.addEventListener('click', () => {
        const isDark = document.documentElement.getAttribute('data-bs-theme') === 'dark';
        setDarkMode(!isDark);
    });

    // Check localStorage on load
    const savedDarkMode = localStorage.getItem('darkMode');
    if (savedDarkMode !== null) {
        setDarkMode(savedDarkMode === 'true');
    }
}

document.addEventListener('DOMContentLoaded', initDarkModeToggle);
