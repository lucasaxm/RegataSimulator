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

        templateReviewItems: [],
        templateReviewCurrentPage: 1,
        templateReviewTotalItems: 0,
        templateReviewTotalPages: 1,
        reviewApproved: false,
        reviewRefused: false,
        reviewReason: '',

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
        },

        async loadTemplateReview(page) {
            this.templateReviewCurrentPage = page;
            const response = await fetch(`/api/templates/review?page=${page}&perPage=${this.ITEMS_PER_PAGE}`);
            const data = await response.json();
            this.templateReviewItems = data.items;
            this.templateReviewTotalItems = data.totalItems;
            this.templateReviewTotalPages = Math.ceil(this.templateReviewTotalItems / this.ITEMS_PER_PAGE);
        },

        changeTab(tab) {
            this.currentTab = tab;
            if (tab === 'sources') {
                this.loadSources(this.sourceCurrentPage);
            } else if (tab === 'templates') {
                this.loadTemplates(this.templateCurrentPage);
            } else if (tab === 'templateReview') {
                this.loadTemplateReview(this.templateReviewCurrentPage);
            }
        },

        async openTemplateReviewModal(item) {
            this.selectedTemplate = item;
            this.modalImage = `/api/templates/review/${item.id}`;
            this.reviewApproved = false;
            this.reviewRefused = false;
            this.reviewReason = '';
            const modal = new bootstrap.Modal(this.$refs.imageModal);
            modal.show();
        },

        async sendReview() {
            if (!this.reviewApproved && !this.reviewRefused) {
                alert('Please select either Approve or Refuse.');
                return;
            }

            if (this.reviewRefused && !this.reviewReason) {
                alert('Please provide a reason for refusing the template.');
                return;
            }

            const reviewData = {
                approved: this.reviewApproved,
                reason: this.reviewRefused ? this.reviewReason : null
            };

            const response = await fetch(`/api/templates/review/${this.selectedTemplate.id}`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify(reviewData)
            });

            if (response.ok) {
                bootstrap.Modal.getInstance(this.$refs.imageModal).hide();
                this.loadTemplateReview(this.templateReviewCurrentPage);
            } else {
                alert('Failed to submit the review. Please try again.');
            }
        }
    };
}