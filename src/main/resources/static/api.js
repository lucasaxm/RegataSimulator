const webSecurity = {
    async refresh() {
        const response = await fetch('/api/csrf', { credentials: 'same-origin', cache: 'no-store' });
        if (!response.ok) throw new Error('Não foi possível obter o token de segurança.');
        return response.json();
    },

    async request(url, options = {}) {
        const headers = new Headers(options.headers || {});
        headers.set('X-Requested-With', 'XMLHttpRequest');
        const method = (options.method || 'GET').toUpperCase();
        if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
            const token = await this.refresh();
            headers.set(token.headerName, token.token);
        }
        const response = await fetch(url, { ...options, headers, credentials: 'same-origin' });
        // Do not replay mutations automatically after authentication/CSRF failures.
        if (response.status === 401 && url !== '/api/login') window.location.href = '/login.html';
        return response;
    },

    async logout() {
        const response = await this.request('/api/logout', { method: 'POST' });
        if (!response.ok) throw new Error('Não foi possível sair.');
        await this.refresh();
        window.location.href = '/login.html';
    }
};

const api = {
    async searchSourcesPOST(criteria) {
        const response = await webSecurity.request('/api/sources/search', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(criteria)
        });
        if (!response.ok) {
            throw new Error('Failed to perform search');
        }
        return response.json();
    },

    async getItems(type, page, perPage, status = '') {
        const url = new URL(`/api/${type}`, window.location.origin);
        url.searchParams.append('page', page);
        url.searchParams.append('perPage', perPage);
        if (status) {
            url.searchParams.append('status', status);
        }
        const response = await webSecurity.request(url);
        if (!response.ok) {
            throw new Error('Failed to fetch items');
        }
        return response.json();
    },

    async deleteItem(type, id) {
        const response = await webSecurity.request(`/api/${type}/${id}`, { method: 'DELETE' });
        if (!response.ok) {
            throw new Error('Failed to delete item');
        }
    },

    async sendReviewTemplate(id, approved, reason) {
        const response = await webSecurity.request(`/api/templates/review`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ templateId: id, approved, reason })
        });
        if (!response.ok) {
            throw new Error('Failed to send review');
        }
    },

    async sendReviewSource(id, approved, reason) {
        const response = await webSecurity.request(`/api/sources/review`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ sourceId: id, approved, reason })
        });
        if (!response.ok) {
            throw new Error('Failed to send source review');
        }
    }

};