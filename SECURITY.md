# Security Policy

## Reporting a vulnerability

Please report security issues privately rather than opening a public issue.

Use **Security → Report a vulnerability** on this repository:

https://github.com/aurius7/kura/security/advisories/new

That opens a private advisory visible only to you and the maintainer, so a fix
can be prepared and released before anyone else knows about the problem. If the
form is unavailable, open a regular issue titled `security report` containing
only enough detail to ask for a private channel — do not put exploit details in
a public issue.

Please include:

- the app version (`Settings → About`, or the `versionCode` from the APK) and
  your Android version,
- what an attacker would gain, and what access they need to do it,
- steps to reproduce, and
- whether a real vault was exposed, or only a test one.

You should get an acknowledgement within a few days. This is a one-person
project, so treat that as a courtesy rather than a commitment: there is no bug
bounty, no paid support, and no guaranteed fix timeline. If a report is valid
and not already known, it will be fixed and credited unless you would rather
stay anonymous.

Please give a reasonable opportunity to publish a fix before disclosing
publicly. Test on a vault you can afford to lose, not on your only copy.

## Supported versions

| Version | Supported |
| --- | --- |
| 1.0.1 (current) | Yes |
| 1.0.0 | Only for the data-loss warning below |
| Anything before 1.0.0 | No |

Only the current release receives fixes. There is no obligation to backport.

## The one thing to know before anything else

**Uninstalling Kura destroys the vault, permanently.**

The vault key is generated in and held by the Android Keystore, which Android
deletes when the app is removed. Nothing is stored outside the app, and there is
no cloud copy, so an uninstall leaves encrypted files with no way to ever read
them again. Not from a backup archive, because the key is gone. Not from the
maintainer, because the key was never anywhere else.

To upgrade, download the new APK and install it over the top. Android replaces
the old version and the vault survives. A missing launcher icon or an app that
will not open is a bug to report, never a reason to uninstall.

## Not vulnerabilities

These are deliberate design decisions and documented limitations, not bugs. They
will not be fixed as security issues, and reporting them as such will not get a
response:

- **A 4-digit PIN is weak against offline attack.** If an attacker has your
  encrypted files and can run code as you, they can try every PIN. This is
  documented in the README's known gaps.
- **There is no network access.** Kura declares no `INTERNET` permission, has no
  analytics, and contains no third-party SDKs. Being unable to sync, back up
  automatically, or receive updates is the intended trade.
- **Backups use PBKDF2, not Argon2id.** A deliberate, documented choice, not a
  broken cipher.
- **Deleted files may survive on flash.** `secureShred` zeroes a file once before
  unlinking it. Flash wear-levelling means the overwrite may not land on the
  physical cells, so erasure cannot be guaranteed the way it can on a spinning
  disk. Anything that ever reached plaintext on disk — video playback, an
  exported copy, a thumbnail fallback, a crash trace — may therefore outlive
  its deletion.
- **Video playback writes decrypted video to the credential-encrypted cache.**
  `VideoView` can only open a file path, so playback decrypts into the app's
  cache, plays from there, and shreds the file afterwards; the cache is wiped
  when the vault locks. It is not in a public location, but it is a documented
  case of plaintext on disk.
- **There has been no third-party security audit.** Reviewers are welcome.

The [threat model](README.md#threat-model) and [known gaps](README.md#known-gaps)
in the README describe what Kura is and is not meant to protect against. It is
the first thing to read before reporting something.

## Scope

In scope: the app source in this repository, the release artifacts it produces,
and the site linked from the README.

Out of scope: vulnerabilities in Android itself, in the Keystore, or in any
library Kura uses unmodified — those belong to their maintainers. Reports about
the *absence* of a feature are feature requests.

A finding is a vulnerability if it lets someone read, recover, tamper with, or
destroy vault contents they should not be able to, or defeats the app lock,
without the access the threat model assumes.
