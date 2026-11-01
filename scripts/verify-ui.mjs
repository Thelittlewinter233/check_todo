import { mkdirSync } from 'node:fs'
import { chromium } from 'playwright'

const errors = []
const browser = await launchBrowser()
const page = await browser.newPage({ viewport: { width: 390, height: 844 } })

page.on('console', message => {
  if (message.type() === 'error') errors.push(message.text())
})
page.on('pageerror', error => errors.push(error.message))

await page.goto('http://localhost:5173/', { waitUntil: 'networkidle' })
await expectVisible(page, page.getByRole('heading', { name: '每日打卡' }))

await page.getByRole('button', { name: '新建任务' }).click()
await page.getByLabel('任务名称').fill('晨间阅读')
await page.locator('input[type="time"]').first().fill('00:00')
await page.locator('input[type="time"]').last().fill('23:59')
await page.getByRole('button', { name: '保存' }).click()

await expectVisible(page, page.getByRole('heading', { name: '晨间阅读' }))
await expectVisible(page, page.getByText('00:00 - 23:59'))
await expectVisible(page, page.getByText('可打卡'))
await expectVisible(page, page.getByText('连续 0 天'))

await page.getByRole('button', { name: '打卡' }).click()
await expectVisible(page, page.getByText('已完成'))
await expectVisible(page, page.getByText('连续 1 天'))

const stored = await page.evaluate(() => localStorage.getItem('check-todo.v1'))
if (!stored?.includes('"name":"晨间阅读"') || !stored.includes(new Date().toISOString().slice(0, 10))) {
  throw new Error('Persisted task or check-in record is missing.')
}

await page.reload({ waitUntil: 'networkidle' })
await expectVisible(page, page.getByText('已完成'))
await expectVisible(page, page.getByText('连续 1 天'))

mkdirSync('artifacts', { recursive: true })
await page.screenshot({ path: 'artifacts/ui-smoke.png', fullPage: true })

if (errors.length) {
  throw new Error(`Console errors:\n${errors.join('\n')}`)
}

await browser.close()
console.log('UI smoke passed: task created, checked in, persisted after reload, no console errors.')

async function expectVisible(page, locator) {
  await locator.waitFor({ state: 'visible', timeout: 5000 })
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
