# Direct media handoff

Fork addition by bearyjd. This separate Android Share entry asks a Cast receiver to fetch and play a media URL through the Default Media Receiver (`CC1AD845`). It does not mirror another app or capture the display.

## Use

1. Share one direct HTTP(S) MP4 or MP3 URL to **Cast shared media**. Text shares infer MIME from `.mp4` or `.mp3` (case insensitive, query allowed). Typed shares may declare `video/mp4` or `audio/mpeg`, including extensionless media endpoints.
2. Review the media host/path and select a discovered receiver (or a receiver address previously saved in the main app).
3. Tap **Cast**. Receipt of the share, rotation and a subsequent incoming share never start playback by themselves. A new share resets selection and needs fresh confirmation. A rejected share leaves the existing receiver controller available.
4. Use Play/Pause or **Stop playback** for the active receiver. **Disconnect controls**, Back, or final activity destruction closes the sender connection without sending media STOP. Selecting another receiver changes the selection; only Cast replaces the controller.

The receiver owns playback after LOAD. Playback may continue when the phone switches apps or disconnects; this depends on the receiver and has not been hardware verified here. The ViewModel retains controls across rotation. There is no handoff foreground service, notification or persistent background control guarantee. Android may stop the app in the background; reopening the share does not reconnect an old session. Stop playback before leaving if stopping is required. A Stop write is a request, not proof that the receiver obeyed it.

## Supported and excluded input

Support is limited to one receiver-accessible direct MP4/MP3 HTTP(S) URL. URL classification performs no HTTP fetch, HEAD, metadata lookup, scraping or DNS lookup of hostnames. Signed query parameters and escaping are forwarded unchanged. MIME and filename classification cannot prove URL availability, codec support, lack of DRM or actual response type; the receiver must be able to fetch and decode the resource without the sharing app's cookies or headers.

Webpages and known YouTube/Spotify links, private app sessions, DRM, local `content:`/`file:` references, authentication credentials in URLs, multiple/ambiguous shares, fragments and unsupported MIME types are rejected. This feature cannot take over a YouTube/Spotify application session and does not relay Android URI grants. Local NAS/LAN HTTP(S) servers are allowed; localhost, loopback, unspecified and link-local IP literals are rejected because they resolve differently on the receiver. Hostname resolution is performed by the receiver, so DNS names are not a security boundary.

The share manifest receives `text/plain`, `audio/*` and `video/*`; unsupported media types produce a recoverable preview error. Existing HLS/WebRTC capture hosts are blocked from handoff so this path does not implicitly stop an app-owned screen cast. Other receiver playback can be replaced only after the user selects that receiver and taps Cast.

## Privacy and protocol

No projection consent, audio permission, encoder or screen-capture service is used by this flow. No URL is intentionally persisted to app preferences or saved ViewModel state, and protocol logs contain sizes and fixed diagnostic fields rather than shared URLs, query tokens or raw receiver messages. The original Android share Intent remains subject to Android's task/lifecycle handling; it is not a secure secret store. A selected receiver receives the full URL including any bearer query token; only share trusted media with trusted receivers.

The inherited Cast TLS transport accepts receiver certificates without authenticating their identity. A malicious LAN peer may impersonate a receiver. HTTP media is not confidential. Use a trusted network; the sender does not proxy media or change its authentication policy.

Direct media LOAD uses `BUFFERED`; HLS callers retain the `LIVE` default and omit `currentTime` to request the live position. The session only accepts the requested receiver application, waits for a valid correlated media status before reporting control readiness, surfaces launch/load failures, and closes resources while retaining errors. Closing the sender uses transport disposal and never issues STOP; Stop playback is an explicit media command.

## Verification limits

JVM/Robolectric tests exercise input classification, Android intent/manifest/activity routing, explicit selection/confirmation, stop versus disconnect, receiver app selection, request/media acknowledgement and cleanup. These checks do not prove actual Chromecast playback, continued playback after disconnect, LAN discovery reliability or codec compatibility. Physical receiver integration remains untested. Build/test evidence is recorded separately from the source checkout by the task orchestrator.
