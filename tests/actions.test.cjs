'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const crypto = require('node:crypto');

const asset = require('node:path').resolve(__dirname, '../src/main/assets/reels-tv.js');
const source = fs.readFileSync(asset, 'utf8');
console.log('# asset SHA-256: ' + crypto.createHash('sha256').update(source).digest('hex'));

function boot(build = () => {}, href = 'https://www.instagram.com/reel/A/', { sourceBridge = true, paused = false } = {}) {
  const calls = { states: [], sources: [], sourceTimes: [], maps: [], intervals: [], timeouts: [], windowScrolls: [], queries: [], actionResults: [], controls: [], events: new Map() };
  const location = new URL(href);
  let now = 100000;
  class Element {
    constructor(tag, attrs = {}) {
      this.tagName = tag;
      this.attrs = { ...attrs };
      this.children = [];
      this.parentElement = null;
      this.css = { display: 'block', overflowY: 'visible' };
      this.rect = { left: 0, top: 0, right: 100, bottom: 100 };
      this.scrollHeight = 100;
      this.clientHeight = 100;
      this.scrolls = [];
      this.clicks = 0;
      this.events = new Map();
      this.style = { setProperty: (name, value, priority) => { this.css[name] = { value, priority }; } };
    }
    append(...children) {
      for (const child of children) {
        this.children.push(child);
        child.parentElement = this;
      }
      return this;
    }
    getAttribute(name) { return this.attrs[name] ?? null; }
    removeAttribute(name) { delete this.attrs[name]; }
    get href() {
      try { return new URL(this.attrs.href, location.href).href; }
      catch { return this.attrs.href; }
    }
    matches(selector) {
      return selector.split(',').some(part => {
        const attrs = [...part.matchAll(/\[([\w-]+)(?:="([^"]*)")?\]/g)];
        const tag = part.replace(/\[([\w-]+)(?:="([^"]*)")?\]/g, '').trim();
        assert.match(tag, /^[a-z]*$/, `DOM substitute must support actual selector: ${part}`);
        return (!tag || this.tagName === tag) && attrs.every(([, name, value]) =>
          Object.hasOwn(this.attrs, name) && (value === undefined || this.attrs[name] === value));
      });
    }
    querySelectorAll(selector) {
      const found = [];
      const visit = node => {
        for (const child of node.children) {
          if (child.matches(selector)) found.push(child);
          visit(child);
        }
      };
      visit(this);
      return found;
    }
    querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
    closest(selector) {
      for (let node = this; node; node = node.parentElement) {
        if (node.matches(selector)) return node;
      }
      return null;
    }
    getBoundingClientRect() {
      for (let node = this; node; node = node.parentElement) {
        if (node.css.display === 'none') return { left: 0, top: 0, right: 0, bottom: 0 };
      }
      return this.rect;
    }
    addEventListener(name, fn) {
      const listeners = this.events.get(name) || [];
      listeners.push(fn);
      this.events.set(name, listeners);
    }
    click() { this.clicks++; if (this.onclick) this.onclick(); }
    scrollBy(options) { this.scrolls.push({ ...options }); }
  }
  class Media extends Element {
    constructor(tag = 'video') {
      super(tag);
      this.rect = { left: 0, top: 0, right: 640, bottom: 600 };
      this.readyState = 0;
      this.paused = true;
      this.muted = false;
      this.ended = false;
      this.error = null;
      this.plays = 0;
      this.pauses = 0;
      this.currentSrc = '';
      this.loads = 0;
      this.loadSnapshots = [];
    }
    get src() { return this.attrs.src || ''; }
    set src(value) { this.attrs.src = value; }
    canPlayType() { return 'probably'; }
    play() { this.plays++; this.paused = false; return Promise.resolve(); }
    pause() { this.pauses++; this.paused = true; }
    load() {
      this.loads++;
      this.loadSnapshots.push({ paused: this.paused, muted: this.muted,
        src: this.getAttribute('src'), sources: this.querySelectorAll('source').map(node => node.getAttribute('src')) });
      this.currentSrc = '';
    }
  }
  const html = new Element('html');
  const body = new Element('body');
  html.append(body);
  const document = {
    body, documentElement: html, readyState: 'complete', events: [],
    querySelectorAll: selector => { calls.queries.push(selector); return html.querySelectorAll(selector); },
    addEventListener: (...args) => document.events.push(args)
  };
  const el = (tag, attrs) => new Element(tag, attrs);
  const media = tag => new Media(tag);
  const refs = build({ el, media, body, html, document }) || {};
  const sandbox = {
    URL, DOMException, location, document, HTMLMediaElement: Media, __reelsTvPaused: paused,
    Map: class extends Map { constructor(...args) { super(...args); calls.maps.push(this); } },
    Date: class extends Date { static now() { return now; } },
    navigator: {}, innerWidth: 1280, innerHeight: 720,
    getComputedStyle: node => {
      let visibility;
      for (let p = node; p && visibility === undefined; p = p.parentElement) visibility = p.css.visibility;
      return { ...node.css, visibility: visibility || 'visible' };
    },
    addEventListener: (name, fn) => calls.events.set(name, fn),
    ReelsTv: { actionResult: (...args) => calls.actionResults.push(args), controls: (...args) => calls.controls.push(args), state: (...args) => calls.states.push(args), source: (...args) => { calls.sources.push(args); calls.sourceTimes.push(now); }, loginRequired: () => { calls.loginRequired = (calls.loginRequired || 0) + 1; } },
    setInterval: (fn, delay) => { calls.intervals.push({ fn, delay, due: now + delay }); return calls.intervals.length; },
    setTimeout: (fn, delay) => { calls.timeouts.push({ fn, delay, due: now + delay }); return calls.timeouts.length; },
    scrollBy: options => calls.windowScrolls.push({ ...options })
  };
  if (!sourceBridge) delete sandbox.ReelsTv.source;
  sandbox.window = sandbox;
  sandbox.top = sandbox;
  const context = vm.createContext(sandbox);
  const inject = () => vm.runInContext(source, context, { filename: asset });
  inject();
  return { ...refs, calls, sandbox, context, inject, document, el, media, body, html,
    advance: milliseconds => { now += milliseconds; },
    tick(milliseconds) {
      const end = now + milliseconds;
      for (;;) {
        const timer = [...calls.intervals, ...calls.timeouts].filter(t => t.due <= end)
          .sort((a, b) => a.due - b.due)[0];
        if (!timer) break;
        now = timer.due;
        timer.due = calls.intervals.includes(timer) ? now + timer.delay : Infinity;
        timer.fn();
      }
      now = end;
    } };
}

function oneVideo({ el, media, body }) {
  const article = el('article');
  const v = media();
  article.append(v);
  body.append(article);
  return { article, v };
}

function withButton(label, svg = true) {
  return kit => {
    const refs = oneVideo(kit);
    const button = kit.el('button', svg ? {} : { 'aria-label': label });
    if (svg) button.append(kit.el('svg', { 'aria-label': label }));
    refs.article.append(button);
    return { ...refs, button };
  };
}

const REEL = 'https://www.instagram.com/reel/A/';
function result(e, action, outcome, request = '1') {
  assert.deepEqual(e.calls.actionResults, [[action, REEL, outcome, request]]);
}
function addControl(e, label, parent = e.article) {
  const button = e.el('button', { 'aria-label': label });
  parent.append(button);
  return button;
}
function withMore(kit) {
  const refs = withButton('Like', false)(kit);
  const more = kit.el('button', { 'aria-label': 'More options' });
  refs.article.append(more);
  return { ...refs, more };
}
function menuOnClick(e, label = 'Not interested', { role = 'dialog', feedback = '' } = {}) {
  const menu = e.el('div', { role });
  const option = e.el('button'); option.textContent = label;
  const report = e.el('button'); report.textContent = 'Report';
  const unfollow = e.el('button'); unfollow.textContent = 'Unfollow';
  const block = e.el('button'); block.textContent = 'Block';
  menu.append(option, report, unfollow, block);
  option.onclick = () => {
    if (feedback) {
      const toast = e.el('div', { role: 'status' }); toast.textContent = feedback; e.body.append(toast);
    }
    menu.css.display = 'none';
  };
  e.more.onclick = () => e.body.append(menu);
  return { menu, option, report, unfollow, block };
}

for (const [label, initial] of [
  ['Like', 'unliked'], ['Unlike', 'liked'], ['赞', 'unliked'], ['点赞', 'unliked'],
  ['赞了', 'liked'], ['已赞', 'liked'], ['取消赞', 'liked'], ['取消点赞', 'liked']
]) {
  for (const svg of [false, true]) test(`official ${label} ${svg ? 'icon' : 'button'} confirms only after state changes`, () => {
    const e = boot(withButton(label, svg));
    assert.equal(e.sandbox.TVReels.like(REEL, '1'), true);
    assert.equal(e.button.clicks, 1);
    assert.equal(e.calls.actionResults.length, 0);
    e.tick(300);
    assert.equal(e.calls.actionResults.length, 0, 'clicking alone is not confirmation');
    const icon = svg ? e.button.children[0] : e.button;
    icon.attrs['aria-label'] = initial === 'liked' ? 'Like' : 'Unlike';
    e.tick(900);
    result(e, 'like', initial === 'liked' ? 'unliked' : 'liked');
    e.tick(10000);
    assert.equal(e.button.clicks, 1, 'there is never an automatic retry');
  });
}

test('unchanged official state times out unconfirmed and permits another manual attempt', () => {
  const e = boot(withButton('Like', false));
  assert.equal(e.sandbox.TVReels.like(REEL, '1'), true);
  e.tick(4200);
  result(e, 'like', 'unconfirmed');
  assert.equal(e.button.clicks, 1);
  assert.equal(e.sandbox.TVReels.like(REEL, '2'), true);
  assert.equal(e.button.clicks, 2);
});

test('two second guard blocks repeated and synchronous calls without queueing', () => {
  const e = boot(withButton('Like', false));
  const repeats = [];
  e.button.onclick = () => {
    repeats.push(e.sandbox.TVReels.like(REEL, 'nested'));
    e.button.attrs['aria-label'] = e.button.attrs['aria-label'] === 'Like' ? 'Unlike' : 'Like';
  };
  assert.equal(e.sandbox.TVReels.like(REEL, '1'), true);
  e.tick(1999);
  assert.equal(e.sandbox.TVReels.like(REEL, '2'), false);
  assert.equal(e.button.clicks, 1);
  e.tick(1);
  assert.equal(e.sandbox.TVReels.like(REEL, '2'), true);
  assert.equal(e.button.clicks, 2);
  assert.deepEqual(repeats, [false, false]);
});

for (const failure of ['disabled', 'aria-disabled', 'hidden', 'ambiguous', 'wrong label', 'conflicting labels']) {
  test(`like refuses ${failure} official control and reports unavailable`, () => {
    const e = boot(withButton('Like', false));
    if (failure === 'disabled') e.button.disabled = true;
    if (failure === 'aria-disabled') e.button.attrs['aria-disabled'] = 'true';
    if (failure === 'hidden') e.button.css.display = 'none';
    if (failure === 'ambiguous') addControl(e, 'Like');
    if (failure === 'wrong label') e.button.attrs['aria-label'] = 'Like comment';
    if (failure === 'conflicting labels') e.button.append(e.el('svg', { 'aria-label': 'Unlike' }));
    assert.equal(e.sandbox.TVReels.like(REEL, '1'), false);
    assert.equal(e.button.clicks, 0);
    result(e, 'like', 'unavailable');
  });
}

for (const shareLabel of ['Share', 'Share Post', '分享帖子']) test(`a list used by the same Reel action rail remains usable with ${shareLabel}`, () => {
  const e = boot(withMore);
  const rail = e.el('div', { role: 'list' });
  const items = [e.button, addControl(e, 'Comment'), addControl(e, shareLabel), e.more];
  e.article.children = e.article.children.filter(node => !items.includes(node));
  for (const button of items) rail.append(e.el('div', { role: 'listitem' }).append(button));
  e.article.append(rail);
  e.sandbox.TVReels.state();
  assert.deepEqual(e.calls.controls.at(-1).slice(1), [true, true]);
  assert.equal(e.sandbox.TVReels.like(REEL, '1'), true);
  assert.equal(e.button.clicks, 1);
  e.sandbox.TVReels.cancelActions();
  const { option } = menuOnClick(e);
  assert.equal(e.sandbox.TVReels.notInterested(REEL, '2'), true);
  e.tick(150);
  assert.equal(option.clicks, 1);
});

for (const role of ['list', 'ul', 'dialog', 'article']) test(`comment or other post in ${role} is never liked`, () => {
  const e = boot(oneVideo);
  const wrapper = e.el(role === 'ul' || role === 'article' ? role : 'div',
    role === 'list' || role === 'dialog' ? { role } : {});
  const row = e.el('li');
  const like = e.el('button', { 'aria-label': 'Like' }); row.append(like); wrapper.append(row);
  e.article.append(wrapper);
  assert.equal(e.sandbox.TVReels.like(REEL, '1'), false);
  assert.equal(like.clicks, 0);
  if (role === 'dialog') assert.equal(e.calls.actionResults.length, 0);
  else result(e, 'like', 'unavailable');
});

test('comment list with an author link cannot masquerade as an action rail', () => {
  const e = boot(oneVideo);
  const comments = e.el('div', { role: 'list' });
  comments.append(e.el('a', { href: '/someone/' })); e.article.append(comments);
  const like = addControl(e, 'Like', comments);
  addControl(e, 'Comment', comments); addControl(e, 'Share', comments);
  assert.equal(e.sandbox.TVReels.like(REEL, '1'), false);
  assert.equal(like.clicks, 0);
});

for (const failure of ['multiple videos', 'multiple reel links', 'different route', 'wrong requested reel', 'missing request']) {
  test(`ambiguous identity: ${failure}`, () => {
    const e = boot(withButton('Like', false));
    if (failure === 'multiple videos') e.article.append(e.media());
    if (failure === 'multiple reel links') e.article.append(e.el('a', { href: REEL }), e.el('a', { href: '/reel/B/' }));
    if (failure === 'different route') e.article.append(e.el('a', { href: '/reel/B/' }));
    assert.equal(e.sandbox.TVReels.like(failure === 'wrong requested reel' ? 'https://www.instagram.com/reel/B/' : REEL,
      failure === 'missing request' ? '' : '1'), false);
    assert.equal(e.button.clicks, 0);
  });
}

for (const boundary of ['hidden', 'paused', 'login dialog', 'verification dialog', 'login route', 'video replaced', 'source changed', 'pagehide', 'popstate', 'cancelActions']) {
  test(`pending like stops across ${boundary} and never confirms a late state`, () => {
    const e = boot(withButton('Like', false));
    assert.equal(e.sandbox.TVReels.like(REEL, '1'), true);
    if (boundary === 'hidden') e.document.hidden = true;
    if (boundary === 'paused') e.sandbox.__reelsTvPaused = true;
    if (boundary === 'login dialog' || boundary === 'verification dialog') {
      const dialog = e.el('div', { role: 'dialog' });
      const login = e.el('button'); login.textContent = boundary === 'login dialog' ? '登录' : '验证身份';
      dialog.append(login); e.body.append(dialog);
    }
    if (boundary === 'login route') e.sandbox.location.pathname = '/accounts/login/';
    if (boundary === 'video replaced') {
      e.v.rect = { left: 2000, top: 0, right: 2100, bottom: 100 };
      e.article.append(e.media());
    }
    if (boundary === 'source changed') e.v.src = 'https://video.synthetic.fbcdn.net/another.mp4';
    if (boundary === 'pagehide' || boundary === 'popstate') e.calls.events.get(boundary)();
    if (boundary === 'cancelActions') e.sandbox.TVReels.cancelActions();
    e.button.attrs['aria-label'] = 'Unlike';
    e.tick(10000);
    assert.equal(e.calls.actionResults.length, 0);
    assert.equal(e.button.clicks, 1);
  });
}

for (const [label, message] of [['Not interested', "We'll show fewer posts like this."], ['不感兴趣', '我们会减少推荐此类帖子']]) {
  test(`not interested: exact ${label} and fresh official feedback confirms submitted`, () => {
    const e = boot(withMore);
    const controls = menuOnClick(e, label, { feedback: message });
    assert.equal(e.sandbox.TVReels.notInterested(REEL, '1'), true);
    assert.equal(e.more.clicks, 1);
    assert.equal(e.calls.actionResults.length, 0);
    e.tick(150);
    result(e, 'notInterested', 'submitted');
    assert.equal(controls.option.clicks, 1);
    for (const key of ['report', 'unfollow', 'block']) assert.equal(controls[key].clicks, 0);
    e.tick(10000);
    assert.equal(e.more.clicks, 1); assert.equal(controls.option.clicks, 1);
  });
}

for (const label of ['More', 'More options', '更多', '更多选项']) test(`supports exact candidate menu label ${label}`, () => {
  const e = boot(withMore); e.more.attrs['aria-label'] = label;
  const { option } = menuOnClick(e);
  assert.equal(e.sandbox.TVReels.notInterested(REEL, '1'), true);
  e.tick(150);
  assert.equal(option.clicks, 1);
});

test('clicking the menu or exact preference option without feedback stays unconfirmed', () => {
  const e = boot(withMore);
  const { option } = menuOnClick(e);
  assert.equal(e.sandbox.TVReels.notInterested(REEL, '1'), true);
  e.tick(4200);
  result(e, 'notInterested', 'unconfirmed');
  assert.equal(option.clicks, 1); assert.equal(e.more.clicks, 1);
  assert.equal(e.sandbox.TVReels.notInterested(REEL, '2'), true);
});

test('a pre-existing preference toast cannot confirm the new action', () => {
  const e = boot(withMore);
  const toast = e.el('div', { role: 'status' }); toast.textContent = 'Post hidden'; e.body.append(toast);
  menuOnClick(e);
  assert.equal(e.sandbox.TVReels.notInterested(REEL, '1'), true);
  e.tick(4200);
  result(e, 'notInterested', 'unconfirmed');
});

for (const label of ['Report', 'Unfollow', 'Block', '举报', '取关', '屏蔽', 'Not interested in this account', '不感兴趣并举报']) {
  test(`missing exact preference option never substitutes ${label}`, () => {
    const e = boot(withMore);
    const controls = menuOnClick(e, label);
    assert.equal(e.sandbox.TVReels.notInterested(REEL, '1'), true);
    e.tick(4200);
    result(e, 'notInterested', 'unavailable');
    for (const key of ['option', 'report', 'unfollow', 'block']) assert.equal(controls[key].clicks, 0);
  });
}

for (const failure of ['ambiguous option', 'disabled option', 'ambiguous menus', 'existing dialog', 'no menu', 'ambiguous More', 'disabled More', 'no More']) {
  test(`not interested refuses ${failure}`, () => {
    const e = boot(withMore);
    const { menu, option } = menuOnClick(e);
    if (failure === 'ambiguous option') { const another = e.el('button'); another.textContent = '不感兴趣'; menu.append(another); }
    if (failure === 'disabled option') option.disabled = true;
    if (failure === 'ambiguous menus') e.more.onclick = () => { e.body.append(menu); e.body.append(e.el('div', { role: 'dialog' })); };
    if (failure === 'existing dialog') e.body.append(e.el('div', { role: 'dialog' }));
    if (failure === 'no menu') e.more.onclick = null;
    if (failure === 'ambiguous More') addControl(e, 'More');
    if (failure === 'disabled More') e.more.disabled = true;
    if (failure === 'no More') e.more.attrs['aria-label'] = 'Share';
    e.sandbox.TVReels.notInterested(REEL, '1');
    e.tick(4200);
    if (failure === 'existing dialog') assert.equal(e.calls.actionResults.length, 0);
    else result(e, 'notInterested', 'unavailable');
    assert.equal(option.clicks, 0);
  });
}

for (const boundary of ['route', 'source', 'login', 'paused', 'cancelled']) test(`not interested stops before selecting on ${boundary}`, () => {
  const e = boot(withMore);
  const { option } = menuOnClick(e);
  assert.equal(e.sandbox.TVReels.notInterested(REEL, '1'), true);
  if (boundary === 'route') e.sandbox.location.pathname = '/reel/B/';
  if (boundary === 'source') e.v.src = 'https://video.synthetic.fbcdn.net/B.mp4';
  if (boundary === 'login') {
    const dialog = e.el('div', { role: 'dialog' }); const login = e.el('button'); login.textContent = 'Log in';
    dialog.append(login); e.body.append(dialog);
  }
  if (boundary === 'paused') e.sandbox.__reelsTvPaused = true;
  if (boundary === 'cancelled') e.sandbox.TVReels.cancelActions();
  e.tick(10000);
  assert.equal(option.clicks, 0); assert.equal(e.calls.actionResults.length, 0);
});

test('new Reel state and return to the same Reel cannot revive a cancelled action', () => {
  const e = boot(withButton('Like', false));
  assert.equal(e.sandbox.TVReels.like(REEL, 'old'), true);
  e.sandbox.location.pathname = '/reel/B/'; e.sandbox.TVReels.state();
  e.sandbox.location.pathname = '/reel/A/'; e.button.attrs['aria-label'] = 'Unlike';
  e.tick(4200);
  assert.equal(e.calls.actionResults.length, 0);
  assert.equal(e.sandbox.TVReels.like(REEL, 'new'), true);
  e.button.attrs['aria-label'] = 'Like'; e.tick(750);
  result(e, 'like', 'unliked', 'new');
});

test('controls report bounded SVG control labels and never read sensitive fields or network APIs', () => {
  const e = boot(kit => {
    Object.defineProperty(kit.document, 'cookie', { get() { throw new Error('cookie read'); } });
    const secret = kit.el('input', { type: 'password' });
    Object.defineProperty(secret, 'value', { get() { throw new Error('credential read'); } }); kit.body.append(secret);
    const refs = withMore(kit);
    refs.button.append(kit.el('svg', { 'aria-label': 'Like' }));
    refs.more.append(kit.el('svg', { 'aria-label': 'More options' }));
    return refs;
  });
  assert.deepEqual(e.calls.controls, [['["Like","More options"]', true, true]]);
  assert.ok(e.calls.controls.every(args => args.length === 3 && args.slice(1).every(value => typeof value === 'boolean')));
  assert.ok(e.calls.queries.every(query => !/input|form|password|cookie/i.test(query)));
  assert.doesNotMatch(source, /fetch\s*\(|XMLHttpRequest|\.cookie\b|\/api\/|graphql|dispatchEvent\(/);
});

test('reinjection preserves one bridge and does not replay actions', () => {
  const e = boot(withButton('Like', false));
  const bridge = e.sandbox.TVReels;
  bridge.like(REEL, '1'); e.inject(); e.inject(); e.tick(4200);
  assert.equal(e.sandbox.TVReels, bridge);
  assert.equal(e.calls.intervals.length, 1); assert.equal(e.button.clicks, 1);
});


test('read-only controls diagnose unknown SVG labels without treating them as clickable actions', () => {
  const e = boot(oneVideo);
  const rail = e.el('div', { role: 'list' }); e.article.append(rail);
  rail.append(e.el('svg', { 'aria-label': 'Unrecognized official control' }));
  e.sandbox.TVReels.state();
  assert.deepEqual(e.calls.controls.at(-1), ['["Unrecognized official control"]', false, false]);
});

test('SVG diagnostics exclude hidden controls and other articles, cap count, length and total size', () => {
  const e = boot(oneVideo);
  const hidden = e.el('svg', { 'aria-label': 'Hidden' }); hidden.css.display = 'none'; e.article.append(hidden);
  const other = e.el('article').append(e.el('svg', { 'aria-label': 'Other post' })); e.article.append(other);
  for (let i = 0; i < 30; i++) e.article.append(e.el('svg', { 'aria-label': '"'.repeat(100) + String(i) }));
  e.article.append(e.el('svg', { 'aria-label': 'More\noptions' }));
  e.sandbox.TVReels.state();
  const json = e.calls.controls.at(-1)[0];
  const labels = JSON.parse(json);
  assert.ok(labels.length <= 16); assert.ok(json.length <= 1200);
  assert.ok(labels.every(label => label.length <= 64 && !/[\u0000-\u001f\u007f-\u009f]/.test(label)));
  assert.ok(!labels.includes('Hidden') && !labels.includes('Other post'));
});

for (const boundary of ['hidden', 'paused', 'login']) test(`controls send nothing while ${boundary}`, () => {
  const e = boot(withButton('Like'));
  const before = e.calls.controls.length;
  if (boundary === 'hidden') e.document.hidden = true;
  if (boundary === 'paused') e.sandbox.__reelsTvPaused = true;
  if (boundary === 'login') {
    const dialog = e.el('div', { role: 'dialog' }); const login = e.el('button'); login.textContent = '登录';
    dialog.append(login); e.body.append(dialog);
  }
  e.sandbox.TVReels.state(); e.tick(1000);
  assert.equal(e.calls.controls.length, before);
});


test('an unstructured comment like with author link never becomes a Reel action', () => {
  const e = boot(oneVideo);
  const row = e.el('div'); row.append(e.el('a', { href: '/comment-author/' })); e.article.append(row);
  const like = addControl(e, 'Like', row);
  like.onclick = () => { like.attrs['aria-label'] = 'Unlike'; };
  assert.equal(e.sandbox.TVReels.like(REEL, '1'), false); e.tick(4200);
  assert.equal(like.clicks, 0); result(e, 'like', 'unavailable');
});

test('a separate action list may contain its own current Reel permalink', () => {
  const e = boot(oneVideo);
  const rail = e.el('div', { role: 'list' }); e.article.append(rail);
  rail.append(e.el('a', { href: REEL }));
  const like = addControl(e, 'Like', rail); addControl(e, 'Comment', rail); addControl(e, 'Share', rail);
  assert.equal(e.sandbox.TVReels.like(REEL, '1'), true); assert.equal(like.clicks, 1);
});

for (const [href, allowed] of [
  ['/reels/audio/12345/', true],
  ['https://instagram.com/reels/audio/12345', true],
  ['https://www.instagram.com:443/reels/audio/12345/?source=reels', true],
  ['/comment-author/', false],
  ['/reel/Other/', false],
  ['/reels/audio/not-an-id/', false],
  ['http://www.instagram.com/reels/audio/12345/', false],
  ['https://www.instagram.com.evil.example/reels/audio/12345/', false],
  ['https://user@www.instagram.com/reels/audio/12345/', false],
  ['https://www.instagram.com:444/reels/audio/12345/', false],
  ['http://[', false]
]) test(`on-page action rail link ${href} ${allowed ? 'accepted' : 'excluded'}`, () => {
  const e = boot(oneVideo);
  const rail = e.el('div'); e.article.append(rail);
  e.article.append(e.el('a', { href: '/creator/' }));
  e.button = e.el('div', { role: 'button', 'aria-label': '赞' });
  e.more = e.el('div', { role: 'button', 'aria-label': '更多' });
  rail.append(e.el('div').append(e.el('div').append(e.el('div').append(e.button))),
    e.el('div').append(e.more), e.el('a', { href }));
  addControl(e, '评论', rail); addControl(e, '分享', rail);
  e.sandbox.TVReels.state();
  assert.deepEqual(e.calls.controls.at(-1).slice(1), [allowed, allowed]);
  assert.equal(e.sandbox.TVReels.like(REEL, '1'), allowed);
  assert.equal(e.button.clicks, allowed ? 1 : 0);
  if (allowed) {
    e.button.attrs['aria-label'] = '取消赞'; e.tick(1200);
    result(e, 'like', 'liked');
  }
  const { option } = menuOnClick(e, '不感兴趣', { feedback: '帖子已隐藏' });
  assert.equal(e.sandbox.TVReels.notInterested(REEL, '2'), allowed);
  e.tick(150);
  assert.equal(e.more.clicks, allowed ? 1 : 0);
  assert.equal(option.clicks, allowed ? 1 : 0);
  if (allowed) assert.deepEqual(e.calls.actionResults.at(-1), ['notInterested', REEL, 'submitted', '2']);
});

test('an unknown verification dialog with Continue blocks an initial like', () => {
  const e = boot(withButton('Like', false));
  const dialog = e.el('div', { role: 'dialog' }); const proceed = e.el('button'); proceed.textContent = 'Continue';
  dialog.append(proceed); e.body.append(dialog);
  assert.equal(e.sandbox.TVReels.like(REEL, '1'), false); assert.equal(e.button.clicks, 0);
});

test('an additional verification dialog stops preference selection even without known auth labels', () => {
  const e = boot(withMore); const { option } = menuOnClick(e);
  assert.equal(e.sandbox.TVReels.notInterested(REEL, '1'), true);
  const dialog = e.el('div', { role: 'dialog' }); const proceed = e.el('button'); proceed.textContent = 'Continue';
  dialog.append(proceed); e.body.append(dialog); e.tick(4200);
  assert.equal(option.clicks, 0); result(e, 'notInterested', 'unavailable');
});

test('cloning an old visible preference toast is not new confirmation', () => {
  const e = boot(withMore);
  const toast = e.el('div', { role: 'status' }); toast.textContent = 'Post hidden'; e.body.append(toast);
  const { option } = menuOnClick(e);
  option.onclick = () => {
    toast.css.display = 'none';
    const clone = e.el('div', { role: 'status' }); clone.textContent = 'Post hidden'; e.body.append(clone);
  };
  assert.equal(e.sandbox.TVReels.notInterested(REEL, '1'), true); e.tick(4200);
  assert.equal(option.clicks, 1); result(e, 'notInterested', 'unconfirmed');
});

for (const label of ['Like', '赞', '点赞']) test(`fixed ${label} label follows official aria-pressed changes`, () => {
  const e = boot(withButton(label, false)); e.button.attrs['aria-pressed'] = 'false';
  assert.equal(e.sandbox.TVReels.like(REEL, '1'), true);
  e.button.attrs['aria-pressed'] = 'true'; e.tick(750);
  result(e, 'like', 'liked');
  e.tick(1250); assert.equal(e.sandbox.TVReels.like(REEL, '2'), true);
  e.button.attrs['aria-pressed'] = 'false'; e.tick(750);
  assert.deepEqual(e.calls.actionResults.at(-1), ['like', REEL, 'unliked', '2']);
});

for (const [label, pressed] of [['Unlike', 'false'], ['已赞', 'false'], ['Like', 'mixed']]) {
  test(`conflicting or unknown pressed state ${label}/${pressed} never guesses`, () => {
    const e = boot(withButton(label, false)); e.button.attrs['aria-pressed'] = pressed;
    assert.equal(e.sandbox.TVReels.like(REEL, '1'), false);
    assert.equal(e.button.clicks, 0); result(e, 'like', 'unavailable');
  });
}

function removeVideo(e) {
  e.article.children = e.article.children.filter(node => node !== e.v);
}

test('official removal after exact preference click confirms old Reel before reporting the next Reel', () => {
  const e = boot(withMore); const { option, menu } = menuOnClick(e);
  const events = [];
  const onResult = e.sandbox.ReelsTv.actionResult, onState = e.sandbox.ReelsTv.state;
  e.sandbox.ReelsTv.actionResult = (...args) => { events.push(['result', ...args]); onResult(...args); };
  e.sandbox.ReelsTv.state = (...args) => { events.push(['state', ...args]); onState(...args); };
  option.onclick = () => {
    menu.css.display = 'none'; removeVideo(e);
    e.sandbox.location.pathname = '/reel/B/'; e.article.append(e.media());
    e.sandbox.TVReels.state();
  };
  assert.equal(e.sandbox.TVReels.notInterested(REEL, '1'), true); e.tick(150);
  result(e, 'notInterested', 'submitted');
  assert.equal(option.clicks, 1); assert.equal(e.more.clicks, 1);
  assert.equal(events[0][0], 'result');
  assert.deepEqual(events[1].slice(0, 2), ['state', 'https://www.instagram.com/reel/B/']);
});

test('delayed official removal is observed without another click or automatic navigation', () => {
  const e = boot(withMore); const { option } = menuOnClick(e);
  assert.equal(e.sandbox.TVReels.notInterested(REEL, '1'), true); e.tick(150);
  assert.equal(e.calls.actionResults.length, 0);
  removeVideo(e); e.sandbox.location.pathname = '/reel/B/'; e.article.append(e.media()); e.tick(150);
  result(e, 'notInterested', 'submitted'); assert.equal(option.clicks, 1); assert.equal(e.more.clicks, 1);
});

for (const boundary of ['user scroll', 'pagehide', 'paused']) {
  test(`removal after ${boundary} cannot confirm an old preference action`, () => {
    const e = boot(withMore); const { option } = menuOnClick(e);
    assert.equal(e.sandbox.TVReels.notInterested(REEL, '1'), true); e.tick(150);
    assert.equal(option.clicks, 1);
    if (boundary === 'user scroll') e.document.events.find(([name]) => name === 'wheel')[1]({ isTrusted: true });
    if (boundary === 'pagehide') e.calls.events.get('pagehide')();
    if (boundary === 'paused') e.sandbox.__reelsTvPaused = true;
    removeVideo(e); e.sandbox.location.pathname = '/reel/B/'; e.article.append(e.media()); e.tick(4500);
    assert.equal(e.calls.actionResults.length, 0); assert.equal(option.clicks, 1);
  });
}

test('replacing the DOM video with the same Reel is not evidence that the Reel was removed', () => {
  const e = boot(withMore); menuOnClick(e);
  assert.equal(e.sandbox.TVReels.notInterested(REEL, '1'), true); e.tick(150);
  removeVideo(e); e.article.append(e.media()); e.tick(4500);
  assert.ok(e.calls.actionResults.every(args => args[2] !== 'submitted'));
});


test('transient official like change that rolls back is never confirmed', () => {
  const e = boot(withButton('Like', false));
  assert.equal(e.sandbox.TVReels.like(REEL, '1'), true);
  e.button.attrs['aria-label'] = 'Unlike'; e.tick(450);
  assert.equal(e.calls.actionResults.length, 0);
  e.button.attrs['aria-label'] = 'Like'; e.tick(3800);
  result(e, 'like', 'unconfirmed'); assert.equal(e.button.clicks, 1);
});

test('after stable like confirmation, later official rollback is reported', () => {
  const e = boot(withButton('Like', false)); e.sandbox.TVReels.like(REEL, '1');
  e.button.attrs['aria-label'] = 'Unlike'; e.tick(750); result(e, 'like', 'liked');
  e.button.attrs['aria-label'] = 'Like'; e.tick(500);
  assert.equal(e.calls.states.at(-1)[1], false); assert.equal(e.button.clicks, 1);
});

for (const direction of ['next', 'previous']) test(`preference pending blocks manual ${direction} without navigating`, () => {
  const e = boot(withMore); const { option } = menuOnClick(e);
  const navigation = e.el('button', { 'aria-label': direction === 'next' ? 'Go to next reel' : 'Go to previous reel' });
  e.body.append(navigation);
  assert.equal(e.sandbox.TVReels.notInterested(REEL, '1'), true); e.tick(150);
  assert.equal(e.sandbox.TVReels[direction](), false); assert.equal(navigation.clicks, 0);
  assert.equal(e.sandbox.TVReels.like(REEL, '2'), false); assert.equal(e.button.clicks, 0);
  removeVideo(e); e.sandbox.location.pathname = '/reel/B/'; e.article.append(e.media()); e.tick(150);
  result(e, 'notInterested', 'submitted'); assert.equal(option.clicks, 1);
});
