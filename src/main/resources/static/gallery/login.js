function loginApp() {
    return {
        username: '',
        password: '',
        errorMessage: '',
        darkMode: false,

        init() {
            this.checkForLoginError();
            this.darkMode = localStorage.getItem('darkMode') === 'true';
            this.applyDarkMode();
        },

        checkForLoginError() {
            const urlParams = new URLSearchParams(window.location.search);
            if (urlParams.get('error')) {
                this.errorMessage = 'Login failed. Please try again.';
            }
        },

        async submitLogin() {
            try {
                const formData = new URLSearchParams();
                formData.append('username', this.username);
                formData.append('password', this.password);

                const response = await fetch('/api/login', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/x-www-form-urlencoded',
                    },
                    body: formData
                });

                if (response.ok) {
                    // Redirect to the gallery page or dashboard
                    window.location.href = '/gallery/gallery.html';
                } else {
                    this.errorMessage = 'Invalid username or password';
                }
            } catch (error) {
                console.error('Login error:', error);
                this.errorMessage = 'An error occurred. Please try again.';
            }
        },

        toggleDarkMode() {
            this.darkMode = !this.darkMode;
            localStorage.setItem('darkMode', this.darkMode);
            this.applyDarkMode();
        },

        applyDarkMode() {
            document.documentElement.setAttribute('data-bs-theme', this.darkMode ? 'dark' : 'light');
        }
    };
}