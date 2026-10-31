/**
 * 生成首屏宣传图（16:9 横向 + 1280×640 social preview）。
 *
 * ## 素材是这台手机真拍的
 *
 * 右侧三张手机截图取自 `assets/screenshots/`，是 dsh-companion 在真机上跑出来的界面，
 * 不是设计稿。左侧文案取自 README 既有表述，不新造功能承诺。
 *
 * ## 为什么用 base64 内嵌
 *
 * `page.setContent()` 的文档没有 origin，Chromium 不加载 `file://` 资源 —— 第一次做
 * dsh-remote-bridge 那张图时四张图全裂成破图标，就是踩的这个。内嵌后文档自包含。
 */
// companion 没有自己的 node_modules；借用姊妹仓 dsh-remote-bridge 装好的 playwright。
// 用绝对路径而不是包名：ESM 不认 NODE_PATH，包名解析会直接 ERR_MODULE_NOT_FOUND。
import pw from '/media/wangke/OFFICE/workspace/dsh-remote-bridge/node_modules/.pnpm/@playwright+test@1.62.1/node_modules/@playwright/test/index.js'
const { chromium } = pw
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const ROOT = process.cwd()
const shot = (name) =>
  `data:image/jpeg;base64,${readFileSync(resolve(ROOT, 'assets/screenshots', name)).toString('base64')}`

const html = `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body {
    width: var(--W, 1600px); height: var(--H, 900px); overflow: hidden;
    font-family: system-ui, "PingFang SC", "Microsoft YaHei", sans-serif;
    background:
      radial-gradient(900px 620px at 10% 10%, #e8f6f7 0%, transparent 62%),
      radial-gradient(820px 520px at 92% 90%, #eef0fb 0%, transparent 60%),
      #fbfcfd;
    color: #18202a;
    display: grid; grid-template-columns: 640px 1fr; align-items: center;
    gap: 30px; padding: 56px 70px;
  }
  .left { display: flex; flex-direction: column; gap: 20px; }
  .kicker {
    display: inline-flex; align-items: center; gap: 9px; align-self: flex-start;
    background: #3ddc84; color: #0b2016; font-size: 17px; font-weight: 700;
    padding: 7px 16px; border-radius: 999px; letter-spacing: .3px;
  }
  h1 { font-size: 48px; line-height: 1.24; font-weight: 800; letter-spacing: -0.5px; }
  h1 .accent { color: #087f8c; }
  .sub { font-size: 20px; line-height: 1.62; color: #4a5563; }
  .cmd {
    align-self: flex-start; font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
    font-size: 15px; background: #18202a; color: #d8f3f5;
    padding: 12px 18px; border-radius: 9px; letter-spacing: .2px;
  }
  .facts { display: flex; gap: 24px; margin-top: 2px; }
  .fact { display: flex; flex-direction: column; gap: 3px; }
  .fact b { font-size: 18px; }
  .fact span { font-size: 13px; color: #77828f; }
  .right { position: relative; height: 800px; display: flex; align-items: center; justify-content: center; gap: 18px; }
  .phone {
    width: 232px; border-radius: 22px; overflow: hidden; background: #fff;
    border: 1px solid #dde3e8;
    box-shadow: 0 20px 46px rgba(24,32,42,.15), 0 3px 10px rgba(24,32,42,.07);
  }
  .phone img { display: block; width: 100%; }
  .p1 { transform: translateY(26px)  rotate(-2.2deg); }
  .p2 { transform: translateY(-14px); z-index: 2; }
  .p3 { transform: translateY(26px)  rotate(2.2deg); }
</style></head><body>
  <div class="left">
    <span class="kicker">Android 原生客户端</span>
    <h1>你的 DSH，<br>现在装进<span class="accent">手机</span>里了。</h1>
    <p class="sub">
      原生 Kotlin + Jetpack Compose 写的客户端。<br>
      连上你自己那台机器，聊天 / 会话 / 文件 / 审批，<br>
      和坐在电脑前用的是同一个 DSH。
    </p>
    <div class="cmd">配套插件：dsh plugin add dsh-remote-bridge</div>
    <div class="facts">
      <div class="fact"><b>Android 8.0+</b><span>Kotlin + Compose 原生界面</span></div>
      <div class="fact"><b>浅色 / 深色 / 四套主题</b><span>跟随系统</span></div>
      <div class="fact"><b>AGPL-3.0</b><span>fork 自 dsh-android-app</span></div>
    </div>
  </div>
  <div class="right">
    <div class="phone p1"><img src="${shot('chat-demo.jpg')}"></div>
    <div class="phone p2"><img src="${shot('model-providers.jpg')}"></div>
    <div class="phone p3"><img src="${shot('file-browser.jpg')}"></div>
  </div>
</body></html>`

const browser = await chromium.launch()

// README 首屏：16:9
{
  const page = await browser.newPage({ viewport: { width: 1600, height: 900 }, deviceScaleFactor: 1 })
  await page.setContent(html)
  await page.waitForLoadState('networkidle')
  await page.screenshot({ path: resolve(ROOT, 'assets/promo-16x9.png') })
  console.log('wrote assets/promo-16x9.png (1600×900)')
}

// GitHub social preview：2:1，比例不对会被居中裁切
{
  const page = await browser.newPage({ viewport: { width: 1280, height: 640 }, deviceScaleFactor: 1 })
  const social = html
    .replace('<body>', '<body style="--W:1280px;--H:640px;padding:34px 46px;grid-template-columns:520px 1fr;gap:20px">')
    .replace('h1 { font-size: 48px', 'h1 { font-size: 38px')
    .replace('.sub { font-size: 20px', '.sub { font-size: 16px')
    .replace('.right { position: relative; height: 800px;', '.right { position: relative; height: 560px;')
    .replace('width: 232px; border-radius: 22px', 'width: 180px; border-radius: 18px')
    .replace('font-size: 15px; background: #18202a', 'font-size: 12.5px; background: #18202a')
  await page.setContent(social)
    await page.waitForLoadState('networkidle')
  await page.screenshot({ path: resolve(ROOT, 'assets/promo-social.png') })
  console.log('wrote assets/promo-social.png (1280×640)')
}

await browser.close()
