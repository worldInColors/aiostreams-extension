# AIOStreams Extension for Aniyomi

An **Aniyomi** extension that aggregates anime streams from multiple debrid services via **AIOStreams**.

## Installation

### Quick Install (Recommended)

Add the extension repo to Aniyomi/Anikku:

|                                                                                                                             Aniyomi                                                                                                                             |                                                                                                                             Anikku                                                                                                                             |
| :-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------: | :------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------: |
| [![Install on Aniyomi](https://img.shields.io/badge/Install%20Repo-Aniyomi-blue?style=flat)](https://intradeus.github.io/http-protocol-redirector/?r=aniyomi://add-repo?url=https://cdn.jsdelivr.net/gh/worldInColors/aiostreams-extension@repo/index.min.json) | [![Install on Anikku](https://img.shields.io/badge/Install%20Repo-Anikku-purple?style=flat)](https://intradeus.github.io/http-protocol-redirector/?r=anikku://add-repo?url=https://cdn.jsdelivr.net/gh/worldInColors/aiostreams-extension@repo/index.min.json) |

Or manually add this repo URL in Aniyomi settings:

```
https://cdn.jsdelivr.net/gh/worldInColors/aiostreams-extension@repo/index.min.json
```

### Manual Install

Download the latest APK from [Releases](https://github.com/worldInColors/aiostreams-extension/releases).

## Setup

1. Visit [AIOStreams Configuration](https://aiostreamsfortheweak.nhyira.dev/stremio/configure) (or any other instance)
2. Add your debrid services (TorBox, RealDebrid, etc.)
3. Enable anime addons (Comet, MediaFusion, etc.)
4. Click **Create** and copy the generated manifest URL
5. Install the extension APK in **Aniyomi**
6. Go to **Settings → Extensions → AIOStreams → Configure**
7. Paste your manifest URL and save

## Public Instances

A list of publicly available AIOStreams instances with at least some level of trustworthiness that allow **Torrentio**. Use at your own risk.

### 🚀 Stable

Receive updates later but are relatively bug-free.

- **https://aiostreamsfortheweak.nhyira.dev** Hosted by **@nhyyeb**, AIOStreams Discord admin.
- **https://aiostreams.12312023.xyz/** Hosted by **@a.ves**.
- **https://aiostreams.stremiofr.com** Hosted by the **StremioFR** community.

### 🌙 Nightly

Receive the latest updates early but with a slightly higher (yet still low) chance of encountering bugs.

- **https://aiostreams.viren070.me** Hosted by **@viren_7**, the developer.
- **https://aiostreams.midnightignite.me** Hosted by **@midnightignite**, TorBox community manager.

## Building

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease
```

Output APK: `aiostreams/build/outputs/apk/`

## Requirements

- Android 6.0+ (API 23+)
- [Aniyomi](https://github.com/aniyomiorg/aniyomi) app
- AIOStreams manifest URL with configured debrid services

## Features

### Core Features

- **AniList Integration**: Browse and search anime using AniList's comprehensive database
- **Multi-Source Streaming**: Aggregates streams from multiple debrid services
- **SeaDex Integration**: Highlights and prioritizes "best" releases according to SeaDex

### Episode Metadata

- **AniZip Mappings**: Uses ani.zip for accurate episode titles and metadata
- **AniDB Support** (Optional): Fetch additional episode titles from AniDB when available
- **Multi-Language Titles**: Falls back through English → Romaji → Native → x-jat

### Seasons Support

- **Seasons Mode**: View related anime (sequels, prequels, side stories) as separate seasons
- Enable in extension settings to organize multi-season anime better
- AniList relations are used to determine season structure

### Settings

| Setting                      | Description                                |
| ---------------------------- | ------------------------------------------ |
| Manifest URL                 | Your AIOStreams configuration URL          |
| ID Priority                  | Order of ID types to use for stream lookup |
| Use Seasons Mode             | Display related anime as seasons           |
| Use AniDB for Episode Titles | Fetch additional titles from AniDB         |
| Show P2P/Torrent Streams     | Show torrent streams (for Anikku users)    |
| Highlight SeaDex Best        | Mark best releases with ⭐                 |
| Move SeaDex Best to Top      | Prioritize best releases                   |

## Auto-Update

Users will automatically receive updates when you:

1. Increment `extVersionCode` in `aiostreams/build.gradle`
2. Push a new tag (e.g., `v4`)
3. GitHub Actions builds and deploys to the `repo` branch

## Credits

- [AIOStreams](https://github.com/Viren070/AIOStreams) for the original addon
- [AniList](https://anilist.co) for the anime database API
- [AniZip](https://ani.zip) for episode mappings and metadata
- [AniDB](https://anidb.net) for additional episode information
- [SeaDex](https://releases.moe) for best release recommendations
- Big thanks to the maintainers of public AIOStreams instances

## License

Apache License 2.0

## Like what I do?

If you enjoyed this addon and would like to support my work, consider buying me a coffee! ☕

[![Ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/nourm)
