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

        backgroundCanvas: null,
        foregroundCanvas: null,
        bgCtx: null,
        fgCtx: null,
        imageObj: null,

        init() {
            this.loadSources(this.sourceCurrentPage);
            this.darkMode = localStorage.getItem('darkMode') === 'true';
            this.applyDarkMode();
        },

        async loadSources(page) {
            this.sourceCurrentPage = page;
            const response = await fetch(`/api/sources?page=${page}&perPage=${this.ITEMS_PER_PAGE}&status=APPROVED`);
            const data = await response.json();
            this.sourceItems = data.items;
            this.sourceTotalItems = data.totalItems;
            this.sourceTotalPages = Math.ceil(this.sourceTotalItems / this.ITEMS_PER_PAGE);
        },

        async loadTemplates(page) {
            this.templateCurrentPage = page;
            const response = await fetch(`/api/templates?page=${page}&perPage=${this.ITEMS_PER_PAGE}&status=APPROVED`);
            const data = await response.json();
            this.templateItems = data.items;
            this.templateTotalItems = data.totalItems;
            this.templateTotalPages = Math.ceil(this.templateTotalItems / this.ITEMS_PER_PAGE);
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

        async openSourceModal(item) {
            this.selectedSource = item;
            this.modalImage = `/api/sources/${item.id}.png`;
            const modal = new bootstrap.Modal(this.$refs.imageModal);
            modal.show();
        },

        async openTemplateModal(item) {
            this.selectedTemplate = item;
            this.modalImage = `/api/templates/${item.id}.png`;
            const modal = new bootstrap.Modal(this.$refs.imageModal);
            modal.show();
            this.$nextTick(() => {
                this.setupCanvas();
                this.$refs.imageModal.addEventListener('shown.bs.modal', this.onModalShown);
            });
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
            this.applyDarkMode();
        },

        applyDarkMode() {
            document.documentElement.setAttribute('data-bs-theme', this.darkMode ? 'dark' : 'light');
        },

        async loadTemplateReview(page) {
            this.templateReviewCurrentPage = page;
            const response = await fetch(`/api/templates?page=${page}&perPage=${this.ITEMS_PER_PAGE}&status=REVIEW`);
            const data = await response.json();
            this.templateReviewItems = data.items;
            this.templateReviewTotalItems = data.totalItems;
            this.templateReviewTotalPages = Math.ceil(this.templateReviewTotalItems / this.ITEMS_PER_PAGE);
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
                templateId: this.selectedTemplate.id,
                approved: this.reviewApproved,
                reason: this.reviewRefused ? this.reviewReason : null
            };

            const response = await fetch(`/api/templates/review`, {
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
        },

        setupCanvas() {
            this.backgroundCanvas = document.getElementById('backgroundCanvas');
            this.foregroundCanvas = document.getElementById('foregroundCanvas');
            this.bgCtx = this.backgroundCanvas.getContext('2d');
            this.fgCtx = this.foregroundCanvas.getContext('2d');
            this.imageObj = document.getElementById('modalImage');

            if (this.imageObj.complete) {
                this.resizeCanvases();
                this.drawAreas();
            } else {
                this.imageObj.onload = () => {
                    this.resizeCanvases();
                    this.drawAreas();
                };
            }

            window.addEventListener('resize', () => {
                this.resizeCanvases();
                this.drawAreas();
            });

            this.foregroundCanvas.addEventListener('mousemove', (event) => this.handleHover(event));
        },

        resizeCanvases() {
            const rect = this.imageObj.getBoundingClientRect();
            [this.backgroundCanvas, this.foregroundCanvas].forEach(canvas => {
                canvas.width = rect.width;
                canvas.height = rect.height;
                canvas.style.width = `${rect.width}px`;
                canvas.style.height = `${rect.height}px`;
            });
        },

        drawAreas() {
            this.bgCtx.clearRect(0, 0, this.backgroundCanvas.width, this.backgroundCanvas.height);
            this.fgCtx.clearRect(0, 0, this.foregroundCanvas.width, this.foregroundCanvas.height);

            if (this.selectedTemplate && this.selectedTemplate.areas) {
                const scaleX = this.foregroundCanvas.width / this.imageObj.naturalWidth;
                const scaleY = this.foregroundCanvas.height / this.imageObj.naturalHeight;

                this.selectedTemplate.areas.forEach(area => {
                    this.drawArea(area, false, scaleX, scaleY);
                });
            }
        },

        drawArea(area, highlight = false, scaleX = 1, scaleY = 1) {
            const ctx = area.background ? this.bgCtx : this.fgCtx;

            ctx.beginPath();
            ctx.moveTo(area.topLeft.x * scaleX, area.topLeft.y * scaleY);
            ctx.lineTo(area.topRight.x * scaleX, area.topRight.y * scaleY);
            ctx.lineTo(area.bottomRight.x * scaleX, area.bottomRight.y * scaleY);
            ctx.lineTo(area.bottomLeft.x * scaleX, area.bottomLeft.y * scaleY);
            ctx.closePath();

            ctx.strokeStyle = highlight ? 'yellow' : 'red';
            ctx.lineWidth = 2;
            ctx.stroke();

            ctx.fillStyle = highlight ? 'rgba(255, 255, 0, 0.2)' : 'rgba(255, 0, 0, 0.1)';
            ctx.fill();
        },

        handleHover(event) {
            const rect = this.foregroundCanvas.getBoundingClientRect();
            const x = event.clientX - rect.left;
            const y = event.clientY - rect.top;

            const scaleX = this.imageObj.naturalWidth / this.foregroundCanvas.width;
            const scaleY = this.imageObj.naturalHeight / this.foregroundCanvas.height;

            let hoveredArea = null;
            if (this.selectedTemplate && this.selectedTemplate.areas) {
                hoveredArea = this.selectedTemplate.areas.find(area =>
                    this.isPointInArea(x * scaleX, y * scaleY, area)
                );
            }

            this.drawAreas();
            if (hoveredArea) {
                this.drawArea(hoveredArea, true, this.foregroundCanvas.width / this.imageObj.naturalWidth, this.foregroundCanvas.height / this.imageObj.naturalHeight);
            }
        },

        isPointInArea(x, y, area) {
            const points = [
                area.topLeft, area.topRight, area.bottomRight, area.bottomLeft
            ];
            let inside = false;
            for (let i = 0, j = points.length - 1; i < points.length; j = i++) {
                const xi = points[i].x, yi = points[i].y;
                const xj = points[j].x, yj = points[j].y;
                const intersect = ((yi > y) !== (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi) + xi);
                if (intersect) inside = !inside;
            }
            return inside;
        },

        onModalShown() {
            this.resizeCanvases();
            this.drawAreas();
            // Remove the event listener to avoid multiple calls
            this.$refs.imageModal.removeEventListener('shown.bs.modal', this.onModalShown);
        }
    };
}
