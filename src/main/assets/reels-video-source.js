(function () {
  if (location.protocol !== 'https:' || !['www.instagram.com', 'instagram.com'].includes(location.hostname)
      || !/^\/reel\/[A-Za-z0-9_-]+\/embed\/$/.test(location.pathname)) return null;
  const video = document.querySelector('video');
  if (!video) return null;
  video.pause();
  try {
    const source = new URL(video.currentSrc || video.src);
    const allowed = source.hostname.split('.').every(Boolean)
      && (source.hostname.endsWith('.cdninstagram.com') || source.hostname === 'fbcdn.net'
        || source.hostname.endsWith('.fbcdn.net'));
    return source.protocol === 'https:' && !source.username && !source.password && allowed
      && (!source.port || source.port === '443') ? source.href : null;
  } catch (_) { return null; }
})()
