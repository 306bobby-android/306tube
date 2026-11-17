# 306Tube

A YouTube client for Android. No ads, no Google Play Services, works signed out.

Status: Daily-drivable, maintained

## What it does

**Watching**
- Background play and picture-in-picture
- Swipe gestures in the player: brightness (left half), volume (right half), seek (horizontal)
- Separate default quality for Wi-Fi and mobile data
- Resume where you left off (always / ask / never)
- Autoplay with a configurable countdown
- Opens `youtube.com` and `youtu.be` links, and shows up in the share sheet

**Feeds**
- Home, Subscriptions, search, channels, playlists
- Signed out, Home is built on-device from your local history and subscriptions. Uses a local algorithm. Turn it off and you get Trending instead.
- Feeds only reload when you pull to refresh. After 30 minutes a bar at the bottom offers a refresh.
- Shorts are hidden by default (Settings → Appearance)
- Hide the Home tab entirely if you only care about Subscriptions

**Library**
- Local subscriptions, playlists and watch history, no account needed
- Downloads for offline viewing, optionally Wi-Fi only
- Block channels and mark videos "not interested"

**Extras**
- SponsorBlock, with per-category control
- DeArrow titles and thumbnails
- Return YouTube Dislike
- Cast to a TV on your network, or link one with a TV code
- On Android TV, 306Tube is also a cast target: it shows up in the cast list of YouTube and 306Tube on phones, and has its own TV code under Settings → Cast.

## Signing in

Optional. Signing in gets you your real Home feed, subscriptions, Watch later, liked videos and playlists. Multiple accounts and brands are supported.

The app keeps your login cookies, encrypted with a key in the Android Keystore. Your password never touches the app.

Watch history sync to YouTube is on by default once you're signed in. Kill it in Settings → Privacy if you don't want that.

## Install

Android 8.0+ on arm64 or 32-bit ARM.

Grab the latest APK from [Releases](https://github.com/306bobby-android/306tube/releases) and open it. Or build it yourself: see [BUILDING.md](BUILDING.md).

## Good to know

- **Playback broke?** YouTube changes things constantly. Switch Settings → Playback → Stream source between the NewPipe extractor and yt-dlp. One of them is usually working.
- **Downloads** use yt-dlp. First download runs a one-time setup; update it from Settings → Downloads when downloads start failing.
- **Third-party services** (SponsorBlock, DeArrow, Return YouTube Dislike) each get the video ID you're watching. All three can be turned off.

## Credits

- [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)
- [yt-dlp](https://github.com/yt-dlp/yt-dlp) via [youtubedl-android](https://github.com/JunkFood02/youtubedl-android)
- [SponsorBlock](https://sponsor.ajay.app), [DeArrow](https://dearrow.ajay.app), [Return YouTube Dislike](https://returnyoutubedislike.com)
- AndroidX Media3

## License

GPL-3.0. See [LICENSE](LICENSE).

Not affiliated with YouTube or Google.
