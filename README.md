# AIOStreams Extension for Aniyomi

An Aniyomi extension that aggregates anime streams from multiple debrid services through AIOStreams.

## Setup

1. Visit [AIOStreams Configuration](https://aiostreamsfortheweak.nhyira.dev/stremio/configure)
2. Add your debrid services (TorBox, RealDebrid, etc.)
3. Enable anime addons (Comet, MediaFusion, etc.)
4. Click "Create" and copy the manifest URL
5. Install the extension APK in Aniyomi
6. Go to Settings → Extensions → AIOStreams → Configure
7. Paste your manifest URL

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

## License

Apache License 2.0
