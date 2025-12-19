/**
 * Benchmark Reports Navigation
 *
 * Handles sidebar navigation, report loading, and benchmark execution.
 */

// Component metadata
const COMPONENTS = {
    full: { name: 'Full End-to-End', icon: '📊', color: '#22c55e' },
    http: { name: 'HTTP', icon: '🌐', color: '#3b82f6' },
    kafka: { name: 'Kafka', icon: '📨', color: '#8b5cf6' },
    flink: { name: 'Flink', icon: '⚡', color: '#06b6d4' },
    drools: { name: 'Drools', icon: '📜', color: '#10b981' },
    gateway: { name: 'Gateway', icon: '🚪', color: '#f59e0b' },
};

// Gateway API URL (configurable)
const GATEWAY_URL = window.location.hostname === 'localhost'
    ? 'http://localhost:8080'
    : window.location.origin;

const API_KEY = 'reactive-admin-key';

// Current state
let currentComponent = 'full';
let reportExists = {};

// ============================================================================
// Initialization
// ============================================================================

document.addEventListener('DOMContentLoaded', () => {
    initNavigation();
    checkReportAvailability();
    loadRecentReports();
    // Load the default (full) report
    loadReport('full');
});

function initNavigation() {
    const navItems = document.querySelectorAll('#componentNav li');

    navItems.forEach(item => {
        item.addEventListener('click', () => {
            const component = item.dataset.component;
            selectComponent(component);
        });
    });

    // Handle iframe load events
    const iframe = document.getElementById('reportFrame');
    iframe.addEventListener('load', () => {
        hideLoading();
    });

    iframe.addEventListener('error', () => {
        showNoReport();
    });
}

// ============================================================================
// Navigation
// ============================================================================

function selectComponent(component) {
    if (!COMPONENTS[component]) return;

    currentComponent = component;

    // Update nav active state
    document.querySelectorAll('#componentNav li').forEach(item => {
        item.classList.toggle('active', item.dataset.component === component);
    });

    // Update header
    document.getElementById('currentComponent').textContent =
        `${COMPONENTS[component].name} Benchmark`;

    // Load report
    loadReport(component);
}

function loadReport(component) {
    const iframe = document.getElementById('reportFrame');
    const noReport = document.getElementById('noReport');

    // Report paths to try in order
    const paths = [
        `${component}/index.html`,
        `benchmark_${component}_latest/index.html`
    ];

    // For file:// protocol, directly set iframe src and use load/error events
    // This works around CORS restrictions with fetch on file:// URLs
    noReport.style.display = 'none';
    iframe.style.display = 'block';

    let pathIndex = 0;

    const tryNextPath = () => {
        if (pathIndex >= paths.length) {
            showNoReport();
            reportExists[component] = false;
            updateStatus(component, 'missing');
            return;
        }

        const reportPath = paths[pathIndex++];

        // Remove previous listeners
        iframe.onload = null;
        iframe.onerror = null;

        // Set up new listeners
        iframe.onload = () => {
            try {
                // Check if we got a valid page (not an error page)
                const iframeDoc = iframe.contentDocument || iframe.contentWindow?.document;
                if (iframeDoc && iframeDoc.body && iframeDoc.body.innerHTML.length > 100) {
                    reportExists[component] = true;
                    updateStatus(component, 'available');
                } else {
                    tryNextPath();
                }
            } catch (e) {
                // Cross-origin access error means page loaded successfully
                reportExists[component] = true;
                updateStatus(component, 'available');
            }
        };

        iframe.onerror = () => {
            tryNextPath();
        };

        iframe.src = reportPath;
    };

    tryNextPath();
}

function showNoReport() {
    const iframe = document.getElementById('reportFrame');
    const noReport = document.getElementById('noReport');

    iframe.style.display = 'none';
    noReport.style.display = 'flex';
}

function hideLoading() {
    // Hide any loading indicators
}

function updateStatus(component, status) {
    const statusEl = document.getElementById(`status-${component}`);
    if (statusEl) {
        if (status === 'available') {
            statusEl.textContent = '';
            statusEl.className = 'status';
        } else if (status === 'missing') {
            statusEl.textContent = 'No data';
            statusEl.className = 'status pending';
        } else if (status === 'running') {
            statusEl.textContent = 'Running';
            statusEl.className = 'status pending';
        } else if (status === 'error') {
            statusEl.textContent = 'Error';
            statusEl.className = 'status error';
        }
    }
}

// ============================================================================
// Report Availability
// ============================================================================

function checkReportAvailability() {
    // For file:// protocol, we can't use fetch to check availability
    // Instead, we'll try loading the default report (full) which sets up the iframe
    // Other reports will be checked when clicked via loadReport()

    // If running via http://, we can still try fetch
    if (window.location.protocol.startsWith('http')) {
        Object.keys(COMPONENTS).forEach(component => {
            const reportPath = `${component}/index.html`;

            fetch(reportPath, { method: 'HEAD' })
                .then(response => {
                    if (response.ok) {
                        reportExists[component] = true;
                        updateStatus(component, 'available');
                    } else {
                        // Try latest fallback
                        return fetch(`benchmark_${component}_latest/index.html`, { method: 'HEAD' });
                    }
                })
                .then(response => {
                    if (response && response.ok) {
                        reportExists[component] = true;
                        updateStatus(component, 'available');
                    } else if (response) {
                        reportExists[component] = false;
                        updateStatus(component, 'missing');
                    }
                })
                .catch(() => {
                    // Assume available - will be checked on click
                    reportExists[component] = undefined;
                });
        });
    } else {
        // For file:// protocol, assume all reports exist until proven otherwise
        // The actual check happens when loadReport is called
        Object.keys(COMPONENTS).forEach(component => {
            reportExists[component] = undefined; // Unknown until clicked
        });
    }
}

// ============================================================================
// Recent Reports
// ============================================================================

function loadRecentReports() {
    const recentList = document.getElementById('recentReports');
    if (!recentList) return;

    // In a static context, we can't dynamically list directories
    // This would need to be generated at build time or fetched from an API
    recentList.innerHTML = `
        <li onclick="loadLatestReport('full')">
            <span class="icon">📊</span>
            <span class="label">Full (Latest)</span>
            <span class="date">Most recent</span>
        </li>
    `;
}

function loadLatestReport(component) {
    const iframe = document.getElementById('reportFrame');
    iframe.src = `benchmark_${component}_latest/index.html`;
}

// ============================================================================
// Benchmark Execution
// ============================================================================

function runBenchmark(component) {
    updateStatus(component, 'running');
    showToast(`Starting ${COMPONENTS[component].name} benchmark...`, 'info');

    fetch(`${GATEWAY_URL}/api/admin/benchmark/${component}`, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'X-API-Key': API_KEY,
        },
        body: JSON.stringify({
            durationMs: 30000,
            concurrency: 8,
            batchSize: 100,
        }),
    })
        .then(response => {
            if (!response.ok) {
                throw new Error(`HTTP ${response.status}`);
            }
            return response.json();
        })
        .then(data => {
            showToast(`${COMPONENTS[component].name} benchmark started`, 'success');

            // Poll for completion
            pollBenchmarkStatus(component);
        })
        .catch(error => {
            updateStatus(component, 'error');
            showToast(`Failed to start benchmark: ${error.message}`, 'error');
        });
}

function runCurrentBenchmark() {
    runBenchmark(currentComponent);
}

function runAllBenchmarks() {
    showToast('Starting all benchmarks...', 'info');

    fetch(`${GATEWAY_URL}/api/admin/benchmark/all`, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'X-API-Key': API_KEY,
        },
        body: JSON.stringify({
            durationMs: 30000,
        }),
    })
        .then(response => {
            if (!response.ok) {
                throw new Error(`HTTP ${response.status}`);
            }
            return response.json();
        })
        .then(data => {
            showToast('All benchmarks started', 'success');

            // Mark all as running
            Object.keys(COMPONENTS).forEach(c => updateStatus(c, 'running'));
        })
        .catch(error => {
            showToast(`Failed to start benchmarks: ${error.message}`, 'error');
        });
}

function pollBenchmarkStatus(component) {
    const checkStatus = () => {
        fetch(`${GATEWAY_URL}/api/admin/benchmark/${component}/result`, {
            headers: { 'X-API-Key': API_KEY },
        })
            .then(response => {
                if (response.ok) {
                    return response.json();
                }
                return null;
            })
            .then(result => {
                if (result && result.status === 'completed') {
                    updateStatus(component, 'available');
                    showToast(`${COMPONENTS[component].name} benchmark completed`, 'success');

                    // Refresh if current component
                    if (component === currentComponent) {
                        setTimeout(() => loadReport(component), 1000);
                    }
                } else {
                    // Keep polling
                    setTimeout(checkStatus, 2000);
                }
            })
            .catch(() => {
                // Keep polling
                setTimeout(checkStatus, 2000);
            });
    };

    // Start polling after a delay
    setTimeout(checkStatus, 5000);
}

// ============================================================================
// UI Actions
// ============================================================================

function refreshReport() {
    loadReport(currentComponent);
}

function openInNewTab() {
    const iframe = document.getElementById('reportFrame');
    if (iframe.src) {
        window.open(iframe.src, '_blank');
    }
}

// ============================================================================
// Toast Notifications
// ============================================================================

function showToast(message, type = 'info') {
    let container = document.querySelector('.toast-container');
    if (!container) {
        container = document.createElement('div');
        container.className = 'toast-container';
        document.body.appendChild(container);
    }

    const toast = document.createElement('div');
    toast.className = `toast ${type}`;
    toast.textContent = message;

    container.appendChild(toast);

    // Auto-remove after 4 seconds
    setTimeout(() => {
        toast.style.animation = 'slideIn 0.3s ease reverse';
        setTimeout(() => toast.remove(), 300);
    }, 4000);
}

// ============================================================================
// Keyboard Shortcuts
// ============================================================================

document.addEventListener('keydown', (e) => {
    // Ctrl/Cmd + R: Refresh report
    if ((e.ctrlKey || e.metaKey) && e.key === 'r') {
        e.preventDefault();
        refreshReport();
    }

    // Number keys 1-6: Select component
    const componentKeys = ['1', '2', '3', '4', '5', '6'];
    const componentOrder = ['full', 'http', 'kafka', 'flink', 'drools', 'gateway'];

    if (componentKeys.includes(e.key) && !e.ctrlKey && !e.metaKey) {
        const index = parseInt(e.key) - 1;
        if (componentOrder[index]) {
            selectComponent(componentOrder[index]);
        }
    }
});
