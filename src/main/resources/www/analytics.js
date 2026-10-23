/* Browser pageviews only. Never read paste content, title, referrer, or URL parameters. */
(async () => {
    'use strict';
    if (navigator.doNotTrack === '1' || navigator.globalPrivacyControl === true) return;
    const pages = {
        '/': '/', '/index.html': '/', '/docs.html': '/docs',
        '/terms.html': '/terms', '/abuse.html': '/abuse', '/view.html': '/view'
    };
    const path = location.pathname;
    const page = pages[path] || (/^\/view\/[a-zA-Z0-9]+$/.test(path) ? '/view' : null);
    if (!page) return;
    try {
        const response = await fetch('/analytics/config', { credentials: 'omit', referrerPolicy: 'no-referrer' });
        if (!response.ok) return;
        const { website } = await response.json();
        if (typeof website !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(website)) return;
        await fetch('https://umami.paradaux.io/api/send', {
            method: 'POST', credentials: 'omit', referrerPolicy: 'no-referrer',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ type: 'event', payload: {
                website, hostname: location.hostname, url: page, title: 'Bytebin', referrer: ''
            } })
        });
    } catch (_) {
        // Analytics failures must never interrupt paste creation or viewing.
    }
})();
