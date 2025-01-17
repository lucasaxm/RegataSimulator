const canvas = document.getElementById('canvas');
const ctx = canvas.getContext('2d');
const imageLoader = document.getElementById('imageLoader');
const img = new Image();
const tg = window.Telegram.WebApp;
const clipboard = new ClipboardJS('#copyCoordinatesButton');
const resetCornersButton = document.getElementById('resetCornersButton');
const backgroundCheckbox = document.getElementById('backgroundCheckbox');
const addAreaButton = document.getElementById('addAreaButton');
const polygonSelector = document.getElementById('polygonSelector');
const sourceSelector = document.getElementById('sourceSelector');

class Area {
  constructor(color) {
    this.corners = [
      {position: "TL", x: 0, y: 0},
      {position: "TR", x: canvas.width, y: 0},
      {position: "BR", x: canvas.width, y: canvas.height},
      {position: "BL", x: 0, y: canvas.height}
    ];
    this.color = color;
    this.background = false;
    this.sourceID = 1;
  }

  draw(ctx) {
    ctx.save();
    ctx.beginPath();
    this.corners.forEach((corner, index) => {
      if (index === 0) ctx.moveTo(corner.x, corner.y);
      else ctx.lineTo(corner.x, corner.y);
    });
    ctx.closePath();

    if (this.background) {
      // Draw fill behind the image
      ctx.fillStyle = `${this.color}73`;  // 33 is 20% opacity in hex
      ctx.fill();

      // Clip and draw the image
      ctx.clip();
      ctx.drawImage(img, 0, 0, canvas.width, canvas.height);

      // Reset clip and draw the outline
      ctx.restore();
      ctx.save();
      ctx.beginPath();
      this.corners.forEach((corner, index) => {
        if (index === 0) ctx.moveTo(corner.x, corner.y);
        else ctx.lineTo(corner.x, corner.y);
      });
      ctx.closePath();
      ctx.strokeStyle = this.color;
      ctx.lineWidth = 2;
      ctx.stroke();
    } else {
      // For non-background, draw fill and stroke over the image
      ctx.fillStyle = `${this.color}73`;
      ctx.fill();
      ctx.strokeStyle = this.color;
      ctx.lineWidth = 2;
      ctx.stroke();
    }

    ctx.restore();

    if (this === polygons[activeAreaIndex]) {
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

  clone() {
    const newArea = new Area(getRandomColor());
    newArea.corners = this.corners.map(corner => ({...corner}));
    newArea.background = this.background;
    newArea.sourceID = getNextSourceID();
    return newArea;
  }
}

let polygons = [new Area('blue')];
let activeAreaIndex = 0;
let draggingCorner = null;
let draggingArea = false;
let lastMousePos = null;
let scale = 1;

function getRandomColor() {
  return '#' + Math.floor(Math.random() * 16777215).toString(16).padStart(6, '0');
}

function updateAreaSelector() {
  polygonSelector.innerHTML = '';
  polygons.forEach((_, index) => {
    const option = document.createElement('option');
    option.value = index;
    option.textContent = `Area ${index + 1}`;
    polygonSelector.appendChild(option);
  });
  polygonSelector.value = activeAreaIndex;
  backgroundCheckbox.checked = polygons[activeAreaIndex].background;
}

function updateSourceSelector() {
  const activeArea = polygons[activeAreaIndex];
  const activeSourceID = activeArea.sourceID;

  // Clear existing options
  sourceSelector.innerHTML = '';

  // Collect all used IDs
  const used = getUsedSourceIDs(); // e.g. [1,2]

  // Populate the dropdown with all used IDs
  used.forEach(id => {
    const option = document.createElement('option');
    option.value = id;
    option.textContent = `Source ${id}`;
    sourceSelector.appendChild(option);
  });

  if (used.length < polygons.length) {
    const option = document.createElement('option');
    option.value = getNextSourceID();
    option.textContent = `Source ${option.value}`;
    sourceSelector.appendChild(option);
  }

  // Finally, select the current area’s SourceID
  sourceSelector.value = activeSourceID;
}

function updateBackgroundCheckbox() {
  backgroundCheckbox.checked = polygons[activeAreaIndex].background;
}

function draw() {
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  drawCheckerboard(ctx, canvas.width, canvas.height, 10);

  // Draw the full image
  ctx.drawImage(img, 0, 0, canvas.width, canvas.height);

  // Draw all polygons
  polygons.forEach(polygon => {
    polygon.draw(ctx);
  });
}

function updateCoordinates() {
  const header = 'Area,SourceID,TLx,TLy,TRx,TRy,BRx,BRy,BLx,BLy,Background';
  const lines = polygons.map((polygon, polygonIndex) => {
    const scaledCorners = polygon.corners.map(corner => ({
      position: corner.position,
      x: Math.round(corner.x / scale),
      y: Math.round(corner.y / scale)
    }));
    // polygonIndex + 1 is the "Area" number
    const areaNumber = polygonIndex + 1;
    const srcID = polygon.sourceID;
    const cornerCoords = scaledCorners.map(c => `${c.x},${c.y}`).join(',');
    const bgFlag = polygon.background ? '1' : '0';

    return `${areaNumber},${srcID},${cornerCoords},${bgFlag}`;
  });

  document.getElementById('coordinates').innerText = header + '\n' + lines.join('\n');
}

function resetCanvas() {
  polygons = [new Area('#0000ff')];
  activeAreaIndex = 0;
  updateAreaSelector();
  updateSourceSelector();
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
  const activeArea = polygons[activeAreaIndex];
  if (activeArea.isCloserToCenter(x, y)) {
    draggingArea = true;
    lastMousePos = {x, y};
  } else {
    draggingCorner = activeArea.getNearestCorner(x, y);
  }
}

function handleMove(x, y) {
  const activeArea = polygons[activeAreaIndex];
  canvas.style.cursor = activeArea.isCloserToCenter(x, y) ? 'move' : 'crosshair';

  if (draggingArea && lastMousePos) {
    const dx = x - lastMousePos.x;
    const dy = y - lastMousePos.y;
    const newCorners = activeArea.corners.map(corner => ({
      ...corner,
      x: corner.x + dx,
      y: corner.y + dy
    }));
    if (newCorners.every(corner => corner.x >= 0 && corner.x <= canvas.width && corner.y >= 0 && corner.y <= canvas.height)) {
      activeArea.corners = newCorners;
      lastMousePos = {x, y};
      draw();
    }
  } else if (draggingCorner !== null) {
    activeArea.corners[draggingCorner].x = Math.max(0, Math.min(x, canvas.width));
    activeArea.corners[draggingCorner].y = Math.max(0, Math.min(y, canvas.height));
    draw();
  }
}

function handleEnd() {
  draggingArea = false;
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

function applyTelegramTheme() {
  const colorScheme = {
    bg_color: tg.themeParams.bg_color ? tg.themeParams.bg_color : "#1e1e1e",
    text_color: tg.themeParams.text_color ? tg.themeParams.text_color : "#ffffff",
    hint_color: tg.themeParams.hint_color ? tg.themeParams.hint_color : "#7d7d7d",
    destructive_text_color: tg.themeParams.destructive_text_color ? tg.themeParams.destructive_text_color : "#ee686f",
    button_color: tg.themeParams.button_color ? tg.themeParams.button_color : "#738fed",
    button_text_color: tg.themeParams.button_text_color ? tg.themeParams.button_text_color : "#ffffff",
    secondary_button_color: tg.themeParams.header_bg_color ? tg.themeParams.header_bg_color : "#242326",
    secondary_button_text_color: tg.themeParams.button_text_color ? tg.themeParams.button_text_color : "#ffffff"
  };
  // Set body background and text color
  document.body.style.backgroundColor = colorScheme.bg_color;
  document.body.style.color = colorScheme.text_color;

  // Style for #coordinates
  const coordinates = document.getElementById('coordinates');
  coordinates.style.color = colorScheme.hint_color;

  // Style for input file button, background checkbox, and addAreaButton (SecondaryButton style)
  const mainButtons = [
    document.getElementById('imageLoader'),
    document.getElementById('copyCoordinatesButton')
  ];
  mainButtons.forEach(button => {
    if (button) {
      button.style.backgroundColor = colorScheme.button_color;
      button.style.color = colorScheme.button_text_color;
      button.style.borderColor = colorScheme.button_text_color;
    }
  });

  // Style for input file button, background checkbox, and addAreaButton (SecondaryButton style)
  const secondaryButtons = [
    document.querySelector('.input-group-text'),
    document.getElementById('addAreaButton'),
    document.getElementById('polygonSelector'),
    document.getElementById('sourceSelector'),
    document.getElementById('cloneAreaButton')
  ];
  secondaryButtons.forEach(button => {
    if (button) {
      button.style.backgroundColor = colorScheme.secondary_button_color;
      button.style.color = colorScheme.secondary_button_text_color;
      button.style.borderColor = colorScheme.secondary_button_text_color;
    }
  });

  // Style for resetCornersButton
  const resetCornersButton = document.getElementById('resetCornersButton');
  if (resetCornersButton) {
    resetCornersButton.style.backgroundColor = colorScheme.secondary_bg_color;
    resetCornersButton.style.color = colorScheme.destructive_text_color;
    resetCornersButton.style.borderColor = colorScheme.destructive_text_color;
  }
}

function handleDoubleClick(e) {
  e.preventDefault(); // Prevent zooming on double-tap on mobile devices
  const rect = canvas.getBoundingClientRect();
  let x, y;

  if (e.type === 'dblclick') {
    x = (e.clientX - rect.left) * canvas.width / rect.width;
    y = (e.clientY - rect.top) * canvas.height / rect.height;
  } else if (e.type === 'touchend') {
    const touch = e.changedTouches[0];
    x = (touch.clientX - rect.left) * canvas.width / rect.width;
    y = (touch.clientY - rect.top) * canvas.height / rect.height;
  }

  const activeArea = polygons[activeAreaIndex];
  const nearestCornerIndex = activeArea.getNearestCorner(x, y);
  straightenAreaFromCorner(activeArea, nearestCornerIndex);
}

function straightenAreaFromCorner(area, cornerIndex) {
  const corners = area.corners;
  const baseCorner = corners[cornerIndex];
  const nextCorner = corners[(cornerIndex + 1) % 4];
  const oppositeCorner = corners[(cornerIndex + 2) % 4];
  const previousCorner = corners[(cornerIndex + 3) % 4];

  switch (baseCorner.position) {
    case "TL":
      nextCorner.y = baseCorner.y;
      previousCorner.x = baseCorner.x;
      oppositeCorner.x = nextCorner.x;
      oppositeCorner.y = previousCorner.y;
      break;
    case "TR":
      nextCorner.x = baseCorner.x;
      previousCorner.y = baseCorner.y;
      oppositeCorner.y = nextCorner.y;
      oppositeCorner.x = previousCorner.x;
      break;
    case "BR":
      nextCorner.y = baseCorner.y;
      previousCorner.x = baseCorner.x;
      oppositeCorner.x = nextCorner.x;
      oppositeCorner.y = previousCorner.y;
      break;
    case "BL":
      nextCorner.x = baseCorner.x;
      previousCorner.y = baseCorner.y;
      oppositeCorner.y = nextCorner.y;
      oppositeCorner.x = previousCorner.x;
      break;
  }

  draw();
  updateCoordinates();
}

function getUsedSourceIDs() {
  // Returns all unique source IDs that polygons currently use
  const used = new Set();
  polygons.forEach(p => used.add(p.sourceID));
  return [...used].sort((a, b) => a - b);
}

function getNextSourceID() {
  // Returns the smallest integer >= 1 that’s NOT in use
  const used = getUsedSourceIDs();  // e.g. [1,2]
  let candidate = 1;
  while (used.includes(candidate)) {
    candidate++;
  }
  return candidate;  // e.g. 3 if 1 and 2 are used
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
backgroundCheckbox.addEventListener('change', (e) => {
  const newState = polygons[activeAreaIndex].toggleBackground();
  e.target.checked = newState;  // Ensure checkbox reflects the polygon's state
  draw();
  updateCoordinates();
});

addAreaButton.addEventListener('click', () => {
  const newArea = polygons[activeAreaIndex].clone();
  polygons.push(newArea);
  activeAreaIndex = polygons.length - 1;
  updateAreaSelector();
  updateSourceSelector();
  draw();
  updateCoordinates();
});

polygonSelector.addEventListener('change', (e) => {
  activeAreaIndex = parseInt(e.target.value);
  backgroundCheckbox.checked = polygons[activeAreaIndex].background;
  updateSourceSelector();
  draw();
  updateCoordinates();
});

sourceSelector.addEventListener('change', (e) => {
  polygons[activeAreaIndex].sourceID = parseInt(e.target.value);

  updateCoordinates();
  draw();
});

document.addEventListener('DOMContentLoaded', () => {
  tg.expand();
  tg.disableVerticalSwipes();
  tg.enableClosingConfirmation();
  resetCanvas();
  resizeCanvas();
  applyTelegramTheme();
  tg.ready();
});

tg.onEvent('viewportChanged', (e) => {
  if (e.isStateStable) {
    document.body.style.height = `${tg.viewportHeight}px`;
    resizeCanvas();
    applyTelegramTheme(); // Add this line
  }
});

clipboard.on('success', function (e) {
  showToast("Copiado!", "#007bff");
  e.clearSelection();
  tg.showConfirm("Cole as coordenadas na descrição do template e envie para o bot", (ok) => ok ? tg.close() : null);
});

clipboard.on('error', function (e) {
  showToast("Erro ao copiar.", "#dc3545");
});

// Mouse double-click event
canvas.addEventListener('dblclick', handleDoubleClick);

// Touch double-tap event
let lastTap = 0;
canvas.addEventListener('touchend', function (e) {
  const currentTime = new Date().getTime();
  const tapLength = currentTime - lastTap;
  if (tapLength < 500 && tapLength > 0) {
    handleDoubleClick(e);
  }
  lastTap = currentTime;
});

document.addEventListener('paste', (e) => {
  const items = (e.clipboardData || e.originalEvent.clipboardData).items;
  for (let i = 0; i < items.length; i++) {
    const item = items[i];
    if (item.type.indexOf('image') !== -1) {
      const file = item.getAsFile();
      const reader = new FileReader();
      reader.onload = (event) => {
        img.onload = () => {
          resizeCanvas();
          resetCanvas();
        };
        img.src = event.target.result;
      };
      reader.readAsDataURL(file);
    }
  }
});

const container = document.querySelector('.flex-grow-1.d-flex.justify-content-center.align-items-center.overflow-auto');
const dropOverlay = document.getElementById('dropOverlay');

container.addEventListener('dragenter', (e) => {
  e.preventDefault();
  e.stopPropagation();
  dropOverlay.style.display = 'flex';
});

container.addEventListener('dragover', (e) => {
  e.preventDefault();
  e.stopPropagation();
  // This keeps the drop action possible and overlay visible
});

container.addEventListener('dragleave', (e) => {
  // Check if actually leaving the container, not just moving over a child element
  if (e.target === container) {
    dropOverlay.style.display = 'none';
  }
});

container.addEventListener('drop', (e) => {
  e.preventDefault();
  e.stopPropagation();
  dropOverlay.style.display = 'none';

  const files = e.dataTransfer.files;
  if (files && files.length > 0) {
    const file = files[0];
    if (file.type.startsWith('image/')) {
      const reader = new FileReader();
      reader.onload = event => {
        img.onload = () => {
          resizeCanvas();
          resetCanvas();
        };
        img.src = event.target.result;
      };
      reader.readAsDataURL(file);
    } else {
      showToast("Por favor, solte um arquivo de imagem.", "#dc3545");
    }
  }
});

updateAreaSelector();
