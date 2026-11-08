function (filename, submitted, folderVerified, verifying, folderId) {
    const visible = element => {
        const r = element.getBoundingClientRect();
        return r.width > 0 && r.height > 0 && r.bottom > 0 && r.top < innerHeight;
    };
    const touch = element => {
        const r = element.getBoundingClientRect();
        return {state: 'touch', x: r.left + r.width / 2, y: r.top + r.height / 2, viewport: innerWidth};
    };
    const text = document.body ? document.body.innerText : '';
    const input = document.querySelector('input#file[type="file"]');
    if (!input) {
        const login = Array.from(document.querySelectorAll('button,a,span')).find(e =>
            visible(e) && /^(登录|立即登录|登录\/注册|登录查看我的云盘)$/.test(e.textContent.trim()));
        return login ? touch(login) : {state: 'login'};
    }
    const path = new URL(location.href).searchParams.get('path') || '/';
    if (!folderVerified || path === '/') {
        if (path !== '/') return {state: 'root'};
        const folder = Array.from(document.querySelectorAll('.pan-list [data-file-id]')).find(e =>
            Array.from(e.querySelectorAll('[title]')).some(n => n.getAttribute('title') === 'Reels TV'));
        if (folder) {
            const link = Array.from(folder.querySelectorAll('a[title]')).find(n => n.getAttribute('title') === 'Reels TV');
            if (link) {
                return {...touch(link), state: 'open-folder', folderId: folder.getAttribute('data-file-id')};
            }
        }
        return {state: 'loading'};
    }
    if (path !== '/Reels TV') return {state: 'folder'};
    const crumbs = Array.from(document.querySelectorAll('.td-breadcrumb__text'));
    if (!crumbs.length || crumbs[crumbs.length - 1].getAttribute('title') !== 'Reels TV') return {state: 'loading'};
    if (Array.from(document.querySelectorAll('.pan-list [data-file-id]')).some(e =>
        e.getAttribute('data-file-id') === folderId)) return {state: 'loading'};
    if (/文件夹不存在|目录不存在/.test(text)) return {state: 'missing-folder'};
    const row = Array.from(document.querySelectorAll('.pan-list [data-file-id]')).find(e =>
        Array.from(e.querySelectorAll('[title]')).some(n => n.getAttribute('title') === filename));
    const done = Array.from(document.querySelectorAll('li')).some(e =>
        e.textContent.includes('已存到云盘') && Array.from(e.querySelectorAll('[title]')).some(n =>
            n.getAttribute('title') === filename));
    if (filename && submitted && verifying && row) return {state: 'saved'};
    if (filename && submitted && !verifying && done) return {state: 'uploaded'};
    if (submitted) return {state: /上传失败/.test(text) ? 'failed' : 'uploading'};
    if (!filename) return {state: 'preparing'};
    const upload = Array.from(document.querySelectorAll('.upload-button')).find(visible);
    if (upload) return touch(upload);
    const add = Array.from(document.querySelectorAll('span,div')).find(e =>
        visible(e) && e.textContent.trim() === '添加');
    return add ? touch(add) : {state: 'loading'};
}
