let currentImageData = null;
let currentHighlightedArea = null;

let currentSourcesPage = 1;
let currentTemplatesPage = 1;
const perPage = 12;

let isDeleting = false;

function fetchGalleryItems(type, page) {
    fetch(`/api/gallery?type=${type}&page=${page}&perPage=${perPage}`)
        .then(response => {
            if (response.ok) {
                return response.json();
            } else if (response.status === 401) {
                window.location.href = 'login.html';
                throw new Error('Unauthorized');
            }
            throw new Error('Network response was not ok');
        })
        .then(data => {
            const galleryContainer = document.getElementById(`${type}Gallery`);
            galleryContainer.innerHTML = '';
            data.items.forEach(item => {
                const col = document.createElement('div');
                col.className = 'col';
                col.innerHTML = `
                    <div class="gallery-item">
                        <img src="${item.imagePath}" alt="${item.name}" data-bs-toggle="modal" data-bs-target="#imageModal" data-filename="${item.name}" data-type="${type}" data-details="${encodeURIComponent(item.details)}">
                        <div class="overlay">
                            <i class="fas fa-search-plus" data-bs-toggle="modal" data-bs-target="#imageModal" data-filename="${item.name}" data-type="${type}" data-details="${encodeURIComponent(item.details)}"></i>
                        </div>
                    </div>
                `;
                galleryContainer.appendChild(col);
            });

            updatePagination(type, page, Math.ceil(data.totalItems / perPage));
            addModalListeners();
        })
        .catch(error => {
            console.error('Error:', error);
            if (error.message !== 'Unauthorized') {
                alert('An error occurred while fetching gallery items');
            }
        });
}

function addModalListeners() {
    document.querySelectorAll('[data-bs-toggle="modal"]').forEach(element => {
        element.addEventListener('click', function (event) {
            event.stopPropagation();

            const modal = document.getElementById('imageModal');
            const modalImage = document.getElementById('modalImage');
            const imageDetails = document.getElementById('imageDetails');
            const modalDeleteBtn = document.getElementById('modalDeleteBtn');

            modalImage.src = this.closest('.gallery-item').querySelector('img').src;
            modalDeleteBtn.setAttribute('data-filename', this.getAttribute('data-filename'));
            modalDeleteBtn.setAttribute('data-type', this.getAttribute('data-type'));

            const details = decodeURIComponent(this.getAttribute('data-details'));
            if (details && this.getAttribute('data-type') === 'templates') {
                currentImageData = parseCSV(details);
                const detailsTable = formatDetails(currentImageData);
                imageDetails.innerHTML = detailsTable;
                addTableHoverListeners();
                modalImage.onload = () => {
                    setupCanvas(modalImage);
                    drawAllPolygons();
                };

                window.addEventListener('resize', () => {
                    setupCanvas(modalImage);
                    drawAllPolygons();
                });
            } else {
                imageDetails.innerHTML = '';
                currentImageData = null;
            }
        });
    });

    document.getElementById('modalDeleteBtn').addEventListener('click', function () {
        if (!isDeleting) {
            isDeleting = true;
            const filename = this.getAttribute('data-filename');
            const type = this.getAttribute('data-type');
            deleteImage(type, filename);
            bootstrap.Modal.getInstance(document.getElementById('imageModal')).hide();
        }
    });
}

function parseCSV(csv) {
    const rows = csv.trim().split('\n');
    const headers = rows.shift().split(',');
    return rows.map(row => {
        const values = row.split(',');
        return headers.reduce((object, header, index) => {
            object[header] = values[index];
            return object;
        }, {});
    });
}

function formatDetails(data) {
    let tableHtml = '<table class="details-table">';
    tableHtml += '<tr>' + Object.keys(data[0]).map(key => `<th>${key}</th>`).join('') + '</tr>';
    data.forEach((row, index) => {
        tableHtml += `<tr data-area="${index + 1}">` + Object.values(row).map(value => `<td>${value}</td>`).join('') + '</tr>';
    });
    tableHtml += '</table>';
    return tableHtml;
}

function setupCanvas(img) {
    const canvas = document.getElementById('imageCanvas');
    const container = document.getElementById('imageContainer');

    canvas.width = container.offsetWidth;
    canvas.height = container.offsetHeight;

    const scaleX = canvas.width / img.naturalWidth;
    const scaleY = canvas.height / img.naturalHeight;
    const scale = Math.min(scaleX, scaleY);

    const ctx = canvas.getContext('2d');
    ctx.setTransform(scale, 0, 0, scale, (canvas.width - img.naturalWidth * scale) / 2, (canvas.height - img.naturalHeight * scale) / 2);
}

function drawPolygon(ctx, area, highlight = false) {
    ctx.beginPath();
    ctx.moveTo(area.TLx, area.TLy);
    ctx.lineTo(area.TRx, area.TRy);
    ctx.lineTo(area.BRx, area.BRy);
    ctx.lineTo(area.BLx, area.BLy);
    ctx.closePath();
    ctx.strokeStyle = highlight ? 'yellow' : 'red';
    ctx.lineWidth = 2 / ctx.getTransform().a;
    ctx.stroke();
    if (highlight) {
        ctx.fillStyle = 'rgba(255, 255, 0, 0.3)';
        ctx.fill();
    }
}

function drawAllPolygons() {
    const canvas = document.getElementById('imageCanvas');
    const ctx = canvas.getContext('2d');

    ctx.save();
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.restore();

    if (currentImageData) {
        currentImageData.forEach((area, index) => {
            drawPolygon(ctx, area, index + 1 === currentHighlightedArea);
        });
    }
}

function addTableHoverListeners() {
    const table = document.querySelector('.details-table');
    if (table) {
        table.addEventListener('mouseover', (event) => {
            const tr = event.target.closest('tr');
            if (tr && tr.dataset.area) {
                currentHighlightedArea = parseInt(tr.dataset.area);
                drawAllPolygons();
            }
        });

        table.addEventListener('mouseout', () => {
            currentHighlightedArea = null;
            drawAllPolygons();
        });
    }
}

function updatePagination(type, currentPage, totalPages) {
    const paginationContainer = document.getElementById(`${type}Pagination`);
    paginationContainer.innerHTML = '';

    for (let i = 1; i <= totalPages; i++) {
        const li = document.createElement('li');
        li.className = `page-item ${i === currentPage ? 'active' : ''}`;
        li.innerHTML = `<a class="page-link" href="#" data-page="${i}">${i}</a>`;
        paginationContainer.appendChild(li);
    }

    paginationContainer.addEventListener('click', (e) => {
        e.preventDefault();
        if (e.target.tagName === 'A') {
            const page = parseInt(e.target.getAttribute('data-page'));
            if (type === 'sources') {
                currentSourcesPage = page;
            } else {
                currentTemplatesPage = page;
            }
            fetchGalleryItems(type, page);
        }
    });
}

function deleteImage(type, filename) {
    if (confirm('Are you sure you want to delete this image?')) {
        fetch(`/api/image/${type}/${filename}`, {method: 'DELETE'})
            .then(response => {
                if (response.ok) {
                    const galleryItem = document.querySelector(`[data-filename="${filename}"][data-type="${type}"]`).closest('.col');
                    galleryItem.remove();
                } else {
                    throw new Error('Delete failed');
                }
            })
            .catch(error => {
                console.error('Error:', error);
                alert('An error occurred while deleting the image');
            })
            .finally(() => {
                isDeleting = false;
            });
    } else {
        isDeleting = false;
    }
}

document.addEventListener('DOMContentLoaded', () => {
    fetchGalleryItems('sources', currentSourcesPage);

    document.getElementById('galleryTabs').addEventListener('shown.bs.tab', (e) => {
        if (e.target.id === 'sources-tab') {
            fetchGalleryItems('sources', currentSourcesPage);
        } else if (e.target.id === 'templates-tab') {
            fetchGalleryItems('templates', currentTemplatesPage);
        }
    });

    document.getElementById('logoutButton').addEventListener('click', function () {
        fetch('/api/logout', {method: 'POST'})
            .then(response => {
                if (response.ok) {
                    window.location.href = 'login.html';
                } else {
                    throw new Error('Logout failed');
                }
            })
            .catch(error => {
                console.error('Error:', error);
                alert('An error occurred during logout');
            });
    });
});
