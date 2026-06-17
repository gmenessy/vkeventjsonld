/*
 * Accessibility-Gate: lädt die laufende SPA, scannt die beiden Kernansichten
 * (Liste + Detail) mit axe-core gegen WCAG 2.1 A/AA und schlägt fehl, sobald ein
 * Verstoß der Schwere "serious" oder "critical" auftritt. Locked die in den
 * A11y-Sprints erreichte Barrierefreiheit gegen Regressionen ab.
 */
import { chromium } from "playwright";
import { AxeBuilder } from "@axe-core/playwright";

const BASE = process.env.BASE_URL || "http://localhost:8080";
const BLOCKING = new Set(["serious", "critical"]);
let failed = false;

async function scan(page, label) {
  const results = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
    .analyze();
  const blocking = results.violations.filter((v) => BLOCKING.has(v.impact));
  console.log(`\n[${label}] Verstöße gesamt: ${results.violations.length}, blockierend (serious/critical): ${blocking.length}`);
  for (const v of blocking) {
    console.log(`  ✗ ${v.id} (${v.impact}): ${v.help} — ${v.nodes.length} Element(e)`);
    for (const n of v.nodes.slice(0, 3)) console.log(`      ${n.target.join(" ")}`);
  }
  if (blocking.length > 0) failed = true;
}

const browser = await chromium.launch();
try {
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });

  // 1) Ergebnisübersicht (Liste)
  await page.goto(`${BASE}/`, { waitUntil: "networkidle" });
  await page.waitForSelector(".vk-card", { timeout: 20000 });
  await scan(page, "Liste");

  // 2) Detailansicht (erste Karte öffnen)
  await page.locator(".vk-card").first().click();
  await page.waitForSelector("#vk-detail-heading", { timeout: 20000 });
  await scan(page, "Detail");

  // 3) Eingeloggte Ansichten: Editor (Selbsteintrag/Cockpit) + Redaktion.
  // Login serverseitig setzen (Demodaten: redaktion@vk.example / redaktion).
  const login = await page.context().request.post(`${BASE}/api/auth/login`, {
    data: { email: "redaktion@vk.example", password: "redaktion" },
    headers: { "Content-Type": "application/json" },
  });
  if (!login.ok()) {
    console.error(`A11y-Login fehlgeschlagen (HTTP ${login.status()}) – Editor/Redaktion nicht gescannt.`);
    failed = true;
  } else {
    await page.goto(`${BASE}/#/me/new`, { waitUntil: "networkidle" });
    await page.waitForSelector(".vk-editor", { timeout: 20000 });
    await scan(page, "Editor");

    await page.goto(`${BASE}/#/admin`, { waitUntil: "networkidle" });
    await page.waitForSelector(".vk-chip-row", { timeout: 20000 });
    await scan(page, "Redaktion");
  }
} finally {
  await browser.close();
}

if (failed) {
  console.error("\nA11y-Gate FEHLGESCHLAGEN: kritische/ernste WCAG-Verstöße gefunden.");
  process.exit(1);
}
console.log("\nA11y-Gate bestanden — keine serious/critical-Verstöße.");
