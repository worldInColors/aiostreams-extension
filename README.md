# AIOStreams Extension for Aniyomi

An **Aniyomi** extension that aggregates anime streams from multiple debrid services via **AIOStreams**.

## Setup

1. Visit [AIOStreams Configuration](https://aiostreamsfortheweak.nhyira.dev/stremio/configure) (or another instance)
2. Add your debrid services (TorBox, RealDebrid, etc.)
3. Enable anime addons (Comet, MediaFusion, etc.)
4. Click **Create** and copy the generated manifest URL
5. Install the extension APK in **Aniyomi**
6. Go to **Settings → Extensions → AIOStreams → Configure**
7. Paste your manifest URL and save

## Public Instances

A list of publicly available AIOStreams instances with at least some level of trustworthiness that allow **Torrentio**.  
Use at your own risk.

### 🚀 Stable

Receive updates later but are relatively bug-free.

- **https://aiostreamsfortheweak.nhyira.dev**  
  Hosted by **@nhyyeb**, AIOStreams Discord admin.

- **https://aiostreams.12312023.xyz/**  
  Hosted by **@a.ves**.

- **https://aiostreams.stremiofr.com**  
  Hosted by the **StremioFR** community.

### 🌙 Nightly

Receive the latest updates early but with a slightly higher (yet still low) chance of encountering bugs.

- **https://aiostreams.viren070.me**  
  Hosted by **@viren_7**, the developer.

- **https://aiostreams.midnightignite.me**  
  Hosted by **@midnightignite**, TorBox community manager.

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

## Credits

- [AIOStreams](https://github.com/Viren070/AIOStreams) for the original addon
- Big thanks to the maintainers of public AIOStreams instances

## License

Apache License 2.0

## Like what I do?

If you enjoyed this addon and would like to support my work, consider buying me a coffee! ☕

[![Ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/nourm)

---

```

```
