import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'

const iconUrl = new URL('../public/icons.svg', import.meta.url).href
const resRoot = 'android/app/src/main/res'

const targets = [
  { density: 'mdpi', legacy: 48, foreground: 108 },
  { density: 'hdpi', legacy: 72, foreground: 162 },
  { density: 'xhdpi', legacy: 96, foreground: 216 },
  { density: 'xxhdpi', legacy: 144, foreground: 324 },
  { density: 'xxxhdpi', legacy: 192, foreground: 432 },
]

const browser = await launchBrowser()
const page = await browser.newPage()
await page.goto(iconUrl)

for (const target of targets) {
  const folder = `${resRoot}/mipmap-${target.density}`
  for (const name of ['ic_launcher.png', 'ic_launcher_round.png']) {
    await capture(page, target.legacy, `${folder}/${name}`)
  }
  await capture(page, target.foreground, `${folder}/ic_launcher_foreground.png`)
}

await browser.close()

async function capture(page, size, path) {
  await page.setViewportSize({ width: size, height: size })
  await page.screenshot({ path, clip: { x: 0, y: 0, width: size, height: size } })
}

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

console.log(`Android launcher icons generated under ${fileURLToPath(new URL('../android/app/src/main/res', import.meta.url))}`)
