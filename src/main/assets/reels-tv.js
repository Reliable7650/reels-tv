(() => {
  'use strict';
  const reelsPage = () => location.protocol === 'https:'
    && !location.username && !location.password && (!location.port || location.port === '443')
    && ['www.instagram.com', 'instagram.com'].includes(location.hostname)
    && /^\/(?:reels\/?|reels?\/[A-Za-z0-9_-]+\/?|reel\/[A-Za-z0-9_-]+\/embed\/)$/.test(location.pathname);
  if (!reelsPage() || window.top !== window || window.TVReels) return;

  const unsupported = type => /(?:vp0?9|vp9|av01|av1|hev1|hvc1|hevc|video\/webm)/i.test(String(type));
  const canPlayType = HTMLMediaElement.prototype.canPlayType;
  HTMLMediaElement.prototype.canPlayType = function(type) {
    return reelsPage() && unsupported(type) ? '' : canPlayType.apply(this, arguments);
  };
  if (window.MediaSource) {
    const supported = MediaSource.isTypeSupported;
    MediaSource.isTypeSupported = function(type) {
      return reelsPage() && unsupported(type) ? false : supported.apply(this, arguments);
    };
    const add = MediaSource.prototype.addSourceBuffer;
    MediaSource.prototype.addSourceBuffer = function(type) {
      if (reelsPage() && unsupported(type)) throw new DOMException('Unsupported television video format', 'NotSupportedError');
      return add.apply(this, arguments);
    };
  }
  if (navigator.mediaCapabilities && navigator.mediaCapabilities.decodingInfo) {
    const capabilities = navigator.mediaCapabilities;
    const decodingInfo = capabilities.decodingInfo;
    capabilities.decodingInfo = function(config) {
      return reelsPage() && config && config.video && unsupported(config.video.contentType)
        ? Promise.resolve({supported: false, smooth: false, powerEfficient: false}) : decodingInfo.apply(this, arguments);
    };
  }

  let reported = '';
  let changingFrom = '';
  let changeStarted = 0;
  let lastLikeAt = null;
  let pendingAction = null;
  const likeLabels = /^(Like|Unlike|赞|赞了|已赞|取消赞|点赞|取消点赞)$/i;
  const unlikeLabels = /^(Unlike|赞了|已赞|取消赞|取消点赞)$/i;
  const moreLabels = /^(More|More options|更多|更多选项)$/i;
  const seen = new WeakSet();
  const sourceOwners = new Map();
  let sourceCandidate = null;
  let changingMedia = null;
  function pauseWebMedia(media) {
    media.muted = true;
    if (!media.paused) media.pause();
  }
  document.addEventListener('play', event => {
    if (!reelsPage()) return;
    const media = event.target;
    if (media && typeof media.pause === 'function') pauseWebMedia(media);
  }, true);
  function visibleArea(node) {
    const r = node.getBoundingClientRect();
    if (getComputedStyle(node).visibility === 'hidden' || getComputedStyle(node).display === 'none') return 0;
    return Math.max(0, Math.min(r.right, innerWidth) - Math.max(r.left, 0))
      * Math.max(0, Math.min(r.bottom, innerHeight) - Math.max(r.top, 0));
  }
  function video() {
    return [...document.querySelectorAll('video')].filter(v => visibleArea(v) > 0)
      .sort((a, b) => visibleArea(b) - visibleArea(a))[0] || null;
  }
  function container(v) {
    const article = v.closest('article');
    if (article) return article;
    for (let p = v.parentElement; p && p !== document.body; p = p.parentElement) {
      if (p.querySelectorAll('video').length === 1
          && [...p.querySelectorAll('button,[role="button"]')].some(node => controlLabel(node).some(
            label => likeLabels.test(label) || moreLabels.test(label)))) return p;
    }
    return v.parentElement;
  }
  function canonical(value) {
    try {
      const u = new URL(value, location.href);
      const match = u.pathname.match(/^\/reels?\/([A-Za-z0-9_-]+)\/?$/)
        || u.pathname.match(/^\/reel\/([A-Za-z0-9_-]+)\/embed\/?$/);
      return u.protocol === 'https:' && !u.username && !u.password && (!u.port || u.port === '443')
        && ['www.instagram.com', 'instagram.com'].includes(u.hostname) && match
        ? 'https://www.instagram.com/reel/' + match[1] + '/' : '';
    } catch (_) { return ''; }
  }
  function reelUrl(v) {
    const scope = container(v);
    const links = [...scope.querySelectorAll('a[href]')].map(a => canonical(a.href)).filter(Boolean);
    const unique = [...new Set(links)];
    if (unique.length === 1) return unique[0];
    return canonical(location.href);
  }
  function controlLabel(button) {
    const labels = [button.getAttribute('aria-label'),
      ...[...button.querySelectorAll('svg[aria-label]')].filter(node => visibleArea(node) > 0)
        .map(node => node.getAttribute('aria-label'))].filter(Boolean).map(label => label.trim());
    return labels.length ? [...new Set(labels)] : [(button.textContent || '').trim()];
  }
  function controlRailLink(link, reel) {
    if (!reel) return false;
    if (canonical(link.href) === reel) return true;
    try {
      const url = new URL(link.href, location.href);
      return url.protocol === 'https:' && !url.username && !url.password && (!url.port || url.port === '443')
        && ['www.instagram.com', 'instagram.com'].includes(url.hostname)
        && /^\/reels\/audio\/[0-9]+\/?$/.test(url.pathname);
    } catch (_) { return false; }
  }
  function actionRail(node, v) {
    const reel = reelUrl(v);
    if ([...node.querySelectorAll('a[href]')].some(link => !controlRailLink(link, reel))) return false;
    const labels = [...node.querySelectorAll('button,[role="button"]')]
      .filter(control => visibleArea(control) > 0).flatMap(controlLabel);
    return labels.some(label => /^(Comment|评论)$/i.test(label))
      && labels.some(label => /^(Share|Share Post|分享|分享帖子)$/i.test(label));
  }
  function sameControlScope(v, button) {
    if (button.closest('article') !== v.closest('article')
        || button.closest('[role="dialog"],[role="menu"]') !== v.closest('[role="dialog"],[role="menu"]')) return false;
    const list = button.closest('ul,ol,[role="list"]');
    if (list !== v.closest('ul,ol,[role="list"]')) {
      if (!list || list.querySelector('video') || /^(Comments|评论|留言)$/i.test(list.getAttribute('aria-label') || '')
          || !actionRail(list, v)) return false;
    } else if (button.closest('li,[role="listitem"]') !== v.closest('li,[role="listitem"]')) return false;
    const scope = container(v);
    for (let parent = button.parentElement; parent && parent !== scope; parent = parent.parentElement) {
      if (parent.querySelector('a[href]') && !parent.querySelector('video')
          && [...parent.querySelectorAll('a[href]')].some(link => !controlRailLink(link, reelUrl(v)))) return false;
      if (actionRail(parent, v)) return true;
    }
    return button.parentElement === scope;
  }
  function currentControls(v, labels) {
    const scope = container(v);
    if (!scope || scope.querySelectorAll('video').length !== 1) return [];
    return [...scope.querySelectorAll('button,[role="button"]')].filter(button => {
      const names = controlLabel(button);
      return names.length > 0 && names.every(name => labels.test(name)) && visibleArea(button) > 0
        && sameControlScope(v, button);
    });
  }
  function likeButton(v) {
    const buttons = currentControls(v, likeLabels);
    return buttons.length === 1 ? buttons[0] : null;
  }
  function likeState(v) {
    const button = likeButton(v);
    if (!button) return '';
    const states = new Set(controlLabel(button).map(label => unlikeLabels.test(label) ? 'liked' : 'unliked'));
    if (states.size !== 1) return '';
    const state = [...states][0];
    const pressed = button.getAttribute('aria-pressed');
    if (pressed === null) return state;
    if (pressed !== 'true' && pressed !== 'false' || state === 'liked' && pressed === 'false') return '';
    // Like/赞/点赞 can be a fixed toggle name; Unlike/已赞 are explicit active-state labels.
    return pressed === 'true' ? 'liked' : 'unliked';
  }
  function liked(v) { return likeState(v) === 'liked'; }
  function enabled(button) {
    return !button.disabled && button.getAttribute('aria-disabled') !== 'true' && !button.closest('[inert]');
  }
  function loginDialog() {
    return [...document.querySelectorAll('[role="dialog"]')].some(dialog => visibleArea(dialog) > 0
      && [...dialog.querySelectorAll('button,a,[role="button"]')]
        .some(button => /^(Log in|Login|登录|登入|Verify identity|验证身份|确认身份)$/i.test(
          (button.textContent || '').trim())));
  }
  function actionReel(v) {
    if (!v) return '';
    const scope = container(v);
    if (!scope || scope.querySelectorAll('video').length !== 1) return '';
    const urls = [...new Set([...scope.querySelectorAll('a[href]')].map(a => canonical(a.href)).filter(Boolean))];
    const route = canonical(location.href);
    if (urls.length > 1 || urls.length === 1 && route && urls[0] !== route) return '';
    return urls[0] || route;
  }
  function actionAllowed(op = null, ignoreMenus = false) {
    if (!reelsPage() || document.hidden || window.__reelsTvPaused || changingFrom || loginDialog()) return false;
    if (ignoreMenus) return true;
    const menus = visibleMenus();
    if (!menus.length) return true;
    return !!op && op.action === 'notInterested' && menus.length === 1
      && (op.menu ? menus[0] === op.menu : !op.menus.has(menus[0]));
  }
  function sameActionReel(op, ignoreMenus = false) {
    return actionAllowed(op, ignoreMenus) && location.pathname === op.path && video() === op.video
      && container(op.video) === op.scope && actionReel(op.video) === op.reel
      && (op.video.currentSrc || op.video.src || '') === op.source;
  }
  function cancelActions() { pendingAction = null; reported = ''; }
  function actionResult(op, outcome, preferenceConfirmed = false) {
    if (pendingAction !== op || !(preferenceConfirmed ? actionAllowed(op)
        : sameActionReel(op, outcome === 'unavailable' || outcome === 'unconfirmed'))) { cancelActions(); return; }
    pendingAction = null;
    if (window.ReelsTv && typeof ReelsTv.actionResult === 'function') {
      ReelsTv.actionResult(op.action, op.reel, outcome, op.requestId);
    }
  }
  function beginAction(action, expectedReel, requestId) {
    if (!actionAllowed() || typeof requestId !== 'string' || !requestId || pendingAction) return null;
    const v = video();
    const reel = actionReel(v);
    if (!reel || expectedReel !== reel) return null;
    const op = {action, reel, requestId, video: v, scope: container(v), path: location.pathname,
      source: v.currentSrc || v.src || '', started: Date.now()};
    pendingAction = op;
    return op;
  }
  function visibleMenus() {
    return [...document.querySelectorAll('[role="dialog"],[role="menu"]')]
      .filter(node => visibleArea(node) > 0)
      .filter(node => !node.parentElement.closest('[role="dialog"],[role="menu"]'));
  }
  const preferenceFeedback = /^(We'll show (?:you )?fewer (?:posts|reels) like this\.?|Post hidden\.?|Reel hidden\.?|帖子已隐藏|已隐藏帖子|已隐藏视频|我们将减少显示此类帖子|我们会减少推荐此类帖子)[。！!]?$/i;
  function feedback() {
    return new Map([...document.querySelectorAll('[role="status"],[role="alert"],[aria-live="polite"],[aria-live="assertive"]')]
      .filter(node => visibleArea(node) > 0).map(node => [node, (node.textContent || '').trim()]));
  }
  function confirmPreference(op) {
    if (pendingAction !== op || op.action !== 'notInterested' || !op.optionClicked || !actionAllowed(op)) return false;
    const confirmed = [...feedback()].some(([, text]) => preferenceFeedback.test(text)
      && !op.feedbackTexts.has(text));
    const removed = ![...document.querySelectorAll('video')].includes(op.video)
      && ![...document.querySelectorAll('video')].some(v => visibleArea(v) > 0 && actionReel(v) === op.reel);
    if (!confirmed && !removed) return false;
    actionResult(op, 'submitted', true);
    return true;
  }
  function observeAction(op) {
    if (pendingAction !== op) return;
    if (op.action === 'notInterested' && op.optionClicked) {
      if (confirmPreference(op)) { report(); return; }
      if (actionAllowed(op) && !sameActionReel(op, true)) {
        if (Date.now() - op.started < 4000) setTimeout(() => observeAction(op), 150);
        else { cancelActions(); report(); }
        return;
      }
    }
    if (!sameActionReel(op, true)) { cancelActions(); return; }
    if (!actionAllowed(op)) { actionResult(op, 'unavailable'); return; }
    if (op.action === 'like') {
      const state = likeState(op.video);
      if (state && state !== op.before) {
        if (op.observedState !== state) { op.observedState = state; op.observedSince = Date.now(); }
        else if (Date.now() - op.observedSince >= 600) { actionResult(op, state); return; }
      } else { op.observedState = ''; }
    } else if (!op.optionClicked) {
      const menus = visibleMenus().filter(menu => !op.menus.has(menu));
      if (menus.length > 1) { actionResult(op, 'unavailable'); return; }
      if (menus.length === 1) {
        op.menu = menus[0];
        const options = [...menus[0].querySelectorAll('button,[role="button"],[role="menuitem"]')]
          .filter(node => visibleArea(node) > 0 && controlLabel(node).every(
            label => /^(Not interested|不感兴趣)$/i.test(label)));
        if (options.length > 1 || options.length === 1 && !enabled(options[0])) {
          actionResult(op, 'unavailable'); return;
        }
        if (options.length === 1) {
          op.feedbackTexts = new Set(feedback().values());
          op.optionClicked = true;
          options[0].click();
          if (pendingAction !== op) return;
          if (confirmPreference(op)) { report(); return; }
          if (!sameActionReel(op)) {
            setTimeout(() => observeAction(op), 150);
            return;
          }
        }
      }
    }
    if (Date.now() - op.started >= 4000) {
      actionResult(op, op.action === 'notInterested' && !op.optionClicked ? 'unavailable' : 'unconfirmed');
      return;
    }
    setTimeout(() => observeAction(op), 150);
  }
  function settledSource(v, url) {
    const source = v.currentSrc || v.src || '';
    if (!source || !url || v.currentSrc && v.src && v.currentSrc !== v.src
        || sourceOwners.has(source) && sourceOwners.get(source) !== url
        || changingMedia && changingMedia.node === v && changingMedia.source === source) {
      sourceCandidate = null;
      return '';
    }
    if (!sourceCandidate || sourceCandidate.node !== v || sourceCandidate.url !== url
        || sourceCandidate.source !== source) {
      sourceCandidate = {node: v, url, source, since: Date.now()};
      return '';
    }
    if (Date.now() - sourceCandidate.since < 650) return '';
    sourceOwners.set(source, url);
    if (sourceOwners.size > 16) sourceOwners.delete(sourceOwners.keys().next().value);
    changingMedia = null;
    return source;
  }
  function report() {
    if (pendingAction && pendingAction.action === 'notInterested' && pendingAction.optionClicked) {
      const op = pendingAction;
      if (!confirmPreference(op) && actionAllowed(op) && !sameActionReel(op, true)) return;
    }
    if (pendingAction && !sameActionReel(pendingAction, true)) cancelActions();
    if (!reelsPage()) return;
    document.querySelectorAll('video,audio').forEach(pauseWebMedia);
    if (document.hidden || window.__reelsTvPaused) return;
    if (loginDialog() && window.ReelsTv && typeof ReelsTv.loginRequired === 'function') {
      sourceCandidate = null;
      ReelsTv.loginRequired();
      return;
    }
    const v = video();
    if (v) {
      v.style.setProperty('object-fit', 'contain', 'important');
      if (!seen.has(v)) {
        seen.add(v);
        ['play', 'pause', 'loadeddata', 'error', 'ended'].forEach(name => v.addEventListener(name, report));
      }
    }
    const url = v ? reelUrl(v) : canonical(location.href);
    if (!v || !url) sourceCandidate = null;
    if (changingFrom) {
      if ((!url || url === changingFrom) && Date.now() - changeStarted < 8000) return;
      changingFrom = '';
    }
    const state = [url, v ? liked(v) : false, !!url, !!v && !v.paused && !v.ended && !v.error];
    const encoded = JSON.stringify(state);
    if (encoded !== reported && window.ReelsTv && typeof ReelsTv.state === 'function') {
      reported = encoded;
      ReelsTv.state(...state);
    }
    if (!visibleMenus().length && window.ReelsTv && typeof ReelsTv.controls === 'function') {
      const like = v && likeButton(v);
      const menus = v ? currentControls(v, moreLabels) : [];
      const available = !!v && !!url && actionReel(v) === url && actionAllowed();
      const capabilities = [available && !!like && !!likeState(v) && enabled(like),
        available && menus.length === 1 && enabled(menus[0])];
      const labels = [];
      if (v && actionReel(v) === url) {
        for (const icon of container(v).querySelectorAll('svg[aria-label]')) {
          if (visibleArea(icon) <= 0 || icon.closest('article') !== v.closest('article')
              || icon.closest('[role="dialog"],[role="menu"]') !== v.closest('[role="dialog"],[role="menu"]')) continue;
          const label = (icon.getAttribute('aria-label') || '').replace(/[\u0000-\u001f\u007f-\u009f]/g, '').trim().slice(0, 64);
          if (!label || labels.includes(label) || JSON.stringify([...labels, label]).length > 1200) continue;
          labels.push(label);
          if (labels.length === 16) break;
        }
      }
      ReelsTv.controls(JSON.stringify(labels), ...capabilities);
    }
    if (v && url && window.ReelsTv && typeof ReelsTv.source === 'function') {
      const source = settledSource(v, url);
      if (source) ReelsTv.source(url, source);
    }
  }
  function change(direction) {
    if (pendingAction && pendingAction.action === 'notInterested') return false;
    cancelActions();
    if (!actionAllowed() || /\/embed\/?$/.test(location.pathname)) return false;
    const label = direction < 0 ? /^(Go to previous reels?|前往上一条 Reels)$/i
      : /^(Go to next reels?|前往下一条 Reels)$/i;
    const button = [...document.querySelectorAll('button[aria-label],[role="button"][aria-label]')]
      .find(node => label.test(node.getAttribute('aria-label') || '') && visibleArea(node) > 0
        && !node.disabled && node.getAttribute('aria-disabled') !== 'true');
    if (!button) return false;
    const v = video();
    changingFrom = v ? reelUrl(v) : canonical(location.href);
    changingMedia = v ? {node: v, source: v.currentSrc || v.src || ''} : null;
    if (changingFrom && changingMedia && changingMedia.source && !sourceOwners.has(changingMedia.source)) {
      sourceOwners.set(changingMedia.source, changingFrom);
      if (sourceOwners.size > 16) sourceOwners.delete(sourceOwners.keys().next().value);
    }
    sourceCandidate = null;
    changeStarted = Date.now();
    reported = '';
    if (window.ReelsTv) ReelsTv.state('', false, true, false);
    button.click();
    setTimeout(report, 650);
    return true;
  }
  window.TVReels = {
    previous: () => change(-1),
    next: () => change(1),
    like: (expectedReel, requestId) => {
      const now = Date.now();
      if (lastLikeAt !== null && now - lastLikeAt < 2000) return false;
      const op = beginAction('like', expectedReel, requestId);
      if (!op) return false;
      const button = likeButton(op.video);
      op.before = likeState(op.video);
      if (!button || !op.before || !enabled(button)) { actionResult(op, 'unavailable'); return false; }
      lastLikeAt = now;
      button.click();
      setTimeout(() => observeAction(op), 150);
      return true;
    },
    notInterested: (expectedReel, requestId) => {
      const op = beginAction('notInterested', expectedReel, requestId);
      if (!op) return false;
      const buttons = currentControls(op.video, moreLabels);
      op.menus = new Set(visibleMenus());
      if (buttons.length !== 1 || !enabled(buttons[0]) || op.menus.size) {
        actionResult(op, 'unavailable'); return false;
      }
      buttons[0].click();
      setTimeout(() => observeAction(op), 150);
      return true;
    },
    cancelActions,
    state: report
  };
  document.addEventListener('visibilitychange', () => { if (document.hidden) cancelActions(); });
  ['wheel', 'touchstart', 'pointerdown', 'keydown'].forEach(name => document.addEventListener(name, event => {
    if (event.isTrusted) cancelActions();
  }, true));
  window.addEventListener('pagehide', cancelActions);
  window.addEventListener('popstate', cancelActions);
  window.addEventListener('hashchange', cancelActions);
  setInterval(report, 500);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', report, {once: true});
  else report();
})();
