let canvas = document.getElementById('canvas');
let ctx = canvas.getContext('2d');
let imageLoader = document.getElementById('imageLoader');
let img = new Image();
let corners = [];
let draggingCorner = null;
let draggingPolygon = false;
let lastMousePos = null;
let scale = 1;
const tg = window.Telegram.WebApp; //Initializes the TELEGRAM BOT
// Define maximum canvas dimensions
const MAX_WIDTH = 800;
const MAX_HEIGHT = 600;

imageLoader.addEventListener('change', function (e) {
    let reader = new FileReader();
    reader.onload = function (event) {
        img.onload = function () {
            // Calculate scaling factor
            scale = Math.min(MAX_WIDTH / img.width, MAX_HEIGHT / img.height, 1);
            canvas.width = img.width * scale;
            canvas.height = img.height * scale;
            resetCorners(); // Initialize the corners
            draw(); // Draw the image and corners
        };
        img.src = event.target.result;
    };
    reader.readAsDataURL(e.target.files[0]);
});

function getNearestCorner(x, y) {
    let minDist = Infinity;
    let nearestCorner = null;

    corners.forEach((corner, index) => {
        let dist = Math.sqrt((corner.x - x) ** 2 + (corner.y - y) ** 2);
        if (dist < minDist) {
            minDist = dist;
            nearestCorner = index;
        }
    });

    return nearestCorner;
}

function getTouchPos(canvas, touchEvent) {
    let rect = canvas.getBoundingClientRect();
    let scaleX = canvas.width / rect.width;
    let scaleY = canvas.height / rect.height;
    return {
        x: (touchEvent.changedTouches[0].clientX - rect.left) * scaleX,
        y: (touchEvent.changedTouches[0].clientY - rect.top) * scaleY
    };
}

function dragCorner(x, y) {
    if (draggingCorner !== null) {
        // Ensure the corner stays within the canvas boundaries
        corners[draggingCorner].x = Math.max(0, Math.min(x, canvas.width));
        corners[draggingCorner].y = Math.max(0, Math.min(y, canvas.height));
        draw();
    }
}

canvas.addEventListener('mousedown', function (e) {
    let { offsetX, offsetY } = e;
    if (isCloserToCenter(offsetX, offsetY)) {
        draggingPolygon = true;
        lastMousePos = { x: offsetX, y: offsetY };
    } else {
        draggingCorner = getNearestCorner(offsetX, offsetY);
    }
});

canvas.addEventListener('mousemove', function (e) {
    let { offsetX, offsetY } = e;
    updateCursorStyle(offsetX, offsetY);
    if (draggingPolygon && lastMousePos) {
        let dx = offsetX - lastMousePos.x;
        let dy = offsetY - lastMousePos.y;
        let newCorners = corners.map(corner => ({
            position: corner.position, // Preserve the position property
            x: corner.x + dx,
            y: corner.y + dy
        }));
        if (newCorners.every(corner => corner.x >= 0 && corner.x <= canvas.width && corner.y >= 0 && corner.y <= canvas.height)) {
            corners = newCorners;
            lastMousePos = { x: offsetX, y: offsetY };
            draw();
        }
    } else {
        dragCorner(offsetX, offsetY);
    }
});

canvas.addEventListener('mouseup', function (e) {
    draggingPolygon = false;
    lastMousePos = null;
    draggingCorner = null;
    updateCoordinates();
});

canvas.addEventListener('touchstart', function (e) {
    e.preventDefault();
    let touch = getTouchPos(canvas, e);
    if (isCloserToCenter(touch.x, touch.y)) {
        draggingPolygon = true;
        lastMousePos = { x: touch.x, y: touch.y };
    } else {
        draggingCorner = getNearestCorner(touch.x, touch.y);
    }
});

canvas.addEventListener('touchmove', function (e) {
    e.preventDefault();
    let touch = getTouchPos(canvas, e);
    if (draggingPolygon && lastMousePos) {
        let dx = touch.x - lastMousePos.x;
        let dy = touch.y - lastMousePos.y;
        let newCorners = corners.map(corner => ({
            position: corner.position, // Preserve the position property
            x: corner.x + dx,
            y: corner.y + dy
        }));
        if (newCorners.every(corner => corner.x >= 0 && corner.x <= canvas.width && corner.y >= 0 && corner.y <= canvas.height)) {
            corners = newCorners;
            lastMousePos = { x: touch.x, y: touch.y };
            draw();
        }
    } else {
        dragCorner(touch.x, touch.y);
    }
});

canvas.addEventListener('touchend', function (e) {
    e.preventDefault();
    draggingPolygon = false;
    lastMousePos = null;
    draggingCorner = null;
    updateCoordinates();
});

function resetCorners() {
    corners = [
        {position: "Top-left", x: 0, y: 0},                      // Top-left
        {position: "Top-right", x: canvas.width, y: 0},             // Top-right
        {position: "Bottom-right", x: canvas.width, y: canvas.height}, // Bottom-right
        {position: "Bottom-left", x: 0, y: canvas.height}           // Bottom-left
    ];
}

function draw() {
    const checkerboardSize = 10; // Size of the checkerboard squares
    drawCheckerboard(ctx, canvas.width, canvas.height, checkerboardSize);
    const backgroundCheckbox = document.getElementById('backgroundCheckbox');
    if (!backgroundCheckbox.checked) {
        ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
    }
    // Draw the polygon connecting the corners
    ctx.beginPath();
    ctx.moveTo(corners[0].x, corners[0].y);
    corners.forEach(corner => ctx.lineTo(corner.x, corner.y));
    ctx.closePath();
    ctx.strokeStyle = 'blue';
    ctx.lineWidth = 2;
    ctx.fillStyle = 'rgba(0, 0, 255, 0.2)'; // Translucent blue
    ctx.fill();
    if (backgroundCheckbox.checked) {
        ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
    }
    ctx.stroke();
    // Draw corner points
    ctx.strokeStyle = 'red'; // Ensure the plus sign is red
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
    let scaledCorners = corners.map(corner => ({
        position: corner.position,
        x: Math.round(corner.x / scale),
        y: Math.round(corner.y / scale)
    }));
    document.getElementById('coordinates').innerText = `Template: ${scaledCorners.map(corner => `${corner.position}: (${corner.x}, ${corner.y})`).join(', ')}`;
}

let resetCornersButton = document.getElementById('resetCornersButton');

resetCornersButton.addEventListener('click', function () {
    resetCanvas();
});

document.addEventListener('DOMContentLoaded', function () {
    document.body.style.height = `${tg.viewportHeight}px`;
    document.body.style.width = '100%';
    resetCanvas();
});

tg.onEvent('viewport_changed', function() {
    document.body.style.height = `${tg.viewportHeight}px`;
});

function resetCanvas() {
    resetCorners();
    draw();
    updateCoordinates();
}

let copyCoordinatesButton = document.getElementById('copyCoordinatesButton');

copyCoordinatesButton.addEventListener('click', function () {
    let coordinatesText = document.getElementById('coordinates').innerText;

    // Try to use the Clipboard API
    navigator.clipboard.writeText(coordinatesText).then(function() {
        Toastify({
            text: "Coordinates copied to clipboard",
            duration: 3000, // Duration in milliseconds
            close: true, // Show close button
            gravity: "bottom", // Position: top or bottom
            position: "right", // Position: left, center or right
            style: {
                background: "#007bff", // Background color
            }
        }).showToast();
    }).catch(function(err) {
        // Fallback method
        let textArea = document.createElement("textarea");
        textArea.value = coordinatesText;
        document.body.appendChild(textArea);
        textArea.select();
        try {
            document.execCommand('copy');
            Toastify({
                text: "Coordinates copied to clipboard",
                duration: 3000, // Duration in milliseconds
                close: true, // Show close button
                gravity: "bottom", // Position: top or bottom
                position: "right", // Position: left, center or right
                style: {
                    background: "#007bff", // Background color
                }
            }).showToast();
        } catch (err) {
            console.error('Could not copy text: ', err);
            Toastify({
                text: "Could not copy text",
                duration: 3000, // Duration in milliseconds
                close: true, // Show close button
                gravity: "bottom", // Position: top or bottom
                position: "right", // Position: left, center or right
                style: {
                    background: "#dc3545", // Background color
                }
            }).showToast();
        }
        document.body.removeChild(textArea);
    });
});

function getPolygonCenter() {
    let sumX = 0, sumY = 0;
    corners.forEach(corner => {
        sumX += corner.x;
        sumY += corner.y;
    });
    return { x: sumX / corners.length, y: sumY / corners.length };
}

function isCloserToCenter(x, y) {
    let center = getPolygonCenter();
    let centerDist = Math.sqrt((center.x - x) ** 2 + (center.y - y) ** 2);
    let minCornerDist = Math.min(...corners.map(corner => Math.sqrt((corner.x - x) ** 2 + (corner.y - y) ** 2)));
    return centerDist < minCornerDist;
}

function updateCursorStyle(x, y) {
    if (isCloserToCenter(x, y)) {
        canvas.style.cursor = 'move';
    } else {
        canvas.style.cursor = 'crosshair';
    }
}

// Handle mouse leave event
canvas.addEventListener('mouseleave', function (e) {
    draggingPolygon = false;
    lastMousePos = null;
    draggingCorner = null;
    updateCoordinates();
});

// Handle touch leave event
canvas.addEventListener('touchleave', function (e) {
    e.preventDefault();
    draggingPolygon = false;
    lastMousePos = null;
    draggingCorner = null;
    updateCoordinates();
});

function drawCheckerboard(ctx, width, height, size) {
    ctx.fillStyle = '#cccccc';
    ctx.fillRect(0, 0, width, height);

    for (let y = 0; y < height; y += size) {
        for (let x = 0; x < width; x += size) {
            if ((x / size + y / size) % 2 === 0) {
                ctx.fillStyle = '#ffffff';
            } else {
                ctx.fillStyle = '#cccccc';
            }
            ctx.fillRect(x, y, size, size);
        }
    }
}

document.getElementById('backgroundCheckbox').addEventListener('change', draw);