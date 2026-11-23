// CapitalDashboard web page. Plain JS and SVG, no libraries. Text from add-ons is only ever set with textContent.
"use strict";
const $ = (id) => document.getElementById(id);
const SVG = "http://www.w3.org/2000/svg";
let cur = { decimals: 2, plural: "coins" }, csrf = "";

function el(tag, cls, text) { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; }
function svg(tag, attrs) { const e = document.createElementNS(SVG, tag); for (const k in attrs) e.setAttribute(k, attrs[k]); return e; }

async function get(path) {
  const r = await fetch(path, { credentials: "same-origin" });
  if (r.status === 401) { $("app").hidden = true; $("login").hidden = false; throw new Error("login"); }
  if (!r.ok) throw new Error(path + ": " + r.status);
  return r.json();
}

function coins(v) {
  if (v == null) return "–";
  return (v / Math.pow(10, cur.decimals)).toLocaleString(undefined, { maximumFractionDigits: cur.decimals }) + " " + cur.plural;
}
function short(v) {   // compact axis labels
  const a = Math.abs(v);
  if (a >= 1e9) return (v / 1e9).toFixed(1) + "B"; if (a >= 1e6) return (v / 1e6).toFixed(1) + "M"; if (a >= 1e4) return (v / 1e3).toFixed(0) + "k";
  return (Math.round(v * 100) / 100).toString();
}
function fmt(unit, v) {
  if (v == null) return "–";
  switch (unit) {
    case "COINS": return coins(v);
    case "PERCENT": return v.toFixed(1) + "%";
    case "RATE_PER_DAY": return (v * 100).toFixed(2) + "% a day";
    case "INDEX": return v.toFixed(2);
    default: return Number.isInteger(v) ? v.toLocaleString() : v.toFixed(1);
  }
}
const scale = (unit) => unit === "COINS" ? Math.pow(10, cur.decimals) : 1;
const day = (ts) => new Date(ts).toLocaleDateString(undefined, { month: "short", day: "numeric" });
const time = (ts) => new Date(ts).toLocaleString(undefined, { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });

// ---- charts: series = [{name, color, unit, points: [[ts, value], ...]}] ------------------------------------------
function chart(box, series, bars) {
  box.replaceChildren();
  const W = 640, H = 220, L = 52, R = 10, T = 10, B = 24;
  const pts = series.flatMap(s => s.points);
  if (!pts.length) { box.append(el("p", "muted", "No data yet. Numbers are recorded every few minutes.")); return; }
  const div = scale(series[0].unit);
  let x0 = Math.min(...pts.map(p => p[0])), x1 = Math.max(...pts.map(p => p[0]));
  let y0 = Math.min(0, ...pts.map(p => p[1] / div)), y1 = Math.max(...pts.map(p => p[1] / div));
  if (x1 === x0) { x0 -= 3600e3; x1 += 3600e3; }
  if (y1 === y0) y1 = y0 + 1;
  const sx = (x) => L + (x - x0) / (x1 - x0) * (W - L - R), sy = (y) => T + (1 - (y - y0) / (y1 - y0)) * (H - T - B);
  const s = svg("svg", { viewBox: `0 0 ${W} ${H}`, role: "img" });
  for (let i = 0; i <= 4; i++) {
    const y = y0 + (y1 - y0) * i / 4;
    s.append(svg("line", { x1: L, x2: W - R, y1: sy(y), y2: sy(y), class: "grid" }));
    const t = svg("text", { x: L - 6, y: sy(y) + 4, "text-anchor": "end", class: "axis" }); t.textContent = short(y); s.append(t);
  }
  const span = x1 - x0, lab = span > 2 * 86400e3 ? day : time;
  [x0, x0 + span / 2, x1].forEach((x, i) => {
    const t = svg("text", { x: sx(x), y: H - 6, "text-anchor": ["start", "middle", "end"][i], class: "axis" }); t.textContent = lab(x); s.append(t);
  });
  if (bars) {
    const days = [...new Set(pts.map(p => p[0]))].sort((a, b) => a - b);
    const slot = (W - L - R) / Math.max(days.length, 1), w = Math.max(1, slot * 0.8 / series.length);
    series.forEach((se, k) => se.points.forEach(([x, v]) => {
      const i = days.indexOf(x), y = v / div, top = sy(Math.max(y, 0));
      const r = svg("rect", { x: L + i * slot + slot * 0.1 + k * w, y: top, width: w, height: Math.max(1, Math.abs(sy(y) - sy(0))), fill: se.color });
      const tip = svg("title", {}); tip.textContent = se.name + " " + day(x) + ": " + fmt(se.unit, v); r.append(tip); s.append(r);
    }));
  } else {
    series.forEach(se => {
      if (!se.points.length) return;
      s.append(svg("polyline", { points: se.points.map(p => sx(p[0]) + "," + sy(p[1] / div)).join(" "), fill: "none", stroke: se.color, "stroke-width": 2 }));
    });
  }
  box.append(s);
  if (series.length > 1 || series[0].name) {
    const lg = el("div", "legend");
    series.forEach(se => { const i = el("i"); i.style.background = se.color; const sp = el("span"); sp.append(i, document.createTextNode(se.name)); lg.append(sp); });
    box.append(lg);
  }
}

const css = (v) => getComputedStyle(document.documentElement).getPropertyValue(v).trim();
async function series(metric, range, daily) {
  const r = await get(`/api/series?metric=${encodeURIComponent(metric)}&range=${range}${daily ? "&daily=1" : ""}`);
  return { name: r.label, unit: r.unit, points: r.points };
}

// ---- sections --------------------------------------------------------------------------------------------------
function cards(s) {
  const c = $("cards"); c.replaceChildren();
  const week = s.supplyWeekChange == null ? "" : ` (${s.supplyWeekChange >= 0 ? "▲" : "▼"}${Math.abs(s.supplyWeekChange).toFixed(1)}% this week)`;
  [["Money in the world", coins(s.moneySupply) + week], ["Created today", coins(s.issuedToday)], ["Destroyed today", coins(s.destroyedToday)],
   ["Treasury", coins(s.treasury)], ["Active players today", fmt("COUNT", s.activePlayers)], ["Open alerts", String(s.openAlerts)]]
    .forEach(([l, v]) => { const d = el("div", "card"); d.append(el("div", "label", l), el("div", "value", v)); c.append(d); });
}

async function supply() {
  const r = $("range").value, a = await series("core.moneySupply", r), b = await series("core.activeMoneySupply", r);
  a.color = css("--a"); b.color = css("--b");
  chart($("supply-chart"), [a, b]);
}

async function daily() {
  const [i, d, ti, to] = await Promise.all(["core.issuedToday", "core.destroyedToday", "core.treasuryInToday", "core.treasuryOutToday"].map(m => series(m, "30d", true)));
  i.color = css("--a"); d.color = css("--b"); ti.color = css("--a"); to.color = css("--b");
  chart($("flow-chart"), [i, d], true);
  chart($("treasury-chart"), [ti, to], true);
}

async function plugins() {
  const r = await get("/api/plugins?range=" + $("plugin-range").value), body = $("plugins").tBodies[0];
  body.replaceChildren();
  if (!r.plugins.length) { const tr = el("tr"); const td = el("td", "muted", "No plugin has moved money in this range."); td.colSpan = 6; tr.append(td); body.append(tr); }
  r.plugins.forEach(p => {
    const tr = el("tr", p.printer ? "printer" : "");
    [p.plugin, coins(p.issued), coins(p.destroyed), coins(p.treasuryIn), coins(p.treasuryOut), coins(p.net)].forEach(v => tr.append(el("td", "", v)));
    body.append(tr);
  });
}

async function addons(s) {
  const box = $("addons"); box.replaceChildren();
  for (const p of s.providers || []) {
    const sec = el("section"); sec.append(el("h2", "", p.name));
    const grid = el("div", "addon-grid"); sec.append(grid); box.append(sec);
    for (const ind of p.indicators) {
      const c = el("div", "card"); c.append(el("div", "label", ind.label), el("div", "value", fmt(ind.unit, ind.value)));
      const ch = el("div", "chart"); c.append(ch); grid.append(c);
      series(ind.key, "30d").then(se => { se.color = css("--a"); se.name = ""; chart(ch, [se]); }).catch(() => {});
    }
  }
}

async function alerts() {
  const list = await get("/api/alerts?open=" + !$("show-acked").checked), ul = $("alerts");
  ul.replaceChildren();
  if (!list.length) ul.append(el("li", "muted", "No alerts."));
  list.forEach(a => {
    const li = el("li", a.open ? "" : "acked");
    const m = el("div", "msg"); m.append(el("strong", "", a.source + ": "), document.createTextNode(a.message));
    m.append(el("div", "muted", `#${a.id} · ${a.code} · ×${a.hits} · last ${time(a.lastTs)}`));
    li.append(el("span", "sev " + a.severity, a.severity), m);
    if (a.open) { const b = el("button", "", "Ack"); b.type = "button"; b.onclick = () => ack(String(a.id)); li.append(b); }
    ul.append(li);
  });
}

async function ack(id) {
  await fetch("/api/ack", { method: "POST", credentials: "same-origin", headers: { "X-CSRF": csrf, "Content-Type": "application/x-www-form-urlencoded" }, body: "id=" + encodeURIComponent(id) });
  refresh();
}

async function refresh() {
  try {
    const s = await get("/api/summary");
    cur = s.currency; csrf = s.csrf;
    $("login").hidden = true; $("app").hidden = false;
    $("updated").textContent = s.ts ? "Updated " + time(s.ts) : "Waiting for the first reading…";
    cards(s);
    await Promise.all([supply(), daily(), plugins(), addons(s), alerts()]);
  } catch (e) { if (e.message !== "login") console.error(e); }
}

$("range").onchange = () => supply().catch(() => {});
$("plugin-range").onchange = () => plugins().catch(() => {});
$("show-acked").onchange = () => alerts().catch(() => {});
$("ack-all").onclick = () => ack("all");
refresh();
setInterval(refresh, 60000);
