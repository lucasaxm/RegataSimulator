let canvas = document.getElementById('canvas');
let ctx = canvas.getContext('2d');
let imageLoader = document.getElementById('imageLoader');
let img = new Image();
let corners = [];
let draggingCorner = null;
let scale = 1;

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
    let {offsetX, offsetY} = e;
    draggingCorner = getNearestCorner(offsetX, offsetY);
});

canvas.addEventListener('mousemove', function (e) {
    let {offsetX, offsetY} = e;
    dragCorner(offsetX, offsetY);
});

canvas.addEventListener('mouseup', function (e) {
    let {offsetX, offsetY} = e;
    dragCorner(offsetX, offsetY);
    updateCoordinates();
    draggingCorner = null;
});

canvas.addEventListener('touchstart', function (e) {
    e.preventDefault(); // Prevent scrolling when touching the canvas
    let touch = getTouchPos(canvas, e);
    draggingCorner = getNearestCorner(touch.x, touch.y);
});

canvas.addEventListener('touchmove', function (e) {
    e.preventDefault(); // Prevent scrolling when touching the canvas
    if (draggingCorner !== null) {
        let touch = getTouchPos(canvas, e);
        dragCorner(touch.x, touch.y);
    }
});

canvas.addEventListener('touchend', function (e) {
    e.preventDefault(); // Prevent scrolling when touching the canvas
    if (draggingCorner !== null) {
        let touch = getTouchPos(canvas, e);
        dragCorner(touch.x, touch.y);
        updateCoordinates();
        draggingCorner = null;
    }
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
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.drawImage(img, 0, 0, canvas.width, canvas.height);

    // Draw the polygon connecting the corners
    ctx.beginPath();
    ctx.moveTo(corners[0].x, corners[0].y);
    corners.forEach(corner => ctx.lineTo(corner.x, corner.y));
    ctx.closePath();
    ctx.strokeStyle = 'blue';
    ctx.lineWidth = 2;
    ctx.stroke();
    ctx.fillStyle = 'rgba(0, 0, 255, 0.2)'; // Translucent blue
    ctx.fill();

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
    document.getElementById('coordinates').innerText = `Corners: ${scaledCorners.map(corner => `${corner.position}: (${corner.x}, ${corner.y})`).join(', ')}`;
}

let resetCornersButton = document.getElementById('resetCornersButton');

resetCornersButton.addEventListener('click', function () {
    resetCanvas();
});

document.addEventListener('DOMContentLoaded', function () {
    resetCanvas();
});

function resetCanvas() {
    resetCorners();
    draw();
    updateCoordinates();
}

let copyCoordinatesButton = document.getElementById('copyCoordinatesButton');

copyCoordinatesButton.addEventListener('click', function () {
    let coordinatesText = document.getElementById('coordinates').innerText;
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
    }, function(err) {
        console.error('Could not copy text: ', err);
    });
});