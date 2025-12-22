/**
 * Benchmark Regression Overlay
 *
 * This script adds regression comparison as a navigation menu item.
 * Clicking it shows the comparison panel. Integrates with existing UI style.
 */
(function() {
    'use strict';

    // Wait for DOM and data to be available
    function init() {
        const regression = window.__BENCHMARK_REGRESSION__;
        if (!regression) {
            console.log('No regression data available');
            return;
        }

        // Wait for the dashboard to render
        const observer = new MutationObserver(function(mutations, obs) {
            const nav = document.querySelector('nav, [class*="sidebar"], [class*="menu"], [class*="nav"]');
            if (nav) {
                obs.disconnect();
                setTimeout(addRegressionMenuItem, 100);
            }
        });

        observer.observe(document.body, { childList: true, subtree: true });

        // Also try immediately in case content is already loaded
        setTimeout(addRegressionMenuItem, 500);
    }

    function addRegressionMenuItem() {
        const regression = window.__BENCHMARK_REGRESSION__;
        if (!regression || !regression.history || regression.history.length === 0) {
            return;
        }

        // Apply styles first
        applyStyles();

        // Find the navigation/menu area
        const nav = document.querySelector('nav ul, [class*="sidebar"] ul, [class*="menu"]');
        if (nav) {
            // Add menu item for regression comparison
            const menuItem = document.createElement('li');
            menuItem.className = 'regression-menu-item';
            menuItem.innerHTML = `<a href="#regression">History</a>`;
            menuItem.addEventListener('click', (e) => {
                e.preventDefault();
                toggleRegressionPanel();
            });
            nav.appendChild(menuItem);
        }

        // Create hidden panel (not shown by default)
        createHiddenPanel(regression);
    }

    function createHiddenPanel(regression) {
        if (document.getElementById('regression-panel')) return;

        const panel = document.createElement('div');
        panel.id = 'regression-panel';
        panel.className = 'regression-panel hidden';
        panel.innerHTML = createRegressionHTML(regression);

        // Add close button
        const closeBtn = document.createElement('button');
        closeBtn.className = 'regression-close';
        closeBtn.innerHTML = '&times;';
        closeBtn.addEventListener('click', () => toggleRegressionPanel(false));
        panel.querySelector('.regression-container').prepend(closeBtn);

        document.body.appendChild(panel);
    }

    function toggleRegressionPanel(show) {
        const panel = document.getElementById('regression-panel');
        if (!panel) return;

        if (show === undefined) {
            panel.classList.toggle('hidden');
        } else if (show) {
            panel.classList.remove('hidden');
        } else {
            panel.classList.add('hidden');
        }
    }

    function createRegressionHTML(regression) {
        const baseline = regression.history[0];
        const hasPreviousData = regression.baselineCommit !== regression.currentCommit;

        let html = `
            <div class="regression-container">
                <div class="regression-header">
                    <h3>Benchmark Comparison</h3>
                    <span class="commit-info">
                        Current: <code>${regression.currentCommit}</code>
                        ${hasPreviousData ? ` vs Baseline: <code>${regression.baselineCommit}</code>` : ' (Baseline)'}
                    </span>
                </div>
        `;

        if (hasPreviousData && regression.deltas) {
            html += `<div class="delta-grid">`;

            const components = ['full', 'drools', 'kafka', 'gateway', 'http'];
            for (const comp of components) {
                const delta = regression.deltas[comp];
                if (!delta) continue;

                const throughputDelta = delta.peakThroughput?.delta || 0;
                const latencyDelta = delta.latencyP99?.delta || 0;

                const throughputClass = throughputDelta > 5 ? 'improved' : throughputDelta < -5 ? 'regressed' : 'unchanged';
                const latencyClass = latencyDelta < -5 ? 'improved' : latencyDelta > 5 ? 'regressed' : 'unchanged';

                html += `
                    <div class="delta-card">
                        <div class="delta-component">${comp}</div>
                        <div class="delta-metrics">
                            <div class="delta-metric ${throughputClass}">
                                <span class="metric-label">Throughput</span>
                                <span class="metric-value">${formatDelta(throughputDelta)}</span>
                            </div>
                            <div class="delta-metric ${latencyClass}">
                                <span class="metric-label">P99 Latency</span>
                                <span class="metric-value">${formatDelta(-latencyDelta)}</span>
                            </div>
                        </div>
                    </div>
                `;
            }

            html += `</div>`;
        } else {
            html += `
                <div class="baseline-notice">
                    <p>This is the baseline benchmark. Run benchmarks again after making changes to see comparisons.</p>
                </div>
            `;
        }

        // History section
        if (regression.history.length > 0) {
            html += `
                <div class="history-section">
                    <h4>Benchmark History</h4>
                    <table class="history-table">
                        <thead>
                            <tr>
                                <th>Commit</th>
                                <th>Date</th>
                                <th>Full E2E</th>
                                <th>Message</th>
                            </tr>
                        </thead>
                        <tbody>
            `;

            for (const entry of regression.history.slice(0, 5)) {
                const fullThroughput = entry.components?.full?.peakThroughput || 'N/A';
                html += `
                    <tr>
                        <td><code>${entry.commit}</code></td>
                        <td>${entry.date}</td>
                        <td>${fullThroughput} ops/s</td>
                        <td class="commit-message">${truncate(entry.message, 40)}</td>
                    </tr>
                `;
            }

            html += `
                        </tbody>
                    </table>
                </div>
            `;
        }

        html += `</div>`;
        return html;
    }

    function formatDelta(delta) {
        if (Math.abs(delta) < 1) return '~0%';
        const sign = delta >= 0 ? '+' : '';
        return `${sign}${delta.toFixed(1)}%`;
    }

    function truncate(str, length) {
        if (!str) return '';
        return str.length > length ? str.substring(0, length) + '...' : str;
    }

    function applyStyles() {
        if (document.getElementById('regression-styles')) return;

        const style = document.createElement('style');
        style.id = 'regression-styles';
        style.textContent = `
            /* Modal overlay */
            #regression-panel {
                position: fixed;
                top: 0;
                left: 0;
                right: 0;
                bottom: 0;
                background: rgba(0, 0, 0, 0.85);
                z-index: 1000;
                display: flex;
                justify-content: center;
                align-items: center;
                padding: 20px;
                transition: opacity 0.2s ease;
            }

            #regression-panel.hidden {
                display: none;
            }

            .regression-container {
                background: #1a1a2e;
                border-radius: 12px;
                border: 1px solid #333;
                max-width: 900px;
                max-height: 90vh;
                overflow-y: auto;
                padding: 24px;
                position: relative;
            }

            .regression-close {
                position: absolute;
                top: 12px;
                right: 12px;
                background: transparent;
                border: none;
                color: #888;
                font-size: 24px;
                cursor: pointer;
                padding: 4px 8px;
                line-height: 1;
            }

            .regression-close:hover {
                color: #fff;
            }

            /* Menu item styling */
            .regression-menu-item a {
                cursor: pointer;
            }

            .regression-header {
                display: flex;
                justify-content: space-between;
                align-items: center;
                margin-bottom: 16px;
                padding-bottom: 12px;
                border-bottom: 1px solid #333;
                padding-right: 30px;
            }

            .regression-header h3 {
                margin: 0;
                color: #fff;
                font-size: 18px;
            }

            .commit-info {
                color: #888;
                font-size: 13px;
            }

            .commit-info code {
                background: #2d2d44;
                padding: 2px 6px;
                border-radius: 4px;
                font-family: monospace;
                color: #61dafb;
            }

            .delta-grid {
                display: grid;
                grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
                gap: 12px;
                margin-bottom: 16px;
            }

            .delta-card {
                background: #2d2d44;
                border-radius: 6px;
                padding: 12px;
            }

            .delta-component {
                font-weight: 600;
                color: #fff;
                margin-bottom: 8px;
                text-transform: capitalize;
            }

            .delta-metrics {
                display: flex;
                flex-direction: column;
                gap: 8px;
            }

            .delta-metric {
                display: flex;
                justify-content: space-between;
                align-items: center;
            }

            .metric-label {
                color: #888;
                font-size: 12px;
            }

            .metric-value {
                font-weight: 600;
                font-size: 14px;
            }

            .delta-metric.improved .metric-value {
                color: #52c41a;
            }

            .delta-metric.regressed .metric-value {
                color: #ff4d4f;
            }

            .delta-metric.unchanged .metric-value {
                color: #888;
            }

            .baseline-notice {
                background: #2d2d44;
                padding: 16px;
                border-radius: 6px;
                text-align: center;
                color: #888;
            }

            .baseline-notice p {
                margin: 0;
            }

            .history-section {
                margin-top: 16px;
                padding-top: 16px;
                border-top: 1px solid #333;
            }

            .history-section h4 {
                color: #fff;
                margin: 0 0 12px 0;
                font-size: 14px;
            }

            .history-table {
                width: 100%;
                border-collapse: collapse;
                font-size: 13px;
            }

            .history-table th,
            .history-table td {
                padding: 8px 12px;
                text-align: left;
                border-bottom: 1px solid #333;
            }

            .history-table th {
                color: #888;
                font-weight: 500;
                background: #2d2d44;
            }

            .history-table td {
                color: #ddd;
            }

            .history-table code {
                background: #1a1a2e;
                padding: 2px 6px;
                border-radius: 4px;
                font-family: monospace;
                color: #61dafb;
            }

            .commit-message {
                color: #888;
                max-width: 300px;
                overflow: hidden;
                text-overflow: ellipsis;
                white-space: nowrap;
            }
        `;
        document.head.appendChild(style);
    }

    // Initialize when DOM is ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
