# packwiz-installer
An installer for launching packwiz modpacks with MultiMC. You'll need [the bootstrapper](https://github.com/comp500/packwiz-installer-bootstrap/releases) to actually use this.

---

# +
packwiz-installer+ is simply a forked version of packwiz-installer with some features un-artfully tacked on.  In short, they are not intended to be submitted upstream.

Similarly, this installer is really meant for my server players and I have removed CDN installer caching as I do not pay for that.  Therefore, if you are not one of these select people, please **DO NOT USE THIS**.  It will likely bust github's free level of rate limits and that breaks **everyone's installer**, not just your own.

## Features Added

### Manual Downloading "Assistance"

In the case of each mod that does not allow third parties to download them through the CurseForge API, the installer will automatically open a download URL in the system's default browser and get the downloaded mod where it needs to be.

#### Limitations

All **you** need to do is clean up (close the download tabs after it is done resolving Curseforge Metadata) and use Chrome (because currently it only supports looking for downloads in Chrome's default download locations for linux, macOS, and Windows).

#### Motivation

*...there is a much more elaborate feature in the pipeline awaiting the maintainer's attention to finish, which was the motivation for hacking this together in the meantime, for family*