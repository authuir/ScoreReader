/* ScoreReader OSMD viewer bridge
 * Exposed entry points (called from MainActivity via evaluateJavascript):
 *   window.osmdViewer.loadBase64(base64Xml)
 *   window.osmdViewer.zoomBy(factor)
 *   window.osmdViewer.pageBy(direction)   // -1 / +1, simulated via scroll
 *
 * This file is intentionally written in ES5 so that it can parse on
 * Android 6.0's stock WebView (Chromium 44).
 */
(function () {
    'use strict';

    var placeholder = document.getElementById('placeholder');
    var container   = document.getElementById('osmdContainer');
    var errorBox    = document.getElementById('errorBox');

    function showError(msg) {
        if (errorBox) {
            errorBox.style.display = 'block';
            errorBox.textContent = msg;
        }
        if (window.Android && window.Android.onError) {
            try { window.Android.onError(String(msg)); } catch (_) {}
        }
        try { console.error('[ScoreReader] ' + msg); } catch (_) {}
    }

    function clearError() {
        if (errorBox) {
            errorBox.style.display = 'none';
            errorBox.textContent = '';
        }
    }

    function diagnoseEnvironment() {
        var ua = (navigator && navigator.userAgent) ? navigator.userAgent : '(no UA)';
        var details = ['UA: ' + ua];
        if (window.__osmdLoadFailed) {
            details.push('The <script> tag for opensheetmusicdisplay.min.js failed to load (network or 404).');
        }
        if (window.__scoreReaderErrors && window.__scoreReaderErrors.length) {
            details.push('Pre-init errors:');
            for (var i = 0; i < window.__scoreReaderErrors.length; i++) {
                details.push('  - ' + window.__scoreReaderErrors[i]);
            }
        } else {
            details.push(
                'No script errors were captured, which usually means the OSMD bundle ' +
                'parsed successfully but did not register its global. Re-run ' +
                'scripts\\fetch-osmd.ps1 to regenerate the ES5-transpiled bundle.'
            );
        }
        return details.join('\n');
    }

    function init() {
        if (typeof opensheetmusicdisplay === 'undefined') {
            showError(
                'OpenSheetMusicDisplay library not available.\n\n' +
                diagnoseEnvironment()
            );
            return;
        }

        var osmd;
        try {
            osmd = new opensheetmusicdisplay.OpenSheetMusicDisplay(container, {
                autoResize: true,
                backend: 'svg',
                drawTitle: true,
                drawSubtitle: true,
                drawComposer: true,
                drawLyricist: true,
                drawPartNames: true,
                followCursor: false,
                disableCursor: true,
                renderSingleHorizontalStaffline: false
            });
        } catch (e) {
            showError('Failed to construct OSMD: ' + (e && e.message ? e.message : e));
            return;
        }

        var currentZoom = 1.0;
        osmd.zoom = currentZoom;

        function decodeBase64Utf8(b64) {
            var binary = atob(b64);
            var bytes = new Uint8Array(binary.length);
            for (var i = 0; i < binary.length; i++) {
                bytes[i] = binary.charCodeAt(i);
            }
            if (typeof TextDecoder !== 'undefined') {
                return new TextDecoder('utf-8').decode(bytes);
            }
            var str = '';
            for (var j = 0; j < bytes.length; j++) {
                str += String.fromCharCode(bytes[j]);
            }
            try { return decodeURIComponent(escape(str)); } catch (_) { return str; }
        }

        function loadXml(xmlString) {
            clearError();
            if (placeholder) placeholder.style.display = 'none';
            osmd.load(xmlString).then(function () {
                osmd.zoom = currentZoom;
                osmd.render();
                var title = '';
                try {
                    title = (osmd.Sheet && osmd.Sheet.TitleString) ? osmd.Sheet.TitleString : '';
                } catch (_) {}
                if (window.Android && window.Android.onRendered) {
                    try { window.Android.onRendered(title); } catch (_) {}
                }
            }).catch(function (err) {
                showError('Failed to load MusicXML: ' + (err && err.message ? err.message : err));
            });
        }

        window.osmdViewer = {
            loadBase64: function (b64) {
                try {
                    var xml = decodeBase64Utf8(b64);
                    loadXml(xml);
                } catch (e) {
                    showError('Could not decode score payload: ' + (e && e.message ? e.message : e));
                }
            },
            zoomBy: function (factor) {
                if (!osmd.Sheet) return;
                currentZoom = Math.max(0.25, Math.min(4.0, currentZoom * factor));
                osmd.zoom = currentZoom;
                osmd.render();
            },
            pageBy: function (dir) {
                var step = Math.round(window.innerHeight * 0.9) * (dir < 0 ? -1 : 1);
                window.scrollBy(0, step);
            }
        };

        if (window.Android && window.Android.onReady) {
            try { window.Android.onReady(); } catch (_) {}
        }
    }

    if (document.readyState === 'complete' || document.readyState === 'interactive') {
        // The script tag is at end of <body>; OSMD has already finished evaluating.
        // Use a microtask delay so any synchronous errors land in __scoreReaderErrors.
        setTimeout(init, 0);
    } else {
        document.addEventListener('DOMContentLoaded', init);
    }
})();
