const api = {
    async getItems(type, page, perPage, status = '') {
        const url = new URL(`/api/${type}`, window.location.origin);
        url.searchParams.append('page', page);
        url.searchParams.append('perPage', perPage);
        if (status) {
            url.searchParams.append('status', status);
        }
        const response = await fetch(url);
        if (!response.ok) {
            throw new Error('Failed to fetch items');
        }
        return response.json();
    },

    async deleteItem(type, id) {
        const response = await fetch(`/api/${type}/${id}`, { method: 'DELETE' });
        if (!response.ok) {
            throw new Error('Failed to delete item');
        }
    },

    async sendReview(id, approved, reason) {
        const response = await fetch(`/api/templates/review`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ templateId: id, approved, reason })
        });
        if (!response.ok) {
            throw new Error('Failed to send review');
        }
    }
};