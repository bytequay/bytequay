---
id: getting-started
title: Getting started
sidebar_label: Getting started
sidebar_position: 1
---

# Getting started

A short walkthrough of installing ByteQuay, connecting GitHub, and doing your
first review.

## Install

Grab the latest `.dmg` from the
[Releases page](https://github.com/bytequay/bytequay/releases), mount it,
and drag **ByteQuay.app** to **Applications**. The bundle ships its own
backend — no Java, Node, or Maven install required. macOS 14+ (Apple Silicon
or Intel).

The first launch may show *"ByteQuay is damaged and can't be opened."* Nothing
is wrong — the app just isn't notarized yet. Clear the quarantine flag once:

```sh
xattr -dr com.apple.quarantine /Applications/ByteQuay.app
```

Then open it as normal; the dialog won't return.

ByteQuay also needs **`git`** on your `PATH` for the local-repo and worktree
features (it shells out to your existing git, so your SSH keys, signing keys,
and credential helper all just work). Most dev Macs already have it; if
`git --version` fails, run `xcode-select --install`.

## Connect GitHub

Open **Settings → GitHub** and paste a personal access token with `repo` +
`read:org` scopes. The token is encrypted with a per-machine key and stored
locally — it never leaves your machine.

For AI features (AI PR review, dev-agent tasks), also add a provider API key in
**Settings → AI**. ByteQuay supports a pluggable provider interface (Claude /
OpenAI / DeepSeek).

## Your first review

Open the **PR dashboard**. It shows two sections — *Awaiting my review* and
*My PRs* — grouped by repo, with a live preview pane. Pick a PR awaiting your
review, open its diff, and work through it: read the change, leave inline
comments, and (optionally) run an [AI review](./ai-pr-review.md) to draft
comments and surface risks for you to accept, edit, or dismiss.

That's the loop ByteQuay is built around. When you're ready to let agents do
the *writing* while you keep reviewing, head to [AI tasks](./tasks.md).
