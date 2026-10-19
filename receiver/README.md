# ScreenCast WebRTC receiver

<!-- Fork modification (2026-10-18): document isolation, tests and deployment limits. -->

Static HTML + JS loaded by the Chromecast's Chrome instance. Accepts a WebRTC
offer from the Android sender over a custom Cast namespace and renders the
remote video track fullscreen.

The app ships with a default App ID (`9098830C`) pointing at the upstream
hosted receiver. Changes in this fork do not update that hosted receiver.
To use this fork's isolation fixes, deploy these files to your own HTTPS host,
register a custom receiver, and configure its App ID in the Android app.

## Hosting your own receiver

Serve `index.html` and `receiver.js` together over HTTPS at a stable URL.
For GitHub Pages, publish the contents of `receiver/` as a Pages artifact or
at the root of your publishing branch; branch publishing supports the root
or `docs/` directory, so `receiver/` is not a source-directory selector; see
[Pages publishing sources](https://docs.github.com/en/pages/getting-started-with-github-pages/configuring-a-publishing-source-for-your-github-pages-site).
The resulting URL might be `https://<user>.github.io/<repo>/`.

After the URL is live, register it at <https://cast.google.com/publish/>:

1. Add a new app → "Custom receiver".
2. Paste the receiver URL.
3. Add the Chromecast's serial number under "Devices" so you can test without
   publishing.
4. Google issues an 8-character App ID. Paste it into the app's WebRTC screen
   to override the default.

Follow Google's [custom receiver setup](https://developers.google.com/cast/docs/web_receiver/basic)
and [registration](https://developers.google.com/cast/docs/registration) instructions.
This source change does not deploy a site or register an App ID.

## Session ownership

The first valid OFFER owns the receiver until that sender sends BYE,
CAF reports its disconnect, negotiation fails, or its peer enters the terminal
`failed` connection state. A transient WebRTC `disconnected` state keeps ownership
while the peer may recover. Other senders' OFFER, ICE and BYE messages are ignored.
An OFFER from the owner replaces its peer and invalidates the previous local
negotiation generation. Late promises, peer events and queued ICE belonging to
that generation cannot signal or alter the replacement stream. ICE received
during remote SDP setup waits on its owning peer.

The existing Android wire format is unchanged. It contains no session or
negotiation token, so a delayed ICE or BYE received from the **same sender ID**
after a replacement OFFER cannot be distinguished from current signaling.
Local generation checks protect receiver continuations; they cannot solve that
wire-protocol ambiguity. Cross-generation wire tokens require a coordinated
sender and receiver change.

CAF's [SENDER_DISCONNECTED event](https://developers.google.com/cast/docs/reference/web_receiver/cast.framework.system#EventType)
supplies the sender identity documented in
[SenderDisconnectedEvent](https://developers.google.com/cast/docs/reference/web_receiver/cast.framework.system.SenderDisconnectedEvent).

## Tests

From the repository root, with Node.js 22 or newer and no dependencies:

```sh
node --check receiver/receiver.js
node --test receiver/receiver.test.cjs
```

Tests execute `receiver.js` in a VM and replace the CAF, WebRTC and browser
boundaries. They verify message ownership, stale negotiation isolation and
resource cleanup. They do not establish real CAF delivery, codec compatibility,
playback latency, HTTPS deployment or device behavior. Verify those on the
registered custom receiver before relying on it.

## Files

- `index.html` — single `<video>` element, loads the CAF Receiver framework
  from Google's CDN and then `receiver.js`.
- `receiver.js` — registers our custom namespace, handles OFFER/ANSWER/ICE.

## Namespace

`urn:x-cast:io.github.ddagunts.screencast.webrtc` — keep in sync with
`WEBRTC_NAMESPACE` in
`app/src/main/java/io/github/ddagunts/screencast/webrtc/WebRtcConfig.kt`.
