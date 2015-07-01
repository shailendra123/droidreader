_DroidReader_ is a PDF reader application for Google's Android Operating System for mobile devices.

It uses native libraries (libjpeg, freetype, MuPDF) for the rendering of the Pages.

_DroidReader_ is Free Software and is licenced under the GPL v3. While the author of _DroidReader_ sympathizes with the Free Software movement, the choice of GPL v3 is more or less enforced due to the use of MuPDF, which is licenced under the GPL v3.

Also, _DroidReader_ is an example of how to use the Android Native Development Kit (NDK) in an Android project. So if you are an Android developer, you might be interested in the code just to get an idea how things work in this environment.

The _DroidReader_ author is a friendly and calm person, so please don't hesitate to contact him for any questions. The author is also quite willing to discuss re-licensing questions.

**Please see the README file in the sources in order to get instruction how to build this application!**

**In order to select PDF files, you need a file manager! _DroidReader_ listens for VIEW intents for PDF files (MIME type application/pdf), which should work for almost all file managers. But for opening PDF files from within the _DroidReader_ App, it uses the more specific openintents.org approach, so you need a filemanager compatible to that, e.g. the OI File Manager.**

In order to discuss the software, bugs, development issues, please head over to

> http://groups.google.com/group/droidreader-developer-list

and subscribe to the Mailing list,

> droidreader-developer-list@googlegroups.com

Have fun!