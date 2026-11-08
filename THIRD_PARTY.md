# Third-party components

The project source license does not replace the licenses of third-party components.

- **Gradle wrapper**: distributed with Gradle under the [Apache License 2.0](https://github.com/gradle/gradle/blob/master/LICENSE).
- **AndroidX Core and AndroidX WebKit**: their published dependency metadata identifies the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).
- **LibVLC for Android** (`org.videolan.android:libvlc-all:3.6.1`): its published dependency metadata identifies the [GNU Lesser General Public License, version 2.1](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html). Source and license information are available from the [VideoLAN Android project](https://code.videolan.org/videolan/vlc-android/).

The LibVLC Android library release tag is [libvlc-3.6.1](https://github.com/videolan/vlc-android/tree/libvlc-3.6.1), which specifies libvlcjni revision `6ec2bf46d1a5d9938c5ab62fd7294f25e50c7e1a`. Its build scripts specify VLC revision `aed678ebbb47b88de66ce343515b6aadc3ae5c10` and additional patches. These references alone do not establish the complete corresponding source and notices for every component in the published binary. See the [upstream build instructions](https://github.com/videolan/vlc-android/blob/libvlc-3.6.1/README.md). License texts are provided in [licenses](licenses/).

Anyone distributing a built binary must check the applicable licenses of LibVLC, its bundled components and the complete dependency graph, including the corresponding source, notice and replacement requirements. Those checks are not yet complete for a public APK from this project.
