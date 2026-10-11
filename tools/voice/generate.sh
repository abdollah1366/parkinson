#!/usr/bin/env bash
# Regenerates the Persian voice-guide clips in app/src/main/res/raw from clips.tsv (name<TAB>text).
# Needs espeak-ng, mbrola and the Persian MBROLA voice (Ubuntu: apt install espeak-ng mbrola mbrola-ir1) and ffmpeg
# with libvorbis. A clip can also be replaced by a human recording: keep the file name, mono Ogg Vorbis, 16 kHz.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$here/../../app/src/main/res/raw"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
while IFS=$'\t' read -r name text; do
  [ -z "$name" ] && continue
  espeak-ng -v mb-ir1 -s 140 -p 45 -w "$tmp/clip.wav" "$text"
  ffmpeg -nostdin -hide_banner -loglevel error -y -i "$tmp/clip.wav" \
    -af "silenceremove=start_periods=1:start_threshold=-45dB,areverse,silenceremove=start_periods=1:start_threshold=-45dB,areverse,loudnorm=I=-16:TP=-1.5" \
    -ar 16000 -ac 1 -c:a libvorbis -q:a 0 "$out/$name.ogg"
done < "$here/clips.tsv"
