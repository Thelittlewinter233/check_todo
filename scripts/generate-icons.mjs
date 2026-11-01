import { mkdirSync } from 'node:fs'
import { chromium } from 'playwright'

mkdirSync('public/icons', { recursive: true })
const browser = await launchBrowser()
const page = await browser.newPage()
await page.goto(new URL('../public/icons.svg', import.meta.url).href)

for (const size of [180, 192, 512]) {
  await page.setViewportSize({ width: size, height: size })
  await page.screenshot({ path: `public/icons/icon-${size}.png`, clip: { x: 0, y: 0, width: size, height: size } })
}

await browser.close()

async function launchBrowser() {
  for (const channel of ['msedge', 'chrome']) {
    try {
      return await chromium.launch({ channel, headless: true })
    } catch {
      // Try the next installed browser channel.
    }
  }
  return chromium.launch({ headless: true })
}
