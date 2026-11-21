# Model browsing and downloads

The Android app combines a native Printables search/catalog with site-owned search pages for MakerWorld, Printables, Thingiverse, Thangs, and Cults3D. `BrowseScreen` presents the shared query and site selection; `PrintablesClient` fetches native results, while `SiteBrowserScreen` hosts the selected site's HTTPS pages in a WebView. `ModelSite` constrains navigation to the configured site hosts. Authentication and unrelated top-level destinations are handed to the external browser chooser, which excludes this app's own URL handler.

## Download flow

Browser network downloads, captured browser blob downloads, native Printables file downloads, and locally shared files enter `DownloadStore`. The store streams bytes into a bounded temporary file, validates the supported model/archive suffix, hashes content with SHA-256, then atomically records a JSON index beneath `filesDir/downloads/<hash>/`. Identical bytes share one stored item; the first stored name and provenance remain authoritative. Provenance stores a public page URL without query or fragment; local imports use an empty page URL. `DownloadStore.entry(hash)` reads one record directly. `listing()` can omit unreadable records while reporting their count; strict `entries()` is used where the full set must be valid.

`LibraryScreen` opens stored model files directly and routes ZIPs to `ArchiveScreen`. Incoming ZIP shares are copied from a granted content URI into app storage before being indexed, so the archive picker does not depend on a temporary share grant. `isIncomingZip` gives recognized model extensions precedence over ZIP MIME types: a `.3mf` file always follows the model loader because 3MF is ZIP-based. Generic archive selection is limited to `.stl` and `.3mf` entries.

`ModelArchive` lists entries without extracting them, rejects unsafe or duplicate paths, and caps an archive at 1,000 entries, 512 MiB expanded total, 256 MiB per file, and a 200:1 compression ratio. Extraction writes only the selected regular-file entry to a new temporary file, checks declared size and CRC, and deletes partial output on failure or cancellation. It does not use archive entry paths as filesystem paths.

## Browser transfer boundaries

Direct off-site downloads are intercepted only for HTTPS URLs whose path ends in `.stl`, `.3mf`, or `.zip`. Some sites provide downloads as page-owned blobs; the WebView bridge accepts messages only from configured allowed origins and the active page/nonce. Blob data is sent as ordered 32 KiB chunks with a 32 MiB total cap, assembled into a temporary file, and cleared on cancellation or navigation. Opaque off-site CDN links, downloads initiated through arbitrary XHR/fetch flows, and formats outside STL/3MF/ZIP are not generalized into download mechanisms.

The browser is a site interface, not a slicer backend. Android UI/instrumentation CI builds the browser app independently; that APK does not include or validate the normal native slicer APK, JNI integration, slicing, or printer communication. WebView behavior also depends on the Android System WebView implementation.

## Experimental workspace integration

The full-preset app exposes Browse and Library alongside Prepare and Printer.
It constructs its catalog services without initializing the legacy slicer
container. A catalog ViewModel owns downloads and archive extraction across
activity rotation; blob callbacks transfer ownership of their temporary file
before the browser deletes its original path. Transfer cancellation waits for
cleanup, and failures leave the existing Prepare model intact.

A completed transfer can open automatically only in its originating navigation
context while Prepare is idle. Otherwise the saved file remains available for
an explicit Open action. Library files are copied into the bounded Prepare
session before preview extraction or slicing, preserving the saved source.
The model name links to its public HTTPS source without credentials, query or
fragment. Separate native instrumentation exercises catalog search, download,
Library and ZIP selection, rendered preview, full-preset slicing and exact-byte
export in the same experimental app.

## Implementation references

- [BrowseScreen](../../app/src/main/java/com/u1/slicer/browse/BrowseScreen.kt), [PrintablesClient](../../app/src/main/java/com/u1/slicer/browse/printables/PrintablesClient.kt), [ModelSite](../../app/src/main/java/com/u1/slicer/browse/ModelSite.kt)
- [SiteBrowserScreen](../../app/src/main/java/com/u1/slicer/browse/SiteBrowserScreen.kt), [BlobTransferAssembler](../../app/src/main/java/com/u1/slicer/browse/BlobTransferAssembler.kt), [BrowserTransferPolicy](../../app/src/main/java/com/u1/slicer/browse/BrowserTransferPolicy.kt)
- [DownloadStore](../../app/src/main/java/com/u1/slicer/library/DownloadStore.kt), [LibraryScreen](../../app/src/main/java/com/u1/slicer/library/LibraryScreen.kt), [ArchiveScreen](../../app/src/main/java/com/u1/slicer/library/ArchiveScreen.kt), [ModelArchive](../../app/src/main/java/com/u1/slicer/library/ModelArchive.kt)
- [Android WebView security guidance](https://developer.android.com/develop/ui/views/layout/webapps/webview)
