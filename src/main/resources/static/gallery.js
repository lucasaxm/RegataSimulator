function galleryApp() {
    return {
        currentTab: 'sources',
        sourcesItems: [],
        templatesItems: [],
        currentPage: 1,
        hasMore: true,
        loading: false,
        error: null,
        selectedItem: null,
        modalImage: '',
        darkMode: false,
        ITEMS_PER_PAGE: 12,
        reviewApproved: false,
        reviewRefused: false,
        reviewReason: '',
        itemStatus: '',

        init() {
            this.loadItems('sources');
            this.darkMode = localStorage.getItem('darkMode') === 'true';
            this.applyDarkMode();
        },

        async loadItems(type, page = 1) {
            this.loading = true;
            this.error = null;
            try {
                const data = await api.getItems(type, page, this.ITEMS_PER_PAGE, this.itemStatus);
                if (page === 1) {
                    this[`${type}Items`] = data.items;
                } else {
                    this[`${type}Items`] = [...this[`${type}Items`], ...data.items];
                }
                this.hasMore = data.items.length === this.ITEMS_PER_PAGE;
                this.currentPage = page;
            } catch (err) {
                this.error = 'Failed to load items. Please try again.';
                console.error(err);
            } finally {
                this.loading = false;
            }
        },

        changeTab(tab) {
            this.currentTab = tab;
            this.currentPage = 1;
            this.loadItems(tab);
        },

        async openModal(item) {
            this.selectedItem = item;
            this.modalImage = `/api/${this.currentTab}/${item.id}.png`;
            const modal = new bootstrap.Modal(this.$refs.imageModal);
            modal.show();
            this.$nextTick(() => {
                this.setupCanvas();
                this.$refs.imageModal.addEventListener('shown.bs.modal', () => this.onModalShown());
            });
        },


        async deleteImage() {
            if (confirm(`Are you sure you want to delete this ${this.currentTab === 'sources' ? 'source image' : 'template'}?`)) {
                try {
                    await api.deleteItem(this.currentTab, this.selectedItem.id);
                    bootstrap.Modal.getInstance(this.$refs.imageModal).hide();
                    this.loadItems(this.currentTab);
                } catch (err) {
                    alert(`Failed to delete the item. Please try again.`);
                    console.error(err);
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

        async sendReview() {
            if (!this.reviewApproved && !this.reviewRefused) {
                alert('Please select either Approve or Refuse.');
                return;
            }

            if (this.reviewRefused && !this.reviewReason) {
                alert('Please provide a reason for refusing.');
                return;
            }

            try {
                if (this.currentTab === 'templates') {
                    await api.sendReviewTemplate(this.selectedItem.id, this.reviewApproved, this.reviewReason);
                    bootstrap.Modal.getInstance(this.$refs.imageModal).hide();
                    this.loadItems('templates');
                } else if (this.currentTab === 'sources') {
                    await api.sendReviewSource(this.selectedItem.id, this.reviewApproved, this.reviewReason);
                    bootstrap.Modal.getInstance(this.$refs.imageModal).hide();
                    this.loadItems('sources');
                }
            } catch (err) {
                alert('Failed to submit the review. Please try again.');
                console.error(err);
            }
        },

        setupCanvas() {
            this.$nextTick(() => {
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
            });
        },


        resizeCanvases() {
            const rect = this.imageObj.getBoundingClientRect();
            [this.backgroundCanvas, this.foregroundCanvas].forEach(canvas => {
                canvas.width = this.imageObj.naturalWidth;
                canvas.height = this.imageObj.naturalHeight;
                canvas.style.width = `${rect.width}px`;
                canvas.style.height = `${rect.height}px`;
            });
        },

        drawAreas() {
            this.bgCtx.clearRect(0, 0, this.backgroundCanvas.width, this.backgroundCanvas.height);
            this.fgCtx.clearRect(0, 0, this.foregroundCanvas.width, this.foregroundCanvas.height);

            if (this.selectedItem && this.selectedItem.areas) {
                this.selectedItem.areas.forEach(area => {
                    this.drawArea(area, false);
                });
            }
        },

        drawArea(area, highlight = false) {
            const ctx = area.background ? this.bgCtx : this.fgCtx;
            const scaleX = this.backgroundCanvas.width / this.imageObj.naturalWidth;
            const scaleY = this.backgroundCanvas.height / this.imageObj.naturalHeight;

            ctx.beginPath();
            ctx.moveTo(area.topLeft.x * scaleX, area.topLeft.y * scaleY);
            ctx.lineTo(area.topRight.x * scaleX, area.topRight.y * scaleY);
            ctx.lineTo(area.bottomRight.x * scaleX, area.bottomRight.y * scaleY);
            ctx.lineTo(area.bottomLeft.x * scaleX, area.bottomLeft.y * scaleY);
            ctx.closePath();

            ctx.strokeStyle = highlight ? 'yellow' : 'red';
            ctx.lineWidth = 2 / scaleX; // Adjust line width based on scale
            ctx.stroke();

            ctx.fillStyle = highlight ? 'rgba(255, 255, 0, 0.2)' : 'rgba(255, 0, 0, 0.1)';
            ctx.fill();
        },

        handleHover(event) {
            const rect = this.foregroundCanvas.getBoundingClientRect();
            const scaleX = this.foregroundCanvas.width / rect.width;
            const scaleY = this.foregroundCanvas.height / rect.height;
            const x = (event.clientX - rect.left) * scaleX;
            const y = (event.clientY - rect.top) * scaleY;

            let hoveredArea = null;
            if (this.selectedItem && this.selectedItem.areas) {
                hoveredArea = this.selectedItem.areas.find(area =>
                    this.isPointInArea(x, y, area)
                );
            }

            this.drawAreas();
            if (hoveredArea) {
                this.drawArea(hoveredArea, true);
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
        },

        loadMore(type) {
            if (this.hasMore && !this.loading) {
                this.loadItems(type, this.currentPage + 1);
            }
        },

        filterItems() {
            this.currentPage = 1;
            if (this.currentTab === 'templates') {
                this.loadItems('templates');
            } else if (this.currentTab === 'sources') {
                this.loadItems('sources');
            }
        },

        getStatusBadgeClass(status) {
            switch (status) {
                case 'REVIEW':
                    return 'bg-warning';
                case 'APPROVED':
                    return 'bg-success';
                case 'REJECTED':
                    return 'bg-danger';
                default:
                    return 'bg-secondary';
            }
        }
    };
}
