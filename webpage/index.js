const canvas = document.getElementById('canvas');
const ctx = canvas.getContext('2d');
const imageLoader = document.getElementById('imageLoader');
const img = new Image();
const tg = window.Telegram.WebApp;
const copyCoordinatesButton = document.getElementById('copyCoordinatesButton');
const resetCornersButton = document.getElementById('resetCornersButton');
const backgroundCheckbox = document.getElementById('backgroundCheckbox');

let corners = [];
let draggingCorner = null;
let draggingPolygon = false;
let lastMousePos = null;
let scale = 1;

function resetCorners() {
    corners = [
        {position: "Top-left", x: 0, y: 0},
        {position: "Top-right", x: canvas.width, y: 0},
        {position: "Bottom-right", x: canvas.width, y: canvas.height},
        {position: "Bottom-left", x: 0, y: canvas.height}
    ];
}

function draw() {
    drawCheckerboard(ctx, canvas.width, canvas.height, 10);
    if (!backgroundCheckbox.checked) {
        ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
    }

    ctx.beginPath();
    corners.forEach((corner, index) => {
        if (index === 0) ctx.moveTo(corner.x, corner.y);
        else ctx.lineTo(corner.x, corner.y);
    });
    ctx.closePath();
    ctx.strokeStyle = 'blue';
    ctx.lineWidth = 2;
    ctx.fillStyle = 'rgba(0, 0, 255, 0.2)';
    ctx.fill();

    if (backgroundCheckbox.checked) {
        ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
    }
    ctx.stroke();

    ctx.strokeStyle = 'red';
    corners.forEach(corner => {
        ctx.beginPath();
        ctx.moveTo(corner.x - 5, corner.y);
        ctx.lineTo(corner.x + 5, corner.y);
        ctx.moveTo(corner.x, corner.y - 5);
        ctx.lineTo(corner.x, corner.y + 5);
        ctx.stroke();
    });
}

function updateCoordinates() {
    const scaledCorners = corners.map(corner => ({
        position: corner.position,
        x: Math.round(corner.x / scale),
        y: Math.round(corner.y / scale)
    }));
    document.getElementById('coordinates').innerText = `Template: ${scaledCorners.map(corner => `${corner.position}: (${corner.x}, ${corner.y})`).join(', ')}`;
}

function resetCanvas() {
    resetCorners();
    draw();
    updateCoordinates();
}

function resizeCanvas() {
    const container = document.querySelector('.flex-grow-1.d-flex');
    const containerWidth = container.clientWidth;
    const containerHeight = container.clientHeight;
    const aspectRatio = img.width / img.height;

    if (containerWidth / containerHeight > aspectRatio) {
        canvas.height = containerHeight;
        canvas.width = containerHeight * aspectRatio;
    } else {
        canvas.width = containerWidth;
        canvas.height = containerWidth / aspectRatio;
    }

    scale = canvas.width / img.width;
    draw();
}

function getNearestCorner(x, y) {
    return corners.reduce((nearest, corner, index) => {
        const dist = Math.hypot(corner.x - x, corner.y - y);
        return dist < nearest.dist ? {index, dist} : nearest;
    }, {index: null, dist: Infinity}).index;
}

function isCloserToCenter(x, y) {
    const center = corners.reduce((sum, corner) => ({x: sum.x + corner.x, y: sum.y + corner.y}), {x: 0, y: 0});
    center.x /= corners.length;
    center.y /= corners.length;

    const centerDist = Math.hypot(center.x - x, center.y - y);
    const minCornerDist = Math.min(...corners.map(corner => Math.hypot(corner.x - x, corner.y - y)));
    return centerDist < minCornerDist;
}

function handleStart(x, y) {
    if (isCloserToCenter(x, y)) {
        draggingPolygon = true;
        lastMousePos = {x, y};
    } else {
        draggingCorner = getNearestCorner(x, y);
    }
}

function handleMove(x, y) {
    canvas.style.cursor = isCloserToCenter(x, y) ? 'move' : 'crosshair';

    if (draggingPolygon && lastMousePos) {
        const dx = x - lastMousePos.x;
        const dy = y - lastMousePos.y;
        const newCorners = corners.map(corner => ({
            ...corner,
            x: corner.x + dx,
            y: corner.y + dy
        }));
        if (newCorners.every(corner => corner.x >= 0 && corner.x <= canvas.width && corner.y >= 0 && corner.y <= canvas.height)) {
            corners = newCorners;
            lastMousePos = {x, y};
            draw();
        }
    } else if (draggingCorner !== null) {
        corners[draggingCorner].x = Math.max(0, Math.min(x, canvas.width));
        corners[draggingCorner].y = Math.max(0, Math.min(y, canvas.height));
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
        style: { background: backgroundColor }
    }).showToast();
}

imageLoader.addEventListener('change', e => {
    const reader = new FileReader();
    reader.onload = event => {
        img.onload = () => {
            resizeCanvas();
            resetCorners();
            draw();
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
backgroundCheckbox.addEventListener('change', draw);

document.addEventListener('DOMContentLoaded', () => {
    document.body.style.height = `${tg.viewportHeight}px`;
    document.body.style.width = '100%';
    resetCanvas();
});

tg.onEvent('viewport_changed', () => {
    document.body.style.height = `${tg.viewportHeight}px`;
});