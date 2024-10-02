function galleryApp() {
    return {
        currentTab: 'sources',
        sourceItems: [],
        templateItems: [],
        sourceCurrentPage: 1,
        templateCurrentPage: 1,
        sourceTotalItems: 0,
        templateTotalItems: 0,
        sourceTotalPages: 1,
        templateTotalPages: 1,
        selectedSource: null,
        selectedTemplate: null,
        modalImage: '',
        darkMode: false,
        ITEMS_PER_PAGE: 12,

        init() {
            this.loadSources(this.sourceCurrentPage);
            this.darkMode = localStorage.getItem('darkMode') === 'true';
            this.applyDarkMode();
        },

        async loadSources(page) {
            this.sourceCurrentPage = page;
            const response = await fetch(`/api/sources?page=${page}&perPage=${this.ITEMS_PER_PAGE}`);
            const data = await response.json();
            this.sourceItems = data.items;
            this.sourceTotalItems = data.totalItems;
            this.sourceTotalPages = Math.ceil(this.sourceTotalItems / this.ITEMS_PER_PAGE);
        },

        async loadTemplates(page) {
            this.templateCurrentPage = page;
            const response = await fetch(`/api/templates?page=${page}&perPage=${this.ITEMS_PER_PAGE}`);
            const data = await response.json();
            this.templateItems = data.items;
            this.templateTotalItems = data.totalItems;
            this.templateTotalPages = Math.ceil(this.templateTotalItems / this.ITEMS_PER_PAGE);
        },

        changeTab(tab) {
            this.currentTab = tab;
            if (tab === 'sources') {
                this.loadSources(this.sourceCurrentPage);
            } else {
                this.loadTemplates(this.templateCurrentPage);
            }
        },

        async openSourceModal(item) {
            this.selectedSource = item;
            this.modalImage = `/api/sources/${item.id}`;
            const modal = new bootstrap.Modal(this.$refs.imageModal);
            modal.show();
        },

        async openTemplateModal(item) {
            this.selectedTemplate = item;
            this.modalImage = `/api/templates/${item.id}`;
            const modal = new bootstrap.Modal(this.$refs.imageModal);
            modal.show();
        },

        async deleteImage() {
            const isSource = this.currentTab === 'sources';
            const id = isSource ? this.selectedSource.id : this.selectedTemplate.id;
            const confirmMessage = `Are you sure you want to delete this ${isSource ? 'source image' : 'template'}?`;

            if (confirm(confirmMessage)) {
                const endpoint = isSource ? `/api/sources/${id}` : `/api/templates/${id}`;
                const response = await fetch(endpoint, {method: 'DELETE'});

                if (response.ok) {
                    bootstrap.Modal.getInstance(this.$refs.imageModal).hide();
                    if (isSource) {
                        this.loadSources(this.sourceCurrentPage);
                    } else {
                        this.loadTemplates(this.templateCurrentPage);
                    }
                } else {
                    alert(`Failed to delete the ${isSource ? 'source image' : 'template'}. Please try again.`);
                }
            }
        },

        toggleDarkMode() {
            this.darkMode = !this.darkMode;
            localStorage.setItem('darkMode', this.darkMode);
            document.documentElement.setAttribute('data-bs-theme', this.darkMode ? 'dark' : 'light');
        },

        applyDarkMode() {
            document.documentElement.setAttribute('data-bs-theme', this.darkMode ? 'dark' : 'light');
        }
    };
}