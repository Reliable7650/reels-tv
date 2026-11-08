# Third-party components

The project source license does not replace the licenses of third-party components.

- **Gradle wrapper**: distributed with Gradle under the [Apache License 2.0](https://github.com/gradle/gradle/blob/master/LICENSE).
- **AndroidX Core and AndroidX WebKit**: their published dependency metadata identifies the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).
- **LibVLC for Android** (`org.videolan.android:libvlc-all:3.6.1`): its published dependency metadata identifies the [GNU Lesser General Public License, version 2.1](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html). Source and license information are available from the [VideoLAN Android project](https://code.videolan.org/videolan/vlc-android/).

This repository references dependencies through Gradle and does not ship the LibVLC Android package or an APK. Anyone distributing a built binary is responsible for checking the applicable licenses of LibVLC, its bundled components and the complete dependency graph, including any source, notice or replacement requirements.
