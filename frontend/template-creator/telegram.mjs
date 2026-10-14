const THEME_KEYS = [
    'bg_color', 'text_color', 'hint_color', 'link_color', 'button_color',
    'button_text_color', 'secondary_bg_color', 'header_bg_color', 'accent_text_color',
    'section_bg_color', 'section_header_text_color', 'section_separator_color',
    'subtitle_text_color', 'destructive_text_color', 'bottom_bar_bg_color',
];
const COPIED_MESSAGE = 'Dados copiados. Envie a imagem como arquivo ao bot e cole os dados na legenda.';

// Telegram is an optional UX bridge, never an identity or submission boundary.
function safely(action) {
    try { return action(); } catch { return undefined; }
}

export function initializeTelegram({ onViewportChanged = () => {} } = {}) {
    const document = globalThis.document;
    let tg;
    let initialized = false;

    function isEmbedded() {
        return safely(() => Boolean(tg && (
            (typeof tg.platform === 'string' && tg.platform.trim() !== ''
                && tg.platform.trim().toLowerCase() !== 'unknown')
            || (typeof tg.initData === 'string' && tg.initData.trim() !== '')
        ))) === true;
    }

    function supports(method, version) {
        return safely(() => {
            if (typeof tg?.[method] !== 'function') return false;
            if (!version) return true;
            if (typeof tg.isVersionAtLeast === 'function') return tg.isVersionAtLeast(version) === true;
            if (typeof tg.version !== 'string' || !/^\d+\.\d+$/.test(tg.version)) return false;
            const [major, minor] = tg.version.split('.').map(Number);
            const [requiredMajor, requiredMinor] = version.split('.').map(Number);
            return major > requiredMajor || (major === requiredMajor && minor >= requiredMinor);
        }) === true;
    }

    function call(method, version, ...args) {
        if (supports(method, version)) safely(() => tg[method](...args));
    }

    function updateTheme() {
        const style = document?.documentElement?.style;
        if (!style) return;
        for (const key of THEME_KEYS) {
            safely(() => {
                const name = `--tg-theme-${key.replaceAll('_', '-')}`;
                const value = tg.themeParams?.[key];
                if (typeof value === 'string' && /^#(?:[\da-f]{3}|[\da-f]{4}|[\da-f]{6}|[\da-f]{8})$/i.test(value)) {
                    style.setProperty(name, value);
                } else {
                    style.removeProperty(name);
                }
            });
        }
        safely(() => {
            if (tg.colorScheme === 'light' || tg.colorScheme === 'dark') {
                style.setProperty('color-scheme', tg.colorScheme);
            } else {
                style.removeProperty('color-scheme');
            }
        });
    }

    function updateViewport() {
        safely(() => {
            const style = document?.documentElement?.style;
            const height = tg.viewportStableHeight;
            if (typeof height === 'number' && Number.isFinite(height) && height > 0) {
                style?.setProperty('--tg-viewport-stable-height', `${height}px`);
            } else {
                style?.removeProperty('--tg-viewport-stable-height');
            }
        });
        // The owner updates view/controls; this adapter never touches editor geometry.
        safely(() => onViewportChanged());
    }

    function close() {
        if (isEmbedded()) call('close');
    }

    function notifyCopied() {
        if (!isEmbedded()) return;
        if (supports('showPopup', '6.2')) {
            call('showPopup', '6.2', {
                message: COPIED_MESSAGE,
                buttons: [
                    { id: 'close', type: 'default', text: 'Voltar ao bot' },
                    { id: 'continue', type: 'default', text: 'Continuar editando' },
                ],
            }, id => { if (id === 'close') close(); });
        } else if (supports('showConfirm', '6.2')) {
            call('showConfirm', '6.2', `${COPIED_MESSAGE}\n\nVoltar ao bot?`,
                confirmed => { if (confirmed === true) close(); });
        }
    }

    function initialize() {
        if (initialized) return;
        const sdk = safely(() => globalThis.window?.Telegram?.WebApp);
        if (!sdk) return;
        tg = sdk;
        initialized = true;
        if (isEmbedded()) {
            call('ready');
            call('expand');
            call('enableClosingConfirmation', '6.2');
        }
        call('onEvent', undefined, 'themeChanged', () => safely(updateTheme));
        call('onEvent', undefined, 'viewportChanged', updateViewport);
        safely(updateTheme);
        updateViewport();
    }

    initialize();
    if (!initialized) safely(() => {
        const script = document?.getElementById('telegram-sdk');
        if (!script) return;
        const removeListeners = () => {
            safely(() => script.removeEventListener('load', loaded));
            safely(() => script.removeEventListener('error', failed));
        };
        const loaded = () => { removeListeners(); initialize(); };
        const failed = () => { removeListeners(); };
        script.addEventListener('load', loaded, { once: true });
        script.addEventListener('error', failed, { once: true });
    });

    return { notifyCopied, isEmbedded, close };
}