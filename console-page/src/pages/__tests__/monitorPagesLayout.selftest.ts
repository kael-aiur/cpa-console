/**
 * FE-02 offline layout self-test for the MonitorRecordsPage heading (mock DOM only; no real
 * browser). Standalone contract test, separate from monitorPages.selftest.ts (pure logic).
 *
 * Recorded run procedure (console-page/):
 *   1. write .tsconfig.selftest.json with module ES2020, moduleResolution Bundler, strict,
 *      types node, outDir .selftest-out, rootDir ".", paths @/* -> src/* and include this
 *      file plus monitorRecordsLayout.fixture.html usage below
 *   2. ./node_modules/.bin/tsc -p .tsconfig.selftest.json
 *   3. rewrite "@/..." specifiers in .selftest-out JS output to relative .js paths
 *   4. node .selftest-out/src/pages/__tests__/monitorPagesLayout.selftest.js
 *
 * Contract under test (NATI-171-FE-02): the manual refresh button keeps an independently
 * usable slot in every viewport — never squeezed or overlapped by the title line, stats
 * copy, long error text or the window form; loading keeps it visible, labelled and
 * disabled with aria-busy; reduced-motion never hides it; keyboard reachability comes from
 * the native <button> element itself.
 */
import { strict as assert } from 'node:assert'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const globalCss = readFileSync(resolve(__dirname, '../../assets/main.css'), 'utf-8')

const fixturePath = resolve(__dirname, 'monitorRecordsLayout.fixture.html')
const fixture = readFileSync(fixturePath, 'utf-8')

// --- static structure: heading and refresh button keep their dedicated classes ---
assert.ok(fixture.includes('class="page-heading monitor-records-heading"'), 'heading must carry the page-scoped layout class')
assert.ok(fixture.includes('class="refresh-action monitor-records-refresh"'), 'refresh button must carry the page-scoped class in addition to the shared one')

// --- loading state contract: same node keeps aria-busy + disabled + visible label ---
assert.ok(fixture.includes(':aria-busy="refreshing"') === false, 'fixture freezes bindings; the live page carries them')
const pageSource = readFileSync(resolve(__dirname, '../MonitorRecordsPage.vue'), 'utf-8')
assert.ok(pageSource.includes(':aria-busy="refreshing"'), 'refreshing state must expose aria-busy')
assert.ok(pageSource.includes(':disabled="refreshing"'), 'refreshing state must keep the native disabled contract')
assert.ok(pageSource.includes("refreshing ? '刷新中…' : '手动刷新'"), 'loading must relabel the visible text, never hide the button')
assert.ok(pageSource.includes('@click="loadCards()"'), 'manual refresh must stay the only manual re-query entry')

// --- no overlay reintroduction on the page: no absolute/float positioning of the action ---
const styleBlock = pageSource.slice(pageSource.indexOf('<style scoped>'))
assert.ok(!styleBlock.includes('monitor-records-refresh'), 'page style must not reposition the heading action; layout lives in main.css')
for (const forbidden of ['position: absolute', 'float:', 'z-index: -1', 'visibility: hidden']) {
  assert.ok(!styleBlock.includes(forbidden), `page style must not contain ${forbidden}`)
}

// --- reduced-motion: the spin is animation-only; no rule ever hides the action ---
const reducedMotionStart = globalCss.indexOf('@media (prefers-reduced-motion: reduce)')
assert.ok(reducedMotionStart > 0, 'reduced-motion block must exist')
const reducedMotionBlock = globalCss.slice(reducedMotionStart, globalCss.indexOf('}', globalCss.indexOf('{', reducedMotionStart)) + 1)
assert.ok(!reducedMotionBlock.includes('monitor-records-refresh'), 'reduced motion must never hide or restyle the refresh action away')
assert.ok(reducedMotionBlock.includes('refresh-action svg.spinning'), 'reduced motion disables the spin animation only')
for (const decl of ['display', 'visibility']) {
  assert.ok(!new RegExp(`${decl}\\s*:`).test(reducedMotionBlock), `reduced motion must not set ${decl}`)
}

function ruleFor(selector: string, from = 0): string {
  let start = globalCss.indexOf(selector, from)
  if (from === 0) {
    // skip occurrences indented inside media queries: top-level rules start at column 0
    while (start > 0) {
      const lineStart = globalCss.lastIndexOf('\n', start) + 1
      if (start === lineStart) break
      start = globalCss.indexOf(selector, start + 1)
    }
  }
  assert.ok(start >= 0, `missing selector in main.css: ${selector}`)
  const open = globalCss.indexOf('{', start)
  let depth = 0
  let end = open
  for (let i = open; i < globalCss.length; i++) {
    if (globalCss[i] === '{') depth += 1
    if (globalCss[i] === '}') {
      depth -= 1
      if (depth === 0) {
        end = i
        break
      }
    }
  }
  assert.ok(depth === 0, `unbalanced braces for ${selector}`)
  return globalCss.slice(open + 1, end)
}

function hasDecl(rule: string, prop: string, value: string): boolean {
  const re = new RegExp(`(?:^|\\s|;)${prop}\\s*:\\s*${value.replace(/\s+/g, '\\s+')}(?:\\s*;|\\s*})`)
  return re.test(rule)
}

// column flexbox: the button is always the next full slot after the copy — no horizontal
// squeeze and nothing can overlap it from a sibling line.
const headingRule = ruleFor('.monitor-records-page .page-heading.monitor-records-heading')
assert.ok(hasDecl(headingRule, 'flex-direction', 'column'), 'heading must stack copy and button vertically')
assert.ok(hasDecl(headingRule, 'align-items', 'stretch'), 'heading children must own the full row width')
assert.ok(hasDecl(headingRule, 'gap', '12px'), 'copy and button need their own gap slot')
assert.ok(hasDecl(headingRule, 'margin-bottom', '2px'), 'heading must not rely on following siblings for spacing')

const refreshRule = ruleFor('.monitor-records-page .monitor-records-refresh')
assert.ok(hasDecl(refreshRule, 'flex-shrink', '0'), 'the refresh slot must never be squeezed')
assert.ok(hasDecl(refreshRule, 'min-height', '44px'), 'touch target stays at least 44px tall')
assert.ok(hasDecl(refreshRule, 'min-width', '0'), 'long labels wrap inside the slot instead of pushing layout')

const spanRule = ruleFor('.monitor-records-page .monitor-records-refresh > span')
assert.ok(hasDecl(spanRule, 'white-space', 'normal'), 'long state labels wrap instead of clipping the button')
assert.ok(hasDecl(spanRule, 'overflow-wrap', 'anywhere'), 'even unbreakable CJK+ascii mixes wrap inside the slot')

const narrowRule = ruleFor('.monitor-records-page .monitor-records-refresh', globalCss.indexOf('@media (max-width: 620px)'))
assert.ok(hasDecl(narrowRule, 'width', '100%'), 'narrow viewports give the refresh action its own full-width row')

console.log('monitorPagesLayout self-test OK — FE-02 refresh slot contract asserted')
