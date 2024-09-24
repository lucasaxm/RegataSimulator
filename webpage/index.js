const canvas = document.getElementById('canvas');
const ctx = canvas.getContext('2d');
const imageLoader = document.getElementById('imageLoader');
const img = new Image();
const tg = window.Telegram.WebApp;
const copyCoordinatesButton = document.getElementById('copyCoordinatesButton');
const resetCornersButton = document.getElementById('resetCornersButton');
const backgroundCheckbox = document.getElementById('backgroundCheckbox');
const addPolygonButton = document.getElementById('addPolygonButton');
const polygonSelector = document.getElementById('polygonSelector');

class Polygon {
    constructor(color) {
        this.corners = [
            {position: "TL", x: 0, y: 0},
            {position: "TR", x: canvas.width, y: 0},
            {position: "BR", x: canvas.width, y: canvas.height},
            {position: "BL", x: 0, y: canvas.height}
        ];
        this.color = color;
        this.background = false;
        this.lastCanvasWidth = canvas.width;
        this.lastCanvasHeight = canvas.height;
    }

    draw(ctx) {
        if (!this.background) {
            ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
        }
        ctx.beginPath();
        this.corners.forEach((corner, index) => {
            if (index === 0) ctx.moveTo(corner.x, corner.y);
            else ctx.lineTo(corner.x, corner.y);
        });
        ctx.closePath();
        ctx.strokeStyle = this.color;
        ctx.lineWidth = 2;
        ctx.fillStyle = `${this.color}33`;  // 33 is 20% opacity in hex
        ctx.fill();
        if (this.background) {
            ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
        }
        ctx.stroke();

        if (this === polygons[activePolygonIndex]) {
            ctx.strokeStyle = 'red';
            this.corners.forEach(corner => {
                ctx.beginPath();
                ctx.moveTo(corner.x - 5, corner.y);
                ctx.lineTo(corner.x + 5, corner.y);
                ctx.moveTo(corner.x, corner.y - 5);
                ctx.lineTo(corner.x, corner.y + 5);
                ctx.stroke();
            });
        }
    }

    getNearestCorner(x, y) {
        return this.corners.reduce((nearest, corner, index) => {
            const dist = Math.hypot(corner.x - x, corner.y - y);
            return dist < nearest.dist ? {index, dist} : nearest;
        }, {index: null, dist: Infinity}).index;
    }

    isCloserToCenter(x, y) {
        const center = this.corners.reduce((sum, corner) => ({x: sum.x + corner.x, y: sum.y + corner.y}), {x: 0, y: 0});
        center.x /= this.corners.length;
        center.y /= this.corners.length;

        const centerDist = Math.hypot(center.x - x, center.y - y);
        const minCornerDist = Math.min(...this.corners.map(corner => Math.hypot(corner.x - x, corner.y - y)));
        return centerDist < minCornerDist;
    }

    toggleBackground() {
        this.background = !this.background;
        return this.background;
    }
}

let polygons = [new Polygon('blue')];
let activePolygonIndex = 0;
let draggingCorner = null;
let draggingPolygon = false;
let lastMousePos = null;
let scale = 1;

function getRandomColor() {
    return `#${Math.floor(Math.random() * 16777215).toString(16)}`;
}

function updatePolygonSelector() {
    polygonSelector.innerHTML = '';
    polygons.forEach((_, index) => {
        const option = document.createElement('option');
        option.value = index;
        option.textContent = `Polygon ${index + 1}`;
        polygonSelector.appendChild(option);
    });
    polygonSelector.value = activePolygonIndex;
    backgroundCheckbox.checked = polygons[activePolygonIndex].background;
}

function updateBackgroundCheckbox() {
    backgroundCheckbox.checked = polygons[activePolygonIndex].background;
}

function draw() {
    drawCheckerboard(ctx, canvas.width, canvas.height, 10);

    polygons.forEach(polygon => {
        polygon.draw(ctx);
    });
}

function updateCoordinates() {
    const activePolygon = polygons[activePolygonIndex];
    const scaledCorners = activePolygon.corners.map(corner => ({
        position: corner.position,
        x: Math.round(corner.x / scale),
        y: Math.round(corner.y / scale)
    }));
    document.getElementById('coordinates').innerText = `Polygon ${activePolygonIndex + 1}: ` +
        scaledCorners.map(corner => `${corner.position}(${corner.x},${corner.y})`).join(', ');
}

function resetCanvas() {
    polygons = [new Polygon('#0000ff')];
    activePolygonIndex = 0;
    updatePolygonSelector();
    draw();
    updateCoordinates();
}

function resizeCanvas() {
    const container = document.querySelector('.flex-grow-1.d-flex');
    const containerWidth = container.clientWidth;
    const containerHeight = container.clientHeight;

    let newWidth, newHeight;

    if (img.src) {
        const imgAspectRatio = img.width / img.height;
        const containerAspectRatio = containerWidth / containerHeight;

        if (containerAspectRatio > imgAspectRatio) {
            newHeight = containerHeight;
            newWidth = newHeight * imgAspectRatio;
        } else {
            newWidth = containerWidth;
            newHeight = newWidth / imgAspectRatio;
        }

        scale = newWidth / img.width;
    } else {
        newWidth = containerWidth;
        newHeight = containerHeight;
        scale = 1;
    }

    if (Math.abs(canvas.width - newWidth) > 1 || Math.abs(canvas.height - newHeight) > 1) {
        const widthRatio = newWidth / canvas.width;
        const heightRatio = newHeight / canvas.height;

        canvas.width = newWidth;
        canvas.height = newHeight;

        polygons.forEach(polygon => {
            polygon.corners = polygon.corners.map(corner => ({
                ...corner,
                x: corner.x * widthRatio,
                y: corner.y * heightRatio
            }));
        });

        draw();
        updateCoordinates();
    }
}

function handleStart(x, y) {
    const activePolygon = polygons[activePolygonIndex];
    if (activePolygon.isCloserToCenter(x, y)) {
        draggingPolygon = true;
        lastMousePos = {x, y};
    } else {
        draggingCorner = activePolygon.getNearestCorner(x, y);
    }
}

function handleMove(x, y) {
    const activePolygon = polygons[activePolygonIndex];
    canvas.style.cursor = activePolygon.isCloserToCenter(x, y) ? 'move' : 'crosshair';

    if (draggingPolygon && lastMousePos) {
        const dx = x - lastMousePos.x;
        const dy = y - lastMousePos.y;
        const newCorners = activePolygon.corners.map(corner => ({
            ...corner,
            x: corner.x + dx,
            y: corner.y + dy
        }));
        if (newCorners.every(corner => corner.x >= 0 && corner.x <= canvas.width && corner.y >= 0 && corner.y <= canvas.height)) {
            activePolygon.corners = newCorners;
            lastMousePos = {x, y};
            draw();
        }
    } else if (draggingCorner !== null) {
        activePolygon.corners[draggingCorner].x = Math.max(0, Math.min(x, canvas.width));
        activePolygon.corners[draggingCorner].y = Math.max(0, Math.min(y, canvas.height));
        draw();
    }
}

function handleEnd() {
    draggingPolygon = false;
    lastMousePos = null;
    draggingCorner = null;
    updateCoordinates();
}

function drawCheckerboard(ctx, width, height, size) {
    ctx.fillStyle = '#cccccc';
    ctx.fillRect(0, 0, width, height);

    for (let y = 0; y < height; y += size) {
        for (let x = 0; x < width; x += size) {
            if ((x / size + y / size) % 2 === 0) {
                ctx.fillStyle = '#ffffff';
                ctx.fillRect(x, y, size, size);
            }
        }
    }
}

function copyToClipboard(text) {
    navigator.clipboard.writeText(text).then(() => {
        showToast("Coordinates copied to clipboard", "#007bff");
    }).catch(err => {
        console.error('Could not copy text: ', err);
        showToast("Could not copy text", "#dc3545");
    });
}

function showToast(message, backgroundColor) {
    Toastify({
        text: message,
        duration: 3000,
        close: true,
        gravity: "bottom",
        position: "right",
        style: {background: backgroundColor}
    }).showToast();
}

imageLoader.addEventListener('change', e => {
    const reader = new FileReader();
    reader.onload = event => {
        img.onload = () => {
            resizeCanvas();
            resetCanvas();
        };
        img.src = event.target.result;
    };
    reader.readAsDataURL(e.target.files[0]);
});

canvas.addEventListener('mousedown', e => handleStart(e.offsetX, e.offsetY));
canvas.addEventListener('mousemove', e => handleMove(e.offsetX, e.offsetY));
canvas.addEventListener('mouseup', handleEnd);
canvas.addEventListener('mouseleave', handleEnd);

canvas.addEventListener('touchstart', e => {
    e.preventDefault();
    const touch = e.touches[0];
    const rect = canvas.getBoundingClientRect();
    handleStart((touch.clientX - rect.left) * canvas.width / rect.width, (touch.clientY - rect.top) * canvas.height / rect.height);
});
canvas.addEventListener('touchmove', e => {
    e.preventDefault();
    const touch = e.touches[0];
    const rect = canvas.getBoundingClientRect();
    handleMove((touch.clientX - rect.left) * canvas.width / rect.width, (touch.clientY - rect.top) * canvas.height / rect.height);
});
canvas.addEventListener('touchend', handleEnd);
canvas.addEventListener('touchcancel', handleEnd);

window.addEventListener('resize', resizeCanvas);
resetCornersButton.addEventListener('click', resetCanvas);
copyCoordinatesButton.addEventListener('click', () => copyToClipboard(document.getElementById('coordinates').innerText));
backgroundCheckbox.addEventListener('change', (e) => {
    const newState = polygons[activePolygonIndex].toggleBackground();
    e.target.checked = newState;  // Ensure checkbox reflects the polygon's state
    draw();
});

addPolygonButton.addEventListener('click', () => {
    polygons.push(new Polygon(getRandomColor()));
    activePolygonIndex = polygons.length - 1;
    updatePolygonSelector();
    draw();
    updateCoordinates();
});

polygonSelector.addEventListener('change', (e) => {
    activePolygonIndex = parseInt(e.target.value);
    backgroundCheckbox.checked = polygons[activePolygonIndex].background;
    draw();
    updateCoordinates();
});

document.addEventListener('DOMContentLoaded', () => {
    document.body.style.height = `${tg.viewportHeight}px`;
    document.body.style.width = '100%';
    resetCanvas();
    resizeCanvas();
});

tg.onEvent('viewport_changed', () => {
    document.body.style.height = `${tg.viewportHeight}px`;
    resizeCanvas();
});

updatePolygonSelector();