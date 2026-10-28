# Third-party components

Application Kotlin sources are provided under the MIT license in LICENSE.
Third-party libraries retain their respective licenses; they are not relicensed by this project.

- LibVLC Android 3.7.7, VideoLAN: LGPL-2.1 or later. https://code.videolan.org/videolan/libvlcjni and https://code.videolan.org/videolan/vlc-android . Unmodified Maven artifact org.videolan.android:libvlc-all:3.7.7. Native codec components retain their upstream notices/licenses.
- jcifs-ng 2.1.10: LGPL-2.1. https://github.com/AgNO3/jcifs-ng . Unmodified Maven artifact eu.agno3.jcifs:jcifs-ng:2.1.10.
- SLF4J 1.7.36: MIT. https://www.slf4j.org/license.html
- JUnit 4.13.2 (test only): EPL-1.0. https://github.com/junit-team/junit4

Transitive dependencies and their notices are resolved by Gradle. Library license and source links should accompany any subsequent distribution. The device smoke test generates its own video and does not require redistributing an external media sample.
