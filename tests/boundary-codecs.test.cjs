'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const crypto = require('node:crypto');

const asset = require('node:path').resolve(__dirname, '../src/main/assets/reels-tv.js');
const source = fs.readFileSync(asset, 'utf8');
console.log('# asset SHA-256: ' + crypto.createHash('sha256').update(source).digest('hex'));
const script = new vm.Script(source, { filename: asset });
const badTypes = [
  'video/webm; codecs="vp9"',
  'video/mp4; codecs="vp9"',
  'video/mp4; codecs="vp09.00.10.08"',
  'VIDEO/MP4; CODECS="VP09.00.10.08"',
  'video/mp4; codecs="av01.0.05M.08"',
  'video/mp4; codecs="av1"',
  'VIDEO/MP4; CODECS="AV01.0.05M.08"',
  'video/mp4; codecs="hev1.1.6.L93.B0"',
  'video/mp4; codecs="hvc1.1.6.L93.B0"',
  'video/mp4; codecs="hevc"',
  'VIDEO/MP4; CODECS="HVC1.1.6.L93.B0"',
];
const goodTypes = [
  'video/mp4; codecs="avc1.42E01E"',
  'video/mp4; codecs="avc3.640028"',
  'video/mp4; codecs="avc1.42E01E, mp4a.40.2"',
  'audio/mp4; codecs="mp4a.40.2"',
  'audio/aac',
];
const malformedType = 'video/mp4; codecs="';

function environment({ origin = 'https://www.instagram.com', iframe = false,
  href = origin + '/reels/', media = [], mediaSource = true, capabilities = 'full', native = {} } = {}) {
  const calls = { canPlay: [], supported: [], add: [], decoding: [], intervals: [], timeouts: [],
    documentReads: [], queries: [], listeners: [], states: [], sources: [], loginRequired: [], messages: [] };
  const sourceBuffer = {};
  const malformedError = new DOMException('native malformed type', 'NotSupportedError');
  const decodingResult = { supported: true, smooth: true, powerEfficient: true, native: true };
  const decodingPromise = Promise.resolve(decodingResult);

  function HTMLMediaElement() {}
  HTMLMediaElement.prototype.canPlayType = function (...args) {
    calls.canPlay.push({ receiver: this, args });
    if (native.canPlay) return Reflect.apply(native.canPlay, this, args);
    return goodTypes.includes(args[0]) ? (args[0] === 'audio/aac' ? 'maybe' : 'probably') : '';
  };
  function MediaSource() {}
  MediaSource.isTypeSupported = function (...args) {
    calls.supported.push({ receiver: this, args });
    if (native.supported) return Reflect.apply(native.supported, this, args);
    return goodTypes.includes(args[0]);
  };
  MediaSource.prototype.addSourceBuffer = function (...args) {
    calls.add.push({ receiver: this, args });
    if (native.add) return Reflect.apply(native.add, this, args);
    if (args[0] === malformedType) throw malformedError;
    return sourceBuffer;
  };
  const nativeCapabilities = {
    decodingInfo: function (...args) {
      calls.decoding.push({ receiver: this, args });
      if (native.decoding) return Reflect.apply(native.decoding, this, args);
      return decodingPromise;
    },
  };
  const navigator = {};
  if (capabilities === 'full') navigator.mediaCapabilities = nativeCapabilities;
  if (capabilities === 'empty') navigator.mediaCapabilities = {};
  if (capabilities === 'null') navigator.mediaCapabilities = null;
  if (capabilities === 'null-method') navigator.mediaCapabilities = { decodingInfo: null };

  const sandbox = {
    location: new URL(href),
    URL,
    HTMLMediaElement,
    navigator,
    DOMException,
    ReelsTv: {
      state(...args) { calls.states.push(args); },
      source(...args) { calls.sources.push(args); },
      loginRequired(...args) { calls.loginRequired.push(args); },
    },
    postMessage(...args) { calls.messages.push(args); },
    addEventListener(...args) { calls.listeners.push(args); },
    document: new Proxy({
      readyState: 'complete',
      addEventListener(...args) { calls.listeners.push(args); },
      querySelectorAll(selector) {
        assert.ok(selector === 'video' || selector === 'video,audio' || selector === '[role="dialog"]' || selector === '[role="dialog"],[role="menu"]');
        calls.queries.push(selector);
        return selector === 'video,audio' ? media : [];
      },
    }, {
      get(target, property, receiver) {
        calls.documentReads.push(property);
        return Reflect.get(target, property, receiver);
      },
    }),
    setInterval(callback, delay) {
      calls.intervals.push({ callback, delay });
      return 1;
    },
    setTimeout(callback, delay) {
      calls.timeouts.push({ callback, delay });
      return 1;
    },
  };
  if (mediaSource) sandbox.MediaSource = MediaSource;
  sandbox.window = sandbox;
  sandbox.top = iframe ? {} : sandbox;
  const original = {
    canPlay: HTMLMediaElement.prototype.canPlayType,
    supported: MediaSource.isTypeSupported,
    add: MediaSource.prototype.addSourceBuffer,
    decoding: nativeCapabilities.decodingInfo,
  };
  const context = vm.createContext(sandbox);
  return { sandbox, calls, original, HTMLMediaElement, MediaSource, nativeCapabilities,
    sourceBuffer, malformedError, decodingResult, decodingPromise,
    run() { script.runInContext(context, { timeout: 1000 }); } };
}

for (const host of ['instagram.com', 'www.instagram.com']) {
  for (const pathname of ['/reels/', '/reel/Ab_C-123/', '/reels/Ab_C-123/', '/reel/Ab_C-123/embed/']) {
    test(`guard allows HTTPS top-level ${host}${pathname}`, () => {
      const env = environment({ href: `https://${host}${pathname}` });
      env.run();
      assert.ok(env.sandbox.TVReels);
      assert.notStrictEqual(env.HTMLMediaElement.prototype.canPlayType, env.original.canPlay);
      assert.notStrictEqual(env.MediaSource.isTypeSupported, env.original.supported);
      assert.notStrictEqual(env.MediaSource.prototype.addSourceBuffer, env.original.add);
      assert.notStrictEqual(env.nativeCapabilities.decodingInfo, env.original.decoding);
      assert.equal(env.calls.intervals.length, 1);
      assert.deepEqual(env.calls.queries, ['video,audio', '[role="dialog"]', 'video', '[role="dialog"],[role="menu"]']);
      const url = pathname === '/reels/' ? '' : 'https://www.instagram.com/reel/Ab_C-123/';
      assert.deepEqual(env.calls.states, [[url, false, !!url, false]]);
    });
  }
}

for (const host of ['instagram.com', 'www.instagram.com']) {
  for (const pathname of ['/reel/Ab_C-123/', '/reel/Ab_C-123', '/reels/Ab_C-123/',
    '/reels/Ab_C-123', '/reel/Ab_C-123/embed/', '/reel/Ab_C-123/embed']) {
    const pageAllowed = pathname !== '/reel/Ab_C-123/embed';
    test(`without video ${pageAllowed ? 'reports canonical' : 'rejects guarded'} current URL: ${host}${pathname}`, () => {
      const env = environment();
      env.run();
      const before = Object.fromEntries(Object.entries(env.calls).map(([name, entries]) => [name, entries.length]));
      env.sandbox.location.href = `https://${host}${pathname}?next=/reel/Other/#ignored`;
      env.sandbox.TVReels.state();
      if (!pageAllowed) {
        env.calls.intervals[0].callback();
        assert.equal(env.sandbox.TVReels.previous(), false);
        assert.equal(env.sandbox.TVReels.next(), false);
        assert.equal(env.sandbox.TVReels.like(), false);
        assert.deepEqual(Object.fromEntries(Object.entries(env.calls).map(([name, entries]) => [name, entries.length])), before);
        env.sandbox.location.href = `https://${host}${pathname}/?next=/reel/Other/#ignored`;
        env.sandbox.TVReels.state();
      }
      assert.deepEqual(env.calls.states, [
        ['', false, false, false],
        ['https://www.instagram.com/reel/Ab_C-123/', false, true, false],
      ]);
      assert.deepEqual(env.calls.queries, [
        'video,audio', '[role="dialog"]', 'video', '[role="dialog"],[role="menu"]',
        'video,audio', '[role="dialog"]', 'video', '[role="dialog"],[role="menu"]',
      ]);
      env.sandbox.TVReels.state();
      env.calls.intervals[0].callback();
      assert.equal(env.calls.states.length, 2);
    });
  }
}

for (const [href, pageAllowed] of [
  ['https://www.instagram.com/', false],
  ['https://www.instagram.com/reels/?next=/reel/A/#/reel/A/', true],
  ['https://www.instagram.com/p/A/', false],
  ['https://www.instagram.com/reel//', false],
  ['https://www.instagram.com/reel/A.B/', false],
  ['https://www.instagram.com/reel/%41/', false],
  ['https://www.instagram.com/reel/A%2FB/', false],
  ['https://www.instagram.com/reel/A/extra/', false],
  ['https://www.instagram.com/reels/A/embed/', false],
  ['https://www.instagram.com/Reel/A/', false],
  ['https://www.instagram.com/reel/A//', false],
  ['https://www.instagram.com:8443/reel/A/', false],
  ['https://user@www.instagram.com/reel/A/', false],
  ['https://:pass@www.instagram.com/reel/A/', false],
]) {
  test(`without video rejects noncanonical current URL: ${href}`, () => {
    const env = environment({ href: 'https://www.instagram.com/reel/Seed/' });
    env.run();
    const before = Object.fromEntries(Object.entries(env.calls).map(([name, entries]) => [name, entries.length]));
    env.sandbox.location.href = href;
    env.sandbox.TVReels.state();
    const expectedStates = [
      ['https://www.instagram.com/reel/Seed/', false, true, false],
      ...(pageAllowed ? [['', false, false, false]] : []),
    ];
    assert.deepEqual(env.calls.states, expectedStates);
    const expectedQueries = [
      'video,audio', '[role="dialog"]', 'video', '[role="dialog"],[role="menu"]',
      ...(pageAllowed ? ['video,audio', '[role="dialog"]', 'video', '[role="dialog"],[role="menu"]'] : []),
    ];
    assert.deepEqual(env.calls.queries, expectedQueries);
    if (!pageAllowed) {
      env.calls.intervals[0].callback();
      assert.equal(env.sandbox.TVReels.previous(), false);
      assert.equal(env.sandbox.TVReels.next(), false);
      assert.equal(env.sandbox.TVReels.like(), false);
      assert.deepEqual(Object.fromEntries(Object.entries(env.calls).map(([name, entries]) => [name, entries.length])), before);
    }
    env.sandbox.location.href = 'https://www.instagram.com/reel/Recovered/';
    env.sandbox.TVReels.state();
    assert.deepEqual(env.calls.states, [
      ...expectedStates,
      ['https://www.instagram.com/reel/Recovered/', false, true, false],
    ]);
    assert.deepEqual(env.calls.queries, [...expectedQueries, 'video,audio', '[role="dialog"]', 'video', '[role="dialog"],[role="menu"]']);
  });
}

test('without video follows current location changes while keeping sourceless audio muted without reloading', () => {
  const audio = { paused: true, currentSrc: '', src: '',
    load() { assert.fail('empty audio must not reload'); },
    removeAttribute() { assert.fail('empty audio needs no source removal'); } };
  const env = environment({ href: 'https://instagram.com/reel/A/', media: [audio] });
  env.run();
  env.sandbox.location.href = 'https://www.instagram.com:443/reel/B/embed/?ignored=1#ignored';
  env.calls.intervals[0].callback();
  env.sandbox.location.href = 'https://www.instagram.com/reels/';
  env.sandbox.TVReels.state();
  assert.deepEqual(env.calls.states, [
    ['https://www.instagram.com/reel/A/', false, true, false],
    ['https://www.instagram.com/reel/B/', false, true, false],
    ['', false, false, false],
  ]);
  assert.equal(audio.muted, true);
  assert.deepEqual(env.calls.queries, [
    'video,audio', '[role="dialog"]', 'video', '[role="dialog"],[role="menu"]',
    'video,audio', '[role="dialog"]', 'video', '[role="dialog"],[role="menu"]',
    'video,audio', '[role="dialog"]', 'video', '[role="dialog"],[role="menu"]',
  ]);
});

const rejectedOrigins = [
  { origin: 'https://example.com' },
  { origin: 'https://m.instagram.com' },
  { origin: 'https://evil.www.instagram.com' },
  { origin: 'https://instagram.com.example.com' },
  { origin: 'https://www.instagram.com.example.com' },
  { origin: 'https://evilinstagram.com' },
  { origin: 'http://instagram.com' },
  { origin: 'http://www.instagram.com' },
  { origin: 'ftp://instagram.com' },
  { origin: 'https://instagram.com', iframe: true },
  { origin: 'https://www.instagram.com', iframe: true },
];
const rejectedPaths = [
  '/',
  '/accounts/login/',
  '/accounts/login',
  '/accounts/login/?next=/reels/',
  '/accounts/login/two_factor/',
  '/accounts/two_factor_authentication/',
  '/challenge/',
  '/challenge',
  '/challenge/Ab_C-123/',
  '/challenge/?next=/reel/Ab_C-123/',
  '/checkpoint/',
  '/checkpoint',
  '/checkpoint/Ab_C-123/',
  '/checkpoint/?next=/reels/',
  '/accounts/confirm_email/',
  '/accounts/confirm_email/Ab_C-123/',
  '/accounts/verify_email/',
  '/accounts/email_confirmation/',
  '/challenge/email/',
  '/challenge/verify_email/?next=/reels/',
  '/accounts/password/reset/',
  '/accounts/onetap/',
  '/some_user/',
  '/some_user/reels/',
  '/p/A/',
  '/reel/',
  '/reel//',
  '/reel/A.B/',
  '/reel/%41/',
  '/reel/A%2FB/',
  '/reel/A/extra/',
  '/reels/A/embed/',
  '/reel/A/embed',
  '/Reel/A/',
  '/reel/A//',
  '/reels//',
];
for (const options of [
  ...rejectedOrigins,
  ...['instagram.com', 'www.instagram.com'].flatMap(host => rejectedPaths.map(pathname => ({
    href: `https://${host}${pathname}`,
  }))),
]) {
  test(`guard rejects ${options.href || options.origin}${options.iframe ? ' in iframe' : ''} without side effects`, () => {
    const env = environment(options);
    env.run();
    assert.equal(env.sandbox.TVReels, undefined);
    assert.strictEqual(env.HTMLMediaElement.prototype.canPlayType, env.original.canPlay);
    assert.strictEqual(env.MediaSource.isTypeSupported, env.original.supported);
    assert.strictEqual(env.MediaSource.prototype.addSourceBuffer, env.original.add);
    assert.strictEqual(env.nativeCapabilities.decodingInfo, env.original.decoding);
    for (const [name, entries] of Object.entries(env.calls)) assert.equal(entries.length, 0, name);
  });
}

test('canPlayType rejects VP9/AV1/HEVC without calling native', () => {
  const env = environment();
  env.run();
  const receiver = new env.HTMLMediaElement();
  for (const type of badTypes) assert.equal(receiver.canPlayType(type), '', type);
  assert.equal(env.calls.canPlay.length, 0);
});

test('canPlayType passes H264/AAC and native negative results with this and arguments', () => {
  const env = environment();
  env.run();
  const receiver = new env.HTMLMediaElement();
  for (const type of [...goodTypes, 'application/octet-stream']) {
    const expected = goodTypes.includes(type) ? (type === 'audio/aac' ? 'maybe' : 'probably') : '';
    assert.equal(receiver.canPlayType(type), expected, type);
    const call = env.calls.canPlay.at(-1);
    assert.strictEqual(call.receiver, receiver);
    assert.deepEqual(call.args, [type]);
  }
  assert.equal(env.calls.canPlay.length, goodTypes.length + 1);
});

test('MediaSource.isTypeSupported rejects VP9/AV1/HEVC without calling native', () => {
  const env = environment();
  env.run();
  for (const type of badTypes) assert.equal(env.MediaSource.isTypeSupported(type), false, type);
  assert.equal(env.calls.supported.length, 0);
});

test('MediaSource.isTypeSupported preserves H264/AAC results, native receiver and arguments', () => {
  const env = environment();
  env.run();
  for (const type of [...goodTypes, 'application/octet-stream']) {
    assert.equal(env.MediaSource.isTypeSupported(type), goodTypes.includes(type), type);
    const call = env.calls.supported.at(-1);
    assert.strictEqual(call.receiver, env.MediaSource);
    assert.deepEqual(call.args, [type]);
  }
  assert.equal(env.calls.supported.length, goodTypes.length + 1);
});

test('MediaSource.addSourceBuffer rejects VP9/AV1/HEVC without calling native', () => {
  const env = environment();
  env.run();
  const receiver = new env.MediaSource();
  for (const type of badTypes) {
    assert.throws(() => receiver.addSourceBuffer(type), error =>
      error instanceof DOMException && error.name === 'NotSupportedError', type);
  }
  assert.equal(env.calls.add.length, 0);
});

test('MediaSource.addSourceBuffer passes H264/AAC, this, arguments and native result', () => {
  const env = environment();
  env.run();
  const receiver = new env.MediaSource();
  for (const type of goodTypes) {
    assert.strictEqual(receiver.addSourceBuffer(type), env.sourceBuffer, type);
    const call = env.calls.add.at(-1);
    assert.strictEqual(call.receiver, receiver);
    assert.deepEqual(call.args, [type]);
  }
  assert.equal(env.calls.add.length, goodTypes.length);
});

test('MediaSource.addSourceBuffer forwards malformed type and the exact native exception', () => {
  const env = environment();
  env.run();
  const receiver = new env.MediaSource();
  assert.throws(() => receiver.addSourceBuffer(malformedType), error => error === env.malformedError);
  assert.equal(env.calls.add.length, 1);
  assert.strictEqual(env.calls.add[0].receiver, receiver);
  assert.deepEqual(env.calls.add[0].args, [malformedType]);
});

test('MediaCapabilities rejects VP9/AV1/HEVC without calling native', async () => {
  const env = environment();
  env.run();
  for (const type of badTypes) {
    const result = await env.nativeCapabilities.decodingInfo({
      type: 'media-source', video: { contentType: type },
    });
    assert.deepEqual(Object.keys(result).sort(), ['powerEfficient', 'smooth', 'supported']);
    assert.equal(result.supported, false, type);
    assert.equal(result.smooth, false, type);
    assert.equal(result.powerEfficient, false, type);
  }
  assert.equal(env.calls.decoding.length, 0);
});

test('MediaCapabilities passes H264/AAC config identity, native receiver, promise and result', async () => {
  const env = environment();
  env.run();
  for (const type of goodTypes) {
    const config = { type: 'media-source' };
    config[type.startsWith('audio/') ? 'audio' : 'video'] = { contentType: type };
    const promise = env.nativeCapabilities.decodingInfo(config);
    assert.strictEqual(promise, env.decodingPromise);
    assert.strictEqual(await promise, env.decodingResult);
    const call = env.calls.decoding.at(-1);
    assert.strictEqual(call.receiver, env.nativeCapabilities);
    assert.equal(call.args.length, 1);
    assert.strictEqual(call.args[0], config);
  }
  assert.equal(env.calls.decoding.length, goodTypes.length);
});

for (const options of [
  { mediaSource: false },
  { capabilities: 'absent' },
  { capabilities: 'null' },
  { capabilities: 'empty' },
  { capabilities: 'null-method' },
  { mediaSource: false, capabilities: 'absent' },
]) {
  test(`optional APIs missing: ${JSON.stringify(options)}`, () => {
    const env = environment(options);
    assert.doesNotThrow(() => env.run());
    assert.ok(env.sandbox.TVReels);
    assert.deepEqual(env.calls.queries, ['video,audio', '[role="dialog"]', 'video', '[role="dialog"],[role="menu"]']);
    assert.equal(env.calls.intervals.length, 1);
    const receiver = new env.HTMLMediaElement();
    assert.equal(receiver.canPlayType(badTypes[0]), '');
    assert.equal(receiver.canPlayType(goodTypes[0]), 'probably');
    if (options.mediaSource === false) assert.equal(env.sandbox.MediaSource, undefined);
    if (options.capabilities === 'absent') assert.equal(env.sandbox.navigator.mediaCapabilities, undefined);
    if (options.capabilities === 'null') assert.equal(env.sandbox.navigator.mediaCapabilities, null);
    if (options.capabilities === 'empty') assert.deepEqual(env.sandbox.navigator.mediaCapabilities, {});
    if (options.capabilities === 'null-method') assert.equal(env.sandbox.navigator.mediaCapabilities.decodingInfo, null);
  });
}

const codecApis = [
  {
    key: 'canPlay', name: 'canPlayType',
    target: env => [env.HTMLMediaElement.prototype.canPlayType, new env.HTMLMediaElement()],
    input: type => type,
    result: () => 'probably',
  },
  {
    key: 'supported', name: 'MediaSource.isTypeSupported',
    target: env => [env.MediaSource.isTypeSupported, env.MediaSource],
    input: type => type,
    result: () => true,
  },
  {
    key: 'add', name: 'MediaSource.addSourceBuffer',
    target: env => [env.MediaSource.prototype.addSourceBuffer, new env.MediaSource()],
    input: type => type,
    result: env => env.sourceBuffer,
  },
  {
    key: 'decoding', name: 'MediaCapabilities.decodingInfo',
    target: env => [env.nativeCapabilities.decodingInfo, env.nativeCapabilities],
    input: type => ({ type: 'media-source', video: { contentType: type } }),
    result: env => env.decodingPromise,
  },
];

for (const host of ['instagram.com', 'www.instagram.com']) {
  for (const pathname of rejectedPaths) {
    test(`all codec APIs restore native behavior on SPA exit to ${host}${pathname} and filter on return`, async () => {
      const env = environment({ native: {
        canPlay() { return 'probably'; },
        supported() { return true; },
      } });
      env.run();
      const targets = codecApis.map(api => api.target(env));
      const sideEffects = Object.fromEntries(Object.entries(env.calls)
        .filter(([name]) => !codecApis.some(api => api.key === name))
        .map(([name, entries]) => [name, entries.length]));
      const extra = { identity: 'extra argument' };
      const marker = Symbol('extra argument');
      for (const [href, filtering] of [
        [`https://${host}/reels/`, true],
        [`https://${host}${pathname}`, false],
        [`https://${host}/reel/Recovered/`, true],
      ]) {
        env.sandbox.location.href = href;
        for (const [index, api] of codecApis.entries()) {
          const [method, receiver] = targets[index];
          for (const type of [...badTypes, ...goodTypes, 'application/octet-stream']) {
            const input = api.input(type);
            const args = [input, extra, marker];
            const count = env.calls[api.key].length;
            const blocked = filtering && badTypes.includes(type);
            const label = `${api.name}: ${href}: ${type}`;
            if (blocked && api.key === 'add') {
              assert.throws(() => method.apply(receiver, args), error =>
                error instanceof DOMException && error.name === 'NotSupportedError', label);
            } else {
              const result = method.apply(receiver, args);
              if (blocked && api.key === 'decoding') {
                const value = await result;
                assert.deepEqual(Object.keys(value).sort(), ['powerEfficient', 'smooth', 'supported'], label);
                assert.equal(value.supported, false, label);
                assert.equal(value.smooth, false, label);
                assert.equal(value.powerEfficient, false, label);
              } else {
                assert.strictEqual(result, blocked ? (api.key === 'canPlay' ? '' : false) : api.result(env), label);
                if (api.key === 'decoding') assert.strictEqual(await result, env.decodingResult, label);
              }
            }
            assert.equal(env.calls[api.key].length, count + (blocked ? 0 : 1), label);
            if (!blocked) {
              const call = env.calls[api.key].at(-1);
              assert.strictEqual(call.receiver, receiver, label);
              assert.equal(call.args.length, args.length, label);
              for (const [i, argument] of args.entries()) assert.strictEqual(call.args[i], argument, label);
            }
          }
        }
      }
      for (const [name, count] of Object.entries(sideEffects)) assert.equal(env.calls[name].length, count, name);
    });
  }
}

for (const api of codecApis) {
  test(`${api.name} on non-Reels preserves a borrowed receiver and does not read unsupported type.toString`, async () => {
    const env = environment({ native: {
      canPlay() { return 'probably'; },
      supported() { return true; },
    } });
    env.run();
    const [method] = api.target(env);
    env.sandbox.location.href = 'https://www.instagram.com/accounts/login/?next=/reels/';
    const type = Object.defineProperty({}, 'toString', {
      get() { assert.fail('non-Reels wrapper must not read type.toString'); },
    });
    const input = api.input(type);
    const receiver = { borrowed: api.name };
    const extra = {};
    const result = method.call(receiver, input, extra);
    assert.strictEqual(result, api.result(env));
    if (api.key === 'decoding') assert.strictEqual(await result, env.decodingResult);
    assert.equal(env.calls[api.key].length, 1);
    assert.strictEqual(env.calls[api.key][0].receiver, receiver);
    assert.equal(env.calls[api.key][0].args.length, 2);
    assert.strictEqual(env.calls[api.key][0].args[0], input);
    assert.strictEqual(env.calls[api.key][0].args[1], extra);
  });

  test(`${api.name} on non-Reels leaves unsupported type coercion entirely to native`, async () => {
    let reads = 0;
    let conversions = 0;
    const type = Object.defineProperty({}, 'toString', {
      get() {
        reads++;
        return function () {
          assert.strictEqual(this, type);
          conversions++;
          return badTypes[0];
        };
      },
    });
    const env = environment({ native: {
      [api.key](input) {
        assert.equal(String(api.key === 'decoding' ? input.video.contentType : input), badTypes[0]);
        return api.result(env);
      },
    } });
    env.run();
    const [method, receiver] = api.target(env);
    env.sandbox.location.href = 'https://www.instagram.com/challenge/verify_email/';
    const input = api.input(type);
    const result = method.call(receiver, input);
    assert.strictEqual(result, api.result(env));
    if (api.key === 'decoding') assert.strictEqual(await result, env.decodingResult);
    assert.equal(reads, 1);
    assert.equal(conversions, 1);
    assert.equal(env.calls[api.key].length, 1);
    assert.strictEqual(env.calls[api.key][0].receiver, receiver);
    assert.strictEqual(env.calls[api.key][0].args[0], input);
  });

  test(`${api.name} on non-Reels propagates the exact synchronous native exception for a filtered type`, () => {
    const error = new TypeError(`native ${api.name} failure`);
    const env = environment({ native: {
      [api.key]() { throw error; },
    } });
    env.run();
    const [method] = api.target(env);
    const receiver = { borrowed: api.name };
    const input = api.input(badTypes[0]);
    const extra = {};
    env.sandbox.location.href = 'https://www.instagram.com/checkpoint/';
    assert.throws(() => method.call(receiver, input, extra), thrown => thrown === error);
    assert.equal(env.calls[api.key].length, 1);
    assert.strictEqual(env.calls[api.key][0].receiver, receiver);
    assert.equal(env.calls[api.key][0].args.length, 2);
    assert.strictEqual(env.calls[api.key][0].args[0], input);
    assert.strictEqual(env.calls[api.key][0].args[1], extra);
  });

  test(`${api.name} on non-Reels preserves zero arguments without adding undefined`, async () => {
    const env = environment({ native: {
      canPlay() { return 'probably'; },
      supported() { return true; },
    } });
    env.run();
    const [method, receiver] = api.target(env);
    env.sandbox.location.href = 'https://www.instagram.com/some_user/';
    const result = method.call(receiver);
    assert.strictEqual(result, api.result(env));
    if (api.key === 'decoding') assert.strictEqual(await result, env.decodingResult);
    assert.equal(env.calls[api.key].length, 1);
    assert.strictEqual(env.calls[api.key][0].receiver, receiver);
    assert.deepEqual(env.calls[api.key][0].args, []);
  });
}

test('MediaCapabilities on non-Reels preserves the exact native rejected Promise and rejection', async () => {
  const error = new DOMException('native decoding failure', 'InvalidStateError');
  let nativePromise;
  const env = environment({ native: {
    decoding() { return nativePromise = Promise.reject(error); },
  } });
  env.run();
  const method = env.nativeCapabilities.decodingInfo;
  const receiver = { borrowed: 'decodingInfo' };
  const config = { type: 'media-source', video: { contentType: badTypes[0] } };
  const extra = {};
  env.sandbox.location.href = 'https://www.instagram.com/accounts/two_factor_authentication/';
  const result = method.call(receiver, config, extra);
  await assert.rejects(result, thrown => thrown === error);
  assert.strictEqual(result, nativePromise);
  assert.equal(env.calls.decoding.length, 1);
  assert.strictEqual(env.calls.decoding[0].receiver, receiver);
  assert.equal(env.calls.decoding[0].args.length, 2);
  assert.strictEqual(env.calls.decoding[0].args[0], config);
  assert.strictEqual(env.calls.decoding[0].args[1], extra);
});

test('MediaCapabilities on non-Reels does not inspect config.video or video.contentType before native', () => {
  const env = environment();
  env.run();
  const method = env.nativeCapabilities.decodingInfo;
  env.sandbox.location.href = 'https://www.instagram.com/p/A/';
  const configs = [
    Object.defineProperty({}, 'video', {
      get() { assert.fail('non-Reels wrapper must not read config.video'); },
    }),
    { video: Object.defineProperty({}, 'contentType', {
      get() { assert.fail('non-Reels wrapper must not read video.contentType'); },
    }) },
  ];
  for (const config of configs) {
    assert.strictEqual(method.call(env.nativeCapabilities, config), env.decodingPromise);
    assert.strictEqual(env.calls.decoding.at(-1).args[0], config);
  }
  assert.equal(env.calls.decoding.length, configs.length);
});
