/**
 * Reactive Platform - Shared JavaScript
 * Handles theme persistence, navigation, and common UI interactions
 */

(function() {
    'use strict';

    // ============================================
    // Theme Management
    // ============================================
    const THEME_KEY = 'reactive-platform-theme';

    function getPreferredTheme() {
        const stored = localStorage.getItem(THEME_KEY);
        if (stored) return stored;

        // Default to light mode as requested
        return 'light';
    }

    function setTheme(theme) {
        document.documentElement.setAttribute('data-theme', theme);
        localStorage.setItem(THEME_KEY, theme);

        // Update toggle button aria-label
        const toggle = document.querySelector('.theme-toggle');
        if (toggle) {
            toggle.setAttribute('aria-label', theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode');
        }
    }

    function toggleTheme() {
        const current = document.documentElement.getAttribute('data-theme') || 'light';
        setTheme(current === 'dark' ? 'light' : 'dark');
    }

    // Apply theme immediately to prevent flash
    (function applyThemeEarly() {
        const theme = getPreferredTheme();
        document.documentElement.setAttribute('data-theme', theme);
    })();

    // ============================================
    // Navigation State
    // ============================================
    function setActiveNavLink() {
        const currentPath = window.location.pathname;
        const links = document.querySelectorAll('.sidebar-link');

        links.forEach(link => {
            const href = link.getAttribute('href');
            if (!href) return;

            // Normalize paths for comparison
            const linkPath = new URL(href, window.location.origin).pathname;

            // Check if this link matches current page
            const isActive = currentPath.endsWith(linkPath) ||
                             currentPath.includes(href.replace('./', '').replace('../', ''));

            link.classList.toggle('active', isActive);
        });
    }

    // ============================================
    // Initialization
    // ============================================
    function init() {
        // Set up theme toggle
        const themeToggle = document.querySelector('.theme-toggle');
        if (themeToggle) {
            themeToggle.addEventListener('click', toggleTheme);
        }

        // Apply stored theme
        setTheme(getPreferredTheme());

        // Set active nav link
        setActiveNavLink();

        // Expose toggle function globally for inline onclick handlers
        window.toggleTheme = toggleTheme;
    }

    // Run init when DOM is ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

    // ============================================
    // Auto-Polling (refresh page every 10 seconds)
    // ============================================
    const POLL_INTERVAL = 10000; // 10 seconds
    const POLL_KEY = 'reactive-platform-polling';

    function isPollingEnabled() {
        const stored = localStorage.getItem(POLL_KEY);
        return stored !== 'disabled'; // Enabled by default
    }

    function setPolling(enabled) {
        localStorage.setItem(POLL_KEY, enabled ? 'enabled' : 'disabled');
        if (enabled) {
            startPolling();
        } else {
            stopPolling();
        }
        updatePollingUI();
    }

    function togglePolling() {
        setPolling(!isPollingEnabled());
    }

    let pollTimer = null;

    function startPolling() {
        if (pollTimer) return;
        pollTimer = setInterval(() => {
            // Only refresh if page is visible
            if (!document.hidden) {
                location.reload();
            }
        }, POLL_INTERVAL);
        console.log('[ReactivePlatform] Auto-refresh enabled (every 10s)');
    }

    function stopPolling() {
        if (pollTimer) {
            clearInterval(pollTimer);
            pollTimer = null;
            console.log('[ReactivePlatform] Auto-refresh disabled');
        }
    }

    function updatePollingUI() {
        const indicator = document.querySelector('.polling-indicator');
        if (indicator) {
            indicator.classList.toggle('active', isPollingEnabled());
            indicator.title = isPollingEnabled() ? 'Auto-refresh ON (10s) - Click to disable' : 'Auto-refresh OFF - Click to enable';
        }
    }

    // Start polling if enabled
    function initPolling() {
        if (isPollingEnabled()) {
            startPolling();
        }
        updatePollingUI();

        // Expose toggle function
        window.togglePolling = togglePolling;
    }

    // Initialize polling after DOM ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initPolling);
    } else {
        initPolling();
    }

    // ============================================
    // Utility Functions (exposed globally)
    // ============================================
    window.ReactivePlatform = {
        toggleTheme,
        setTheme,
        getTheme: () => document.documentElement.getAttribute('data-theme') || 'light',
        togglePolling,
        isPollingEnabled,
        setPolling
    };

})();
