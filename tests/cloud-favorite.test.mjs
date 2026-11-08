import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
const code = fs.readFileSync(new URL('../src/main/assets/cloud-favorite.js', import.meta.url), 'utf8');
const element = (title, visible = true) => ({
    textContent: title,
    getAttribute: key => key === 'title' ? title : null,
    getBoundingClientRect: () => ({left: 10, top: 20, right: 110, bottom: 60, width: visible ? 100 : 0, height: 40}),
});
const row = title => ({getAttribute: key => key === 'data-file-id' ? title : null, querySelectorAll: selector => ['[title]', 'a[title]'].includes(selector) ? [element(title)] : []});
function probe({path = '/Reels TV', input = true, rows = [], crumbs = ['Reels TV'], text = '', upload = [], filename = 'video_12345678.mp4', submitted = false, verified = true, verifying = false, tasks = [], folderId = 'root-Reels TV'} = {}) {
    const document = {
        body: {innerText: text},
        querySelector: selector => selector === 'input#file[type="file"]' && input ? {} : null,
        querySelectorAll: selector => ({
            '.pan-list [data-file-id]': rows.map(row), 'li': tasks.map(t => ({textContent: t.status, querySelectorAll: () => [element(t.title)]})), '.td-breadcrumb__text': crumbs.map(t => element(t)),
            '.upload-button': upload, 'button,a,span': [], 'span,div': [],
        }[selector] || []),
    };
    return JSON.parse(JSON.stringify(vm.runInNewContext(`(${code})(${JSON.stringify(filename)},${submitted},${verified},${verifying},${JSON.stringify(folderId)})`, {
        document, window: {}, location: {href: `https://pan.xunlei.com/?path=${encodeURIComponent(path)}`}, URL, innerWidth: 1440, innerHeight: 800,
    })));
}
test('未登录时不会提供文件或宣称收藏成功', () => assert.equal(probe({input: false, rows: ['video_12345678.mp4']}).state, 'login'));
test('仅有网址参数不代表目标文件夹已存在', () => assert.equal(probe({verified: false}).state, 'root'));
test('从官方根目录确认 Reels TV 存在后才能进入', () => assert.equal(probe({path: '/', verified: false, rows: ['other', 'Reels TV']}).state, 'open-folder'));
test('根目录部分列表不含 Reels TV 时继续等待，不会改存到根目录', () => assert.equal(probe({path: '/', verified: false, rows: ['other']}).state, 'loading'));
test('根目录显示空列表时仍不推断文件夹不存在', () => assert.equal(probe({path: '/', verified: false, text: '暂无文件'}).state, 'loading'));
test('后续列表出现 Reels TV 时可以进入', () => {
    assert.equal(probe({path: '/', verified: false, rows: ['other']}).state, 'loading');
    assert.equal(probe({path: '/', verified: false, rows: ['other', 'Reels TV']}).state, 'open-folder');
});
test('目标目录明确提示不存在时报告文件夹缺失', () => assert.equal(probe({text: '文件夹不存在'}).state, 'missing-folder'));
test('只有当前目录面包屑匹配时才提供上传入口', () => assert.equal(probe({crumbs: ['other'], upload: [element('上传文件')]}).state, 'loading'));
test('相似文件名与上传进度100%不会被当成已保存', () => assert.equal(probe({submitted: true, rows: ['video_12345678.mp4.part'], text: '100%'}).state, 'uploading'));
test('已完成目录必须出现完整且精确的文件名', () => assert.equal(probe({submitted: true, verifying: true, rows: ['video_12345678.mp4']}).state, 'saved'));
test('下载未完成时不点击上传', () => assert.equal(probe({filename: '', upload: [element('上传文件')]}).state, 'preparing'));
test('只点击可见的上传控件并返回页面坐标', () => assert.deepEqual(probe({upload: [element('上传文件', false), element('上传文件')]}), {state: 'touch', x: 60, y: 40, viewport: 1440}));

test('上传中的临时目录行不会触发成功或重载', () => assert.equal(probe({submitted: true, rows: ['video_12345678.mp4'], tasks: [{title: 'video_12345678.mp4', status: '正在上传 12%'}]}).state, 'uploading'));
test('只有本文件已存到云盘后才要求重新核对', () => assert.equal(probe({submitted: true, tasks: [{title: 'video_12345678.mp4', status: '已存到云盘'}]}).state, 'uploaded'));
test('其他任务完成不能把本文件标成完成', () => assert.equal(probe({submitted: true, tasks: [{title: 'other.mp4', status: '已存到云盘'}]}).state, 'uploading'));

test('重载后若仍是根目录内容，不能把网址和面包屑当成目标目录', () => assert.equal(probe({folderId: 'Reels TV', rows: ['Reels TV', 'video_12345678.mp4'], verifying: true, submitted: true}).state, 'loading'));

test('上传前已有同名文件不能当成本次成功', () => assert.notEqual(probe({rows: ['video_12345678.mp4']}).state, 'uploaded'));
test('上传前历史任务完成不能证明本次已提交', () => assert.notEqual(probe({tasks: [{title: 'video_12345678.mp4', status: '已存到云盘'}]}).state, 'uploaded'));
