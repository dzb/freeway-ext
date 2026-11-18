package com.jujin.freeway.fleet.server;

import com.jujin.freeway.http.HttpExchange;
import com.jujin.freeway.http.HttpStatus;
import com.jujin.freeway.http.route.RouteHandler;
import java.io.IOException;

/** The self-contained operations dashboard; no frontend build step is required. */
final class FleetDashboardHandler implements RouteHandler {

  private static final String PAGE =
      """
      <!doctype html>
      <html lang="en">
      <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>Fleet Server</title>
        <style>
          :root { color-scheme: light dark; font-family: Inter, system-ui, sans-serif; }
          body { margin: 0; background: #111827; color: #e5e7eb; }
          header { padding: 24px 32px; border-bottom: 1px solid #374151; display:flex; gap:16px; align-items:center; }
          h1 { font-size: 22px; margin: 0; }
          #summary { color:#9ca3af; }
          main { padding: 24px 32px; }
          .controls { display:flex; gap:10px; margin-bottom:20px; }
          input { background:#1f2937; color:#e5e7eb; border:1px solid #4b5563; border-radius:6px; padding:8px 10px; }
          button { background:#2563eb; color:white; border:0; border-radius:6px; padding:8px 14px; cursor:pointer; }
          button.secondary { background:#374151; }
          table { width:100%; border-collapse:collapse; font-size:14px; }
          th, td { text-align:left; padding:9px 10px; border-bottom:1px solid #374151; vertical-align:top; }
          th { color:#9ca3af; font-weight:600; }
          .ready { color:#4ade80; } .warming { color:#fbbf24; } .down { color:#f87171; }
          #error { color:#f87171; margin:10px 0; min-height:20px; }
          .mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size:12px; color:#9ca3af; }
        </style>
      </head>
      <body>
        <header><h1>Fleet Server</h1><span id="summary">loading…</span></header>
        <main>
          <div class="controls">
            <input id="token" type="password" placeholder="Fleet token, if configured" autocomplete="off">
            <button id="refresh">Refresh</button>
            <button id="clear" class="secondary">Clear token</button>
          </div>
          <div id="error"></div>
          <table>
            <thead><tr><th>Service</th><th>Instance</th><th>Endpoint</th><th>Status</th><th>Last seen</th><th>Metadata</th></tr></thead>
            <tbody id="rows"></tbody>
          </table>
        </main>
        <script>
          const tokenInput = document.getElementById('token');
          const rows = document.getElementById('rows');
          const summary = document.getElementById('summary');
          const error = document.getElementById('error');
          const saved = sessionStorage.getItem('fleetToken');
          if (saved) tokenInput.value = saved;

          function headers() {
            const token = tokenInput.value.trim();
            if (token) sessionStorage.setItem('fleetToken', token);
            return token ? {'X-Fleet-Token': token} : {};
          }

          function text(value, className) {
            const td = document.createElement('td');
            if (className) td.className = className;
            td.textContent = value ?? '';
            return td;
          }

          function endpoint(value) {
            return value.scheme + '://' + value.host + ':' + value.port + (value.basePath || '');
          }

          function status(value) {
            if (!value.live) return ['down', 'down'];
            return value.ready ? ['ready', 'ready'] : ['warming', 'warming'];
          }

          async function get(path) {
            const response = await fetch(path, {headers: headers()});
            if (response.status === 401) throw new Error('token required or invalid');
            if (!response.ok) throw new Error(path + ' returned ' + response.status);
            return response.json();
          }

          async function refresh() {
            error.textContent = '';
            try {
              const [overview, leases] = await Promise.all([
                get('/fleet/admin/overview'),
                get('/fleet/admin/instances')
              ]);
              summary.textContent = 'protocol ' + overview.protocol + ' · ' +
                overview.instances + ' instances · ' + overview.ready + ' ready · store ' + overview.store;
              rows.replaceChildren();
              for (const lease of leases) {
                const instance = lease.instance;
                const health = lease.health;
                const [label, css] = status(health);
                const tr = document.createElement('tr');
                tr.append(text(instance.serviceId));
                tr.append(text(instance.instanceId, 'mono'));
                tr.append(text(endpoint(instance.endpoint), 'mono'));
                tr.append(text(label, css));
                tr.append(text(health.lastSeen, 'mono'));
                tr.append(text(JSON.stringify(instance.metadata || {}), 'mono'));
                rows.append(tr);
              }
            } catch (failure) {
              error.textContent = failure.message;
            }
          }

          document.getElementById('refresh').addEventListener('click', refresh);
          document.getElementById('clear').addEventListener('click', () => {
            tokenInput.value = '';
            sessionStorage.removeItem('fleetToken');
            refresh();
          });
          refresh();
          setInterval(refresh, 5000);
        </script>
      </body>
      </html>
      """;

  @Override
  public void handle(HttpExchange ctx) throws IOException {
    ctx.setHeader("Content-Type", "text/html; charset=utf-8");
    ctx.sendText(HttpStatus.OK, PAGE);
  }
}
