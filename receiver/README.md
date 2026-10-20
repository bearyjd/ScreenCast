# ScreenCast WebRTC receiver

<!-- Fork modification by bearyjd (2026-10-19): document receiver-only artifact preparation, provenance and manual hosting. -->

Static HTML + JS loaded by the Chromecast's Chrome instance. Accepts a WebRTC
offer from the Android sender over a custom Cast namespace and renders the
remote video track fullscreen.

The app ships with a default App ID (`9098830C`) pointing at the upstream
hosted receiver. Changes in this fork do not update that hosted receiver.
To use this fork's isolation fixes, deploy these files to your own HTTPS host,
register a custom receiver, and configure its App ID in the Android app.

## Obtain a receiver-only package

In a successful **CI → Receiver tests & artifact** job, download the ordinary
Actions artifact named `receiver-<full-source-commit>` and extract its ZIP into
an empty directory. Choose a reviewed commit; a pull request artifact is
unreviewed code. Packaging does not deploy a site or enable GitHub Pages.

The extracted directory contains exactly `index.html`, `receiver.js`, `LICENSE`,
`ATTRIBUTION.txt` and `build-info.json`. Keep these files together when hosting
or redistributing. The manifest records the full source commit, fork/upstream
URLs, upstream base and SHA-256 hashes of the four local files. It contains no
build timestamp or machine path. It does not hash itself or authenticate the
producer: obtain the manifest from the reviewed CI run, then compare its
`sourceCommit` with that run's commit.

To build locally from a clean committed checkout with Node.js 22+ and Git:

```sh
node --test receiver/receiver.test.cjs scripts/package-receiver.test.cjs
node scripts/package-receiver.cjs /absolute/path/to/new-or-empty-directory
```

Use an output directory outside the checkout whose parent already exists.
The builder rejects nonempty destinations and symlinks without deleting their
contents, source-tree destinations (including directory aliases), missing Git
metadata and relevant input bytes that differ from HEAD. It copies only
committed allowlisted bytes; unrelated local files are never packaged.
`GITHUB_SHA`, when present, must be the full commit matching Git HEAD. Relevant
inputs include the packager itself, HTML, JS, license and attribution. Commit
reviewed changes before packaging; the builder intentionally refuses to label
uncommitted receiver changes as an existing commit.

Verify every extracted local file from inside the extracted directory:

```sh
node -e 'const fs=require("node:fs"),c=require("node:crypto"),m=JSON.parse(fs.readFileSync("build-info.json"));for(const [f,h] of Object.entries(m.files)){if(c.createHash("sha256").update(fs.readFileSync(f)).digest("hex")!==h)throw Error("Checksum mismatch: "+f)}console.log("Local checksums match")'
```

Google CAF remains an external HTTPS script hosted by Google, as
[Google requires](https://developers.google.com/cast/docs/web_receiver/basic).
Its URL is recorded under `externalScripts`; the CDN code can change
independently. This artifact pins local bytes, not the whole receiver runtime.
Do not download CAF into the package or self-host it.

## Hosting your own receiver

After reviewing and verifying the extracted package, manually upload all five
files to an HTTPS host you control. Serve `index.html` at a stable root URL and
keep `receiver.js` adjacent. Confirm both URLs return the expected files and
compare the hosted JS bytes against the manifest before registering a receiver.
The builder and CI never configure hosting, change the default App ID, or
publish a Google Cast application.
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
Site publication and Google Cast application publication are separate owner
actions. Device registration permits development testing; making a Cast app
available publicly is a separate decision. This source change does not deploy
a site, register an App ID or publish a Cast application.

Before relying on the hosted receiver, record the reviewed source commit,
receiver URL, your own App ID, device model and VPN state. On the registered
test device check capture consent, visible video, Stop and disappearance of the
capture indicator, retry after failure, receiver disconnect and network routing.
Measure latency on hardware before claiming improvement. HTTPS hosting does
not authenticate the inherited Cast control channel or prove VPN routing.

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
node --test receiver/receiver.test.cjs scripts/package-receiver.test.cjs
```

Tests execute `receiver.js` in a VM and replace the CAF, WebRTC and browser
boundaries. They verify message ownership, stale negotiation isolation and
resource cleanup. Packaging tests use disposable Git fixture histories and
filesystem boundaries to check allowlisting, provenance, hashes, determinism,
asset references and refusal of unsafe inputs/output. They do not establish real CAF delivery, codec compatibility,
playback latency, HTTPS deployment or device behavior. Verify those on the
registered custom receiver before relying on it.

## Files

- `index.html` — single `<video>` element, loads the CAF Receiver framework
  from Google's CDN and then `receiver.js`.
- `receiver.js` — registers our custom namespace, handles OFFER/ANSWER/ICE.
- `ATTRIBUTION.txt` and root `LICENSE` — retained credits and Apache-2.0 terms.
- `build-info.json` — generated source identity and hashes in the package.
- `scripts/package-receiver.cjs` and its test — repository build tooling; not
  included in the deployable artifact.

## Namespace

`urn:x-cast:io.github.ddagunts.screencast.webrtc` — keep in sync with
`WEBRTC_NAMESPACE` in
`app/src/main/java/io/github/ddagunts/screencast/webrtc/WebRtcConfig.kt`.
